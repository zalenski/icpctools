package org.icpc.tools.cds.photos;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import javax.imageio.ImageIO;

import org.icpc.tools.contest.model.feed.JSONParser;
import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.photos.PhotoMetadata;
import org.icpc.tools.photoimporter.CDSPhotoClient;
import org.icpc.tools.photoimporter.PhotoSyncService;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PhotoImportIntegrationTest {
	@Test
	public void localImportUsesHttpKeepsOriginalsLocalAndResumesWithoutReplacingPhotos() throws Exception {
		File work = Files.createTempDirectory("photo-http-integration").toFile();
		File config = new File(work, "cds");
		File data = new File(work, "importer");
		File source = new File(work, "input");
		assertTrue(source.mkdirs());
		File first = new File(source, "first.png");
		File second = new File(source, "second.png");
		assertTrue(ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", first));
		Files.copy(first.toPath(), second.toPath());
		try (PhotoApiTestServer server = new PhotoApiTestServer(config)) {
			CDSPhotoClient client = new CDSPhotoClient(server.uri(), "admin", "password", false);
			JsonObject definition = definition(source);
			client.sources("photos-test", definition);
			File display = new File(config, "present/photos-test");
			File manual = new File(display, "manual.jpg");
			Files.writeString(manual.toPath(), "manual");
			new PhotoSyncService(client, data).tick();
			List<PhotoMetadata> records = client.index("photos-test");
			assertEquals(2, records.size());
			for (PhotoMetadata metadata : records) {
				assertEquals("local-import", metadata.getImportId());
				assertEquals(20, ImageIO.read(new File(display, metadata.getFile())).getWidth());
				File original = new File(data, "originals/photos-test/" + metadata.getOriginalFile());
				assertEquals(40, ImageIO.read(original).getWidth());
			}
			assertFalse(new File(config, "present/original-photos").exists());
			assertFalse(new File(config, "originals").exists());
			byte[] index = Files.readAllBytes(new File(display, PhotoMetadata.INDEX_NAME).toPath());
			new PhotoSyncService(client, data).tick();
			assertTrue(Arrays.equals(index, Files.readAllBytes(new File(display, PhotoMetadata.INDEX_NAME).toPath())));
			assertEquals(5, display.list().length); // Two photos, definitions, index and an untouched manual photo.
		} finally {
			deleteTree(work);
		}
	}

	@Test
	public void catalogIsolationDeletionMissingSourceAndChangedSizeLimits() throws Exception {
		File work = Files.createTempDirectory("photo-http-updates").toFile();
		File config = new File(work, "cds");
		File data = new File(work, "importer");
		File source = new File(work, "input");
		assertTrue(source.mkdirs());
		assertTrue(ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", new File(source, "first.png")));
		try (PhotoApiTestServer server = new PhotoApiTestServer(config)) {
			CDSPhotoClient client = new CDSPhotoClient(server.uri(), "admin", "password", false);
			client.sources("photos-one", definition(source));
			client.sources("photos-two", definition(source));
			new PhotoSyncService(client, data).tick();
			assertEquals(1, client.index("photos-one").size());
			assertEquals(1, client.index("photos-two").size());
			assertTrue(new File(data, "originals/photos-one").isDirectory());
			assertTrue(new File(data, "originals/photos-two").isDirectory());

			JsonObject updated = definition(source, 10);
			client.sources("photos-one", updated);
			new PhotoSyncService(client, data).tick();
			assertEquals(10, client.index("photos-one").get(0).getWidth());
			assertEquals(20, client.index("photos-two").get(0).getWidth());

			File moved = new File(work, "moved");
			assertTrue(source.renameTo(moved));
			new PhotoSyncService(client, data).tick();
			assertEquals(1, client.index("photos-one").size());
			assertTrue(source.mkdirs());
			new PhotoSyncService(client, data).tick();
			assertTrue(client.index("photos-one").isEmpty());
			assertTrue(client.index("photos-two").isEmpty());
		} finally {
			deleteTree(work);
		}
	}

	@Test
	public void apiRejectsUnauthenticatedRequestsAndInvalidCatalogPaths() throws Exception {
		File work = Files.createTempDirectory("photo-http-security").toFile();
		try (PhotoApiTestServer server = new PhotoApiTestServer(work)) {
			try {
				new CDSPhotoClient(server.uri(), null, null, false).catalogs();
				fail("Expected authentication failure");
			} catch (IOException e) {
				assertTrue(e.getMessage().contains("401"));
			}
			CDSPhotoClient client = new CDSPhotoClient(server.uri(), "admin", "password", false);
			try {
				client.sources("not-a-photo-catalog", new JSONParser("{\"sources\":[]}").readObject());
				fail("Expected invalid catalog rejection");
			} catch (IOException e) {
				assertTrue(e.getMessage().contains("400"));
			}
		} finally {
			deleteTree(work);
		}
	}

	@Test
	public void standaloneMainInstallsDefinitionsAndImportsOverHttp() throws Exception {
		File work = Files.createTempDirectory("photo-cli").toFile();
		File config = new File(work, "cds");
		File data = new File(work, "importer");
		File input = new File(work, "input.png");
		assertTrue(ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", input));
		File definitions = new File(work, "sources.json");
		PhotoMetadata.writeObject(definitions, definition(input));
		try (PhotoApiTestServer server = new PhotoApiTestServer(config)) {
			File output = new File(work, "cli-output.txt");
			ProcessBuilder builder = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").toString(),
					"-Djava.awt.headless=true", "-Djava.io.tmpdir=" + work,
					"-cp", System.getProperty("java.class.path"), "org.icpc.tools.photoimporter.PhotoImporter",
					server.uri().toString(), "admin", "password", "--data", data.toString(),
					"--once", "--catalog", "photos-cli", "--sources", definitions.toString());
			builder.redirectErrorStream(true).redirectOutput(output);
			Process process = builder.start();
			if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				fail("Importer CLI did not finish");
			}
			assertEquals(Files.readString(output.toPath()), 0, process.exitValue());
			assertEquals(1, server.store.index("photos-cli").size());
			assertTrue(new File(data, "originals/photos-cli").isDirectory());
		} finally {
			deleteTree(work);
		}
	}

	private static JsonObject definition(File source) {
		return definition(source, 20);
	}

	private static JsonObject definition(File source, int maxWidth) {
		JsonObject entry = new JsonObject();
		entry.put("id", "local-import");
		entry.put("source", source.toURI().toString());
		entry.put("enabled", Boolean.TRUE);
		entry.put("maxWidth", Integer.valueOf(maxWidth));
		JsonObject definition = new JsonObject();
		definition.put("sources", new Object[] { entry });
		return definition;
	}

	private static void deleteTree(File file) throws IOException {
		try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(file.toPath())) {
			for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
				Files.deleteIfExists(path);
			}
		}
	}
}
