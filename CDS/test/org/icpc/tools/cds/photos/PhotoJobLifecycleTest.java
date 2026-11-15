package org.icpc.tools.cds.photos;

import static org.junit.Assert.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.BooleanSupplier;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.photoimporter.CDSPhotoClient;
import org.icpc.tools.photoimporter.PhotoSyncService;
import org.icpc.tools.photoimporter.PhotoSource;
import org.icpc.tools.photoimporter.PhotoSourceRegistry;
import org.icpc.tools.photoimporter.FlickrPhotoSource;
import org.icpc.tools.photoimporter.Photo;
import org.icpc.tools.photoimporter.PhotoSyncService.State;
import org.icpc.tools.photoimporter.PhotoSyncService.Status;
import org.junit.Test;

public class PhotoJobLifecycleTest {
	@Test
	public void discoveryDoesNotTakeOverAndStartPublishesProgressAndReloadsChanges() throws Exception {
		Path work = Files.createTempDirectory("photo-jobs");
		try (PhotoApiTestServer server = new PhotoApiTestServer(work.resolve("cds").toFile())) {
			CDSPhotoClient client = client(server);
			File source = image(work.resolve("input.png"));
			client.sources("photos-test", definition(source, true, 20));
			try (PhotoSyncService service = new PhotoSyncService(client, work.resolve("data").toFile())) {
				service.poll();
				assertEquals(State.STOPPED, status(service).state());
				assertTrue(client.index("photos-test").isEmpty());
				service.start("photos-test", "test", false);
				await(() -> status(service).state() == State.SUCCEEDED);
				Status status = status(service);
				assertEquals(1, status.progress().processed());
				assertEquals(1, status.progress().total());
				assertEquals(1, status.uploaded());
				assertTrue(status.lastSuccess() > 0);
				client.sources("photos-test", definition(source, true, 10));
				service.poll();
				await(() -> status(service).state() == State.SUCCEEDED);
				assertEquals(10, client.index("photos-test").get(0).getWidth());
				client.sources("photos-test", definition(source, false, 10));
				service.poll();
				assertEquals(State.DISABLED, status(service).state());
				assertEquals(1, client.index("photos-test").size());
				JsonObject malformed = new JsonObject();
				malformed.put("sources", new Object[] { 42 });
				client.sources("photos-test", malformed);
				service.poll();
				assertNotNull(service.error());
				assertEquals(1, client.index("photos-test").size());
				client.sources("photos-test", definition(source, true, 10));
				service.poll();
				assertNull(service.error());
				await(() -> status(service).state() == State.SUCCEEDED);
			}
		} finally {
			delete(work);
		}
	}

	@Test
	public void restartRequiresOptInAndStopPersistsWithoutDeletingPhotos() throws Exception {
		Path work = Files.createTempDirectory("photo-restart");
		try (PhotoApiTestServer server = new PhotoApiTestServer(work.resolve("cds").toFile())) {
			CDSPhotoClient client = client(server);
			client.sources("photos-test", definition(image(work.resolve("input.png")), true, 20));
			File data = work.resolve("data").toFile();
			long success;
			try (PhotoSyncService service = new PhotoSyncService(client, data)) {
				service.poll();
				service.start("photos-test", "test", false);
				await(() -> status(service).state() == State.SUCCEEDED);
				success = status(service).lastSuccess();
			}
			try (PhotoSyncService service = new PhotoSyncService(client, data)) {
				service.poll();
				assertEquals(State.STOPPED, status(service).state());
				assertTrue(status(service).selected());
				assertEquals(success, status(service).lastSuccess());
				service.start("photos-test", "test", true);
				await(() -> status(service).state() == State.SUCCEEDED);
			}
			try (PhotoSyncService service = new PhotoSyncService(client, data)) {
				service.poll();
				await(() -> status(service).state() == State.SUCCEEDED);
				assertEquals(0, status(service).progress().downloaded());
				assertEquals(0, status(service).uploaded());
				service.stop("photos-test", "test");
				assertEquals(State.STOPPED, status(service).state());
			}
			try (PhotoSyncService service = new PhotoSyncService(client, data)) {
				service.poll();
				assertEquals(State.STOPPED, status(service).state());
				assertFalse(status(service).selected());
				assertEquals(1, client.index("photos-test").size());
			}
		} finally {
			delete(work);
		}
	}

	@Test
	public void failedJobCanRetryAndRemovedDefinitionStopsIt() throws Exception {
		Path work = Files.createTempDirectory("photo-retry");
		try (PhotoApiTestServer server = new PhotoApiTestServer(work.resolve("cds").toFile())) {
			CDSPhotoClient client = client(server);
			File input = work.resolve("missing.png").toFile();
			client.sources("photos-test", definition(input, true, 20));
			try (PhotoSyncService service = new PhotoSyncService(client, work.resolve("data").toFile())) {
				service.poll();
				service.start("photos-test", "test", false);
				await(() -> status(service).state() == State.FAILED);
				assertNotNull(status(service).error());
				assertEquals(0, status(service).lastSuccess());
				image(input.toPath());
				service.retry("photos-test", "test");
				await(() -> status(service).state() == State.SUCCEEDED);
				assertNull(status(service).error());
				JsonObject empty = new JsonObject();
				empty.put("sources", new Object[0]);
				client.sources("photos-test", empty);
				service.poll();
				assertEquals(State.DISABLED, status(service).state());
				assertNotNull(status(service).error());
				assertEquals(1, client.index("photos-test").size());
			}
		} finally {
			delete(work);
		}
	}

	@Test
	public void missingCredentialsAreVisibleAndSelectionsBelongToOneTarget() throws Exception {
		Path work = Files.createTempDirectory("photo-job-credentials");
		try (PhotoApiTestServer server = new PhotoApiTestServer(work.resolve("cds").toFile());
				PhotoApiTestServer other = new PhotoApiTestServer(work.resolve("other").toFile())) {
			CDSPhotoClient client = client(server);
			JsonObject entry = new JsonObject();
			entry.put("id", "test");
			entry.put("source", "flickr://album/42");
			entry.put("credentialProfile", "flickr/default");
			entry.put("enabled", true);
			JsonObject definitions = new JsonObject();
			definitions.put("sources", new Object[] { entry });
			client.sources("photos-test", definitions);
			File data = work.resolve("data").toFile();
			try (PhotoSyncService service = new PhotoSyncService(client, data)) {
				service.poll();
				service.start("photos-test", "test", true);
				assertEquals(State.MISSING_CREDENTIALS, status(service).state());
				assertTrue(status(service).error().contains("flickr/default"));
			}
			try (PhotoSyncService ignored = new PhotoSyncService(client(other), data)) {
				fail("Must not resume jobs against another CDS");
			} catch (IOException expected) {
				assertTrue(expected.getMessage().contains("another CDS"));
			}
		} finally {
			delete(work);
		}
	}

	@Test
	public void stopInterruptsUploadAndKeepsAvailablePhotos() throws Exception {
		Path work = Files.createTempDirectory("photo-job-stop");
		java.util.concurrent.CountDownLatch received = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		try (PhotoApiTestServer server = new PhotoApiTestServer(work.resolve("cds").toFile())) {
			CDSPhotoClient client = client(server);
			File input = image(work.resolve("input.png"));
			client.sources("photos-test", definition(input, true, 20));
			File data = work.resolve("data").toFile();
			try (PhotoSyncService service = new PhotoSyncService(client, data)) {
				service.poll();
				service.start("photos-test", "test", true);
				await(() -> status(service).state() == State.SUCCEEDED);
				// Force a new upload, then hold its response while requesting stop.
				client.sources("photos-test", definition(input, true, 10));
				server.beforeUpload = () -> {
					received.countDown();
					try {
						release.await(5, java.util.concurrent.TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				};
				service.poll();
				assertTrue(received.await(5, java.util.concurrent.TimeUnit.SECONDS));
				service.stop("photos-test", "test");
				await(() -> status(service).state() == State.STOPPED);
				assertNull(status(service).error());
				assertFalse(status(service).selected());
				release.countDown();
				assertEquals(1, client.index("photos-test").size());
				assertEquals(1, work.resolve("data/originals/photos-test").toFile().list().length);
			}
		} finally {
			release.countDown();
			delete(work);
		}
	}

	@Test
	public void changedDefinitionCancelsRunningJobAndQueuedStopPreventsExecution() throws Exception {
		Path work = Files.createTempDirectory("photo-job-reconfigure");
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch cancelled = new CountDownLatch(1);
		AtomicInteger listed = new AtomicInteger();
		AtomicBoolean block = new AtomicBoolean(true);
		PhotoSourceRegistry.register("flickr", (uri, key, tag) -> new PhotoSource() {
			@Override
			public String getType() { return "flickr"; }
			@Override
			public String getUri() { return uri; }
			@Override
			public List<Photo> listPhotos() throws IOException {
				listed.incrementAndGet();
				if (block.getAndSet(false)) {
					entered.countDown();
					try {
						new CountDownLatch(1).await(5, TimeUnit.SECONDS);
						throw new IOException("Test worker was not cancelled");
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						cancelled.countDown();
						throw new java.io.InterruptedIOException("Stopped");
					}
				}
				return List.of();
			}
			@Override
			public void fetchOriginal(Photo photo, File destination) { fail("No photos expected"); }
		});
		try (PhotoApiTestServer server = new PhotoApiTestServer(work.resolve("cds").toFile())) {
			CDSPhotoClient client = client(server);
			File data = work.resolve("data").toFile();
			assertTrue(data.mkdirs());
			Files.writeString(data.toPath().resolve("credentials.json"),
					"{\"profiles\":{\"flickr/default\":{\"apiKey\":\"test-key\"}}}");
			JsonObject first = cloudDefinition("first");
			JsonObject second = cloudDefinition("second");
			JsonObject definitions = new JsonObject();
			definitions.put("sources", new Object[] { first, second });
			client.sources("photos-test", definitions);
			try (PhotoSyncService service = new PhotoSyncService(client, data)) {
				service.poll();
				service.start("photos-test", "first", false);
				assertTrue(entered.await(5, TimeUnit.SECONDS));
				service.start("photos-test", "second", false);
				assertEquals(State.QUEUED, service.status().get(1).state());
				service.stop("photos-test", "second");
				// Edit the definition while its worker is blocked. Polling must stay responsive.
				JsonObject changed = cloudDefinition("first");
				changed.put("maxWidth", 10);
				JsonObject replacement = new JsonObject();
				replacement.put("sources", new Object[] { changed, second });
				client.sources("photos-test", replacement);
				service.poll();
				assertTrue(cancelled.await(3, TimeUnit.SECONDS));
				await(() -> status(service).state() == State.SUCCEEDED);
				assertEquals(2, listed.get());
				assertEquals(State.STOPPED, service.status().get(1).state());
				// Removing a key pauses a selected job; restoring it makes it runnable again.
				Files.writeString(data.toPath().resolve("credentials.json"), "{\"profiles\":{}}");
				service.poll();
				assertEquals(State.MISSING_CREDENTIALS, status(service).state());
				Files.writeString(data.toPath().resolve("credentials.json"),
						"{\"profiles\":{\"flickr/default\":{\"apiKey\":\"replacement-key\"}}}");
				service.poll();
				await(() -> status(service).state() == State.SUCCEEDED);
				assertEquals(3, listed.get());
				block.set(true);
				service.start("photos-test", "first", true);
				await(() -> listed.get() == 4 && status(service).state() == State.RUNNING);
				service.close();
				assertEquals(State.STOPPED, status(service).state());
				assertTrue(status(service).selected());
				assertFalse(Files.readString(data.toPath().resolve("state.json")).contains("replacement-key"));
			}
		} finally {
			PhotoSourceRegistry.register("flickr", FlickrPhotoSource::new);
			delete(work);
		}
	}

	private static JsonObject cloudDefinition(String id) {
		JsonObject entry = new JsonObject();
		entry.put("id", id);
		entry.put("source", "flickr://album/42");
		entry.put("credentialProfile", "flickr/default");
		entry.put("enabled", true);
		return entry;
	}

	private static CDSPhotoClient client(PhotoApiTestServer server) throws IOException {
		return new CDSPhotoClient(server.uri(), "admin", "password", false);
	}

	private static Status status(PhotoSyncService service) {
		return service.status().get(0);
	}

	private static JsonObject definition(File input, boolean enabled, int width) {
		JsonObject entry = new JsonObject();
		entry.put("id", "test");
		entry.put("source", input.toURI().toString());
		entry.put("enabled", enabled);
		entry.put("maxWidth", width);
		JsonObject root = new JsonObject();
		root.put("sources", new Object[] { entry });
		return root;
	}

	private static File image(Path path) throws IOException {
		assertTrue(ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", path.toFile()));
		return path.toFile();
	}

	private static void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			Thread.sleep(5);
		}
		assertTrue("Job did not reach the expected state", condition.getAsBoolean());
	}

	private static void delete(Path root) throws IOException {
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
				Files.deleteIfExists(path);
			}
		}
	}
}
