package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.icpc.tools.contest.model.feed.JSONParser;
import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.feed.JSONWriter;

/** Local development/web launcher. Production container authentication belongs to the later WAR adapter. */
public final class PhotoWebServer implements AutoCloseable {
	private final PhotoWebApplication application;
	private final HttpServer server;
	private final String context;
	private final Path assets;
	private final String token = UUID.randomUUID().toString() + UUID.randomUUID();
	private final ExecutorService requests = Executors.newFixedThreadPool(8, task -> {
		Thread thread = new Thread(task, "Photo web request");
		thread.setDaemon(true);
		return thread;
	});

	public PhotoWebServer(File data, int port, String context, Path assets) throws IOException {
		if (!context.matches("(?:/[A-Za-z0-9_-]+)+")) {
			throw new IllegalArgumentException("Expected a context path such as /photo-importer");
		}
		this.context = context;
		this.assets = assets == null ? null : assets.toAbsolutePath().normalize();
		application = new PhotoWebApplication(data);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		server.createContext("/", this::handle);
		server.setExecutor(requests);
		server.start();
	}

	public URI uri() {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + context + "/");
	}

	private void handle(HttpExchange exchange) throws IOException {
		try {
			String host = exchange.getRequestHeaders().getFirst("Host");
			int port = server.getAddress().getPort();
			if (!("127.0.0.1:" + port).equals(host) && !("localhost:" + port).equals(host)) {
				reply(exchange, 403, "text/plain", "Unexpected host".getBytes(StandardCharsets.UTF_8));
				return;
			}
			String path = exchange.getRequestURI().getPath();
			if (path.equals(context)) {
				exchange.getResponseHeaders().set("Location", context + "/");
				exchange.sendResponseHeaders(302, -1);
				return;
			}
			if (!path.startsWith(context + "/") || path.contains("..") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
				exchange.sendResponseHeaders(404, -1);
				return;
			}
			String relative = path.substring(context.length() + 1);
			if (relative.startsWith("/")) {
				exchange.sendResponseHeaders(404, -1);
				return;
			}
			if (relative.startsWith("api/")) {
				api(exchange, relative.substring(4));
			} else if ("GET".equals(exchange.getRequestMethod()) || "HEAD".equals(exchange.getRequestMethod())) {
				asset(exchange, relative.isEmpty() ? "index.html" : relative);
			} else {
				exchange.sendResponseHeaders(405, -1);
			}
		} finally {
			exchange.close();
		}
	}

	private void api(HttpExchange exchange, String path) throws IOException {
		String method = exchange.getRequestMethod();
		if (!"GET".equals(method)) {
			String supplied = exchange.getRequestHeaders().getFirst("X-Photo-Token");
			String origin = exchange.getRequestHeaders().getFirst("Origin");
			String expectedOrigin = "http://" + exchange.getRequestHeaders().getFirst("Host");
			if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))
					|| (origin != null && !origin.equals(expectedOrigin))) {
				json(exchange, 403, error("Refresh this page before making changes"));
				return;
			}
			String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
			if (contentType == null || !contentType.startsWith("application/json")) {
				json(exchange, 415, error("Expected application/json"));
				return;
			}
		}
		try {
			String[] parts = path.split("/");
			JsonObject input = "GET".equals(method) ? new JsonObject() : read(exchange.getRequestBody());
			if (path.equals("state") && method.equals("GET")) {
				JsonObject state = application.state();
				state.put("csrfToken", token);
				json(exchange, 200, state);
				return;
			}
			if (path.equals("connection") && method.equals("PUT")) {
				application.connect(input);
			} else if (parts.length == 3 && parts[0].equals("credentials") && (method.equals("PUT") || method.equals("DELETE"))) {
				application.putCredential(parts[1] + "/" + parts[2], method.equals("DELETE") ? null : input);
			} else if (parts.length == 2 && parts[0].equals("catalogs") && method.equals("PUT")) {
				application.saveCatalog(parts[1], input);
			} else if (parts.length == 4 && parts[0].equals("catalogs") && parts[2].equals("sources") && method.equals("PUT")) {
				application.saveSource(parts[1], parts[3], input);
			} else if (parts.length == 4 && parts[0].equals("catalogs") && parts[2].equals("sources") && method.equals("DELETE")) {
				application.removeSource(parts[1], parts[3]);
			} else if (parts.length == 4 && parts[0].equals("jobs") && method.equals("POST")) {
				application.action(parts[1], parts[2], parts[3], input);
			} else if (parts.length == 3 && parts[0].equals("results") && method.equals("GET")) {
				json(exchange, 200, application.results(parts[1], parts[2]));
				return;
			} else {
				json(exchange, 404, error("Unknown photo endpoint"));
				return;
			}
			json(exchange, 200, new JsonObject());
		} catch (IllegalArgumentException | ClassCastException e) {
			json(exchange, 400, error(PhotoHttp.redact(e.getMessage())));
		} catch (IllegalStateException e) {
			json(exchange, 409, error(PhotoHttp.redact(e.getMessage())));
		} catch (IOException e) {
			json(exchange, 502, error(PhotoHttp.redact(e.getMessage())));
		} catch (RuntimeException e) {
			json(exchange, 500, error("Photo request failed; check the importer configuration"));
		}
	}

	private void asset(HttpExchange exchange, String name) throws IOException {
		byte[] bytes;
		if (assets != null && Files.isRegularFile(assets.resolve(name))) {
			bytes = Files.readAllBytes(assets.resolve(name));
		} else {
			try (InputStream input = PhotoWebServer.class.getResourceAsStream("/photo-ui/" + name)) {
				if (input == null) {
					reply(exchange, 404, "text/plain", "Build PhotoImporter/ui first (pnpm build).".getBytes(StandardCharsets.UTF_8));
					return;
				}
				bytes = input.readAllBytes();
			}
		}
		String type = name.endsWith(".html") ? "text/html;charset=UTF-8" : name.endsWith(".js") ? "text/javascript;charset=UTF-8"
				: name.endsWith(".css") ? "text/css;charset=UTF-8" : name.endsWith(".svg") ? "image/svg+xml"
				: name.endsWith(".woff2") ? "font/woff2" : "application/octet-stream";
		exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'");
		reply(exchange, 200, type, bytes);
	}

	private static JsonObject read(InputStream input) throws IOException {
		byte[] bytes = input.readNBytes(65537);
		if (bytes.length > 65536) {
			throw new IllegalArgumentException("Request exceeds 64 KiB");
		}
		JsonObject result;
		try {
			result = bytes.length == 0 ? new JsonObject() : new JSONParser(new String(bytes, StandardCharsets.UTF_8)).readObject();
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid JSON request");
		}
		if (result == null) {
			throw new IllegalArgumentException("Expected a JSON object");
		}
		return result;
	}

	private static JsonObject error(String message) {
		JsonObject object = new JsonObject();
		object.put("error", message == null ? "Photo request failed" : message);
		return object;
	}

	private static void json(HttpExchange exchange, int status, JsonObject object) throws IOException {
		StringWriter writer = new StringWriter();
		new JSONWriter(writer).writeObject(object);
		reply(exchange, status, "application/json;charset=UTF-8", writer.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static void reply(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", type);
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
		if ("HEAD".equals(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(status, -1);
		} else {
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
		}
	}

	@Override
	public void close() {
		server.stop(0);
		requests.shutdownNow();
		application.close();
	}

	public static void main(String[] args) throws Exception {
		File data = new File("photo-importer");
		Path assets = Path.of("PhotoImporter/ui/build");
		String context = "/photo-importer";
		int port = 8787;
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("--help")) {
				System.out.println("PhotoWebServer [--data DIRECTORY] [--port PORT] [--ui DIRECTORY] [--context PATH]");
				return;
			}
			if (i + 1 >= args.length) {
				throw new IllegalArgumentException("Missing value for " + args[i]);
			}
			switch (args[i++]) {
				case "--data": data = new File(args[i]); break;
				case "--port": port = Integer.parseInt(args[i]); break;
				case "--ui": assets = Path.of(args[i]); break;
				case "--context": context = args[i]; break;
				default: throw new IllegalArgumentException("Unknown option " + args[i - 1]);
			}
		}
		PhotoWebServer server = new PhotoWebServer(data, port, context, assets);
		Runtime.getRuntime().addShutdownHook(new Thread(server::close, "Photo web shutdown"));
		System.out.println("Photo management: " + server.uri());
		new java.util.concurrent.CountDownLatch(1).await();
	}
}
