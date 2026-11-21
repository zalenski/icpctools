package org.icpc.tools.cds.photos;

import static org.junit.Assert.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.icpc.tools.contest.model.feed.JSONParser;
import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.feed.JSONWriter;
import org.icpc.tools.photoimporter.PhotoWebServer;
import org.junit.Test;

public class PhotoWebServerTest {
	@Test
	public void webControlsPersistConfigurationAndKeepSecretsOutOfResponses() throws Exception {
		Path work = Files.createTempDirectory("photo-web");
		Path data = work.resolve("data");
		Path assets = Files.createDirectory(work.resolve("ui"));
		Files.writeString(assets.resolve("index.html"), "<html>Photo UI</html>");
		try (PhotoApiTestServer cds = new PhotoApiTestServer(work.resolve("cds").toFile())) {
			try (PhotoWebServer server = new PhotoWebServer(data.toFile(), 0, "/manage/photos", assets)) {
				Client client = new Client(server.uri());
				assertEquals(200, client.http.send(HttpRequest.newBuilder(server.uri()).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
				JsonObject initial = client.state();
				assertFalse(initial.getBoolean("configured"));
				JsonObject profile = object("apiKey", "private-test-api-key");
				assertEquals(403, client.send("credentials/flickr/default", "PUT", profile, null, null).statusCode());
				assertEquals(403, client.send("credentials/flickr/default", "PUT", profile, client.token, "https://elsewhere.test").statusCode());
				client.change("credentials/flickr/default", "PUT", profile);
				JsonObject connection = object("url", cds.uri().toString(), "user", "admin", "password", "password");
				client.change("connection", "PUT", connection);
				client.change("catalogs/photos-test", "PUT", object("refreshSeconds", 30));
				Path input = work.resolve("input.png");
				assertTrue(ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", input.toFile()));
				JsonObject source = object("id", "local", "source", input.toUri().toString(), "enabled", false, "maxWidth", 20);
				client.change("catalogs/photos-test/sources/local", "PUT", object("source", source, "create", true, "refreshSeconds", 45));
				assertEquals(45, ((JsonObject) client.state().getArray("jobs")[0]).getInt("refreshSeconds"));
				assertEquals(409, client.send("catalogs/photos-test/sources/local", "PUT", object("source", source, "create", true), client.token, null).statusCode());
				client.change("jobs/photos-test/local/run", "POST", new JsonObject());
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				JsonObject job;
				do {
					job = (JsonObject) client.state().getArray("jobs")[0];
					if (job.getInt("available") == 1) {
						break;
					}
					Thread.sleep(5);
				} while (System.nanoTime() < deadline);
				assertEquals(1, job.getInt("available"));
				assertFalse(job.getBoolean("periodic"));
				assertEquals(1, client.get("results/photos-test/local").getArray("photos").length);
				Files.writeString(input, "broken image");
				client.change("jobs/photos-test/local/run", "POST", new JsonObject());
				deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				do {
					job = (JsonObject) client.state().getArray("jobs")[0];
					if (job.getJsonObject("progress").getInt("failed") == 1) { break; }
					Thread.sleep(5);
				} while (System.nanoTime() < deadline);
				assertEquals(1, client.get("results/photos-test/local").getArray("failures").length);
				assertEquals(1, cds.store.index("photos-test").size());
				client.change("jobs/photos-test/local/activation", "POST", object("enabled", true));
				assertFalse(((JsonObject) cds.store.sources("photos-test").getArray("sources")[0]).containsKey("enabled"));
				assertFalse(cds.store.sources("photos-test").containsKey("refreshSeconds"));
				assertTrue(((JsonObject) client.state().getArray("jobs")[0]).getBoolean("enabled"));
				String state = json(client.state());
				assertFalse(state.contains("private-test-api-key"));
				assertFalse(state.contains("\"apiKey\""));
				assertFalse(state.contains("\"password\":\"password\""));
				client.change("catalogs/photos-test/sources/local", "DELETE", new JsonObject());
				assertEquals(1, cds.store.index("photos-test").size());
				assertEquals(1, ((JsonObject) client.state().getArray("catalogs")[0]).getInt("available"));
			}
			try (PhotoWebServer reopened = new PhotoWebServer(data.toFile(), 0, "/manage/photos", assets)) {
				JsonObject state = new Client(reopened.uri()).state();
				assertTrue(state.getBoolean("configured"));
				assertEquals("flickr/default", state.getArray("profiles")[0]);
				assertTrue(state.getJsonObject("connection").getBoolean("passwordConfigured"));
			}
			if (Files.getFileStore(data).supportsFileAttributeView("posix")) {
				assertFalse(Files.getPosixFilePermissions(data.resolve("credentials.json")).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
				assertFalse(Files.getPosixFilePermissions(data.resolve("connection.json")).contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ));
			}
		} finally {
			delete(work);
		}
	}

	@Test
	public void rejectsWrongContentTypeAndOversizedRequests() throws Exception {
		Path work = Files.createTempDirectory("photo-web-input");
		try (PhotoWebServer server = new PhotoWebServer(work.toFile(), 0, "/photo-importer", null)) {
			Client client = new Client(server.uri());
			client.state();
			HttpRequest oversized = HttpRequest.newBuilder(server.uri().resolve("api/connection"))
					.header("X-Photo-Token", client.token).header("Content-Type", "application/json")
					.PUT(HttpRequest.BodyPublishers.ofString(" ".repeat(65537))).build();
			assertEquals(400, client.http.send(oversized, HttpResponse.BodyHandlers.ofString()).statusCode());
			HttpRequest wrongType = HttpRequest.newBuilder(server.uri().resolve("api/connection"))
					.header("X-Photo-Token", client.token).header("Content-Type", "text/plain")
					.PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
			assertEquals(415, client.http.send(wrongType, HttpResponse.BodyHandlers.ofString()).statusCode());
		} finally {
			delete(work);
		}
	}

	private static final class Client {
		final URI base;
		final HttpClient http = HttpClient.newHttpClient();
		String token;
		Client(URI base) { this.base = base; }
		JsonObject state() throws Exception {
			JsonObject result = get("state"); token = result.getString("csrfToken"); return result;
		}
		JsonObject get(String path) throws Exception {
			HttpResponse<String> response = send(path, "GET", new JsonObject(), null, null);
			assertEquals(response.body(), 200, response.statusCode());
			return new JSONParser(response.body()).readObject();
		}
		void change(String path, String method, JsonObject input) throws Exception {
			HttpResponse<String> response = send(path, method, input, token, null);
			assertEquals(response.body(), 200, response.statusCode());
		}
		HttpResponse<String> send(String path, String method, JsonObject body, String csrf, String origin) throws Exception {
			HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve("api/" + path)).timeout(java.time.Duration.ofSeconds(10));
			if (csrf != null) { request.header("X-Photo-Token", csrf); }
			if (origin != null) { request.header("Origin", origin); }
			request.header("Content-Type", "application/json");
			request.method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json(body)));
			return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	private static JsonObject object(Object... values) {
		JsonObject object = new JsonObject();
		for (int i = 0; i < values.length; i += 2) { object.put((String) values[i], values[i + 1]); }
		return object;
	}
	private static String json(JsonObject object) throws IOException {
		StringWriter writer = new StringWriter(); new JSONWriter(writer).writeObject(object); return writer.toString();
	}
	private static void delete(Path root) throws IOException {
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) { Files.deleteIfExists(path); }
		}
	}
}
