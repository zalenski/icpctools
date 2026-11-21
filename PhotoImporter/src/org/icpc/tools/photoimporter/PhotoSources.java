package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.icpc.tools.contest.model.feed.JSONParser;
import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;

/** User-edited catalog imports. Credentials are stored separately, outside served catalogs. */
public final class PhotoSources {
	public static final String FILE_NAME = "photo-sources.json";
	public static final String CREDENTIALS_FILE = "credentials.json";
	public static final String DEFAULT_PROFILE = "flickr/default";
	public static final int DEFAULT_REFRESH_SECONDS = 300;
	public static final int MIN_REFRESH_SECONDS = 15;

	public static final class Source {
		public final String id;
		public final String uri;
		public final String credentialProfile;
		public final String tag;
		public final Integer maxWidth;
		public final Integer maxHeight;
		public final boolean enabled;

		private Source(JsonObject obj, JsonObject defaults) throws IOException {
			id = required(obj, "id");
			if (!id.matches("[A-Za-z0-9_-]+")) {
				throw new IOException("Import IDs may contain letters, digits, underscores and hyphens");
			}
			uri = required(obj, "source");
			String profile = text(obj, "credentialProfile");
			credentialProfile = uri.startsWith("file:") ? null : profile == null ? DEFAULT_PROFILE : profile;
			tag = text(obj, "tag");
			maxWidth = dimension(obj, defaults, "maxWidth");
			maxHeight = dimension(obj, defaults, "maxHeight");
			enabled = obj.getBoolean("enabled");
			if (obj.containsKey("apiKey")) {
				throw new IOException("Use credentialProfile instead of apiKey in " + FILE_NAME);
			}
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof Source)) {
				return false;
			}
			Source s = (Source) other;
			return id.equals(s.id) && uri.equals(s.uri) && Objects.equals(credentialProfile, s.credentialProfile)
					&& Objects.equals(tag, s.tag) && Objects.equals(maxWidth, s.maxWidth)
					&& Objects.equals(maxHeight, s.maxHeight) && enabled == s.enabled;
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, uri, credentialProfile, tag, maxWidth, maxHeight, enabled);
		}
	}

	public static final class Settings {
		public final int refreshSeconds;
		public final List<Source> sources;

		private Settings(int refreshSeconds, List<Source> sources) {
			this.refreshSeconds = refreshSeconds;
			this.sources = Collections.unmodifiableList(sources);
		}
	}

	private PhotoSources() {
		// utility
	}

	public static Settings read(File catalog) throws IOException {
		return parse(readObject(new File(catalog, FILE_NAME)));
	}

	public static Settings parse(JsonObject root) throws IOException {
		if (root.containsKey("apiKey")) {
			throw new IOException("Keep API keys in " + CREDENTIALS_FILE);
		}
		int refresh = DEFAULT_REFRESH_SECONDS;
		if (root.containsKey("refreshSeconds")) {
			refresh = root.getInt("refreshSeconds");
			if (refresh < 0) {
				throw new IOException("Invalid refreshSeconds in " + FILE_NAME);
			}
			refresh = Math.max(MIN_REFRESH_SECONDS, refresh);
		}
		List<Source> sources = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		Object[] entries = root.getArray("sources");
		if (entries == null) {
			throw new IOException("Missing sources array in " + FILE_NAME);
		}
		for (Object entry : entries) {
			if (!(entry instanceof JsonObject)) {
				throw new IOException("Invalid source in " + FILE_NAME);
			}
			Source source = new Source((JsonObject) entry, root);
			if (!ids.add(source.id)) {
				throw new IOException("Duplicate import ID in " + FILE_NAME);
			}
			sources.add(source);
		}
		return new Settings(refresh, sources);
	}

	/** Replace one activation flag while preserving other source and catalog settings. */
	public static JsonObject withEnabled(JsonObject definitions, String importId, boolean enabled) throws IOException {
		parse(definitions);
		Object[] sources = definitions.getArray("sources").clone();
		boolean found = false;
		for (int i = 0; i < sources.length; i++) {
			JsonObject source = (JsonObject) sources[i];
			if (importId.equals(source.getString("id"))) {
				JsonObject updated = copyExcept(source, "enabled");
				updated.put("enabled", enabled);
				sources[i] = updated;
				found = true;
			}
		}
		if (!found) {
			throw new IOException("Unknown photo import");
		}
		JsonObject updated = copyExcept(definitions, "sources");
		updated.put("sources", sources);
		return updated;
	}

	/** Strip legacy scheduling fields before saving shared definitions on CDS. */
	static JsonObject definitions(JsonObject root) {
		JsonObject result = copyExcept(root, "refreshSeconds", "enabled");
		Object[] sources = root.getArray("sources");
		if (sources != null) {
			Object[] cleaned = sources.clone();
			for (int i = 0; i < cleaned.length; i++) {
				if (cleaned[i] instanceof JsonObject source) {
					cleaned[i] = copyExcept(source, "enabled", "refreshSeconds");
				}
			}
			result.put("sources", cleaned);
		}
		return result;
	}

	static JsonObject copyExcept(JsonObject object, String... excluded) {
		JsonObject result = new JsonObject();
		result.props = new java.util.LinkedHashMap<>();
		for (String key : object.props.keySet()) {
			if (!java.util.Arrays.asList(excluded).contains(key)) {
				result.put(key, object.props.get(key));
			}
		}
		return result;
	}

	public static Map<String, String> readCredentials(File configFolder) throws IOException {
		File file = new File(configFolder, CREDENTIALS_FILE);
		if (!file.isFile()) {
			return Collections.emptyMap();
		}
		JsonObject profiles = readObject(file).getJsonObject("profiles");
		if (profiles == null) {
			throw new IOException("Missing profiles object in credentials.json");
		}
		Map<String, String> keys = new HashMap<>();
		for (String name : profiles.props.keySet()) {
			JsonObject profile = profiles.getJsonObject(name);
			if (profile == null) {
				throw new IOException("Invalid credential profile");
			}
			keys.put(name, required(profile, "apiKey"));
		}
		return keys;
	}

	private static JsonObject readObject(File file) throws IOException {
		try {
			return new JSONParser(Files.readString(file.toPath(), StandardCharsets.UTF_8)).readObject();
		} catch (RuntimeException e) {
			throw new IOException("Invalid JSON in " + file.getName());
		}
	}

	private static String text(JsonObject obj, String key) throws IOException {
		Object value = obj.get(key);
		if (value == null || "null".equals(value)) {
			return null;
		}
		if (!(value instanceof String)) {
			throw new IOException("Expected text for " + key);
		}
		String text = obj.getString(key).trim();
		return text.isEmpty() ? null : text;
	}

	private static String required(JsonObject obj, String key) throws IOException {
		String value = text(obj, key);
		if (value == null) {
			throw new IOException("Missing " + key);
		}
		return value;
	}

	private static Integer dimension(JsonObject obj, JsonObject defaults, String name) throws IOException {
		JsonObject settings = obj.containsKey(name) ? obj : defaults;
		if (!settings.containsKey(name) || settings.isNull(name)) {
			return null;
		}
		int value = settings.getInt(name);
		if (value <= 0) {
			throw new IOException("Invalid " + name + " in " + FILE_NAME);
		}
		return Integer.valueOf(value);
	}
}
