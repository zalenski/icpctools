package org.icpc.tools.cds.photos;

import static org.junit.Assert.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.photos.PhotoMetadata;
import org.icpc.tools.photoimporter.PhotoImporter;
import org.junit.Test;

public class PhotoCliIntegrationTest {
	@Test
	public void setupSavesKeysAndDefinitionsButOnlyRunImports() throws Exception {
		Path work = Files.createTempDirectory("photo-cli-setup");
		Path data = work.resolve("data");
		try (PhotoApiTestServer cds = new PhotoApiTestServer(work.resolve("cds").toFile())) {
			String output = cli(data, "--flickr-key", "private-default-key");
			assertFalse(output.contains("private-default-key"));
			assertEquals("private-default-key", PhotoMetadata.readObject(data.resolve("credentials.json").toFile())
					.getJsonObject("profiles").getJsonObject("flickr/default").getString("apiKey"));
			cli(data, "--cds", cds.uri().toString(), "--user", "admin", "--password", "password");
			Path input = work.resolve("input.png");
			assertTrue(ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", input.toFile()));
			cli(data, "--id", "local", "--source", input.toString(), "--catalog", "teams", "--max-width", "20");
			assertTrue(cds.store.index("photos-teams").isEmpty());
			JsonObject saved = cds.store.sources("photos-teams");
			assertFalse(saved.containsKey("refreshSeconds"));
			JsonObject source = (JsonObject) saved.getArray("sources")[0];
			assertFalse(source.containsKey("enabled"));
			assertNull(source.getString("credentialProfile"));
			output = cli(data);
			assertTrue(output.contains("connection.json"));
			assertTrue(output.contains("credentials.json"));
			assertTrue(output.contains("/api/photos/catalogs/photos-teams/sources"));
			assertTrue(output.contains("photos-teams/local"));
			assertFalse(output.contains("private-default-key"));
			assertFalse(output.contains("password=password"));
			cli(data, "--run", "local");
			assertEquals(20, cds.store.index("photos-teams").get(0).getWidth());
			java.util.concurrent.atomic.AtomicInteger uploads = new java.util.concurrent.atomic.AtomicInteger();
			cds.beforeUpload = uploads::incrementAndGet;
			cli(data, "--run", "photos-teams/local");
			assertEquals(0, uploads.get());
			assertFalse(Files.exists(data.resolve("state.json")));
			try (var paths = Files.walk(data)) {
				assertFalse(paths.anyMatch(path -> path.getFileName().toString().equals("originals")));
			}
		} finally {
			delete(work);
		}
	}

	@Test
	public void profilesDefaultExplicitSelectionAndEditsPreserveTheChosenKey() throws Exception {
		Path work = Files.createTempDirectory("photo-cli-profiles");
		Path data = work.resolve("data");
		try (PhotoApiTestServer cds = new PhotoApiTestServer(work.resolve("cds").toFile())) {
			cli(data, "--flickr-key", "default-key");
			cli(data, "--flickr-key", "other-key", "--profile", "other");
			assertEquals(2, PhotoMetadata.readObject(data.resolve("credentials.json").toFile())
					.getJsonObject("profiles").props.size());
			cli(data, "--cds", cds.uri().toString(), "--user", "admin", "--password", "password",
					"--id", "album", "--source", "flickr://album/42", "--profile", "other");
			cli(data, "--id", "album", "--max-width", "800");
			JsonObject album = (JsonObject) cds.store.sources("photos").getArray("sources")[0];
			assertEquals("flickr/other", album.getString("credentialProfile"));
			assertEquals(800, album.getInt("maxWidth"));
			cli(data, "--id", "second", "--source", "flickr://album/43");
			JsonObject second = (JsonObject) cds.store.sources("photos").getArray("sources")[1];
			assertEquals("flickr/default", second.getString("credentialProfile"));
			cli(data, "--id", "album", "--max-width", "0");
			album = (JsonObject) cds.store.sources("photos").getArray("sources")[0];
			assertTrue(album.get("maxWidth") == null || "null".equals(album.get("maxWidth")));
		} finally {
			delete(work);
		}
	}

	@Test
	public void inspectionWorksWithoutAConnectionAndWhenCdsIsUnavailable() throws Exception {
		Path work = Files.createTempDirectory("photo-cli-inspect");
		try {
			assertTrue(cli(work).contains("No CDS configured"));
			assertFalse(Files.exists(work.resolve("connection.json")));
			String url;
			try (PhotoApiTestServer cds = new PhotoApiTestServer(work.resolve("cds").toFile())) {
				url = cds.uri().toString();
			}
			cli(work, "--cds", url);
			String output = cli(work);
			assertTrue(output.contains("connection.json"));
			assertTrue(output.contains("CDS unavailable"));
		} finally {
			delete(work);
		}
	}

	@Test
	public void periodicRunStopsOnInterruptionAndDoesNotPersistScheduling() throws Exception {
		Path work = Files.createTempDirectory("photo-cli-period");
		Path data = work.resolve("data");
		try (PhotoApiTestServer cds = new PhotoApiTestServer(work.resolve("cds").toFile())) {
			Path input = work.resolve("input.png");
			assertTrue(ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", input.toFile()));
			cli(data, "--cds", cds.uri().toString(), "--user", "admin", "--password", "password",
					"--id", "local", "--source", input.toString());
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Thread run = new Thread(() -> {
				try {
					PhotoImporter.main(new String[] { "--data", data.toString(), "--run", "local", "--period", "15" });
				} catch (Exception e) {
					failure.set(e);
				}
			});
			run.start();
			try {
				long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
				while (cds.store.index("photos").isEmpty() && System.nanoTime() < deadline) {
					Thread.sleep(5);
				}
				assertEquals(1, cds.store.index("photos").size());
			} finally {
				run.interrupt();
				run.join(5000);
			}
			assertFalse(run.isAlive());
			assertTrue(failure.get() instanceof InterruptedException || failure.get() instanceof java.io.InterruptedIOException);
			assertFalse(Files.exists(data.resolve("state.json")));
			assertFalse(cds.store.sources("photos").containsKey("refreshSeconds"));
		} finally {
			delete(work);
		}
	}

	@Test
	public void invalidRuntimeOptionsDoNotSaveConfiguration() throws Exception {
		Path work = Files.createTempDirectory("photo-cli-invalid");
		try {
			try {
				cli(work, "--flickr-key", "do-not-save", "--period", "30");
				fail("A period must require --run");
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage().contains("--run"));
			}
			assertFalse(Files.exists(work.resolve("credentials.json")));
		} finally {
			delete(work);
		}
	}

	@Test
	public void selectionsAreExplicitAndAmbiguousIdsDoNotRun() throws Exception {
		Path work = Files.createTempDirectory("photo-cli-selection");
		Path data = work.resolve("data");
		try (PhotoApiTestServer cds = new PhotoApiTestServer(work.resolve("cds").toFile())) {
			Path input = work.resolve("input.png");
			assertTrue(ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", input.toFile()));
			cli(data, "--cds", cds.uri().toString(), "--user", "admin", "--password", "password");
			for (String catalog : List.of("first", "second")) {
				cli(data, "--id", "local", "--source", input.toString(), "--catalog", catalog);
			}
			try {
				cli(data, "--run", "local");
				fail("Ambiguous IDs must require a catalog");
			} catch (java.io.IOException expected) {
				assertTrue(expected.getMessage().contains("ambiguous"));
			}
			assertTrue(cds.store.index("photos-first").isEmpty());
			assertTrue(cds.store.index("photos-second").isEmpty());
			cli(data, "--catalog", "first", "--run", "all");
			assertEquals(1, cds.store.index("photos-first").size());
			assertTrue(cds.store.index("photos-second").isEmpty());
			cli(data, "--run", "all");
			assertEquals(1, cds.store.index("photos-second").size());
		} finally {
			delete(work);
		}
	}

	private static String cli(Path data, String... args) throws Exception {
		List<String> command = new ArrayList<>(List.of("--data", data.toString()));
		command.addAll(Arrays.asList(args));
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		PrintStream previous = System.out;
		try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
			System.setOut(output);
			PhotoImporter.main(command.toArray(String[]::new));
		} finally {
			System.setOut(previous);
		}
		return bytes.toString(StandardCharsets.UTF_8);
	}

	private static void delete(Path root) throws Exception {
		try (var paths = Files.walk(root)) {
			for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
				Files.deleteIfExists(path);
			}
		}
	}
}
