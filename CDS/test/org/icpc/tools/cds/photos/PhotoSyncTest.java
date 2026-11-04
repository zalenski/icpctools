package org.icpc.tools.cds.photos;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(JUnit4.class)
public class PhotoSyncTest {
	@Test
	public void onlyManagesFilesItCreated() throws Exception {
		File folder = Files.createTempDirectory("photo-sync").toFile();
		try {
			write(new File(folder, "manual.jpg"), "manual");
			write(new File(folder, "notes.txt"), "notes");

			PhotoMetadata other = new PhotoMetadata("flickr", "flickr://album/other", "9", "Other", "Pat", "4",
					"https://example.test/9.jpg", 10L, "other-source.jpg");
			write(new File(folder, "other-source.jpg"), "other");
			PhotoMetadata.writeSidecar(new File(folder, "other-source.jpg"), other);
			PhotoMetadata.writeIndex(folder, Arrays.asList(other));

			String blocked = PhotoSync.imageFileName("flickr", "flickr://album/42", "1",
					"https://live.staticflickr.com/1/x.jpg");
			write(new File(folder, blocked), "do-not-touch");

			MemorySource source = new MemorySource("flickr://album/42");
			source.add("1", "Say \"hi\"", "Lidia Example", "4", "https://live.staticflickr.com/1/x.jpg",
					"downloaded".getBytes(StandardCharsets.UTF_8));

			PhotoSync.Result result = new PhotoSync().sync(folder, source, 1700000000000L);
			assertEquals(1, result.downloaded);
			assertEquals(0, result.removed);
			assertEquals("manual", read(new File(folder, "manual.jpg")));
			assertEquals("notes", read(new File(folder, "notes.txt")));
			assertEquals("other", read(new File(folder, "other-source.jpg")));
			assertEquals("do-not-touch", read(new File(folder, blocked)));

			String created = "flickr-album_42-1-2.jpg";
			assertEquals("downloaded", read(new File(folder, created)));
			PhotoMetadata sidecar = PhotoMetadata.readSidecar(new File(folder, created));
			assertEquals("flickr://album/42", sidecar.getSourceUri());
			assertEquals("1", sidecar.getPhotoId());
			assertEquals("Say \"hi\"", sidecar.getTitle());
			assertEquals("Lidia Example", sidecar.getOwner());
			assertEquals("4", sidecar.getLicense());
			assertEquals("https://live.staticflickr.com/1/x.jpg", sidecar.getOriginalUrl());
			assertEquals(1700000000000L, sidecar.getFetchTime());
			assertEquals(created, sidecar.getFile());

			assertEquals(Arrays.asList("other-source.jpg", created), filenames(PhotoMetadata.readIndex(folder)));

			PhotoSync.Result again = new PhotoSync().sync(folder, source, 1700000000500L);
			assertEquals(0, again.downloaded);
			assertEquals(0, again.removed);
			assertEquals("do-not-touch", read(new File(folder, blocked)));
			assertEquals("downloaded", read(new File(folder, created)));

			source.photos.clear();
			PhotoSync.Result removed = new PhotoSync().sync(folder, source, 1700000001000L);
			assertEquals(1, removed.removed);
			assertFalse(new File(folder, created).exists());
			assertFalse(new File(folder, created + ".json").exists());
			assertEquals("manual", read(new File(folder, "manual.jpg")));
			assertEquals("notes", read(new File(folder, "notes.txt")));
			assertEquals("other", read(new File(folder, "other-source.jpg")));
			assertEquals("do-not-touch", read(new File(folder, blocked)));
			assertEquals(Arrays.asList("other-source.jpg"), filenames(PhotoMetadata.readIndex(folder)));
		} finally {
			deleteTree(folder);
		}
	}

	@Test
	public void listingFailureLeavesExistingFiles() throws Exception {
		File folder = Files.createTempDirectory("photo-sync-fail").toFile();
		try {
			write(new File(folder, "manual.jpg"), "manual");
			MemorySource source = new MemorySource("flickr://album/42");
			source.add("7", "Kept", "Owner", "1", "https://live.staticflickr.com/7.jpg",
					"pixels".getBytes(StandardCharsets.UTF_8));
			new PhotoSync().sync(folder, source, 20L);
			String name = PhotoSync.imageFileName("flickr", "flickr://album/42", "7",
					"https://live.staticflickr.com/7.jpg");
			assertTrue(new File(folder, name).isFile());

			source.listFailure = new IOException("offline");
			try {
				new PhotoSync().sync(folder, source, 30L);
				fail("Expected the source failure to abort the sync");
			} catch (IOException e) {
				assertEquals("offline", e.getMessage());
			}
			assertEquals("manual", read(new File(folder, "manual.jpg")));
			assertEquals("pixels", read(new File(folder, name)));
			assertEquals(1, PhotoMetadata.readIndex(folder).size());
		} finally {
			deleteTree(folder);
		}
	}

	private static List<String> filenames(List<PhotoMetadata> metas) {
		List<String> names = new ArrayList<>();
		for (PhotoMetadata meta : metas)
			names.add(meta.getFile());
		return names;
	}

	private static void write(File file, String text) throws IOException {
		FileOutputStream out = new FileOutputStream(file);
		try {
			out.write(text.getBytes(StandardCharsets.UTF_8));
		} finally {
			out.close();
		}
	}

	private static String read(File file) throws IOException {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	private static void deleteTree(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children)
				deleteTree(child);
		}
		file.delete();
	}

	private static final class MemorySource implements PhotoSource {
		private final String uri;
		private final List<Photo> photos = new ArrayList<>();
		private final Map<String, byte[]> bytes = new LinkedHashMap<>();
		private IOException listFailure;

		private MemorySource(String uri) {
			this.uri = uri;
		}

		private void add(String id, String title, String owner, String license, String url, byte[] body) {
			photos.add(new Photo(id, title, owner, license, url));
			bytes.put(id, body);
		}

		@Override
		public String getType() {
			return "flickr";
		}

		@Override
		public String getUri() {
			return uri;
		}

		@Override
		public List<Photo> listPhotos() throws IOException {
			if (listFailure != null)
				throw listFailure;
			return new ArrayList<>(photos);
		}

		@Override
		public void fetchOriginal(Photo photo, File destination) throws IOException {
			byte[] body = bytes.get(photo.getId());
			if (body == null)
				throw new IOException("missing " + photo.getId());
			FileOutputStream out = new FileOutputStream(destination);
			try {
				out.write(body);
			} finally {
				out.close();
			}
		}
	}
}
