package org.icpc.tools.contest.model.photos;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.icpc.tools.contest.model.feed.JSONParser;
import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.feed.JSONWriter;

/**
 * Per-photo records in the catalog index. Files not recorded here are left alone.
 */
public final class PhotoMetadata {
	public static final String INDEX_NAME = "photos-index.json";

	private final String importId;
	private final String sourceType;
	private final String sourceUri;
	private final String photoId;
	private final String title;
	private final String owner;
	private final String license;
	private final String originalUrl;
	private final long fetchTime;
	private final String file;
	private final String originalFile;
	private final int width;
	private final int height;
	private final Integer maxWidth;
	private final Integer maxHeight;

	public PhotoMetadata(String sourceType, String sourceUri, String photoId, String title, String owner,
			String license, String originalUrl, long fetchTime, String file) {
		this(sourceType, sourceUri, photoId, title, owner, license, originalUrl, fetchTime, file, null, 0, 0, null,
				null);
	}

	public PhotoMetadata(String sourceType, String sourceUri, String photoId, String title, String owner,
			String license, String originalUrl, long fetchTime, String file, String originalFile, int width, int height,
			Integer maxWidth, Integer maxHeight) {
		this(sourceType, sourceUri, photoId, title, owner, license, originalUrl, fetchTime, file, originalFile,
				width, height, maxWidth, maxHeight, null);
	}

	private PhotoMetadata(String sourceType, String sourceUri, String photoId, String title, String owner,
			String license, String originalUrl, long fetchTime, String file, String originalFile, int width, int height,
			Integer maxWidth, Integer maxHeight, String importId) {
		this.importId = importId;
		this.sourceType = sourceType;
		this.sourceUri = sourceUri;
		this.photoId = photoId;
		this.title = title;
		this.owner = owner;
		this.license = license;
		this.originalUrl = originalUrl;
		this.fetchTime = fetchTime;
		this.file = file;
		this.originalFile = originalFile;
		this.width = width;
		this.height = height;
		this.maxWidth = maxWidth;
		this.maxHeight = maxHeight;
	}

	public String getImportId() {
		return importId == null ? sourceUri : importId;
	}

	public PhotoMetadata withImportId(String id) {
		return new PhotoMetadata(sourceType, sourceUri, photoId, title, owner, license, originalUrl, fetchTime, file,
				originalFile, width, height, maxWidth, maxHeight, id);
	}

	public PhotoMetadata withFile(String name) {
		return new PhotoMetadata(sourceType, sourceUri, photoId, title, owner, license, originalUrl, fetchTime, name,
				originalFile, width, height, maxWidth, maxHeight, importId);
	}

	public String getSourceType() {
		return sourceType;
	}

	public String getSourceUri() {
		return sourceUri;
	}

	public String getPhotoId() {
		return photoId;
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

	public long getFetchTime() {
		return fetchTime;
	}

	public String getFile() {
		return file;
	}

	public String getOriginalFile() {
		return originalFile;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public Integer getMaxWidth() {
		return maxWidth;
	}

	public Integer getMaxHeight() {
		return maxHeight;
	}

	public PhotoMetadata withDetails(String title2, String owner2, String license2) {
		return new PhotoMetadata(sourceType, sourceUri, photoId, title2, owner2, license2, originalUrl, fetchTime, file,
				originalFile, width, height, maxWidth, maxHeight, importId);
	}

	public PhotoMetadata withLimits(Integer maxWidth2, Integer maxHeight2) {
		return new PhotoMetadata(sourceType, sourceUri, photoId, title, owner, license, originalUrl, fetchTime, file,
				originalFile, width, height, maxWidth2, maxHeight2, importId);
	}

	/** Whether the records describe the same prepared image, regardless of metadata and allocated filename. */
	public boolean sameImage(PhotoMetadata other) {
		return other != null && Objects.equals(getImportId(), other.getImportId())
				&& Objects.equals(sourceType, other.sourceType) && Objects.equals(sourceUri, other.sourceUri)
				&& Objects.equals(photoId, other.photoId) && Objects.equals(originalUrl, other.originalUrl)
				&& fetchTime == other.fetchTime && width == other.width && height == other.height;
	}

	public JsonObject toJson() {
		JsonObject obj = new JsonObject();
		obj.put("importId", getImportId());
		obj.put("sourceType", sourceType);
		obj.put("sourceUri", sourceUri);
		obj.put("photoId", photoId);
		obj.put("title", title);
		obj.put("owner", owner);
		obj.put("license", license);
		obj.put("originalUrl", originalUrl);
		obj.put("fetchTime", Long.valueOf(fetchTime));
		obj.put("file", file);
		if (originalFile != null) {
			obj.put("originalFile", originalFile);
		}
		obj.put("width", Integer.valueOf(width));
		obj.put("height", Integer.valueOf(height));
		if (maxWidth != null) {
			obj.put("maxWidth", maxWidth);
		}
		if (maxHeight != null) {
			obj.put("maxHeight", maxHeight);
		}
		return obj;
	}

	public static PhotoMetadata fromJson(JsonObject obj) {
		if (obj == null) {
			return null;
		}
		int w = obj.getInt("width");
		int h = obj.getInt("height");
		return new PhotoMetadata(obj.getString("sourceType"), obj.getString("sourceUri"), obj.getString("photoId"),
				obj.getString("title"), obj.getString("owner"), obj.getString("license"), obj.getString("originalUrl"),
				obj.getLong("fetchTime"), obj.getString("file"), obj.getString("originalFile"), w < 0 ? 0 : w,
				h < 0 ? 0 : h, dimension(obj, "maxWidth"), dimension(obj, "maxHeight"))
				.withImportId(obj.getString("importId"));
	}

	private static Integer dimension(JsonObject obj, String key) {
		if (obj == null || !obj.containsKey(key) || obj.isNull(key)) {
			return null;
		}
		int value = obj.getInt(key);
		if (value <= 0) {
			return null;
		}
		return Integer.valueOf(value);
	}

	public static void writeIndex(File folder, List<PhotoMetadata> photos) throws IOException {
		JsonObject root = new JsonObject();
		Object[] arr = new Object[photos.size()];
		for (int i = 0; i < photos.size(); i++) {
			arr[i] = photos.get(i).toJson();
		}
		root.put("photos", arr);
		writeObject(new File(folder, INDEX_NAME), root);
	}

	public static List<PhotoMetadata> readIndex(File folder) throws IOException {
		File index = new File(folder, INDEX_NAME);
		if (!index.isFile()) {
			return new ArrayList<>();
		}

		JsonObject root;
		try {
			root = readObject(index);
		} catch (RuntimeException e) {
			throw new IOException("Could not read " + INDEX_NAME);
		}
		List<PhotoMetadata> list = new ArrayList<>();
		Object[] arr = root.getArray("photos");
		if (arr == null) {
			throw new IOException("Missing photos array in " + INDEX_NAME);
		}
		for (Object item : arr) {
			if (!(item instanceof JsonObject)) {
				throw new IOException("Invalid photo record in " + INDEX_NAME);
			}
			PhotoMetadata meta = fromJson((JsonObject) item);
			if (meta.getPhotoId() == null || meta.getImportId() == null || meta.getFile() == null) {
				throw new IOException("Incomplete photo record in " + INDEX_NAME);
			}
			list.add(meta);
		}
		return list;
	}

	public static JsonObject readObject(File file) throws IOException {
		InputStream in = new FileInputStream(file);
		try {
			return new JSONParser(in).readObject();
		} catch (RuntimeException e) {
			throw new IOException("Could not parse " + file.getName());
		} finally {
			in.close();
		}
	}

	public static void writeObject(File dest, JsonObject obj) throws IOException {
		File folder = dest.getParentFile();
		if (folder != null && !folder.exists() && !folder.mkdirs()) {
			throw new IOException("Could not create " + folder);
		}

		File tmp = File.createTempFile("photometa-", ".tmp", folder);
		try {
			try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
				new JSONWriter(writer).writeObject(obj);
			}
			Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} finally {
			if (tmp.exists()) {
				tmp.delete();
			}
		}
	}

}
