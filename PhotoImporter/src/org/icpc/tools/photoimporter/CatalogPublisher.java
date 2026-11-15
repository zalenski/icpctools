package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.icpc.tools.contest.model.photos.PhotoMetadata;

/** Publishes completed local photos; only a completed listing may remove this import's old photos. */
final class CatalogPublisher implements PhotoSync.Publisher {
	private final CDSPhotoClient cds;
	private final String catalog;
	private final String importId;
	private final File cache;
	private final BiConsumer<Integer, Integer> progress;
	private int uploaded;
	private int removed;
	private final Map<String, PhotoMetadata> remote = new HashMap<>();

	CatalogPublisher(CDSPhotoClient cds, String catalog, String importId, File cache) throws IOException {
		this(cds, catalog, importId, cache, (uploaded, removed) -> { });
	}

	CatalogPublisher(CDSPhotoClient cds, String catalog, String importId, File cache,
			BiConsumer<Integer, Integer> progress) throws IOException {
		this.progress = progress;
		this.cds = cds;
		this.catalog = catalog;
		this.importId = importId;
		this.cache = cache;
		for (PhotoMetadata metadata : cds.index(catalog)) {
			if (importId.equals(metadata.getImportId())) {
				remote.put(metadata.getPhotoId(), metadata);
			}
		}
	}

	@Override
	public void publish(List<PhotoMetadata> photos, boolean complete) throws IOException {
		Set<String> present = new HashSet<>();
		for (PhotoMetadata photo : photos) {
			PhotoSync.checkCancelled();
			if (!importId.equals(photo.getImportId())) {
				continue;
			}
			present.add(photo.getPhotoId());
			PhotoMetadata previous = remote.get(photo.getPhotoId());
			// CDS may choose a different filename to avoid an existing user's file.
			PhotoMetadata expected = previous == null ? photo : photo.withFile(previous.getFile());
			if (!PhotoSync.sameMetadata(previous, expected)) {
				if (!PhotoSync.isSafeName(photo.getFile())) {
					throw new IOException("Unsafe local photo filename");
				}
				File image = new File(cache, photo.getFile());
				if (!image.isFile()) {
					if (complete) {
						throw new IOException("Missing completed display image: " + photo.getFile());
					}
					continue;
				}
				remote.put(photo.getPhotoId(), cds.upload(catalog, importId, photo, image));
				progress.accept(++uploaded, removed);
			}
		}
		if (complete) {
			for (String id : new HashSet<>(remote.keySet())) {
				PhotoSync.checkCancelled();
				if (!present.contains(id)) {
					cds.delete(catalog, importId, id);
					remote.remove(id);
					progress.accept(uploaded, ++removed);
				}
			}
		}
	}
}
