package org.icpc.tools.cds.photos;

/**
 * A photo listed by a {@link PhotoSource}, before it is stored on disk.
 */
public final class Photo {
	private final String id;
	private final String title;
	private final String owner;
	private final String license;
	private final String originalUrl;

	public Photo(String id, String title, String owner, String license, String originalUrl) {
		this.id = id;
		this.title = title;
		this.owner = owner;
		this.license = license;
		this.originalUrl = originalUrl;
	}

	public String getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public String getOwner() {
		return owner;
	}

	public String getLicense() {
		return license;
	}

	public String getOriginalUrl() {
		return originalUrl;
	}
}
