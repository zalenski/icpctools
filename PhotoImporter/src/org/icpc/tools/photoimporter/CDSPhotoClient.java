package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import org.icpc.tools.contest.model.feed.HTTPSSecurity.ContestTrustManager;
import org.icpc.tools.contest.model.feed.JSONParser;
import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.feed.JSONWriter;
import org.icpc.tools.contest.model.photos.PhotoMetadata;

/** All catalog access uses HTTP, including when importer and CDS share a host. */
public final class CDSPhotoClient {
	private final URI base;
	private final String authorization;
	private final HttpClient client;

	public CDSPhotoClient(URI base, String user, String password, boolean insecure) throws IOException {
		if (!("https".equals(base.getScheme()) || "http".equals(base.getScheme())) || base.getHost() == null
				|| base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
			throw new IllegalArgumentException("Expected a CDS HTTP(S) URL without credentials or query");
		}
		this.base = base;
		authorization = user == null ? null : "Basic " + Base64.getEncoder().encodeToString(
				(user + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
		try {
			HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(PhotoHttp.CONNECT_TIMEOUT)
					.followRedirects(Redirect.NEVER);
			SSLContext insecureContext = insecure ? SSLContext.getInstance("TLS") : null;
			if (insecureContext != null) {
				insecureContext.init(null, new TrustManager[] { new ContestTrustManager() }, null);
				builder.sslContext(insecureContext);
			}
			client = builder.build();
		} catch (Exception e) {
			throw new IOException("Could not initialize CDS TLS", e);
		}
	}

	public URI target() {
		return URI.create(base.toString().replaceAll("/+$", ""));
	}

	public List<String> catalogs() throws IOException {
		JsonObject response = get("/catalogs");
		List<String> catalogs = new ArrayList<>();
		for (Object item : response.getArray("catalogs")) {
			catalogs.add((String) item);
		}
		return catalogs;
	}

	public JsonObject sources(String catalog) throws IOException {
		return get(path(catalog) + "/sources");
	}

	public void sources(String catalog, JsonObject definitions) throws IOException {
		HttpRequest request = request(path(catalog) + "/sources").header("Content-Type", "application/json")
				.PUT(BodyPublishers.ofString(json(definitions))).build();
		response(request);
	}

	public List<PhotoMetadata> index(String catalog) throws IOException {
		List<PhotoMetadata> records = new ArrayList<>();
		for (Object item : get(path(catalog) + "/index").getArray("photos")) {
			records.add(PhotoMetadata.fromJson((JsonObject) item));
		}
		return records;
	}

	PhotoMetadata upload(String catalog, String importId, PhotoMetadata metadata, File image) throws IOException {
		String boundary = "icpc-photo-" + UUID.randomUUID();
		String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"metadata\"\r\n"
				+ "Content-Type: application/json\r\n\r\n" + json(metadata.toJson()) + "\r\n"
				+ "--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"photo\"\r\n"
				+ "Content-Type: application/octet-stream\r\n\r\n";
		HttpRequest request = request(importPath(catalog, importId))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(BodyPublishers.concat(BodyPublishers.ofString(prefix), BodyPublishers.ofFile(image.toPath()),
						BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"))).build();
		return PhotoMetadata.fromJson(response(request));
	}

	PhotoMetadata updateMetadata(String catalog, String importId, PhotoMetadata metadata) throws IOException {
		HttpRequest request = request(importPath(catalog, importId)).header("Content-Type", "application/json")
				.PUT(BodyPublishers.ofString(json(metadata.toJson()))).build();
		HttpResponse<String> response = PhotoHttp.send(client, request, BodyHandlers.ofString(StandardCharsets.UTF_8));
		return response.statusCode() == 404 ? null : PhotoMetadata.fromJson(response(response));
	}

	void delete(String catalog, String importId, String photoId) throws IOException {
		response(request(importPath(catalog, importId) + "?photoId=" + encode(photoId)).DELETE().build());
	}

	private JsonObject get(String path) throws IOException {
		return response(request(path).GET().build());
	}

	private HttpRequest.Builder request(String path) {
		String root = base.toString().replaceAll("/+$", "");
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(root + "/api/photos" + path))
				.timeout(PhotoHttp.REQUEST_TIMEOUT).header("Accept", "application/json");
		if (authorization != null) {
			builder.header("Authorization", authorization);
		}
		return builder;
	}

	private JsonObject response(HttpRequest request) throws IOException {
		return response(PhotoHttp.send(client, request, BodyHandlers.ofString(StandardCharsets.UTF_8)));
	}

	private static JsonObject response(HttpResponse<String> response) throws IOException {
		int code = response.statusCode();
		if (code < 200 || code >= 300) {
			throw new IOException("CDS photo API returned HTTP " + code);
		}
		if (code == 204) {
			return null;
		}
		return new JSONParser(response.body()).readObject();
	}

	private static String path(String catalog) {
		return "/catalogs/" + encode(catalog);
	}

	private static String importPath(String catalog, String importId) {
		return path(catalog) + "/imports/" + encode(importId) + "/photos";
	}

	private static String encode(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String json(JsonObject object) throws IOException {
		StringWriter writer = new StringWriter();
		new JSONWriter(writer).writeObject(object);
		return writer.toString();
	}

}
