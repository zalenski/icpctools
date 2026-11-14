package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.icpc.tools.contest.Trace;

/** Reloads catalog definitions and private credential profiles before each sync cycle. */
public final class PhotoSyncService {
	private final CDSPhotoClient cds;
	private final File data;

	private static final class Run {
		final PhotoSources.Source source;
		final String key;
		final long time;

		Run(PhotoSources.Source source, String key, long time) {
			this.source = source;
			this.key = key;
			this.time = time;
		}
	}

	private final Map<File, Map<String, Run>> runs = new HashMap<>();

	public PhotoSyncService(CDSPhotoClient cds, File data) {
		this.cds = cds;
		this.data = data;
	}

	public boolean tick() throws IOException {
		Map<String, String> credentials = PhotoSources.readCredentials(data);
		Set<File> active = new HashSet<>();
		boolean successful = true;
		for (String catalog : cds.catalogs()) {
			if (!("photos".equals(catalog) || catalog.matches("photos-[A-Za-z0-9_-]+"))) {
				throw new IOException("CDS returned an invalid catalog name");
			}
			File cache = new File(data, "cache/" + catalog);
			active.add(cache);
			try {
				successful &= syncCatalog(catalog, cache, PhotoSources.parse(cds.sources(catalog)), credentials);
			} catch (Exception e) {
				successful = false;
				Trace.trace(Trace.WARNING, "Photo sync failed for " + catalog + ": "
						+ PhotoHttp.redact(e.getMessage()) + ". Keeping available photos.");
			}
		}
		runs.keySet().retainAll(active);
		return successful;
	}

	private boolean syncCatalog(String catalog, File cache, PhotoSources.Settings settings, Map<String, String> credentials)
			throws IOException {
		Map<String, Run> catalogRuns = runs.computeIfAbsent(cache, file -> new HashMap<>());
		Set<String> enabled = new HashSet<>();
		boolean successful = true;
		long now = System.currentTimeMillis();
		// Keep each catalog's originals separate even when it imports the same source as another.
		File originals = new File(data, "originals/" + catalog);
		for (PhotoSources.Source configured : settings.sources) {
			if (!configured.enabled) {
				continue;
			}
			enabled.add(configured.id);
			String key = credentials.get(configured.credentialProfile);
			Run previous = catalogRuns.get(configured.id);
			if (previous != null && configured.equals(previous.source) && Objects.equals(key, previous.key)
					&& now - previous.time < settings.refreshSeconds * 1000L) {
				continue;
			}
			try {
				if (key == null && !configured.uri.startsWith("file:")) {
					throw new IOException("Missing credential profile for import " + configured.id);
				}
				PhotoSource source = PhotoSourceRegistry.create(configured.uri, key, configured.tag);
				CatalogPublisher publisher = new CatalogPublisher(cds, catalog, configured.id, cache);
				PhotoSync.Result result = new PhotoSync(() -> System.nanoTime() / 1_000_000, publisher).sync(cache,
						originals, source, configured.id, configured.maxWidth, configured.maxHeight);
				successful &= result.failed == 0;
			} catch (Exception e) {
				successful = false;
				Trace.trace(Trace.WARNING, "Photo sync failed for import " + configured.id + " ("
						+ PhotoHttp.redact(e.getMessage()) + "). Keeping cached photos.");
			}
			catalogRuns.put(configured.id, new Run(configured, key, now));
		}
		catalogRuns.keySet().retainAll(enabled);
		return successful;
	}
}
