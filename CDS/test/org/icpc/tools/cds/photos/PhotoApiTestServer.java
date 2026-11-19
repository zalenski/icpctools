package org.icpc.tools.cds.photos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.icpc.tools.contest.model.IAccount;

import com.sun.net.httpserver.HttpServer;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;

/** Runs the actual servlet and store through HTTP without requiring a Liberty installation. */
final class PhotoApiTestServer implements AutoCloseable {
	final PhotoCatalogStore store;
	private final HttpServer server;
	volatile Runnable beforeUpload = () -> { };

	PhotoApiTestServer(File config) throws IOException {
		store = new PhotoCatalogStore(config);
		PhotoServlet servlet = new PhotoServlet() {
			@Override
			protected PhotoCatalogStore store() {
				return PhotoApiTestServer.this.store;
			}
		};
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/photos", exchange -> {
			byte[] body = exchange.getRequestBody().readAllBytes();
			if ("POST".equals(exchange.getRequestMethod())) {
				beforeUpload.run();
			}
			Map<String, byte[]> parts = multipart(exchange.getRequestHeaders().getFirst("Content-Type"), body);
			String expected = "Basic " + Base64.getEncoder().encodeToString("admin:password".getBytes(StandardCharsets.UTF_8));
			boolean authenticated = expected.equals(exchange.getRequestHeaders().getFirst("Authorization"));
			IAccount account = proxy(IAccount.class, (object, method, args) -> {
				return "getAccountType".equals(method.getName()) ? IAccount.ADMIN : null;
			});
			HttpSession session = proxy(HttpSession.class, (object, method, args) -> {
				return authenticated && "getAttribute".equals(method.getName()) && "account".equals(args[0]) ? account : null;
			});
			HttpServletRequest request = proxy(HttpServletRequest.class, (object, method, args) -> {
				switch (method.getName()) {
					case "getMethod": return exchange.getRequestMethod();
					case "getPathInfo": return exchange.getRequestURI().getPath().substring("/api/photos".length());
					case "getSession": return session;
					case "getRemoteUser": return authenticated ? "admin" : null;
					case "getInputStream": return input(body);
					case "getParameter": return parameter(exchange.getRequestURI(), (String) args[0]);
					case "getPart": {
						byte[] data = parts.get(args[0]);
						return data == null ? null : proxy(Part.class, (part, operation, values) -> {
							if ("getInputStream".equals(operation.getName())) {
								return new ByteArrayInputStream(data);
							}
							return null;
						});
					}
					default: return null;
				}
			});
			int[] status = { 200 };
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			PrintWriter writer = new PrintWriter(new OutputStreamWriter(bytes, StandardCharsets.UTF_8));
			HttpServletResponse response = proxy(HttpServletResponse.class, (object, method, args) -> {
				switch (method.getName()) {
					case "setStatus": case "sendError": status[0] = (int) args[0]; break;
					case "setHeader": exchange.getResponseHeaders().set((String) args[0], (String) args[1]); break;
					case "setContentType": exchange.getResponseHeaders().set("Content-Type", (String) args[0]); break;
					case "getWriter": return writer;
					default: break;
				}
				return null;
			});
			try {
				servlet.service(request, response);
			} catch (Exception e) {
				status[0] = 500;
			}
			writer.flush();
			exchange.sendResponseHeaders(status[0], status[0] == 204 ? -1 : bytes.size());
			if (status[0] != 204) {
				exchange.getResponseBody().write(bytes.toByteArray());
			}
			exchange.close();
		});
		server.start();
	}

	URI uri() {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private static ServletInputStream input(byte[] bytes) {
		ByteArrayInputStream input = new ByteArrayInputStream(bytes);
		return new ServletInputStream() {
			@Override public int read() throws IOException { return input.read(); }
			@Override public boolean isFinished() { return input.available() == 0; }
			@Override public boolean isReady() { return true; }
			@Override public void setReadListener(ReadListener listener) { }
		};
	}

	private static String parameter(URI uri, String name) {
		if (uri.getRawQuery() != null) {
			for (String pair : uri.getRawQuery().split("&")) {
				String[] values = pair.split("=", 2);
				if (values.length == 2 && values[0].equals(name)) {
					return URLDecoder.decode(values[1], StandardCharsets.UTF_8);
				}
			}
		}
		return null;
	}

	private static Map<String, byte[]> multipart(String contentType, byte[] body) {
		Map<String, byte[]> result = new HashMap<>();
		if (contentType == null || !contentType.startsWith("multipart/form-data; boundary=")) {
			return result;
		}
		String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
		String text = new String(body, StandardCharsets.ISO_8859_1);
		for (String part : text.split(Pattern.quote("--" + boundary))) {
			int split = part.indexOf("\r\n\r\n");
			if (split < 0) {
				continue;
			}
			Matcher name = Pattern.compile("name=\"([^\"]+)\"").matcher(part.substring(0, split));
			if (name.find()) {
				result.put(name.group(1), part.substring(split + 4, part.length() - 2).getBytes(StandardCharsets.ISO_8859_1));
			}
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
	}
}
