package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.photos.PhotoMetadata;

/** Configure imports or run them directly, without a server or saved job selections. */
public final class PhotoImporter {
	private PhotoImporter() { }

	private static final Set<String> VALUES = Set.of("--data", "--cds", "--user", "--password", "--catalog",
			"--id", "--source", "--profile", "--flickr-key", "--tag", "--max-width", "--max-height", "--sources", "--period");

	public static void main(String[] args) throws Exception {
		run(args, System.out);
	}

	static void run(String[] args, PrintStream out) throws Exception {
		Map<String, String> options = new LinkedHashMap<>();
		List<String> runs = new ArrayList<>();
		int i = 0;
		// Keep the clients' positional connection syntax as an alternative to named options.
		if (args.length > 0 && !args[0].startsWith("--")) {
			options.put("--cds", args[i++]);
			if (i + 1 < args.length && !args[i].startsWith("--") && !args[i + 1].startsWith("--")) {
				options.put("--user", args[i++]);
				options.put("--password", args[i++]);
			}
		}
		while (i < args.length) {
			String option = args[i++];
			if ("--help".equals(option)) {
				help(out);
				return;
			}
			if ("--run".equals(option)) {
				runs.add(i < args.length && !args[i].startsWith("--") ? args[i++] : "");
			} else if ("--insecure-cds".equals(option) || "--secure-cds".equals(option)) {
				options.put(option, "true");
			} else if (VALUES.contains(option)) {
				if (i == args.length || args[i].startsWith("--")) {
					throw new IllegalArgumentException("Missing value for " + option);
				}
				if (options.put(option, args[i++]) != null) {
					throw new IllegalArgumentException("Duplicate option " + option);
				}
			} else {
				throw new IllegalArgumentException("Unknown option; use --help");
			}
		}
		long period = options.containsKey("--period") ? Integer.parseInt(options.get("--period")) : 0;
		if (options.containsKey("--period") && (period < 15 || runs.isEmpty())) {
			throw new IllegalArgumentException("Use --period SECONDS (at least 15) together with --run");
		}
		if (options.containsKey("--insecure-cds") && options.containsKey("--secure-cds")) {
			throw new IllegalArgumentException("Choose --insecure-cds or --secure-cds");
		}
		String id = options.get("--id");
		if (id != null && !id.matches("[A-Za-z0-9_-]+")) {
			throw new IllegalArgumentException("Import IDs may contain letters, digits, underscores and hyphens");
		}
		String catalog = catalog(options.getOrDefault("--catalog", "photos"));
		String profile = PhotoConfig.profile(options.get("--profile"));
		for (String limit : List.of("--max-width", "--max-height")) {
			if (options.containsKey(limit) && Integer.parseInt(options.get(limit)) < 0) {
				throw new IllegalArgumentException("Size limits must be non-negative; zero removes a limit");
			}
		}
		boolean edit = options.keySet().stream().anyMatch(Set.of("--source", "--tag", "--max-width", "--max-height")::contains)
				|| id != null && options.containsKey("--profile");
		if (edit && id == null) {
			throw new IllegalArgumentException("Use --id when configuring an import");
		}
		if (edit && options.containsKey("--sources")) {
			throw new IllegalArgumentException("Choose --sources FILE or individual import settings");
		}
		File data = new File(options.getOrDefault("--data", "photo-importer")).getAbsoluteFile();
		JsonObject connection = PhotoConfig.connection(data);
		boolean connectionChanged = options.keySet().stream().anyMatch(Set.of("--cds", "--user", "--password", "--insecure-cds", "--secure-cds")::contains);
		if (options.containsKey("--cds") && !options.get("--cds").equals(connection.getString("url"))) {
			connection = new JsonObject();
		}
		for (String field : List.of("cds", "user", "password")) {
			if (options.containsKey("--" + field)) {
				connection.put(field.equals("cds") ? "url" : field, options.get("--" + field));
			}
		}
		if (options.containsKey("--insecure-cds") || options.containsKey("--secure-cds")) {
			connection.put("insecureCds", options.containsKey("--insecure-cds"));
		}
		String url = connection.getString("url");
		CDSPhotoClient cds = url == null ? null : new CDSPhotoClient(URI.create(url), connection.getString("user"),
				connection.getString("password"), connection.getBoolean("insecureCds"));
		if (connectionChanged && cds == null) {
			throw new IllegalArgumentException("Configure --cds URL first");
		}
		if ((edit || options.containsKey("--sources") || !runs.isEmpty()) && cds == null) {
			throw new IllegalArgumentException("Configure --cds URL before configuring or running imports");
		}
		if (connectionChanged) {
			PhotoConfig.writePrivate(new File(data, "connection.json"), connection);
			out.println("Saved CDS connection: " + new File(data, "connection.json"));
		}
		if (options.containsKey("--flickr-key")) {
			PhotoConfig.credential(data, profile, options.get("--flickr-key"));
			out.println("Saved key profile " + profile + ": " + new File(data, "credentials.json"));
		}
		if (options.containsKey("--sources")) {
			JsonObject definitions = PhotoSources.definitions(PhotoMetadata.readObject(new File(options.get("--sources"))));
			PhotoSources.parse(definitions);
			cds.sources(catalog, definitions);
			out.println("Saved definitions on CDS: " + catalog);
		}
		if (edit) {
			JsonObject definitions = PhotoSources.definitions(cds.sources(catalog));
			List<Object> sources = new ArrayList<>(List.of(definitions.getArray("sources")));
			JsonObject source = null;
			for (Object entry : sources) {
				if (id.equals(((JsonObject) entry).getString("id"))) {
					source = (JsonObject) entry;
				}
			}
			if (source == null) {
				if (!options.containsKey("--source")) {
					throw new IllegalArgumentException("A new import requires --source");
				}
				source = new JsonObject();
				source.put("id", id);
				sources.add(source);
			}
			if (options.containsKey("--source")) {
				String uri = options.get("--source");
				if (!uri.matches("(?i)^(file:|flickr:|https?:).*")) {
					uri = Path.of(uri).toAbsolutePath().toUri().toString();
				}
				PhotoSourceRegistry.create(uri, null, null);
				source.put("source", uri);
			}
			boolean local = source.getString("source").startsWith("file:");
			if (local || options.containsKey("--profile") || source.getString("credentialProfile") == null) {
				source.put("credentialProfile", local ? null : profile);
			}
			if (options.containsKey("--tag")) {
				source.put("tag", options.get("--tag").isBlank() ? null : options.get("--tag"));
			}
			for (String dimension : List.of("width", "height")) {
				String option = "--max-" + dimension;
				if (options.containsKey(option)) {
					int limit = Integer.parseInt(options.get(option));
					source.put(dimension.equals("width") ? "maxWidth" : "maxHeight", limit == 0 ? null : Integer.toString(limit));
				}
			}
			definitions.put("sources", sources.toArray());
			PhotoSources.parse(definitions);
			cds.sources(catalog, definitions);
			out.println("Saved import on CDS: " + catalog + "/" + id);
		}
		if (runs.isEmpty()) {
			if (!connectionChanged && !edit && !options.containsKey("--sources") && !options.containsKey("--flickr-key")) {
				show(data, connection, cds, out);
			}
			return;
		}
		String configured = id == null ? "all" : catalog + "/" + id;
		runs = runs.stream().map(run -> run.isEmpty() ? configured : run).toList();
		if (options.containsKey("--catalog")) {
			String prefix = catalog + "/";
			runs = runs.stream().map(run -> run.contains("/") || run.equals("all") ? run : prefix + run).toList();
		}
		File targetData = PhotoConfig.targetData(data, cds);
		Thread runner = Thread.currentThread();
		Thread shutdown = new Thread(() -> {
			runner.interrupt();
			try {
				runner.join(5000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, "Photo importer shutdown");
		Runtime.getRuntime().addShutdownHook(shutdown);
		try {
			do {
				try {
					if (!importOnce(cds, data, targetData, runs, options.containsKey("--catalog") ? catalog : null, out)) {
						throw new IOException("One or more photo imports failed");
					}
				} catch (IOException e) {
					PhotoSync.checkCancelled();
					if (period == 0) {
						throw e;
					}
					out.println("Import failed: " + PhotoHttp.redact(e.getMessage()));
				}
				if (period > 0) {
					Thread.sleep(period * 1000L);
				}
			} while (period > 0);
		} finally {
			try {
				Runtime.getRuntime().removeShutdownHook(shutdown);
			} catch (IllegalStateException e) {
				// JVM shutdown is already in progress.
			}
		}
	}

	private static boolean importOnce(CDSPhotoClient cds, File credentials, File data, List<String> selected,
			String onlyCatalog, PrintStream out) throws IOException {
		Map<String, PhotoSources.Source> imports = new LinkedHashMap<>();
		for (String catalog : cds.catalogs()) {
			if (onlyCatalog == null || onlyCatalog.equals(catalog)) {
				for (PhotoSources.Source source : PhotoSources.parse(cds.sources(catalog)).sources) {
					imports.put(catalog + "/" + source.id, source);
				}
			}
		}
		Map<String, PhotoSources.Source> chosen = new LinkedHashMap<>();
		for (String selection : selected) {
			List<String> matches = imports.keySet().stream().filter(key -> selection.equals("all")
					|| key.equals(selection) || !selection.contains("/") && key.endsWith("/" + selection)).toList();
			if (!selection.equals("all") && matches.size() != 1) {
				throw new IOException("Import selection is missing or ambiguous: use catalog/import-id");
			}
			for (String match : matches) {
				chosen.put(match, imports.get(match));
			}
		}
		Map<String, String> keys = PhotoSources.readCredentials(credentials);
		boolean success = true;
		for (Map.Entry<String, PhotoSources.Source> entry : chosen.entrySet()) {
			PhotoSync.checkCancelled();
			String catalog = entry.getKey().split("/", 2)[0];
			PhotoSources.Source definition = entry.getValue();
			try {
				String key = keys.get(definition.credentialProfile);
				if (!definition.uri.startsWith("file:") && key == null) {
					throw new IOException("Missing credential profile: " + definition.credentialProfile);
				}
				PhotoSource source = PhotoSourceRegistry.create(definition.uri, key, definition.tag);
				File cache = new File(data, "cache/" + catalog);
				CatalogPublisher publisher = new CatalogPublisher(cds, catalog, definition.id, cache);
				PhotoSync.Result result = new PhotoSync(() -> System.nanoTime() / 1_000_000, publisher)
						.sync(cache, new File(data, "originals/" + catalog), source, definition.id, definition.maxWidth, definition.maxHeight);
				success &= result.failed == 0;
				out.println(entry.getKey() + ": " + result.downloaded + " prepared, " + result.failed + " failed");
			} catch (IOException | IllegalArgumentException e) {
				PhotoSync.checkCancelled();
				success = false;
				out.println(entry.getKey() + ": " + PhotoHttp.redact(e.getMessage()));
			}
		}
		return success;
	}

	private static void show(File data, JsonObject connection, CDSPhotoClient cds, PrintStream out) throws IOException {
		out.println("Connection (private): " + new File(data, "connection.json"));
		out.println("Credentials (private): " + new File(data, "credentials.json"));
		Set<String> profiles = PhotoSources.readCredentials(data).keySet();
		for (String name : profiles.stream().sorted().toList()) {
			out.println("  Key profile " + name + ": configured");
		}
		if (cds == null) {
			out.println("No CDS configured. Use --cds URL --user USER --password PASSWORD.");
			return;
		}
		out.println("CDS: " + cds.target() + " (user " + connection.getString("user") + ", password "
				+ (connection.getString("password") == null ? "missing" : "configured") + ")");
		out.println("Prepared images and resume indexes: " + PhotoConfig.targetData(data, cds));
		try {
			for (String catalog : cds.catalogs()) {
				out.println("Definitions on CDS: " + cds.target() + "/api/photos/catalogs/" + catalog + "/sources");
				out.println("  CDS config file: present/" + catalog + "/photo-sources.json");
				for (PhotoSources.Source source : PhotoSources.parse(cds.sources(catalog)).sources) {
					out.println("  " + catalog + "/" + source.id + " <- " + source.uri
							+ (source.credentialProfile == null ? "" : " [" + source.credentialProfile + (profiles.contains(source.credentialProfile) ? ": configured]" : ": missing]"))
							+ " maxWidth=" + source.maxWidth + " maxHeight=" + source.maxHeight
							+ (source.tag == null ? "" : " tag=" + source.tag));
				}
			}
		} catch (IOException e) {
			out.println("CDS unavailable: " + PhotoHttp.redact(e.getMessage()));
		}
	}

	private static String catalog(String value) {
		String catalog = value.equals("photos") ? value : value.startsWith("photos-") ? value : "photos-" + value;
		if (!catalog.matches("photos(?:-[A-Za-z0-9_-]+)?")) {
			throw new IllegalArgumentException("Invalid catalog name");
		}
		return catalog;
	}

	private static void help(PrintStream out) {
		out.println("PhotoImporter [options] (no options: show configuration; never starts imports)");
		out.println("  --data DIRECTORY                 Private configuration and cache (default photo-importer)");
		out.println("  --cds URL --user USER --password PASSWORD   Save the CDS connection");
		out.println("  --insecure-cds | --secure-cds     Save CDS TLS preference; source TLS is unchanged");
		out.println("  --flickr-key KEY [--profile ID]   Save a key (default flickr/default)");
		out.println("  --id ID --source URL_OR_PATH [--catalog NAME] [--profile ID] [--tag TAG]");
		out.println("  --max-width PIXELS --max-height PIXELS      Save limits; zero removes a limit");
		out.println("  --sources FILE [--catalog NAME]  Install shared definitions on CDS");
		out.println("  --run [ID|CATALOG/ID|all]         Run selected imports once; repeat for several");
		out.println("  --period SECONDS                 Repeat --run while running (minimum 15); never saved");
		out.println("Configuration arguments are saved. Definitions live on CDS; credentials stay local.");
	}
}
