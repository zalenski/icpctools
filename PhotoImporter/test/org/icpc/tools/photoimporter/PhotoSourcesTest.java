package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PhotoSourcesTest {
	@Test
	public void readsCatalogDefinitionsWithStableIdsAndProfiles() throws Exception {
		File catalog = Files.createTempDirectory("photo-sources").toFile();
		try {
			write(new File(catalog, PhotoSources.FILE_NAME), "{\"refreshSeconds\":1,\"maxWidth\":1920,"
					+ "\"maxHeight\":1080,\"sources\":[{\"id\":\"teams\",\"source\":\"flickr://album/9\","
					+ "\"credentialProfile\":\"flickr/default\",\"tag\":\"Team Photos\",\"maxWidth\":800,\"enabled\":true}]}");
			PhotoSources.Settings settings = PhotoSources.read(catalog);
			PhotoSources.Source source = settings.sources.get(0);
			assertEquals("teams", source.id);
			assertEquals("flickr://album/9", source.uri);
			assertEquals("flickr/default", source.credentialProfile);
			assertEquals("Team Photos", source.tag);
			assertEquals(Integer.valueOf(800), source.maxWidth);
			assertEquals(Integer.valueOf(1080), source.maxHeight);
		} finally {
			deleteTree(catalog);
		}
	}

	@Test
	public void rereadsEditsAndDefaultsToTheDefaultFlickrProfile() throws Exception {
		File catalog = Files.createTempDirectory("photo-source-edit").toFile();
		try {
			File config = new File(catalog, PhotoSources.FILE_NAME);
			write(config, "{\"sources\":[{\"id\":\"teams\",\"source\":\"flickr://album/9\"}]}");
			PhotoSources.Settings first = PhotoSources.read(catalog);
			assertEquals("flickr/default", first.sources.get(0).credentialProfile);
			assertEquals(null, first.sources.get(0).maxWidth);
			write(config, "{\"sources\":[{\"id\":\"teams\",\"source\":\"flickr://album/10\",\"enabled\":true}]}");
			PhotoSources.Source next = PhotoSources.read(catalog).sources.get(0);
			assertEquals("teams", next.id);
			assertEquals("flickr://album/10", next.uri);
		} finally {
			deleteTree(catalog);
		}
	}

	@Test
	public void readsCredentialsWithoutAnyCatalogAndReloadsKeys() throws Exception {
		File config = Files.createTempDirectory("photo-credentials").toFile();
		try {
			assertTrue(PhotoSources.readCredentials(config).isEmpty());
			File file = new File(config, PhotoSources.CREDENTIALS_FILE);
			Files.createDirectories(file.getParentFile().toPath());
			write(file, "{\"profiles\":{\"flickr/default\":{\"apiKey\":\"first\"},\"flickr/other\":{\"apiKey\":\"second\"}}}");
			Map<String, String> keys = PhotoSources.readCredentials(config);
			assertEquals("first", keys.get("flickr/default"));
			assertEquals("second", keys.get("flickr/other"));
			write(file, "{\"profiles\":{\"flickr/default\":{\"apiKey\":\"replacement\"}}}");
			assertEquals("replacement", PhotoSources.readCredentials(config).get("flickr/default"));
		} finally {
			deleteTree(config);
		}
	}

	@Test
	public void rejectsDuplicateIdsInlineKeysAndInvalidLimits() throws Exception {
		File catalog = Files.createTempDirectory("photo-source-invalid").toFile();
		try {
			String source = "{\"id\":\"teams\",\"source\":\"flickr://album/9\"}";
			String[] invalid = { "{\"sources\":[" + source + "," + source + "]}",
					"{\"apiKey\":\"secret\",\"sources\":[]}",
					"{\"sources\":[{\"id\":\"teams\",\"source\":\"flickr://album/9\",\"apiKey\":\"secret\"}]}",
					"{\"maxWidth\":0,\"sources\":[" + source + "]}", "{\"sources\":[{\"source\":\"flickr://album/9\"}]}" };
			for (String json : invalid) {
				write(new File(catalog, PhotoSources.FILE_NAME), json);
				try {
					PhotoSources.read(catalog);
					fail("Expected invalid configuration to be rejected");
				} catch (IOException expected) {
					assertFalse(expected.getMessage().contains("secret"));
				}
			}
		} finally {
			deleteTree(catalog);
		}
	}

	private static void write(File file, String text) throws IOException {
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	private static void deleteTree(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteTree(child);
			}
		}
		file.delete();
	}
}
