package org.icpc.tools.cds.photos;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Picks a {@link PhotoSource} from the URI scheme. Flickr is the first implementation; further
 * schemes can register a factory here.
 */
public final class PhotoSourceRegistry {
	public interface Factory {
		/**
		 * @param tag only list photos with this tag, or {@code null} for all of them
		 */
		PhotoSource create(String canonicalUri, String apiKey, String tag);
	}

	private static final Map<String, Factory> FACTORIES = new HashMap<>();

	static {
		register("flickr", new Factory() {
			@Override
			public PhotoSource create(String canonicalUri, String apiKey, String tag) {
				return new FlickrPhotoSource(canonicalUri, apiKey, tag);
			}
		});
	}

	private PhotoSourceRegistry() {
		// utility
	}

	public static void register(String scheme, Factory factory) {
		if (scheme == null || factory == null) {
			throw new IllegalArgumentException("Missing photo source factory");
		}
		FACTORIES.put(scheme.toLowerCase(Locale.ROOT), factory);
	}

	public static PhotoSource create(String uri, String apiKey) {
		return create(uri, apiKey, null);
	}

	public static PhotoSource create(String uri, String apiKey, String tag) {
		PhotoSourceUris.Parsed parsed = PhotoSourceUris.parse(uri);
		Factory factory = FACTORIES.get(parsed.scheme);
		if (factory == null) {
			throw new IllegalArgumentException("No photo source for scheme: " + parsed.scheme);
		}
		return factory.create(parsed.canonicalUri, apiKey, tag);
	}
}
