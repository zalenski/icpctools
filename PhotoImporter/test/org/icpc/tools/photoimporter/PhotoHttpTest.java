package org.icpc.tools.photoimporter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

public class PhotoHttpTest {

	@Test
	public void interruptionStopsPendingResponse() throws Exception {
		assertInterruptedRequest(false);
	}

	@Test
	public void interruptionStopsResponseBodyDownload() throws Exception {
		assertInterruptedRequest(true);
	}

	private static void assertInterruptedRequest(boolean bodyStarted) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		CountDownLatch received = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Path destination = Files.createTempFile("photo-cancel-http", ".tmp");
		ExecutorService worker = Executors.newSingleThreadExecutor();
		server.createContext("/slow", exchange -> {
			try {
				if (bodyStarted) {
					exchange.sendResponseHeaders(200, 1000);
					exchange.getResponseBody().write(1);
					exchange.getResponseBody().flush();
				}
				received.countDown();
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
		});
		server.start();
		try {
			String url = "http://localhost:" + server.getAddress().getPort() + "/slow";
			Future<?> job = worker.submit(() -> {
				try {
					if (bodyStarted) {
						PhotoHttp.download(url, destination.toFile());
					} else {
						PhotoHttp.get(url);
					}
					failure.set(new AssertionError("Request was not interrupted"));
				} catch (java.io.InterruptedIOException expected) {
					if (!Thread.currentThread().isInterrupted()) {
						failure.set(new AssertionError("Interrupt flag was lost"));
					}
				} catch (Throwable e) {
					failure.set(e);
				} finally {
					finished.countDown();
				}
			});
			org.junit.Assert.assertTrue(received.await(5, TimeUnit.SECONDS));
			job.cancel(true);
			org.junit.Assert.assertTrue("HTTP operation did not stop", finished.await(3, TimeUnit.SECONDS));
			if (failure.get() != null) {
				throw new AssertionError(failure.get());
			}
		} finally {
			release.countDown();
			worker.shutdownNow();
			server.stop(0);
			Files.deleteIfExists(destination);
		}
	}

	@Test
	public void followsRedirectsAndDownloadsExactBytesReplacingExistingFile() throws Exception {
		byte[] content = "photo \u00e5\u00e4\u00f6".getBytes(StandardCharsets.UTF_8);
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		Path destination = Files.createTempFile("photo-http", ".tmp");
		server.createContext("/photo", exchange -> {
			try {
				exchange.sendResponseHeaders(200, content.length);
				exchange.getResponseBody().write(content);
			} finally {
				exchange.close();
			}
		});
		server.createContext("/redirect", exchange -> {
			exchange.getResponseHeaders().set("Location", "/photo");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		server.start();
		try {
			String url = "http://localhost:" + server.getAddress().getPort() + "/redirect";
			assertEquals(new String(content, StandardCharsets.UTF_8), PhotoHttp.get(url));
			Files.writeString(destination, "old content longer than the replacement photo");
			PhotoHttp.download(url, destination.toFile());
			assertArrayEquals(content, Files.readAllBytes(destination));
		} finally {
			server.stop(0);
			Files.deleteIfExists(destination);
		}
	}

	@Test
	public void failedDownloadDoesNotOverwriteDestination() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		Path destination = Files.createTempFile("photo-http-error", ".tmp");
		server.createContext("/missing", exchange -> {
			exchange.sendResponseHeaders(404, -1);
			exchange.close();
		});
		server.start();
		try {
			Files.writeString(destination, "existing photo");
			String url = "http://localhost:" + server.getAddress().getPort() + "/missing";
			try {
				PhotoHttp.download(url, destination.toFile());
				fail("Expected HTTP error");
			} catch (IOException e) {
				assertEquals("HTTP 404", e.getMessage());
			}
			assertEquals("existing photo", Files.readString(destination));
		} finally {
			server.stop(0);
			Files.deleteIfExists(destination);
		}
	}
}
