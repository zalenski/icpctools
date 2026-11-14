package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

/** A local file or folder; unchanged originals are recognized by their modification time and size. */
final class LocalPhotoSource implements PhotoSource {
	private final Path root;
	private final Map<String, Path> files = new HashMap<>();

	LocalPhotoSource(URI uri) {
		root = Path.of(uri).toAbsolutePath().normalize();
	}

	@Override
	public String getType() {
		return "local";
	}

	@Override
	public String getUri() {
		return root.toUri().toString();
	}

	@Override
	public List<Photo> listPhotos() throws IOException {
		if (!Files.exists(root)) {
			throw new IOException("Local photo source is missing");
		}
		List<Photo> photos = new ArrayList<>();
		files.clear();
		try (Stream<Path> paths = Files.isDirectory(root) ? Files.walk(root) : Stream.of(root)) {
			for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile).sorted()::iterator) {
				String name = path.getFileName().toString();
				String lower = name.toLowerCase(Locale.ROOT);
				if (lower.endsWith(".json") || lower.endsWith(".tmp")) {
					continue;
				}
				try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
					if (input == null || !ImageIO.getImageReaders(input).hasNext()) {
						continue;
					}
				}
				String id = root.equals(path) ? name : root.relativize(path).toString();
				String version = path.toUri() + "?modified=" + Files.getLastModifiedTime(path).toMillis()
						+ "&size=" + Files.size(path);
				files.put(id, path);
				photos.add(new Photo(id, name, null, null, version));
			}
		}
		return photos;
	}

	@Override
	public void fetchOriginal(Photo photo, File destination) throws IOException {
		Path path = files.get(photo.getId());
		if (path == null) {
			throw new IOException("Local photo is no longer listed");
		}
		Files.copy(path, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}
}
