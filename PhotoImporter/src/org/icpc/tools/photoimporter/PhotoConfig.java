package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.feed.JSONWriter;
import org.icpc.tools.contest.model.photos.PhotoMetadata;

/** Private connection and key settings shared by the CLI and web launcher. */
final class PhotoConfig {
	private PhotoConfig() { }

	static JsonObject connection(File data) throws IOException {
		File file = new File(data, "connection.json");
		return file.isFile() ? PhotoSources.copyExcept(PhotoMetadata.readObject(file)) : new JsonObject();
	}

	static File targetData(File data, CDSPhotoClient cds) throws IOException {
		File state = new File(data, "state.json");
		if (state.isFile() && cds.target().toString().equals(PhotoMetadata.readObject(state).getString("target"))) {
			return data;
		}
		return new File(data, "targets/" + UUID.nameUUIDFromBytes(cds.target().toString().getBytes(StandardCharsets.UTF_8)));
	}

	static String profile(String name) {
		String profile = name == null ? PhotoSources.DEFAULT_PROFILE : name.startsWith("flickr/") ? name : "flickr/" + name;
		if (!profile.matches("flickr/[A-Za-z0-9_-]+")) {
			throw new IllegalArgumentException("Expected a profile such as flickr/default");
		}
		return profile;
	}

	static void credential(File data, String name, String key) throws IOException {
		name = profile(name);
		File file = new File(data, PhotoSources.CREDENTIALS_FILE);
		JsonObject root = file.isFile() ? PhotoSources.copyExcept(PhotoMetadata.readObject(file)) : new JsonObject();
		JsonObject profiles = root.getJsonObject("profiles");
		profiles = profiles == null ? new JsonObject() : PhotoSources.copyExcept(profiles, name);
		if (key != null) {
			if (key.isBlank()) {
				throw new IllegalArgumentException("Flickr key must not be empty");
			}
			JsonObject entry = new JsonObject();
			entry.put("apiKey", key);
			profiles.put(name, entry);
		}
		root.put("profiles", profiles);
		writePrivate(file, root);
	}

	static void writePrivate(File file, JsonObject object) throws IOException {
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
}
