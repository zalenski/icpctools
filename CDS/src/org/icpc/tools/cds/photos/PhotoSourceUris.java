package org.icpc.tools.cds.photos;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses photo-source URIs into a scheme and a canonical URI. Flickr albums are
 * {@code flickr://album/&lt;albumId&gt;} or a normal album URL such as
 * {@code https://www.flickr.com/photos/&lt;user&gt;/albums/&lt;id&gt;}.
 */
public final class PhotoSourceUris {
	public static final class Parsed {
		public final String scheme;
		public final String canonicalUri;
		public final String albumId;

		public Parsed(String scheme, String canonicalUri, String albumId) {
			this.scheme = scheme;
			this.canonicalUri = canonicalUri;
			this.albumId = albumId;
		}
	}

	private PhotoSourceUris() {
		// utility
	}

	public static Parsed parse(String raw) {
		if (raw == null || raw.trim().isEmpty())
			throw new IllegalArgumentException("Missing photo source URI");

		String uri = raw.trim();
		String lower = uri.toLowerCase(Locale.ROOT);
		if (lower.startsWith("flickr:"))
			return parseFlickrUri(uri);

		try {
			URI parsed = new URI(uri);
			String scheme = parsed.getScheme();
			if (scheme != null) {
				scheme = scheme.toLowerCase(Locale.ROOT);
				if ("http".equals(scheme) || "https".equals(scheme))
					return parseFlickrWeb(parsed, uri);
			}
		} catch (Exception e) {
			throw new IllegalArgumentException("Unrecognized photo source URI: " + uri);
		}
		throw new IllegalArgumentException("Unrecognized photo source URI: " + uri);
	}

	private static Parsed parseFlickrUri(String uri) {
		String rest = uri.substring("flickr:".length());
		if (rest.startsWith("//"))
			rest = rest.substring(2);
		int cut = rest.indexOf('?');
		if (cut < 0)
			cut = rest.indexOf('#');
		else {
			int hash = rest.indexOf('#');
			if (hash >= 0 && hash < cut)
				cut = hash;
		}
		if (cut >= 0)
			rest = rest.substring(0, cut);
		while (rest.endsWith("/"))
			rest = rest.substring(0, rest.length() - 1);

		if (!rest.toLowerCase(Locale.ROOT).startsWith("album/"))
			throw new IllegalArgumentException("Unsupported Flickr URI: " + uri);

		String id = rest.substring("album/".length());
		return flickrAlbum(id, uri);
	}

	private static Parsed parseFlickrWeb(URI parsed, String raw) {
		String host = parsed.getHost();
		if (host == null)
			throw new IllegalArgumentException("Unrecognized photo source URI: " + raw);
		host = host.toLowerCase(Locale.ROOT);
		if (!"flickr.com".equals(host) && !host.endsWith(".flickr.com"))
			throw new IllegalArgumentException("Unrecognized photo source URI: " + raw);

		String path = parsed.getPath();
		if (path == null)
			throw new IllegalArgumentException("Unrecognized Flickr album URL: " + raw);

		List<String> segs = new ArrayList<>();
		for (String part : path.split("/")) {
			if (!part.isEmpty())
				segs.add(part);
		}
		if (segs.size() >= 4 && "photos".equalsIgnoreCase(segs.get(0))
				&& ("albums".equalsIgnoreCase(segs.get(2)) || "sets".equalsIgnoreCase(segs.get(2))))
			return flickrAlbum(segs.get(3), raw);

		throw new IllegalArgumentException("Unrecognized Flickr album URL: " + raw);
	}

	private static Parsed flickrAlbum(String id, String raw) {
		if (id == null)
			throw new IllegalArgumentException("Missing Flickr album id: " + raw);
		id = id.trim();
		if (id.isEmpty() || id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.contains(".."))
			throw new IllegalArgumentException("Invalid Flickr album id: " + raw);
		return new Parsed("flickr", "flickr://album/" + id, id);
	}
}
