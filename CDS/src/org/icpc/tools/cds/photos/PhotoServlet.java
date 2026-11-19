package org.icpc.tools.cds.photos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.icpc.tools.cds.CDSAuth;
import org.icpc.tools.cds.CDSConfig;
import org.icpc.tools.contest.model.feed.JSONParser;
import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.feed.JSONWriter;
import org.icpc.tools.contest.model.photos.PhotoMetadata;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

@WebServlet(urlPatterns = "/api/photos/*")
@MultipartConfig(maxFileSize = 104857600, maxRequestSize = 105906176, fileSizeThreshold = 0)
public class PhotoServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private PhotoCatalogStore store;

	protected synchronized PhotoCatalogStore store() {
		if (store == null) {
			store = new PhotoCatalogStore(CDSConfig.getFolder());
		}
		return store;
	}

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		if (!CDSAuth.isPresAdmin(request)) {
			response.setHeader("WWW-Authenticate", "Basic realm=\"CDS photos\"");
			response.sendError(request.getRemoteUser() == null ? 401 : 403);
			return;
		}
		response.setHeader("Cache-Control", "no-store");
		try {
			String path = request.getPathInfo();
			String[] parts = path == null ? new String[0] : path.split("/");
			String method = request.getMethod();
			if (parts.length == 2 && "catalogs".equals(parts[1]) && "GET".equals(method)) {
				JsonObject result = new JsonObject();
				result.put("catalogs", store().catalogs().toArray());
				json(response, result);
				return;
			}
			if (parts.length == 4 && "catalogs".equals(parts[1])) {
				String catalog = parts[2];
				if ("sources".equals(parts[3])) {
					if ("GET".equals(method)) {
						json(response, store().sources(catalog));
						return;
					}
					if ("PUT".equals(method)) {
						store().sources(catalog, readJson(request.getInputStream()));
						response.setStatus(204);
						return;
					}
				}
				if ("index".equals(parts[3]) && "GET".equals(method)) {
					List<PhotoMetadata> records = store().index(catalog);
					JsonObject result = new JsonObject();
					result.put("photos", records.stream().map(PhotoMetadata::toJson).toArray());
					json(response, result);
					return;
				}
			}
			if (parts.length == 6 && "catalogs".equals(parts[1]) && "imports".equals(parts[3])
					&& "photos".equals(parts[5])) {
				if ("POST".equals(method)) {
					Part metadata = request.getPart("metadata");
					Part photo = request.getPart("photo");
					if (metadata == null || photo == null) {
						throw new IllegalArgumentException("Missing photo or metadata part");
					}
					try (InputStream meta = metadata.getInputStream(); InputStream image = photo.getInputStream()) {
						json(response, store().upload(parts[2], parts[4], PhotoMetadata.fromJson(readJson(meta)), image).toJson());
					}
					return;
				}
				if ("PUT".equals(method)) {
					PhotoMetadata supplied = PhotoMetadata.fromJson(readJson(request.getInputStream()));
					PhotoMetadata stored = store().updateMetadata(parts[2], parts[4], supplied);
					if (stored == null) {
						response.sendError(404);
					} else {
						json(response, stored.toJson());
					}
					return;
				}
				if ("DELETE".equals(method)) {
					String photoId = request.getParameter("photoId");
					if (photoId == null || photoId.isEmpty()) {
						throw new IllegalArgumentException("Missing photoId");
					}
					store().delete(parts[2], parts[4], photoId);
					response.setStatus(204);
					return;
				}
			}
			response.sendError(404);
		} catch (IllegalArgumentException | ClassCastException e) {
			response.sendError(400, "Invalid photo request");
		}
	}

	private static JsonObject readJson(InputStream input) throws IOException {
		byte[] bytes = input.readNBytes(65537);
		if (bytes.length > 65536) {
			throw new IllegalArgumentException("Photo JSON exceeds 64 KiB");
		}
		return new JSONParser(new String(bytes, StandardCharsets.UTF_8)).readObject();
	}

	private static void json(HttpServletResponse response, JsonObject object) throws IOException {
		response.setContentType("application/json;charset=UTF-8");
		new JSONWriter(response.getWriter()).writeObject(object);
	}
}
