package org.icpc.tools.cds.photos;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.icpc.tools.contest.Trace;

/**
 * Downloads one {@link PhotoSource} into the presentation photos folder. Only files recorded in
 * the metadata index are replaced or removed. Photos placed in the folder by hand are kept.
 */
public final class PhotoSync {
	public static final class Result {
		public final int remote;
		public final int downloaded;
		public final int removed;

		public Result(int remote, int downloaded, int removed) {
			this.remote = remote;
			this.downloaded = downloaded;
			this.removed = removed;
		}
	}

	public Result sync(File folder, PhotoSource source) throws IOException {
		return sync(folder, source, System.currentTimeMillis());
	}

	public Result sync(File folder, PhotoSource source, long now) throws IOException {
		if (folder == null || source == null)
			throw new IOException("Missing photo folder or source");

		// Fail before touching disk so an API or network error leaves the folder as it is.
		List<Photo> remote = source.listPhotos();
		if (remote == null)
			throw new IOException("Photo source returned no list");

		if (!folder.exists() && !folder.mkdirs())
			throw new IOException("Could not create photo folder");

		List<PhotoMetadata> index = PhotoMetadata.readIndex(folder);
		String uri = source.getUri();
		List<PhotoMetadata> others = new ArrayList<>();
		Map<String, PhotoMetadata> oursById = new HashMap<>();
		for (PhotoMetadata meta : index) {
			if (uri.equals(meta.getSourceUri()))
				oursById.put(meta.getPhotoId(), meta);
			else
				others.add(meta);
		}

		List<PhotoMetadata> current = new ArrayList<>();
		int downloaded = 0;
		int remoteCount = 0;
		for (Photo photo : remote) {
			if (photo == null || photo.getId() == null || photo.getId().isEmpty())
				continue;
			if (photo.getOriginalUrl() == null || photo.getOriginalUrl().isEmpty())
				continue;
			remoteCount++;
			PhotoMetadata existing = oursById.get(photo.getId());
			try {
				Ensure ensured = ensure(folder, source, photo, existing, now);
				current.add(ensured.meta);
				if (ensured.downloaded)
					downloaded++;
			} catch (IOException e) {
				Trace.trace(Trace.WARNING, "Photo sync kept previous file for " + photo.getId() + ": " + e.getMessage());
				if (existing != null)
					current.add(existing.withDetails(photo.getTitle(), photo.getOwner(), photo.getLicense()));
			}
		}

		int removed = 0;
		for (PhotoMetadata old : oursById.values()) {
			boolean stillThere = false;
			for (PhotoMetadata meta : current) {
				if (old.getPhotoId().equals(meta.getPhotoId())) {
					stillThere = true;
					break;
				}
			}
			if (!stillThere) {
				deleteOwned(folder, old);
				removed++;
			}
		}

		List<PhotoMetadata> merged = new ArrayList<>(others);
		merged.addAll(current);
		PhotoMetadata.writeIndex(folder, merged);
		return new Result(remoteCount, downloaded, removed);
	}

	/**
	 * File name the sync would use for a new photo. A file that already has this name and is not
	 * tracked is left in place; the download uses another name.
	 */
	public static String imageFileName(String sourceType, String sourceUri, String photoId, String originalUrl) {
		return sanitize(sourceType) + "-" + cacheKey(sourceUri) + "-" + sanitize(photoId) + "."
				+ extension(originalUrl);
	}

	private static final class Ensure {
		private final PhotoMetadata meta;
		private final boolean downloaded;

		private Ensure(PhotoMetadata meta, boolean downloaded) {
			this.meta = meta;
			this.downloaded = downloaded;
		}
	}

	private Ensure ensure(File folder, PhotoSource source, Photo photo, PhotoMetadata existing, long now)
			throws IOException {
		if (existing != null && photo.getOriginalUrl().equals(existing.getOriginalUrl())) {
			File image = ownedFile(folder, existing.getFile());
			if (image != null && image.isFile()) {
				PhotoMetadata updated = existing.withDetails(photo.getTitle(), photo.getOwner(), photo.getLicense());
				PhotoMetadata.writeSidecar(image, updated);
				return new Ensure(updated, false);
			}
		}

		String preferred = imageFileName(source.getType(), source.getUri(), photo.getId(), photo.getOriginalUrl());

		String fileName = preferred;
		if (existing != null && existing.getFile() != null && !existing.getFile().isEmpty()
				&& isSafeName(existing.getFile()))
			fileName = existing.getFile();
		if (!isSafeName(fileName) || fileTakenBySomeoneElse(folder, fileName, source.getUri(), photo.getId(), existing))
			fileName = alternateName(folder, preferred, source.getUri(), photo.getId(), existing);
		if (fileName == null)
			throw new IOException("No free file name for photo " + photo.getId());

		File dest = new File(folder, fileName);
		File tmp = File.createTempFile("photosync-", ".tmp", folder);
		try {
			source.fetchOriginal(photo, tmp);
			if (!tmp.isFile() || tmp.length() == 0)
				throw new IOException("Empty image for photo " + photo.getId());
			move(tmp, dest);
		} finally {
			if (tmp.exists())
				tmp.delete();
		}

		PhotoMetadata meta = new PhotoMetadata(source.getType(), source.getUri(), photo.getId(), photo.getTitle(),
				photo.getOwner(), photo.getLicense(), photo.getOriginalUrl(), now, fileName);
		PhotoMetadata.writeSidecar(dest, meta);
		if (existing != null && existing.getFile() != null && !existing.getFile().equals(fileName))
			deleteOwned(folder, existing);
		return new Ensure(meta, true);
	}

	private static boolean fileTakenBySomeoneElse(File folder, String fileName, String sourceUri, String photoId,
			PhotoMetadata existing) {
		File file = new File(folder, fileName);
		if (!file.exists())
			return false;
		return !isOurs(folder, fileName, sourceUri, photoId, existing);
	}

	private static boolean isOurs(File folder, String fileName, String sourceUri, String photoId,
			PhotoMetadata existing) {
		if (existing != null && fileName.equals(existing.getFile()))
			return true;
		try {
			PhotoMetadata sidecar = PhotoMetadata.readSidecar(new File(folder, fileName));
			return sidecar != null && sourceUri.equals(sidecar.getSourceUri()) && photoId.equals(sidecar.getPhotoId());
		} catch (IOException e) {
			return false;
		}
	}

	private static String alternateName(File folder, String preferred, String sourceUri, String photoId,
			PhotoMetadata existing) {
		int dot = preferred.lastIndexOf('.');
		String stem = dot > 0 ? preferred.substring(0, dot) : preferred;
		String ext = dot > 0 ? preferred.substring(dot) : "";
		for (int n = 2; n <= 20; n++) {
			String candidate = stem + "-" + n + ext;
			if (!fileTakenBySomeoneElse(folder, candidate, sourceUri, photoId, existing))
				return candidate;
		}
		return null;
	}

	private static void deleteOwned(File folder, PhotoMetadata meta) {
		if (meta == null || !isSafeName(meta.getFile()) || PhotoMetadata.INDEX_NAME.equals(meta.getFile()))
			return;
		File image = new File(folder, meta.getFile());
		try {
			if (!isInside(folder, image))
				return;
		} catch (IOException e) {
			return;
		}
		if (image.exists() && !image.delete())
			Trace.trace(Trace.WARNING, "Could not delete synced photo " + image.getName());
		File sidecar = PhotoMetadata.sidecarFile(image);
		if (sidecar.exists() && !sidecar.delete())
			Trace.trace(Trace.WARNING, "Could not delete photo metadata " + sidecar.getName());
	}

	private static File ownedFile(File folder, String name) {
		if (!isSafeName(name))
			return null;
		File file = new File(folder, name);
		try {
			if (!isInside(folder, file))
				return null;
		} catch (IOException e) {
			return null;
		}
		return file;
	}

	private static boolean isInside(File folder, File file) throws IOException {
		String root = folder.getCanonicalPath() + File.separator;
		return file.getCanonicalPath().startsWith(root);
	}

	static boolean isSafeName(String name) {
		if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains(".."))
			return false;
		return true;
	}

	private static void move(File tmp, File dest) throws IOException {
		try {
			Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static String cacheKey(String sourceUri) {
		String rest = sourceUri == null ? "" : sourceUri;
		int scheme = rest.indexOf("://");
		if (scheme >= 0)
			rest = rest.substring(scheme + 3);
		return sanitize(rest);
	}

	private static String extension(String url) {
		if (url == null)
			return "jpg";
		String path = url;
		int query = path.indexOf('?');
		if (query >= 0)
			path = path.substring(0, query);
		int slash = path.lastIndexOf('/');
		int dot = path.lastIndexOf('.');
		if (dot <= slash)
			return "jpg";
		String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
		if ("jpeg".equals(ext))
			return "jpg";
		if ("jpg".equals(ext) || "png".equals(ext) || "gif".equals(ext) || "webp".equals(ext))
			return ext;
		return "jpg";
	}

	private static String sanitize(String value) {
		if (value == null || value.isEmpty())
			return "_";
		StringBuilder sb = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_'
					|| c == '-')
				sb.append(c);
			else
				sb.append('_');
		}
		if (sb.length() == 0)
			return "_";
		return sb.toString();
	}
}
