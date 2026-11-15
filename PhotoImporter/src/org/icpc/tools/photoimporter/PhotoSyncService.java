package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.icpc.tools.contest.Trace;
import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.photos.PhotoMetadata;

/** Importer-owned jobs; polling discovers CDS definitions without taking ownership of new jobs. */
public final class PhotoSyncService implements AutoCloseable {
	public enum State { STOPPED, DISABLED, MISSING_CREDENTIALS, QUEUED, RUNNING, STOPPING, SUCCEEDED, FAILED }

	/** Immutable UI snapshot. No source credentials are exposed or persisted here. */
	public record Status(String catalog, String importId, State state, boolean selected, boolean resumeOnRestart,
			PhotoSync.Progress progress, int uploaded, int removed, long lastAttempt, long lastSuccess, String error,
			boolean enabled, boolean periodic, long nextRun, int available) { }

	private static final class Job {
		final String catalog;
		final String id;
		PhotoSources.Source source;
		String key;
		int refreshSeconds;
		boolean selected;
		boolean resume;
		boolean active;
		long lastAttempt;
		long lastSuccess;
		long nextRun;
		String error;
		String configError;
		State state = State.STOPPED;
		PhotoSync.Progress progress = new PhotoSync.Progress("idle", 0, 0, 0, 0);
		int uploaded;
		int removed;
		int available = -1;
		Run run;

		Job(String catalog, String id) {
			this.catalog = catalog;
			this.id = id;
		}
	}

	private static final class Run {
		final PhotoSources.Source source;
		final String key;
		Future<?> future;
		boolean started;
		volatile boolean cancelled;

		Run(Job job) {
			source = job.source;
			key = job.key;
		}
	}

	private final CDSPhotoClient cds;
	private final File data;
	private final Map<String, Job> jobs = new LinkedHashMap<>();
	private final Object pollLock = new Object();
	// Serialize cache/index writes, including different imports in the same catalog.
	private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "Photo importer");
		thread.setDaemon(true);
		return thread;
	});
	private boolean closed;
	private String error;

	public PhotoSyncService(CDSPhotoClient cds, File data) throws IOException {
		this.cds = cds;
		this.data = data;
		loadState();
	}

	/** Discover/reload definitions and credentials; never wait for an import to finish. */
	public void poll() throws IOException {
		try {
			refresh();
		} catch (IOException | RuntimeException e) {
			synchronized (this) {
				error = PhotoHttp.redact(e.getMessage());
			}
			throw e;
		}
	}

	private void refresh() throws IOException {
		synchronized (pollLock) {
			synchronized (this) {
				checkOpen();
			}
			Map<String, String> credentials = PhotoSources.readCredentials(data);
			List<String> catalogs = cds.catalogs();
			Set<String> seen = new HashSet<>();
			Set<String> unavailable = new HashSet<>();
			String failure = null;
			for (String catalog : catalogs) {
				validateCatalog(catalog);
				PhotoSources.Settings settings;
				try {
					settings = PhotoSources.parse(cds.sources(catalog));
				} catch (IOException | RuntimeException e) {
					unavailable.add(catalog);
					failure = "Could not read definitions for " + catalog + ": " + PhotoHttp.redact(e.getMessage());
					continue;
				}
				Map<String, Integer> counts = null;
				try {
					counts = new LinkedHashMap<>();
					for (PhotoMetadata photo : cds.index(catalog)) {
						counts.merge(photo.getImportId(), 1, Integer::sum);
					}
				} catch (IOException e) {
					counts = null;
					failure = "Could not read photo counts for " + catalog + ": " + PhotoHttp.redact(e.getMessage());
				}
				synchronized (this) {
					checkOpen();
					for (PhotoSources.Source source : settings.sources) {
						String id = jobId(catalog, source.id);
						seen.add(id);
						Job job = jobs.computeIfAbsent(id, value -> new Job(catalog, source.id));
						String key = credentials.get(source.credentialProfile);
						if (!source.equals(job.source) || !Objects.equals(key, job.key)) {
							cancel(job);
							job.nextRun = 0;
						}
						job.source = source;
						job.key = key;
						job.refreshSeconds = settings.refreshSeconds;
						if (counts != null) {
							job.available = counts.getOrDefault(source.id, 0);
						}
						job.configError = !source.uri.startsWith("file:") && key == null
								? (source.credentialProfile == null ? "No credential profile configured"
										: "Missing credential profile: " + source.credentialProfile) : null;
						schedule(job);
					}
				}
			}
			synchronized (this) {
				checkOpen();
				error = failure;
				for (Map.Entry<String, Job> entry : jobs.entrySet()) {
					Job job = entry.getValue();
					if (!seen.contains(entry.getKey()) && !unavailable.contains(job.catalog)) {
						cancel(job);
						job.source = null;
						job.configError = "Import definition is no longer available";
						schedule(job);
					}
				}
			}
		}
	}

	/** Explicit one-shot run of all enabled imports, without saving job selections. */
	public boolean tick() throws IOException {
		poll();
		List<Future<?>> pending = new ArrayList<>();
		synchronized (this) {
			for (Job job : jobs.values()) {
				if (job.source != null && job.source.enabled) {
					if (job.run == null && job.configError == null) {
						submit(job);
					}
					if (job.run != null) {
						pending.add(job.run.future);
					}
				}
			}
		}
		for (Future<?> future : pending) {
			try {
				future.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				PhotoSync.checkCancelled();
			} catch (CancellationException | ExecutionException e) {
				return false;
			}
		}
		synchronized (this) {
			return error == null && jobs.values().stream().filter(job -> job.source != null && job.source.enabled)
					.allMatch(job -> job.state == State.SUCCEEDED);
		}
	}

	public synchronized List<Status> status() {
		List<Status> result = new ArrayList<>();
		for (Job job : jobs.values()) {
			result.add(new Status(job.catalog, job.id, job.state, job.selected, job.resume, job.progress,
					job.uploaded, job.removed, job.lastAttempt, job.lastSuccess,
					job.configError == null ? job.error : job.configError,
					job.source != null && job.source.enabled, job.active,
					job.active && job.source != null && job.source.enabled && job.configError == null && job.run == null
							? job.nextRun : 0, job.available));
		}
		return List.copyOf(result);
	}

	public synchronized String error() {
		return error;
	}

	/** Run one cycle without changing periodic selection, activation or restart preferences. */
	public synchronized boolean runNow(String catalog, String importId) throws IOException {
		Job job = requireJob(catalog, importId);
		if (job.source == null) {
			throw new IOException("Import definition is no longer available");
		}
		if (job.configError != null) {
			throw new IOException(job.configError);
		}
		if (job.run != null) {
			return false;
		}
		submit(job);
		return true;
	}

	/** Save shared activation on CDS, then reconcile this importer's jobs. */
	public void setEnabled(String catalog, String importId, boolean enabled) throws IOException {
		synchronized (pollLock) {
			synchronized (this) {
				requireJob(catalog, importId);
			}
			cds.sources(catalog, PhotoSources.withEnabled(cds.sources(catalog), importId, enabled));
			if (!enabled) {
				synchronized (this) {
					cancel(requireJob(catalog, importId));
				}
			}
			poll();
		}
	}

	/** Start an explicitly selected job; resumeOnRestart is local to this importer. */
	public synchronized void start(String catalog, String importId, boolean resumeOnRestart) throws IOException {
		Job job = requireJob(catalog, importId);
		boolean selected = job.selected;
		boolean resume = job.resume;
		job.selected = true;
		job.resume = resumeOnRestart;
		try {
			saveState();
		} catch (IOException e) {
			job.selected = selected;
			job.resume = resume;
			throw e;
		}
		job.active = true;
		job.nextRun = 0;
		schedule(job);
	}

	public synchronized void stop(String catalog, String importId) throws IOException {
		Job job = requireJob(catalog, importId);
		boolean selected = job.selected;
		job.selected = false;
		try {
			saveState();
		} catch (IOException e) {
			job.selected = selected;
			throw e;
		}
		job.active = false;
		cancel(job);
		schedule(job);
	}

	/** Retry with the current definition, retaining the job's restart preference. */
	public synchronized void retry(String catalog, String importId) throws IOException {
		Job job = requireJob(catalog, importId);
		cancel(job);
		start(catalog, importId, job.resume);
	}

	private void schedule(Job job) {
		if (job.run != null) {
			return;
		}
		if (job.source == null || !job.source.enabled) {
			job.state = State.DISABLED;
		} else if (job.configError != null) {
			job.state = State.MISSING_CREDENTIALS;
		} else if (!job.active) {
			job.state = State.STOPPED;
		} else if (System.currentTimeMillis() >= job.nextRun) {
			submit(job);
		}
	}

	private void submit(Job job) {
		Run run = new Run(job);
		job.run = run;
		job.state = State.QUEUED;
		job.error = null;
		job.progress = new PhotoSync.Progress("queued", 0, 0, 0, 0);
		job.uploaded = 0;
		job.removed = 0;
		run.future = worker.submit(() -> execute(job, run));
	}

	private void execute(Job job, Run run) {
		synchronized (this) {
			if (run.cancelled) {
				return;
			}
			run.started = true;
			job.state = State.RUNNING;
			job.lastAttempt = System.currentTimeMillis();
			job.progress = new PhotoSync.Progress("reading catalog", 0, 0, 0, 0);
		}
		String failure = null;
		boolean success = false;
		try {
			PhotoSource source = PhotoSourceRegistry.create(run.source.uri, run.key, run.source.tag);
			File cache = new File(data, "cache/" + job.catalog);
			CatalogPublisher publisher = new CatalogPublisher(cds, job.catalog, job.id, cache, update -> {
				synchronized (this) {
					job.uploaded = update.uploaded();
					job.removed = update.removed();
					job.available = update.available();
				}
			});
			PhotoSync.Result result = new PhotoSync(() -> System.nanoTime() / 1_000_000, publisher, progress -> {
				synchronized (this) {
					job.progress = progress;
				}
			}, () -> run.cancelled).sync(cache, new File(data, "originals/" + job.catalog), source, job.id,
					run.source.maxWidth, run.source.maxHeight);
			success = result.failed == 0;
			if (!success) {
				failure = result.failed + " photos failed";
			}
		} catch (Exception e) {
			failure = PhotoHttp.redact(e.getMessage());
		} finally {
			synchronized (this) {
				job.run = null;
				if (run.cancelled || Thread.currentThread().isInterrupted()) {
					job.state = State.STOPPED;
				} else {
					job.state = success ? State.SUCCEEDED : State.FAILED;
					job.error = failure;
					job.nextRun = System.currentTimeMillis() + job.refreshSeconds * 1000L;
					if (success) {
						job.lastSuccess = System.currentTimeMillis();
						try {
							saveState();
						} catch (IOException e) {
							job.error = "Could not save importer state: " + PhotoHttp.redact(e.getMessage());
							job.state = State.FAILED;
						}
					}
					if (job.error != null) {
						Trace.trace(Trace.WARNING, "Photo import " + job.catalog + "/" + job.id + ": " + job.error);
					}
				}
				if (!closed && run.cancelled) {
					schedule(job);
				}
			}
		}
	}

	private void cancel(Job job) {
		Run run = job.run;
		if (run != null) {
			run.cancelled = true;
			run.future.cancel(true);
			if (run.started) {
				job.state = State.STOPPING;
			} else {
				job.run = null;
				job.state = State.STOPPED;
			}
		}
	}

	private Job requireJob(String catalog, String importId) throws IOException {
		checkOpen();
		Job job = jobs.get(jobId(catalog, importId));
		if (job == null) {
			throw new IOException("Unknown photo import");
		}
		return job;
	}

	private void checkOpen() throws IOException {
		if (closed) {
			throw new IOException("Photo importer is closed");
		}
	}

	@Override
	public void close() {
		synchronized (this) {
			if (!closed) {
				closed = true;
				for (Job job : jobs.values()) {
					job.active = false;
					cancel(job);
				}
				worker.shutdownNow();
			}
		}
		try {
			worker.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static String jobId(String catalog, String id) {
		return catalog + "/" + id;
	}

	private static void validateCatalog(String catalog) throws IOException {
		if (catalog == null || !("photos".equals(catalog) || catalog.matches("photos-[A-Za-z0-9_-]+"))) {
			throw new IOException("Invalid catalog name");
		}
	}

	private void loadState() throws IOException {
		File file = new File(data, "state.json");
		if (!file.exists()) {
			return;
		}
		JsonObject root = PhotoMetadata.readObject(file);
		if (!cds.target().toString().equals(root.getString("target"))) {
			throw new IOException("Importer data belongs to another CDS; use a separate data directory");
		}
		Object[] saved = root.getArray("jobs");
		if (saved == null) {
			throw new IOException("Missing jobs in importer state");
		}
		for (Object entry : saved) {
			if (!(entry instanceof JsonObject item)) {
				throw new IOException("Invalid importer job state");
			}
			String catalog = item.getString("catalog");
			String id = item.getString("importId");
			validateCatalog(catalog);
			if (id == null || !id.matches("[A-Za-z0-9_-]+")) {
				throw new IOException("Invalid import ID in importer state");
			}
			Job job = new Job(catalog, id);
			job.selected = item.getBoolean("selected");
			job.resume = item.getBoolean("resumeOnRestart");
			job.active = job.selected && job.resume;
			job.lastSuccess = item.containsKey("lastSuccess") ? item.getLong("lastSuccess") : 0;
			jobs.put(jobId(catalog, id), job);
		}
	}

	private void saveState() throws IOException {
		JsonObject root = new JsonObject();
		root.put("target", cds.target().toString());
		List<JsonObject> saved = new ArrayList<>();
		for (Job job : jobs.values()) {
			JsonObject item = new JsonObject();
			item.put("catalog", job.catalog);
			item.put("importId", job.id);
			item.put("selected", job.selected);
			item.put("resumeOnRestart", job.resume);
			item.put("lastSuccess", job.lastSuccess);
			saved.add(item);
		}
		root.put("jobs", saved.toArray());
		PhotoMetadata.writeObject(new File(data, "state.json"), root);
	}
}
