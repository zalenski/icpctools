package org.icpc.tools.cds.photos;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Flickr album source. Calls {@code flickr.photosets.getPhotos} and downloads the original image,
 * or the largest size the album exposes. An optional tag keeps only the album's photos with that
 * tag, for example {@code event$Team Photos} from the ICPC gallery.
 */
public final class FlickrPhotoSource implements PhotoSource {
	static final String TYPE = "flickr";
	private static final int MAX_PAGES = 40;
	private static final String EXTRAS = "url_o,url_k,url_l,owner_name,license,tags";

	private final String canonicalUri;
	private final String albumId;
	private final String apiKey;
	private final String tag;

	public FlickrPhotoSource(String canonicalUri, String apiKey) {
		this(canonicalUri, apiKey, null);
	}

	/**
	 * @param tag a tag as written on Flickr or in the gallery, or {@code null} for the whole album
	 */
	public FlickrPhotoSource(String canonicalUri, String apiKey, String tag) {
		PhotoSourceUris.Parsed parsed = PhotoSourceUris.parse(canonicalUri);
		if (!TYPE.equals(parsed.scheme)) {
			throw new IllegalArgumentException("Not a Flickr album URI: " + canonicalUri);
		}
		this.canonicalUri = parsed.canonicalUri;
		this.albumId = parsed.albumId;
		this.apiKey = apiKey;
		this.tag = normalizeTag(tag);
	}

	/**
	 * Flickr's normalised form of a tag, which is what the API returns: lower case, with only letters
	 * and digits kept. So {@code event$Team Photos} becomes {@code eventteamphotos}. Returns
	 * {@code null} for no tag.
	 */
	public static String normalizeTag(String tag) {
		if (tag == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		String lower = tag.toLowerCase(Locale.ROOT);
		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			if (Character.isLetterOrDigit(c)) {
				sb.append(c);
			}
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	@Override
	public String getType() {
		return TYPE;
	}

	@Override
	public String getUri() {
		// a filtered source is its own source, so its photos don't mix with the whole album's
		if (tag != null) {
			return canonicalUri + "?tag=" + tag;
		}
		return canonicalUri;
	}

	public String getAlbumId() {
		return albumId;
	}

	@Override
	public List<Photo> listPhotos() throws IOException {
		if (apiKey == null || apiKey.isEmpty()) {
			throw new IOException("Flickr API key is not set");
		}

		List<Photo> all = new ArrayList<>();
		int page = 1;
		int pages = 1;
		while (page <= pages && page <= MAX_PAGES) {
			String body;
			try {
				body = PhotoHttp.get(buildUrl(page));
			} catch (IOException e) {
				throw new IOException(
						"Flickr album " + albumId + " request failed: " + PhotoHttp.redact(e.getMessage()));
			}
			FlickrResponseParser.Page parsed = FlickrResponseParser.parse(body, tag);
			if (parsed.pages > pages) {
				pages = parsed.pages;
			}
			all.addAll(parsed.photos);
			page++;
		}
		return all;
	}

	@Override
	public void fetchOriginal(Photo photo, File destination) throws IOException {
		if (photo == null || photo.getOriginalUrl() == null || photo.getOriginalUrl().isEmpty()) {
			throw new IOException("Photo has no original URL");
		}
		try {
			PhotoHttp.download(photo.getOriginalUrl(), destination);
		} catch (IOException e) {
			throw new IOException("Could not download photo " + photo.getId() + ": " + PhotoHttp.redact(e.getMessage()));
		}
	}

	private String buildUrl(int page) throws IOException {
		try {
			return "https://api.flickr.com/services/rest/?method=flickr.photosets.getPhotos&api_key="
					+ URLEncoder.encode(apiKey, "UTF-8") + "&photoset_id=" + URLEncoder.encode(albumId, "UTF-8")
					+ "&extras=" + URLEncoder.encode(EXTRAS, "UTF-8")
					+ "&format=json&nojsoncallback=1&per_page=500&page=" + page;
		} catch (Exception e) {
			throw new IOException("Could not build Flickr request");
		}
	}
}
