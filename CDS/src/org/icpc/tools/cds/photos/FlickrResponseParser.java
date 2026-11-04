package org.icpc.tools.cds.photos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.icpc.tools.contest.model.feed.JSONParser;
import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;

/**
 * Parses a {@code flickr.photosets.getPhotos} JSON body ({@code format=json&nojsoncallback=1}).
 * The largest available image is preferred: {@code url_o}, then {@code url_k}, then {@code url_l}.
 */
public final class FlickrResponseParser {
	private static final String[] URL_KEYS = new String[] { "url_o", "url_k", "url_l" };

	public static final class Page {
		public final int page;
		public final int pages;
		public final int total;
		public final List<Photo> photos;

		public Page(int page, int pages, int total, List<Photo> photos) {
			this.page = page;
			this.pages = pages;
			this.total = total;
			this.photos = photos;
		}
	}

	private FlickrResponseParser() {
		// utility
	}

	public static Page parse(String json) throws IOException {
		if (json == null || json.trim().isEmpty())
			throw new IOException("Empty Flickr response");

		JsonObject root;
		try {
			root = new JSONParser(json).readObject();
		} catch (RuntimeException e) {
			throw new IOException("Could not parse Flickr response");
		}

		String stat = text(root, "stat");
		if (!"ok".equals(stat)) {
			String message = text(root, "message");
			if (message == null || message.isEmpty())
				message = "Flickr API request failed";
			throw new IOException(message);
		}

		JsonObject photoset = root.getJsonObject("photoset");
		if (photoset == null)
			throw new IOException("Flickr response has no photoset");

		int page = positive(photoset, "page", 1);
		int pages = positive(photoset, "pages", 1);
		int total = positive(photoset, "total", 0);
		String setOwner = text(photoset, "ownername");
		if (setOwner == null)
			setOwner = text(photoset, "owner_name");

		List<Photo> photos = new ArrayList<>();
		Object[] raw = photoset.getArray("photo");
		if (raw != null) {
			for (Object item : raw) {
				if (!(item instanceof JsonObject))
					continue;
				Photo photo = toPhoto((JsonObject) item, setOwner);
				if (photo != null)
					photos.add(photo);
			}
		}
		return new Page(page, pages, total, photos);
	}

	private static Photo toPhoto(JsonObject obj, String setOwner) {
		String id = text(obj, "id");
		if (id == null || id.isEmpty())
			return null;

		String url = null;
		for (String key : URL_KEYS) {
			String candidate = text(obj, key);
			if (candidate != null && !candidate.isEmpty()) {
				url = candidate;
				break;
			}
		}
		if (url == null)
			return null;

		String owner = text(obj, "ownername");
		if (owner == null)
			owner = text(obj, "owner_name");
		if (owner == null)
			owner = setOwner;

		return new Photo(id, text(obj, "title"), owner, text(obj, "license"), url);
	}

	private static int positive(JsonObject obj, String key, int fallback) {
		int value = obj.getInt(key);
		if (value < 0)
			return fallback;
		return value;
	}

	private static String text(JsonObject obj, String key) {
		if (obj == null || !obj.containsKey(key) || obj.isNull(key))
			return null;
		Object value = obj.get(key);
		if (value instanceof String)
			return obj.getString(key);
		return String.valueOf(value);
	}
}
