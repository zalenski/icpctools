package org.icpc.tools.cds.photos;

import java.io.File;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.icpc.tools.cds.CDSConfig;
import org.icpc.tools.contest.Trace;

/**
 * Background sync from {@link PhotoSources} into {@code {icpc.cds.config}/present/photos}. Wakes
 * often enough to notice a runtime source change, and downloads on the configured interval.
 */
public final class PhotoSyncService {
	private static final PhotoSyncService INSTANCE = new PhotoSyncService();

	private long lastSync;
	private String lastSignature = "";

	private PhotoSyncService() {
		// singleton
	}

	public static void start(ScheduledExecutorService executor) {
		executor.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				try {
					INSTANCE.tick();
				} catch (Exception e) {
					Trace.trace(Trace.WARNING, "Photo sync failed. Keeping cached photos.", e);
				}
			}
		}, 10, 10, TimeUnit.SECONDS);
	}

	void tick() {
		// Loading the CDS config is what fills PhotoSources from cdsConfig.xml.
		if (CDSConfig.getInstance() == null)
			return;

		PhotoSources.Settings settings = PhotoSources.getInstance().get();
		if (settings.getSources().isEmpty())
			return;

		long now = System.currentTimeMillis();
		String signature = signature(settings);
		boolean changed = !signature.equals(lastSignature);
		long intervalMs = settings.getRefreshSeconds() * 1000L;
		if (!changed && lastSync != 0 && now - lastSync < intervalMs)
			return;

		File folder = new File(CDSConfig.getFolder(), "present" + File.separator + "photos");
		PhotoSync sync = new PhotoSync();
		for (PhotoSources.Source configured : settings.getSources()) {
			String key = configured.getApiKey() != null ? configured.getApiKey() : settings.getApiKey();
			try {
				PhotoSource source = PhotoSourceRegistry.create(configured.getUri(), key);
				PhotoSync.Result result = sync.sync(folder, source);
				Trace.trace(Trace.INFO, "Photo sync " + source.getUri() + " listed " + result.remote + ", downloaded "
						+ result.downloaded + ", removed " + result.removed);
			} catch (Exception e) {
				Trace.trace(Trace.WARNING,
						"Photo sync failed for " + configured.getUri() + " (" + PhotoHttp.redact(e.getMessage())
								+ "). Keeping cached photos.");
			}
		}
		lastSync = now;
		lastSignature = signature;
	}

	private static String signature(PhotoSources.Settings settings) {
		StringBuilder sb = new StringBuilder();
		for (PhotoSources.Source source : settings.getSources()) {
			sb.append(source.getUri() == null ? "" : source.getUri());
			sb.append('\n');
			sb.append(source.getApiKey() == null ? "" : source.getApiKey());
			sb.append('\n');
		}
		sb.append(settings.getApiKey() == null ? "" : settings.getApiKey());
		return sb.toString();
	}
}
