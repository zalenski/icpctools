package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.photos.PhotoMetadata;

/** Reads local originals directly; the scan index caches observations, not photo ownership. */
final class LocalPhotoSource implements PhotoSource {
	private record Stamp(long size, String modified, Object fileKey) {
		static Stamp read(Path path) throws IOException {
			BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
			return new Stamp(attributes.size(), attributes.lastModifiedTime().toString(), attributes.fileKey());
		}
	}

	private record Input(Path path, Stamp stamp) { }
	private record Entry(long size, String modified, String result, int originalWidth, int originalHeight) {
		boolean matches(Stamp stamp) {
			return size == stamp.size && modified.equals(stamp.modified);
		}
	}

	private final Path root;
	private final Map<String, Input> files = new LinkedHashMap<>();
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private File indexFile;
	private Object rootKey;
	private boolean dirty;

	LocalPhotoSource(URI uri) {
		root = Path.of(uri).toAbsolutePath().normalize();
	}

	@Override
	public String getType() {
		return "local";
	}

	@Override
	public String getUri() {
		return root.toUri().toString();
	}

	void begin(File cache, String importId) throws IOException {
		String name = importId.matches("[A-Za-z0-9_-]+") ? importId
				: UUID.nameUUIDFromBytes(importId.getBytes(StandardCharsets.UTF_8)).toString();
		indexFile = new File(cache, "local-index-" + name + ".json");
		entries.clear();
		dirty = !indexFile.exists();
		if (dirty) {
			return;
		}
		JsonObject saved = PhotoMetadata.readObject(indexFile);
		if (!getUri().equals(saved.getString("source"))) {
			dirty = true;
			return;
		}
		Object[] observations = saved.getArray("files");
		if (observations == null) {
			throw new IOException("Missing files in local scan index");
		}
		for (Object observation : observations) {
			if (!(observation instanceof JsonObject item) || item.getString("path") == null
					|| item.getString("modified") == null || item.getString("result") == null) {
				throw new IOException("Invalid local scan entry");
			}
			entries.put(item.getString("path"), new Entry(item.getLong("size"), item.getString("modified"),
					item.getString("result"), item.getInt("originalWidth"), item.getInt("originalHeight")));
		}
	}

	@Override
	public List<Photo> listPhotos() throws IOException {
		rootKey = Stamp.read(root).fileKey;
		List<Photo> photos = new ArrayList<>();
		files.clear();
		try (Stream<Path> paths = Files.isDirectory(root) ? Files.walk(root) : Stream.of(root)) {
			for (Path path : (Iterable<Path>) paths.sorted()::iterator) {
				PhotoSync.checkCancelled();
				if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
					continue;
				}
				String name = path.getFileName().toString();
				String lower = name.toLowerCase(Locale.ROOT);
				if (lower.endsWith(".json") || lower.endsWith(".tmp")) {
					continue;
				}
				String id = root.equals(path) ? name : root.relativize(path).toString().replace(File.separatorChar, '/');
				Stamp stamp = Stamp.read(path);
				files.put(id, new Input(path, stamp));
				photos.add(new Photo(id, name, null, null,
						path.toUri() + "?modified=" + stamp.modified + "&size=" + stamp.size));
			}
		}
		verifyScan();
		return photos;
	}

	PhotoMetadata prepare(Photo photo, File cache, PhotoMetadata existing, Integer maxWidth, Integer maxHeight,
			long now) throws IOException {
		Input input = files.get(photo.getId());
		if (input == null) {
			throw new IOException("Local photo is no longer listed");
		}
		Entry previous = entries.get(photo.getId());
		if (previous != null && previous.matches(input.stamp)) {
			if ("invalid".equals(previous.result)) {
				if (existing != null) {
					throw new IOException("Local photo is still invalid: " + photo.getId());
				}
				return null;
			}
			if ("valid".equals(previous.result) && previous.originalWidth > 0 && previous.originalHeight > 0 && existing != null
					&& photo.getOriginalUrl().equals(existing.getOriginalUrl())
					&& PhotoSync.isSafeName(existing.getFile()) && new File(cache, existing.getFile()).isFile()) {
				PhotoScale.Size size = PhotoScale.fit(previous.originalWidth, previous.originalHeight, maxWidth, maxHeight);
				if (size.width == existing.getWidth() && size.height == existing.getHeight()) {
					return existing.withLimits(maxWidth, maxHeight);
				}
			}
		}

		String name = PhotoSync.chooseName(cache,
				PhotoSync.imageFileName(getType(), getUri(), photo.getId(), photo.getOriginalUrl()),
				existing == null ? null : existing.getFile());
		if (name == null) {
			throw new IOException("No free file name for local photo");
		}
		// Keep the last good display copy until decoding and the stability check both succeed.
		File temporary = File.createTempFile("local-photo-", name.substring(name.lastIndexOf('.')), cache);
		try {
			verifyInput(input);
			PhotoScale.Written written = PhotoScale.writeDisplay(input.path.toFile(), temporary, maxWidth, maxHeight);
			verifyInput(input);
			PhotoSync.checkCancelled();
			PhotoSync.move(temporary, new File(cache, name));
			record(photo, input.stamp, "valid", written.originalWidth, written.originalHeight);
			return new PhotoMetadata(getType(), getUri(), photo.getId(), photo.getTitle(), null, null,
					photo.getOriginalUrl(), now, name, null, written.width, written.height, maxWidth, maxHeight);
		} catch (PhotoScale.InvalidImageException e) {
			// Cache only stable decoding failures; changing files must be tried again.
			try {
				verifyInput(input);
			} catch (IOException changed) {
				record(photo, input.stamp, "error", 0, 0);
				throw changed;
			}
			record(photo, input.stamp, "invalid", 0, 0);
			if (existing != null) {
				throw e;
			}
			return null;
		} catch (IOException e) {
			record(photo, input.stamp, "error", 0, 0);
			throw e;
		} finally {
			Files.deleteIfExists(temporary.toPath());
		}
	}

	private static void verifyInput(Input input) throws IOException {
		if (!Files.isReadable(input.path)) {
			throw new IOException("Local file cannot be read; retry next scan");
		}
		if (!input.stamp.equals(Stamp.read(input.path))) {
			throw new IOException("Local file changed during reading; retry next scan");
		}
	}

	void verifyScan() throws IOException {
		if (!Objects.equals(rootKey, Stamp.read(root).fileKey)) {
			throw new IOException("Local source changed during scanning; retry next scan");
		}
	}

	private void record(Photo photo, Stamp stamp, String result, int originalWidth, int originalHeight) {
		Entry entry = new Entry(stamp.size, stamp.modified, result, originalWidth, originalHeight);
		dirty |= !entry.equals(entries.put(photo.getId(), entry));
	}

	boolean isDirty() {
		return dirty;
	}

	void finishScan() {
		dirty |= entries.keySet().retainAll(files.keySet());
	}

	void checkpoint() throws IOException {
		if (!dirty) {
			return;
		}
		JsonObject saved = new JsonObject();
		saved.put("source", getUri());
		List<JsonObject> observations = new ArrayList<>();
		for (Map.Entry<String, Entry> item : entries.entrySet()) {
			JsonObject observation = new JsonObject();
			observation.put("path", item.getKey());
			observation.put("size", item.getValue().size);
			observation.put("modified", item.getValue().modified);
			observation.put("result", item.getValue().result);
			observation.put("originalWidth", item.getValue().originalWidth);
			observation.put("originalHeight", item.getValue().originalHeight);
			observations.add(observation);
		}
		saved.put("files", observations.toArray());
		PhotoMetadata.writeObject(indexFile, saved);
		dirty = false;
	}

	@Override
	public void fetchOriginal(Photo photo, File destination) throws IOException {
		throw new IOException("Local originals are read directly, not copied");
	}
}
