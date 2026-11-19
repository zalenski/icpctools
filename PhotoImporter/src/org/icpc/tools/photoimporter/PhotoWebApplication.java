package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.feed.JSONWriter;
import org.icpc.tools.contest.model.photos.PhotoMetadata;

/** Application behind the standalone web adapter; no browser or servlet state owns import jobs. */
public final class PhotoWebApplication implements AutoCloseable {
	private record Connection(String url, String user, String password, boolean insecure) { }
	private record Session(Connection connection, CDSPhotoClient cds, PhotoSyncService service, File data) { }
	private final File data;
	private volatile Session session;
	private volatile String error;
	private volatile boolean loading;
	private volatile long lastAccess;
	private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "Photo importer polling");
		thread.setDaemon(true);
		return thread;
	});
	private boolean polling;
	private volatile boolean closed;

	public PhotoWebApplication(File data) throws IOException {
		this.data = data;
		File config = new File(data, "connection.json");
		if (config.exists()) {
			try {
				JsonObject saved = PhotoMetadata.readObject(config);
				session = createSession(new Connection(saved.getString("url"), saved.getString("user"),
						saved.getString("password"), saved.getBoolean("insecureCds")));
				loading = true;
				if (session.service.status().stream().anyMatch(job -> job.selected() && job.resumeOnRestart())) {
					ensurePolling();
				}
			} catch (IOException | RuntimeException e) {
				error = "Could not load saved CDS connection: " + PhotoHttp.redact(e.getMessage());
			}
		}
	}

	/** Safe browser snapshot. Passwords and API keys are never returned. */
	public JsonObject state() throws IOException {
		lastAccess = System.currentTimeMillis();
		ensurePolling();
		Session current = session;
		JsonObject result = new JsonObject();
		result.put("configured", current != null);
		result.put("loading", loading);
		result.put("error", error != null ? error : current == null ? null : current.service.error());
		result.put("dataDirectory", current == null ? data.getAbsolutePath() : current.data.getAbsolutePath());
		JsonObject connection = new JsonObject();
		connection.put("url", current == null ? "" : current.connection.url);
		connection.put("user", current == null ? "" : current.connection.user);
		connection.put("insecureCds", current != null && current.connection.insecure);
		connection.put("passwordConfigured", current != null && current.connection.password != null);
		result.put("connection", connection);
		result.put("profiles", PhotoSources.readCredentials(data).keySet().stream().sorted().toArray());
		List<JsonObject> catalogs = new ArrayList<>();
		List<JsonObject> jobs = new ArrayList<>();
		if (current != null) {
			for (Map.Entry<String, PhotoSources.Settings> entry : current.service.catalogs().entrySet()) {
				JsonObject catalog = new JsonObject();
				catalog.put("id", entry.getKey());
				catalog.put("available", current.service.available(entry.getKey()));
				catalog.put("refreshSeconds", entry.getValue().refreshSeconds);
				catalog.put("sources", entry.getValue().sources.stream().map(PhotoWebApplication::sourceJson).toArray());
				catalogs.add(catalog);
			}
			for (PhotoSyncService.Status status : current.service.status()) {
				JsonObject job = new JsonObject();
				job.put("catalog", status.catalog());
				job.put("importId", status.importId());
				job.put("state", status.state().name());
				job.put("selected", status.selected());
				job.put("periodic", status.periodic());
				job.put("resumeOnRestart", status.resumeOnRestart());
				job.put("nextRun", status.nextRun());
				job.put("lastSuccess", status.lastSuccess());
				job.put("available", status.available());
				job.put("uploaded", status.uploaded());
				job.put("removed", status.removed());
				job.put("error", status.error());
				JsonObject progress = new JsonObject();
				progress.put("phase", status.progress().phase());
				progress.put("processed", status.progress().processed());
				progress.put("total", status.progress().total());
				progress.put("downloaded", status.progress().downloaded());
				progress.put("failed", status.progress().failed());
				progress.put("photoId", status.progress().photoId());
				job.put("progress", progress);
				jobs.add(job);
			}
		}
		result.put("catalogs", catalogs.toArray());
		result.put("jobs", jobs.toArray());
		return result;
	}

	public synchronized void connect(JsonObject input) throws IOException {
		checkOpen();
		String url = required(input, "url");
		String user = text(input, "user");
		String password = input.getString("password");
		if (password != null && password.isEmpty()) {
			password = null;
		}
		Session old = session;
		if (password == null && old != null && old.connection.url.equals(url)
				&& java.util.Objects.equals(old.connection.user, user)) {
			password = old.connection.password;
		}
		Connection connection = new Connection(url, user, password, input.getBoolean("insecureCds"));
		Session candidate = createSession(connection);
		try {
			candidate.cds.catalogs(); // Verify the target and credentials before replacing a working connection.
			if (old != null && old.connection.equals(connection)) {
				candidate.service.close();
				refresh();
				return;
			}
			JsonObject saved = new JsonObject();
			saved.put("url", url);
			saved.put("user", user);
			saved.put("password", password);
			saved.put("insecureCds", connection.insecure);
			writePrivate(new File(data, "connection.json"), saved);
		} catch (IOException | RuntimeException e) {
			candidate.service.close();
			throw e;
		}
		if (old != null) {
			old.service.close();
		}
		session = candidate;
		error = null;
		refresh();
		ensurePolling();
	}

	private Session createSession(Connection connection) throws IOException {
		CDSPhotoClient cds = new CDSPhotoClient(URI.create(connection.url), connection.user, connection.password,
				connection.insecure);
		File state = new File(data, "state.json");
		File targetData = state.exists() && cds.target().toString().equals(PhotoMetadata.readObject(state).getString("target"))
				? data : new File(data, "targets/" + UUID.nameUUIDFromBytes(cds.target().toString().getBytes(StandardCharsets.UTF_8)));
		return new Session(connection, cds, new PhotoSyncService(cds, targetData, data), targetData);
	}

	public synchronized void putCredential(String name, JsonObject input) throws IOException {
		checkOpen();
		if (!name.matches("flickr/[A-Za-z0-9_-]+")) {
			throw new IllegalArgumentException("Expected a profile such as flickr/default");
		}
		File file = new File(data, "credentials.json");
		JsonObject saved = file.exists() ? PhotoMetadata.readObject(file) : new JsonObject();
		JsonObject profiles = saved.getJsonObject("profiles");
		JsonObject updated = profiles == null ? new JsonObject() : PhotoSources.copyExcept(profiles, name);
		if (input != null) {
			JsonObject profile = new JsonObject();
			profile.put("apiKey", required(input, "apiKey"));
			updated.put(name, profile);
		}
		JsonObject root = PhotoSources.copyExcept(saved, "profiles");
		root.put("profiles", updated);
		writePrivate(file, root);
		refresh();
		ensurePolling();
	}

	public synchronized void saveCatalog(String catalog, JsonObject input) throws IOException {
		Session current = requireSession();
		JsonObject definitions = PhotoSources.copyExcept(current.cds.sources(catalog), "refreshSeconds");
		int refresh = input.getInt("refreshSeconds");
		if (refresh < PhotoSources.MIN_REFRESH_SECONDS) {
			throw new IllegalArgumentException("Refresh must be at least 15 seconds");
		}
		definitions.put("refreshSeconds", refresh);
		current.cds.sources(catalog, definitions);
		current.service.poll();
	}

	public synchronized void saveSource(String catalog, String id, JsonObject input) throws IOException {
		Session current = requireSession();
		JsonObject source = input.getJsonObject("source");
		if (source == null || !id.equals(source.getString("id"))) {
			throw new IllegalArgumentException("Source ID does not match");
		}
		PhotoSourceRegistry.create(required(source, "source"), null, text(source, "tag"));
		String destination = text(input, "destination");
		if (destination == null) {
			destination = catalog;
		}
		JsonObject definitions = current.cds.sources(destination);
		List<Object> sources = new ArrayList<>(Arrays.asList(definitions.getArray("sources")));
		JsonObject previous = null;
		for (Object entry : sources) {
			JsonObject candidate = (JsonObject) entry;
			if (id.equals(candidate.getString("id"))) {
				previous = candidate;
			}
		}
		if ((!destination.equals(catalog) || input.getBoolean("create")) && previous != null) {
			throw new IllegalStateException("That source ID already exists in the destination");
		}
		if (previous != null) {
			JsonObject merged = new JsonObject();
			for (String key : previous.props.keySet()) {
				if (!source.props.containsKey(key)) {
					merged.put(key, previous.props.get(key));
				}
			}
			for (String key : source.props.keySet()) {
				merged.put(key, source.props.get(key));
			}
			source = merged;
		}
		sources.remove(previous);
		sources.add(source);
		JsonObject updated = PhotoSources.copyExcept(definitions, "sources");
		updated.put("sources", sources.toArray());
		PhotoSources.parse(updated);
		current.cds.sources(destination, updated);
		if (!destination.equals(catalog)) {
			removeSource(catalog, id);
		}
		current.service.poll();
		if (input.getBoolean("runHere")) {
			current.service.start(destination, id, input.getBoolean("resumeOnRestart"));
		} else {
			String target = destination;
			if (current.service.status().stream().anyMatch(job -> job.catalog().equals(target) && job.importId().equals(id) && job.periodic())) {
				current.service.stop(destination, id);
			}
		}
	}

	public synchronized void removeSource(String catalog, String id) throws IOException {
		Session current = requireSession();
		JsonObject definitions = current.cds.sources(catalog);
		List<Object> sources = new ArrayList<>();
		for (Object item : definitions.getArray("sources")) {
			if (!id.equals(((JsonObject) item).getString("id"))) {
				sources.add(item);
			}
		}
		JsonObject updated = PhotoSources.copyExcept(definitions, "sources");
		updated.put("sources", sources.toArray());
		current.cds.sources(catalog, updated);
		current.service.poll();
	}

	public void action(String catalog, String id, String action, JsonObject input) throws IOException {
		PhotoSyncService service = requireSession().service;
		switch (action) {
			case "run": {
				if (!service.runNow(catalog, id)) {
					throw new IllegalStateException("This import is already running or stopping");
				}
				break;
			}
			case "stop": service.stop(catalog, id); break;
			case "start": service.start(catalog, id, input.getBoolean("resumeOnRestart")); break;
			case "activation": service.setEnabled(catalog, id, input.getBoolean("enabled")); break;
			case "restart": service.setResumeOnRestart(catalog, id, input.getBoolean("enabled")); break;
			default: throw new IllegalArgumentException("Unknown job action");
		}
	}

	public JsonObject results(String catalog, String id) throws IOException {
		Session current = requireSession();
		if (!catalog.matches("photos(?:-[A-Za-z0-9_-]+)?") || !id.matches("[A-Za-z0-9_-]+")) {
			throw new IllegalArgumentException("Invalid catalog or import");
		}
		JsonObject result = new JsonObject();
		result.put("photos", current.cds.index(catalog).stream().filter(photo -> id.equals(photo.getImportId()))
				.map(PhotoMetadata::toJson).toArray());
		File local = new File(current.data, "cache/" + catalog + "/local-index-" + id + ".json");
		result.put("local", local.exists() ? PhotoMetadata.readObject(local).getArray("files") : new Object[0]);
		result.put("failures", current.service.failures(catalog, id).stream().map(failure -> {
			JsonObject item = new JsonObject();
			item.put("photoId", failure.photoId());
			item.put("message", failure.message());
			return item;
		}).toArray());
		return result;
	}

	private void ensurePolling() {
		synchronized (poller) {
			if (!polling && !closed) {
				polling = true;
				poller.scheduleWithFixedDelay(() -> {
					Session current = session;
					if (current != null && (System.currentTimeMillis() - lastAccess < 30_000
							|| current.service.status().stream().anyMatch(job -> job.periodic() || job.state() == PhotoSyncService.State.RUNNING))) {
						refresh();
					}
				}, 0, 10, TimeUnit.SECONDS);
			}
		}
	}

	private void refresh() {
		Session current = session;
		if (current == null) {
			return;
		}
		loading = true;
		try {
			current.service.poll();
			if (session == current) {
				error = null;
			}
		} catch (IOException | RuntimeException e) {
			if (session == current) {
				error = PhotoHttp.redact(e.getMessage());
			}
		} finally {
			if (session == current) {
				loading = false;
			}
		}
	}

	private Session requireSession() throws IOException {
		checkOpen();
		Session current = session;
		if (current == null) {
			throw new IllegalStateException("Connect to a CDS first");
		}
		return current;
	}

	private void checkOpen() throws IOException {
		if (closed) {
			throw new IOException("Photo web application is closed");
		}
	}

	private static JsonObject sourceJson(PhotoSources.Source source) {
		JsonObject result = new JsonObject();
		result.put("id", source.id);
		result.put("source", source.uri);
		result.put("credentialProfile", source.credentialProfile);
		result.put("tag", source.tag);
		result.put("maxWidth", source.maxWidth);
		result.put("maxHeight", source.maxHeight);
		result.put("enabled", source.enabled);
		return result;
	}

	private static String text(JsonObject input, String name) {
		String value = input.getString(name);
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String required(JsonObject input, String name) {
		String value = text(input, name);
		if (value == null) {
			throw new IllegalArgumentException("Missing " + name);
		}
		return value;
	}

	private static void writePrivate(File file, JsonObject object) throws IOException {
		Files.createDirectories(file.getParentFile().toPath());
		Path temporary = Files.createTempFile(file.getParentFile().toPath(), "photo-private-", ".tmp");
		try {
			try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
				new JSONWriter(writer).writeObject(object);
			}
			Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	@Override
	public synchronized void close() {
		if (!closed) {
			closed = true;
			poller.shutdownNow();
			if (session != null) {
				session.service.close();
			}
		}
	}
}
