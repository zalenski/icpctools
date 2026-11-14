package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/** Small HTTP helper for photo sources. */
final class PhotoHttp {
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
			.followRedirects(Redirect.NORMAL).build();

	private PhotoHttp() {
		// utility
	}

	static String get(String url) throws IOException {
		HttpResponse<String> response = send(CLIENT, request(url), BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode());
		}
		return response.body();
	}

	static void download(String url, File destination) throws IOException {
		BodyHandler<Path> file = BodyHandlers.ofFile(destination.toPath(), StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		HttpResponse<Path> response = send(CLIENT, request(url), info -> {
			if (info.statusCode() == 200) {
				return file.apply(info);
			}
			return BodyHandlers.<Path>replacing(null).apply(info);
		});
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode());
		}
	}

	static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, BodyHandler<T> handler) throws IOException {
		try {
			return client.send(request, handler);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			InterruptedIOException interrupted = new InterruptedIOException("Photo HTTP request interrupted");
			interrupted.initCause(e);
			throw interrupted;
		}
	}

	static String redact(String message) {
		if (message == null) {
			return "";
		}
		return message.replaceAll("(?i)api_key=[^&\\s\"]+", "api_key=redacted");
	}

	private static HttpRequest request(String url) throws IOException {
		try {
			return HttpRequest.newBuilder(new URI(url)).timeout(REQUEST_TIMEOUT)
					.header("User-Agent", "ICPC-PhotoImporter").header("Accept", "*/*").GET().build();
		} catch (Exception e) {
			throw new IOException("Could not open photo URL");
		}
	}
}
