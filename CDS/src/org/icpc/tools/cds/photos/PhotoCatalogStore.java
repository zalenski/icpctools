package org.icpc.tools.cds.photos;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.photos.PhotoMetadata;

/** Generic catalog storage. Sources, credentials and image processing belong to the importer. */
public final class PhotoCatalogStore {
	private final File present;

	public PhotoCatalogStore(File configFolder) {
		present = new File(configFolder, "present");
	}

	public synchronized List<String> catalogs() {
		List<String> result = new ArrayList<>();
		File[] folders = present.listFiles();
		if (folders != null) {
			for (File folder : folders) {
				if (folder.isDirectory() && validCatalog(folder.getName())) {
					result.add(folder.getName());
				}
			}
		}
		result.sort(String::compareTo);
		return result;
	}

	public synchronized JsonObject sources(String catalog) throws IOException {
		File file = new File(folder(catalog), "photo-sources.json");
		if (file.isFile()) {
			return PhotoMetadata.readObject(file);
		}
		JsonObject empty = new JsonObject();
		empty.put("sources", new Object[0]);
		return empty;
	}

	public synchronized void sources(String catalog, JsonObject definitions) throws IOException {
		if (definitions.getArray("sources") == null) {
			throw new IllegalArgumentException("Missing sources array");
		}
		PhotoMetadata.writeObject(new File(folder(catalog), "photo-sources.json"), definitions);
	}

	public synchronized List<PhotoMetadata> index(String catalog) throws IOException {
		return PhotoMetadata.readIndex(folder(catalog));
	}

	public synchronized PhotoMetadata upload(String catalog, String importId, PhotoMetadata supplied, InputStream image)
			throws IOException {
		checkImport(importId);
		if (supplied == null || supplied.getPhotoId() == null || supplied.getPhotoId().isEmpty()
				|| !safeFile(supplied.getFile()) || !importId.equals(supplied.getImportId())) {
			throw new IllegalArgumentException("Invalid photo metadata or ownership");
		}
		File folder = folder(catalog);
		Files.createDirectories(folder.toPath());
		List<PhotoMetadata> records = PhotoMetadata.readIndex(folder);
		PhotoMetadata previous = find(records, importId, supplied.getPhotoId());
		String name = previous == null ? availableName(folder, supplied.getFile()) : previous.getFile();
		if (!safeFile(name)) {
			throw new IOException("Invalid indexed photo filename");
		}
		File destination = inside(folder, name);
		File temp = File.createTempFile("photo-upload-", ".tmp", folder);
		try {
			Files.copy(image, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
			if (temp.length() == 0) {
				throw new IllegalArgumentException("Empty photo upload");
			}
			Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			PhotoMetadata stored = supplied.withFile(name);
			if (previous != null) {
				records.remove(previous);
			}
			records.add(stored);
			PhotoMetadata.writeIndex(folder, records);
			return stored;
		} finally {
			Files.deleteIfExists(temp.toPath());
		}
	}

	/** Updates metadata for an existing image without replacing its bytes or allocated filename. */
	public synchronized PhotoMetadata updateMetadata(String catalog, String importId, PhotoMetadata supplied)
			throws IOException {
		checkImport(importId);
		if (supplied == null || supplied.getPhotoId() == null || supplied.getPhotoId().isEmpty()
				|| !importId.equals(supplied.getImportId())) {
			throw new IllegalArgumentException("Invalid photo metadata or ownership");
		}
		File folder = folder(catalog);
		List<PhotoMetadata> records = PhotoMetadata.readIndex(folder);
		PhotoMetadata previous = find(records, importId, supplied.getPhotoId());
		if (previous == null) {
			return null;
		}
		if (!safeFile(previous.getFile())) {
			throw new IOException("Invalid indexed photo filename");
		}
		if (!inside(folder, previous.getFile()).isFile()) {
			return null;
		}
		if (!previous.sameImage(supplied)) {
			throw new IllegalArgumentException("Changed images require an upload");
		}
		PhotoMetadata stored = supplied.withFile(previous.getFile());
		records.set(records.indexOf(previous), stored);
		PhotoMetadata.writeIndex(folder, records);
		return stored;
	}

	public synchronized void delete(String catalog, String importId, String photoId) throws IOException {
		checkImport(importId);
		File folder = folder(catalog);
		List<PhotoMetadata> records = PhotoMetadata.readIndex(folder);
		PhotoMetadata previous = find(records, importId, photoId);
		if (previous == null) {
			return;
		}
		File image = inside(folder, previous.getFile());
		Files.deleteIfExists(image.toPath());
		records.remove(previous);
		PhotoMetadata.writeIndex(folder, records);
	}

	private File folder(String catalog) throws IOException {
		if (!validCatalog(catalog)) {
			throw new IllegalArgumentException("Invalid photo catalog");
		}
		return inside(present, catalog);
	}

	private static boolean validCatalog(String name) {
		return "photos".equals(name) || name.matches("photos-[A-Za-z0-9_-]+");
	}

	private static void checkImport(String id) {
		if (id == null || !id.matches("[A-Za-z0-9_-]+")) {
			throw new IllegalArgumentException("Invalid import ID");
		}
	}

	private static boolean safeFile(String name) {
		return name != null && name.matches("[A-Za-z0-9_.-]+") && !name.contains("..") && !".".equals(name)
				&& !name.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
				&& !name.toLowerCase(java.util.Locale.ROOT).endsWith(".tmp");
	}

	private static File inside(File folder, String name) throws IOException {
		File file = new File(folder, name);
		if (!file.getCanonicalPath().startsWith(folder.getCanonicalPath() + File.separator)) {
			throw new IllegalArgumentException("Photo path leaves its folder");
		}
		return file;
	}

	private static PhotoMetadata find(List<PhotoMetadata> records, String importId, String photoId) {
		for (PhotoMetadata record : records) {
			if (importId.equals(record.getImportId()) && record.getPhotoId().equals(photoId)) {
				return record;
			}
		}
		return null;
	}

	private static String availableName(File folder, String preferred) throws IOException {
		if (!new File(folder, preferred).exists()) {
			return preferred;
		}
		int dot = preferred.lastIndexOf('.');
		String stem = dot > 0 ? preferred.substring(0, dot) : preferred;
		String suffix = dot > 0 ? preferred.substring(dot) : "";
		for (int i = 2; i <= 10000; i++) {
			String candidate = stem + "-" + i + suffix;
			if (!new File(folder, candidate).exists()) {
				return candidate;
			}
		}
		throw new IOException("No available photo filename");
	}
}
