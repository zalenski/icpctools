package org.icpc.tools.cds.photos;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * A remote album the CDS can list and download. Implementations are chosen from the source URI
 * scheme by {@link PhotoSourceRegistry}.
 */
public interface PhotoSource {
	/**
	 * Stable type name, for example {@code flickr}.
	 */
	String getType();

	/**
	 * Canonical source URI, for example {@code flickr://album/123}.
	 */
	String getUri();

	/**
	 * Photos currently in the album, in source order.
	 */
	List<Photo> listPhotos() throws IOException;

	/**
	 * Writes the original image, or the largest available size, to {@code destination}.
	 */
	void fetchOriginal(Photo photo, File destination) throws IOException;
}
