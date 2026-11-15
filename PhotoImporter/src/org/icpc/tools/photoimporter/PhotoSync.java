package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

import org.icpc.tools.contest.model.photos.PhotoMetadata;
import org.icpc.tools.contest.Trace;

/**
 * Downloads a source into the importer's local originals and display cache. Checkpoints publish
 * completed photos to the target CDS. Only indexed local files are replaced or removed.
 */
public final class PhotoSync {
	static final long CHECKPOINT_INTERVAL_MS = 10_000;

	@FunctionalInterface
	interface Publisher {
		void publish(List<PhotoMetadata> photos, boolean complete) throws IOException;
	}

	private final LongSupplier clock;
	private final Publisher publisher;
	private final Consumer<Progress> progress;
	private final BooleanSupplier cancelled;

	/** Immutable progress snapshot, independent of logs and suitable for a UI. */
	public record Progress(String phase, int processed, int total, int downloaded, int failed) { }

	static void checkCancelled() throws InterruptedIOException {
		if (Thread.currentThread().isInterrupted()) {
			throw new InterruptedIOException("Photo import stopped");
		}
	}

	private void checkStopped() throws InterruptedIOException {
		if (cancelled.getAsBoolean()) {
			Thread.currentThread().interrupt();
		}
		checkCancelled();
	}

	public PhotoSync() {
		this(() -> System.nanoTime() / 1_000_000);
	}

	PhotoSync(LongSupplier clock) {
		this(clock, (photos, complete) -> { });
	}

	PhotoSync(LongSupplier clock, Publisher publisher) {
		this(clock, publisher, update -> { });
	}

	PhotoSync(LongSupplier clock, Publisher publisher, Consumer<Progress> progress) {
		this(clock, publisher, progress, () -> false);
	}

	PhotoSync(LongSupplier clock, Publisher publisher, Consumer<Progress> progress, BooleanSupplier cancelled) {
		this.clock = clock;
		this.publisher = publisher;
		this.progress = progress;
		this.cancelled = cancelled;
	}

	/** Default catalog folder, served by CDS at {@code /presentation/photos}. */
	public static final String DISPLAY_FOLDER = "photos";

	/** Local original storage, independent of the target CDS. */
	public static final String ORIGINALS_FOLDER = "originals";

	public static final class Result {
		public final int remote;
		public final int downloaded;
		public final int removed;
		public final int failed;

		public Result(int remote, int downloaded, int removed, int failed) {
			this.remote = remote;
			this.downloaded = downloaded;
			this.removed = removed;
			this.failed = failed;
		}
	}

	public Result sync(File displayFolder, File originalsFolder, PhotoSource source, Integer maxWidth, Integer maxHeight)
			throws IOException {
		return sync(displayFolder, originalsFolder, source, maxWidth, maxHeight, System.currentTimeMillis());
	}

	public Result sync(File displayFolder, File originalsFolder, PhotoSource source, Integer maxWidth, Integer maxHeight,
			long now) throws IOException {
		return sync(displayFolder, originalsFolder, source, source == null ? null : source.getUri(), maxWidth,
				maxHeight, now);
	}

	public Result sync(File displayFolder, File originalsFolder, PhotoSource source, String importId,
			Integer maxWidth, Integer maxHeight) throws IOException {
		return sync(displayFolder, originalsFolder, source, importId, maxWidth, maxHeight, System.currentTimeMillis());
	}

	public Result sync(File displayFolder, File originalsFolder, PhotoSource source, String importId,
			Integer maxWidth, Integer maxHeight, long now) throws IOException {
		if (displayFolder == null || originalsFolder == null || source == null || importId == null || importId.isEmpty()) {
			throw new IOException("Missing photo folder or source");
		}

		checkStopped();
		progress.accept(new Progress("listing", 0, 0, 0, 0));
		String label = displayFolder.getName() + "/" + importId;
		Trace.trace(Trace.INFO, "Photo sync " + label + " starting");
		// Fail before touching disk so an API or network error leaves the folders as they are.
		List<Photo> remote = source.listPhotos();
		if (remote == null) {
			throw new IOException("Photo source returned no list");
		}

		checkStopped();
		Trace.trace(Trace.INFO, "Photo sync " + label + " listed " + remote.size() + " photos");

		if (!displayFolder.exists() && !displayFolder.mkdirs()) {
			throw new IOException("Could not create photo folder");
		}
		if (!originalsFolder.exists() && !originalsFolder.mkdirs()) {
			throw new IOException("Could not create photo originals folder");
		}

		List<PhotoMetadata> index = PhotoMetadata.readIndex(displayFolder);
		List<PhotoMetadata> others = new ArrayList<>();
		Map<String, PhotoMetadata> oursById = new LinkedHashMap<>();
		for (PhotoMetadata meta : index) {
			if (importId.equals(meta.getImportId())) {
				oursById.put(meta.getPhotoId(), meta);
			} else {
				others.add(meta);
			}
		}

		// Until the complete listing has been processed, keep unvisited old records in checkpoints.
		Map<String, PhotoMetadata> checkpoint = new LinkedHashMap<>(oursById);
		Map<String, PhotoMetadata> current = new LinkedHashMap<>();
		if (!new File(displayFolder, PhotoMetadata.INDEX_NAME).isFile()) {
			PhotoMetadata.writeIndex(displayFolder, index);
		}
		publisher.publish(index, false);
		long lastCheckpoint = clock.getAsLong();
		boolean changed = false;
		int downloaded = 0;
		int remoteCount = 0;
		int failed = 0;
		try {
			for (Photo photo : remote) {
				checkStopped();
				if (photo == null || photo.getId() == null || photo.getId().isEmpty()) {
					continue;
				}
				if (photo.getOriginalUrl() == null || photo.getOriginalUrl().isEmpty()) {
					continue;
				}
				remoteCount++;
				progress.accept(new Progress("preparing", remoteCount - 1, remote.size(), downloaded, failed));
				PhotoMetadata existing = oursById.get(photo.getId());
				PhotoMetadata updated = null;
				try {
					Ensure ensured = ensure(displayFolder, originalsFolder, source, photo, existing, maxWidth, maxHeight, now);
					updated = ensured.meta.withImportId(importId);
					if (ensured.downloaded) {
						downloaded++;
						changed = true;
					}
				} catch (IOException e) {
					checkStopped();
					failed++;
					Trace.trace(Trace.WARNING, "Photo sync " + label + " failed for photo " + photo.getId() + ": "
							+ PhotoHttp.redact(e.getMessage()));
					if (existing != null) {
						updated = existing.withDetails(photo.getTitle(), photo.getOwner(), photo.getLicense());
					}
				}
				if (updated != null) {
					current.put(photo.getId(), updated);
					checkpoint.put(photo.getId(), updated);
					changed |= !sameMetadata(existing, updated);
				}
				progress.accept(new Progress("preparing", remoteCount, remote.size(), downloaded, failed));
				checkStopped();
				long elapsed = clock.getAsLong();
				if (changed && elapsed - lastCheckpoint >= CHECKPOINT_INTERVAL_MS) {
					List<PhotoMetadata> progress = merged(others, checkpoint.values());
					PhotoMetadata.writeIndex(displayFolder, progress);
					this.progress.accept(new Progress("uploading", remoteCount, remote.size(), downloaded, failed));
					publisher.publish(progress, false);
					Trace.trace(Trace.INFO, "Photo sync " + label + " processed " + remoteCount + "/" + remote.size()
							+ ", downloaded " + downloaded + ", failed " + failed + "; saved index");
					lastCheckpoint = elapsed;
					changed = false;
				}
			}

			checkStopped();
		} finally {
			if (Thread.currentThread().isInterrupted()) {
				// Save completed local work without issuing more HTTP requests on cancellation.
				Thread.interrupted();
				try {
					PhotoMetadata.writeIndex(displayFolder, merged(others, checkpoint.values()));
				} finally {
					Thread.currentThread().interrupt();
				}
			}
		}

		int removed = 0;
		for (PhotoMetadata old : oursById.values()) {
			checkStopped();
			if (!current.containsKey(old.getPhotoId())) {
				deleteOwned(displayFolder, old.getFile());
				deleteOwned(originalsFolder, old.getOriginalFile());
				removed++;
				changed = true;
			}
		}

		if (changed) {
			PhotoMetadata.writeIndex(displayFolder, merged(others, current.values()));
		}
		checkStopped();
		progress.accept(new Progress("uploading", remoteCount, remote.size(), downloaded, failed));
		publisher.publish(merged(others, current.values()), true);
		Trace.trace(Trace.INFO, "Photo sync " + label + " finished: processed " + remoteCount + "/" + remote.size()
				+ ", downloaded " + downloaded + ", removed " + removed + ", failed " + failed);
		return new Result(remoteCount, downloaded, removed, failed);
	}

	static boolean sameMetadata(PhotoMetadata before, PhotoMetadata after) {
		if (before == null) {
			return false;
		}
		Map<String, Object> oldValues = before.toJson().props;
		Map<String, Object> newValues = after.toJson().props;
		for (String key : oldValues.keySet()) {
			if (!Objects.equals(oldValues.get(key), newValues.get(key))) {
				return false;
			}
		}
		return oldValues.size() == newValues.size();
	}

	private static List<PhotoMetadata> merged(List<PhotoMetadata> others, Collection<PhotoMetadata> current) {
		List<PhotoMetadata> merged = new ArrayList<>(others);
		merged.addAll(current);
		return merged;
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

	private Ensure ensure(File displayFolder, File originalsFolder, PhotoSource source, Photo photo,
			PhotoMetadata existing, Integer maxWidth, Integer maxHeight, long now) throws IOException {
		if (existing != null && photo.getOriginalUrl().equals(existing.getOriginalUrl())) {
			File original = ownedFile(originalsFolder, existing.getOriginalFile());
			if (original != null && original.isFile()) {
				boolean sameLimits = sameLimit(existing.getMaxWidth(), maxWidth)
						&& sameLimit(existing.getMaxHeight(), maxHeight);
				File display = ownedFile(displayFolder, existing.getFile());
				if (sameLimits && display != null && display.isFile()) {
					PhotoMetadata updated = existing.withDetails(photo.getTitle(), photo.getOwner(), photo.getLicense());
					return new Ensure(updated, false);
				}
				String displayName = chooseName(displayFolder, imageFileName(source.getType(), source.getUri(),
						photo.getId(), photo.getOriginalUrl()), existing.getFile());
				checkStopped();
				PhotoScale.Written written = PhotoScale.writeDisplay(original, new File(displayFolder, displayName),
						maxWidth, maxHeight);
				PhotoMetadata updated = new PhotoMetadata(source.getType(), source.getUri(), photo.getId(),
						photo.getTitle(), photo.getOwner(), photo.getLicense(), photo.getOriginalUrl(),
						existing.getFetchTime(), displayName, existing.getOriginalFile(), written.width, written.height,
						maxWidth, maxHeight);
				if (existing.getFile() != null && !existing.getFile().equals(displayName)) {
					deleteOwned(displayFolder, existing.getFile());
				}
				return new Ensure(updated, false);
			}
		}

		String preferred = imageFileName(source.getType(), source.getUri(), photo.getId(), photo.getOriginalUrl());
		String originalName = chooseName(originalsFolder, preferred,
				existing == null ? null : existing.getOriginalFile());
		String displayName = chooseName(displayFolder, preferred, existing == null ? null : existing.getFile());
		if (originalName == null || displayName == null) {
			throw new IOException("No free file name for photo " + photo.getId());
		}

		File originalDest = new File(originalsFolder, originalName);
		File displayDest = new File(displayFolder, displayName);
		File originalTmp = File.createTempFile("photosync-", ".tmp", originalsFolder);
		try {
			checkStopped();
			source.fetchOriginal(photo, originalTmp);
			checkStopped();
			if (!originalTmp.isFile() || originalTmp.length() == 0) {
				throw new IOException("Empty image for photo " + photo.getId());
			}
			PhotoScale.Written written = PhotoScale.writeDisplay(originalTmp, displayDest, maxWidth, maxHeight);
			move(originalTmp, originalDest);
			PhotoMetadata meta = new PhotoMetadata(source.getType(), source.getUri(), photo.getId(), photo.getTitle(),
					photo.getOwner(), photo.getLicense(), photo.getOriginalUrl(), now, displayName, originalName,
					written.width, written.height, maxWidth, maxHeight);
			if (existing != null) {
				if (existing.getFile() != null && !existing.getFile().equals(displayName)) {
					deleteOwned(displayFolder, existing.getFile());
				}
				if (existing.getOriginalFile() != null && !existing.getOriginalFile().equals(originalName)) {
					deleteOwned(originalsFolder, existing.getOriginalFile());
				}
			}
			return new Ensure(meta, true);
		} finally {
			if (originalTmp.exists()) {
				originalTmp.delete();
			}
		}
	}

	private static String chooseName(File folder, String preferred, String existingName) {
		if (isSafeName(existingName)) {
			return existingName;
		}
		if (!new File(folder, preferred).exists()) {
			return preferred;
		}
		int dot = preferred.lastIndexOf('.');
		String stem = dot > 0 ? preferred.substring(0, dot) : preferred;
		String ext = dot > 0 ? preferred.substring(dot) : "";
		for (int n = 2; n <= 20; n++) {
			String candidate = stem + "-" + n + ext;
			if (!new File(folder, candidate).exists()) {
				return candidate;
			}
		}
		return null;
	}

	private static void deleteOwned(File folder, String name) {
		if (!isSafeName(name) || PhotoMetadata.INDEX_NAME.equals(name)) {
			return;
		}
		File image = new File(folder, name);
		try {
			if (!isInside(folder, image)) {
				return;
			}
		} catch (IOException e) {
			return;
		}
		if (image.exists() && !image.delete()) {
			Trace.trace(Trace.WARNING, "Could not delete synced photo " + image.getName());
		}
	}

	private static File ownedFile(File folder, String name) {
		if (!isSafeName(name)) {
			return null;
		}
		File file = new File(folder, name);
		try {
			if (!isInside(folder, file)) {
				return null;
			}
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
		if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")
				|| ".".equals(name) || name.indexOf('\0') >= 0 || PhotoMetadata.INDEX_NAME.equals(name)
				|| PhotoSources.FILE_NAME.equals(name)) {
			return false;
		}
		return true;
	}

	private static boolean sameLimit(Integer a, Integer b) {
		if (a == null || a.intValue() <= 0) {
			return b == null || b.intValue() <= 0;
		}
		return a.equals(b);
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
		if (scheme >= 0) {
			rest = rest.substring(scheme + 3);
		}
		return sanitize(rest);
	}

	private static String extension(String url) {
		if (url == null) {
			return "jpg";
		}
		String path = url;
		int query = path.indexOf('?');
		if (query >= 0) {
			path = path.substring(0, query);
		}
		int slash = path.lastIndexOf('/');
		int dot = path.lastIndexOf('.');
		if (dot <= slash) {
			return "jpg";
		}
		String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
		if ("jpeg".equals(ext)) {
			return "jpg";
		}
		if ("jpg".equals(ext) || "png".equals(ext) || "gif".equals(ext) || "webp".equals(ext)) {
			return ext;
		}
		return "jpg";
	}

	private static String sanitize(String value) {
		if (value == null || value.isEmpty()) {
			return "_";
		}
		StringBuilder sb = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_'
					|| c == '-') {
				sb.append(c);
			} else {
				sb.append('_');
			}
		}
		if (sb.length() == 0) {
			return "_";
		}
		return sb.toString();
	}
}
