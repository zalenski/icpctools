package org.icpc.tools.photoimporter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

public class PhotoHttpTest {

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
