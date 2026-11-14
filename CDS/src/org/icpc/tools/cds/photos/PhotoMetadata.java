package org.icpc.tools.cds.photos;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.icpc.tools.contest.model.feed.JSONParser;
import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.feed.JSONWriter;

/**
 * Metadata stored next to a synced image and in the folder index. Files the sync did not record
 * here are left alone.
 */
public final class PhotoMetadata {
	public static final String INDEX_NAME = "photos-index.json";

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
				originalFile, width, height, maxWidth, maxHeight);
	}

	public JsonObject toJson() {
		JsonObject obj = new JsonObject();
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
				h < 0 ? 0 : h, dimension(obj, "maxWidth"), dimension(obj, "maxHeight"));
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

	public static File sidecarFile(File imageFile) {
		return new File(imageFile.getParentFile(), imageFile.getName() + ".json");
	}

	public static void writeSidecar(File imageFile, PhotoMetadata meta) throws IOException {
		writeObject(sidecarFile(imageFile), meta.toJson());
	}

	public static PhotoMetadata readSidecar(File imageFile) throws IOException {
		File sidecar = sidecarFile(imageFile);
		if (!sidecar.isFile()) {
			return null;
		}
		return fromJson(readObject(sidecar));
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
			return list;
		}
		for (Object item : arr) {
			if (item instanceof JsonObject) {
				PhotoMetadata meta = fromJson((JsonObject) item);
				if (meta != null && meta.getPhotoId() != null) {
					list.add(meta);
				}
			}
		}
		return list;
	}

	private static JsonObject readObject(File file) throws IOException {
		InputStream in = new FileInputStream(file);
		try {
			return new JSONParser(in).readObject();
		} catch (RuntimeException e) {
			throw new IOException("Could not parse " + file.getName());
		} finally {
			in.close();
		}
	}

	private static void writeObject(File dest, JsonObject obj) throws IOException {
		File folder = dest.getParentFile();
		if (folder != null && !folder.exists() && !folder.mkdirs()) {
			throw new IOException("Could not create " + folder);
		}

		File tmp = File.createTempFile("photometa-", ".tmp", folder);
		Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
		try {
			new JSONWriter(writer).writeObject(obj);
		} finally {
			writer.close();
		}
		try {
			move(tmp, dest);
		} finally {
			if (tmp.exists()) {
				tmp.delete();
			}
		}
	}

	private static void move(File tmp, File dest) throws IOException {
		try {
			Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
