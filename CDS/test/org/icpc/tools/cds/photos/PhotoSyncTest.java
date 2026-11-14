package org.icpc.tools.cds.photos;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.imageio.ImageIO;
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
		File display = Files.createTempDirectory("photo-sync").toFile();
		File originals = Files.createTempDirectory("photo-originals").toFile();
		try {
			write(new File(display, "manual.jpg"), "manual");
			write(new File(display, "notes.txt"), "notes");
			write(new File(originals, "keep.jpg"), "keep");

			PhotoMetadata other = new PhotoMetadata("flickr", "flickr://album/other", "9", "Other", "Pat", "4",
					"https://example.test/9.jpg", 10L, "other-source.jpg");
			write(new File(display, "other-source.jpg"), "other");
			PhotoMetadata.writeSidecar(new File(display, "other-source.jpg"), other);
			PhotoMetadata.writeIndex(display, Arrays.asList(other));

			String blocked = PhotoSync.imageFileName("flickr", "flickr://album/42", "1",
					"https://live.staticflickr.com/1/x.jpg");
			write(new File(display, blocked), "do-not-touch");
			write(new File(originals, blocked), "do-not-touch-original");

			MemorySource source = new MemorySource("flickr://album/42");
			source.add("1", "Say \"hi\"", "Lidia Example", "4", "https://live.staticflickr.com/1/x.jpg",
					"downloaded".getBytes(StandardCharsets.UTF_8));

			PhotoSync.Result result = new PhotoSync().sync(display, originals, source, null, null, 1700000000000L);
			assertEquals(1, result.downloaded);
			assertEquals(0, result.removed);
			assertEquals(1, source.fetches);
			assertEquals("manual", read(new File(display, "manual.jpg")));
			assertEquals("notes", read(new File(display, "notes.txt")));
			assertEquals("other", read(new File(display, "other-source.jpg")));
			assertEquals("do-not-touch", read(new File(display, blocked)));
			assertEquals("do-not-touch-original", read(new File(originals, blocked)));
			assertEquals("keep", read(new File(originals, "keep.jpg")));

			String created = "flickr-album_42-1-2.jpg";
			assertEquals("downloaded", read(new File(display, created)));
			assertEquals("downloaded", read(new File(originals, created)));
			PhotoMetadata sidecar = PhotoMetadata.readSidecar(new File(display, created));
			assertEquals("flickr://album/42", sidecar.getSourceUri());
			assertEquals("1", sidecar.getPhotoId());
			assertEquals("Say \"hi\"", sidecar.getTitle());
			assertEquals("Lidia Example", sidecar.getOwner());
			assertEquals("4", sidecar.getLicense());
			assertEquals("https://live.staticflickr.com/1/x.jpg", sidecar.getOriginalUrl());
			assertEquals(1700000000000L, sidecar.getFetchTime());
			assertEquals(created, sidecar.getFile());
			assertEquals(created, sidecar.getOriginalFile());

			assertEquals(Arrays.asList("other-source.jpg", created), filenames(PhotoMetadata.readIndex(display)));

			PhotoSync.Result again = new PhotoSync().sync(display, originals, source, null, null, 1700000000500L);
			assertEquals(0, again.downloaded);
			assertEquals(0, again.removed);
			assertEquals(1, source.fetches);
			assertEquals("do-not-touch", read(new File(display, blocked)));
			assertEquals("downloaded", read(new File(display, created)));
			assertEquals("downloaded", read(new File(originals, created)));

			source.photos.clear();
			PhotoSync.Result removed = new PhotoSync().sync(display, originals, source, null, null, 1700000001000L);
			assertEquals(1, removed.removed);
			assertFalse(new File(display, created).exists());
			assertFalse(new File(display, created + ".json").exists());
			assertFalse(new File(originals, created).exists());
			assertEquals("manual", read(new File(display, "manual.jpg")));
			assertEquals("notes", read(new File(display, "notes.txt")));
			assertEquals("other", read(new File(display, "other-source.jpg")));
			assertEquals("do-not-touch", read(new File(display, blocked)));
			assertEquals("do-not-touch-original", read(new File(originals, blocked)));
			assertEquals("keep", read(new File(originals, "keep.jpg")));
			assertEquals(Arrays.asList("other-source.jpg"), filenames(PhotoMetadata.readIndex(display)));
		} finally {
			deleteTree(display);
			deleteTree(originals);
		}
	}

	@Test
	public void listingFailureLeavesExistingFiles() throws Exception {
		File display = Files.createTempDirectory("photo-sync-fail").toFile();
		File originals = Files.createTempDirectory("photo-originals-fail").toFile();
		try {
			write(new File(display, "manual.jpg"), "manual");
			MemorySource source = new MemorySource("flickr://album/42");
			source.add("7", "Kept", "Owner", "1", "https://live.staticflickr.com/7.jpg",
					"pixels".getBytes(StandardCharsets.UTF_8));
			new PhotoSync().sync(display, originals, source, null, null, 20L);
			String name = PhotoSync.imageFileName("flickr", "flickr://album/42", "7",
					"https://live.staticflickr.com/7.jpg");
			assertTrue(new File(display, name).isFile());
			assertTrue(new File(originals, name).isFile());

			source.listFailure = new IOException("offline");
			try {
				new PhotoSync().sync(display, originals, source, null, null, 30L);
				fail("Expected the source failure to abort the sync");
			} catch (IOException e) {
				assertEquals("offline", e.getMessage());
			}
			assertEquals("manual", read(new File(display, "manual.jpg")));
			assertEquals("pixels", read(new File(display, name)));
			assertEquals("pixels", read(new File(originals, name)));
			assertEquals(1, PhotoMetadata.readIndex(display).size());
		} finally {
			deleteTree(display);
			deleteTree(originals);
		}
	}

	@Test
	public void regeneratesDisplayCopyWhenLimitsChangeWithoutDownloading() throws Exception {
		File display = Files.createTempDirectory("photo-scale").toFile();
		File originals = Files.createTempDirectory("photo-scale-originals").toFile();
		try {
			byte[] png = png(40, 20);
			MemorySource source = new MemorySource("flickr://album/42");
			source.add("1", "Wide", "Owner", "4", "https://live.staticflickr.com/1.png", png);

			PhotoSync.Result first = new PhotoSync().sync(display, originals, source, Integer.valueOf(20),
					Integer.valueOf(10), 50L);
			assertEquals(1, first.downloaded);
			assertEquals(1, source.fetches);
			String name = PhotoSync.imageFileName("flickr", "flickr://album/42", "1", "https://live.staticflickr.com/1.png");
			assertPng(new File(display, name), 20, 10);
			assertTrue(java.util.Arrays.equals(png, Files.readAllBytes(new File(originals, name).toPath())));
			PhotoMetadata sidecar = PhotoMetadata.readSidecar(new File(display, name));
			assertEquals(20, sidecar.getWidth());
			assertEquals(10, sidecar.getHeight());
			assertEquals(Integer.valueOf(20), sidecar.getMaxWidth());
			assertEquals(Integer.valueOf(10), sidecar.getMaxHeight());
			assertEquals(name, sidecar.getOriginalFile());

			PhotoSync.Result second = new PhotoSync().sync(display, originals, source, Integer.valueOf(10),
					Integer.valueOf(10), 60L);
			assertEquals(0, second.downloaded);
			assertEquals(1, source.fetches);
			assertPng(new File(display, name), 10, 5);
			assertTrue(java.util.Arrays.equals(png, Files.readAllBytes(new File(originals, name).toPath())));
			sidecar = PhotoMetadata.readSidecar(new File(display, name));
			assertEquals(10, sidecar.getWidth());
			assertEquals(5, sidecar.getHeight());
			assertEquals(Integer.valueOf(10), sidecar.getMaxWidth());
			assertEquals(Integer.valueOf(10), sidecar.getMaxHeight());
			assertEquals(50L, sidecar.getFetchTime());
		} finally {
			deleteTree(display);
			deleteTree(originals);
		}
	}

	private static List<String> filenames(List<PhotoMetadata> metas) {
		List<String> names = new ArrayList<>();
		for (PhotoMetadata meta : metas) {
			names.add(meta.getFile());
		}
		return names;
	}

	private static byte[] png(int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(Color.RED);
			g.fillRect(0, 0, width, height);
		} finally {
			g.dispose();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(image, "png", out));
		return out.toByteArray();
	}

	private static void assertPng(File file, int width, int height) throws IOException {
		byte[] bytes = Files.readAllBytes(file.toPath());
		assertTrue(bytes.length > 8);
		assertEquals((byte) 0x89, bytes[0]);
		assertEquals((byte) 0x50, bytes[1]);
		assertEquals((byte) 0x4e, bytes[2]);
		assertEquals((byte) 0x47, bytes[3]);
		BufferedImage image = ImageIO.read(file);
		assertEquals(width, image.getWidth());
		assertEquals(height, image.getHeight());
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
			for (File child : children) {
				deleteTree(child);
			}
		}
		file.delete();
	}

	private static final class MemorySource implements PhotoSource {
		private final String uri;
		private final List<Photo> photos = new ArrayList<>();
		private final Map<String, byte[]> bytes = new LinkedHashMap<>();
		private IOException listFailure;
		private int fetches;

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
			if (listFailure != null) {
				throw listFailure;
			}
			return new ArrayList<>(photos);
		}

		@Override
		public void fetchOriginal(Photo photo, File destination) throws IOException {
			fetches++;
			byte[] body = bytes.get(photo.getId());
			if (body == null) {
				throw new IOException("missing " + photo.getId());
			}
			FileOutputStream out = new FileOutputStream(destination);
			try {
				out.write(body);
			} finally {
				out.close();
			}
		}
	}
}
