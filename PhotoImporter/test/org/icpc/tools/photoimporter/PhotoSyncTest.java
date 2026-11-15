package org.icpc.tools.photoimporter;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.icpc.tools.contest.model.photos.PhotoMetadata;
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
			PhotoMetadata.writeIndex(display, Arrays.asList(other));

			String blocked = PhotoSync.imageFileName("flickr", "flickr://album/42", "1",
					"https://live.staticflickr.com/1/x.jpg");
			write(new File(display, blocked), "do-not-touch");
			write(new File(originals, blocked), "do-not-touch-original");

			MemorySource source = new MemorySource("flickr://album/42");
			source.add("1", "Say \"hi\"", "Lidia Example", "4", "https://live.staticflickr.com/1/x.jpg",
					png(10, 10));

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
			org.junit.Assert.assertArrayEquals(png(10, 10), Files.readAllBytes(new File(display, created).toPath()));
			org.junit.Assert.assertArrayEquals(png(10, 10), Files.readAllBytes(new File(originals, created).toPath()));
			PhotoMetadata record = PhotoMetadata.readIndex(display).get(1);
			assertEquals("flickr://album/42", record.getSourceUri());
			assertEquals("1", record.getPhotoId());
			assertEquals("Say \"hi\"", record.getTitle());
			assertEquals("Lidia Example", record.getOwner());
			assertEquals("4", record.getLicense());
			assertEquals("https://live.staticflickr.com/1/x.jpg", record.getOriginalUrl());
			assertEquals(1700000000000L, record.getFetchTime());
			assertEquals(created, record.getFile());
			assertEquals(created, record.getOriginalFile());
			assertFalse(new File(display, created + ".json").exists());

			assertEquals(Arrays.asList("other-source.jpg", created), filenames(PhotoMetadata.readIndex(display)));

			PhotoSync.Result again = new PhotoSync().sync(display, originals, source, null, null, 1700000000500L);
			assertEquals(0, again.downloaded);
			assertEquals(0, again.removed);
			assertEquals(1, source.fetches);
			assertEquals("do-not-touch", read(new File(display, blocked)));
			org.junit.Assert.assertArrayEquals(png(10, 10), Files.readAllBytes(new File(display, created).toPath()));
			org.junit.Assert.assertArrayEquals(png(10, 10), Files.readAllBytes(new File(originals, created).toPath()));

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
					png(10, 10));
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
			org.junit.Assert.assertArrayEquals(png(10, 10), Files.readAllBytes(new File(display, name).toPath()));
			org.junit.Assert.assertArrayEquals(png(10, 10), Files.readAllBytes(new File(originals, name).toPath()));
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
			PhotoMetadata record = PhotoMetadata.readIndex(display).get(0);
			assertEquals(20, record.getWidth());
			assertEquals(10, record.getHeight());
			assertEquals(Integer.valueOf(20), record.getMaxWidth());
			assertEquals(Integer.valueOf(10), record.getMaxHeight());
			assertEquals(name, record.getOriginalFile());

			PhotoSync.Result second = new PhotoSync().sync(display, originals, source, Integer.valueOf(10),
					Integer.valueOf(10), 60L);
			assertEquals(0, second.downloaded);
			assertEquals(1, source.fetches);
			assertPng(new File(display, name), 10, 5);
			assertTrue(java.util.Arrays.equals(png, Files.readAllBytes(new File(originals, name).toPath())));
			record = PhotoMetadata.readIndex(display).get(0);
			assertEquals(10, record.getWidth());
			assertEquals(5, record.getHeight());
			assertEquals(Integer.valueOf(10), record.getMaxWidth());
			assertEquals(Integer.valueOf(10), record.getMaxHeight());
			assertEquals(50L, record.getFetchTime());
		} finally {
			deleteTree(display);
			deleteTree(originals);
		}
	}

	@Test
	public void stableImportIdsOwnPhotosIndependentlyOfSourceUris() throws Exception {
		File display = Files.createTempDirectory("photo-import-ids").toFile();
		File originals = Files.createTempDirectory("photo-import-originals").toFile();
		try {
			MemorySource source = new MemorySource("flickr://album/42");
			source.add("1", "Shared", "Owner", "4", "https://example.test/1.jpg",
					png(10, 10));
			PhotoSync sync = new PhotoSync();
			sync.sync(display, originals, source, "first", null, null, 10L);
			sync.sync(display, originals, source, "second", null, null, 20L);
			List<PhotoMetadata> records = PhotoMetadata.readIndex(display);
			assertEquals(2, records.size());
			String first = records.get(0).getFile();
			String second = records.get(1).getFile();
			assertFalse(first.equals(second));
			assertEquals("first", records.get(0).getImportId());
			assertEquals("second", records.get(1).getImportId());

			MemorySource retargeted = new MemorySource("flickr://album/other");
			sync.sync(display, originals, retargeted, "first", null, null, 30L);
			assertFalse(new File(display, first).exists());
			assertTrue(new File(display, second).isFile());
			assertEquals(1, PhotoMetadata.readIndex(display).size());
			assertEquals("second", PhotoMetadata.readIndex(display).get(0).getImportId());
			assertEquals(0, sync.sync(display, originals, source, "second", null, null, 40L).downloaded);
		} finally {
			deleteTree(display);
			deleteTree(originals);
		}
	}

	@Test
	public void aFailedDownloadKeepsCompletedAndPreviousPhotosInTheIndex() throws Exception {
		File display = Files.createTempDirectory("photo-partial").toFile();
		File originals = Files.createTempDirectory("photo-partial-originals").toFile();
		try {
			MemorySource source = new MemorySource("flickr://album/42");
			source.add("1", "First", "Owner", "4", "https://example.test/1.jpg",
					png(10, 10));
			PhotoSync sync = new PhotoSync();
			sync.sync(display, originals, source, "teams", null, null, 10L);
			source.photos.clear();
			source.add("1", "Updated", "Owner", "4", "https://example.test/changed.jpg", null);
			source.add("2", "Second", "Owner", "4", "https://example.test/2.jpg",
					png(20, 10));
			PhotoSync.Result result = sync.sync(display, originals, source, "teams", null, null, 20L);
			assertEquals(1, result.downloaded);
			assertEquals(0, result.removed);
			assertEquals(1, result.failed);
			List<PhotoMetadata> records = PhotoMetadata.readIndex(display);
			assertEquals(2, records.size());
			org.junit.Assert.assertArrayEquals(png(10, 10), Files.readAllBytes(new File(display, records.get(0).getFile()).toPath()));
			org.junit.Assert.assertArrayEquals(png(20, 10), Files.readAllBytes(new File(display, records.get(1).getFile()).toPath()));
			assertEquals("teams", records.get(0).getImportId());
			assertEquals("teams", records.get(1).getImportId());
		} finally {
			deleteTree(display);
			deleteTree(originals);
		}
	}

	@Test
	public void checkpointsEveryTenSecondsAndKeepsUnvisitedRecordsUntilCompletion() throws Exception {
		File display = Files.createTempDirectory("photo-checkpoint").toFile();
		File originals = Files.createTempDirectory("photo-checkpoint-originals").toFile();
		try {
			PhotoMetadata other = new PhotoMetadata("flickr", "flickr://album/other", "other", "Other", "Owner",
					"4", "https://example.test/other.jpg", 1L, "other.jpg").withImportId("other-import");
			PhotoMetadata old = new PhotoMetadata("flickr", "flickr://album/42", "old", "Old", "Owner", "4",
					"https://example.test/old.jpg", 1L, "old.jpg").withImportId("teams");
			write(new File(display, "other.jpg"), "other");
			write(new File(display, "old.jpg"), "old");
			PhotoMetadata.writeIndex(display, Arrays.asList(other, old));
			MemorySource source = new MemorySource("flickr://album/42");
			for (int i = 1; i <= 3; i++) {
				source.add(Integer.toString(i), "New", "Owner", "4", "https://example.test/" + i + ".jpg",
						png(10, 10));
			}
			AtomicLong clock = new AtomicLong();
			source.beforeFetch = () -> {
				try {
					List<PhotoMetadata> index = PhotoMetadata.readIndex(display);
					if (source.fetches <= 2) {
						assertEquals(2, index.size());
						clock.set(source.fetches == 1 ? PhotoSync.CHECKPOINT_INTERVAL_MS - 1
								: PhotoSync.CHECKPOINT_INTERVAL_MS);
					} else {
						assertEquals(Arrays.asList("other.jpg", "old.jpg", "flickr-album_42-1.jpg",
								"flickr-album_42-2.jpg"), filenames(index));
						for (String file : display.list()) {
							assertFalse(file.endsWith(".tmp"));
						}
						throw new IllegalStateException("interrupted");
					}
				} catch (IOException e) {
					throw new AssertionError(e);
				}
			};
			PhotoSync sync = new PhotoSync(clock::get);
			try {
				sync.sync(display, originals, source, "teams", null, null, 20L);
				fail("Expected the test interruption");
			} catch (IllegalStateException e) {
				assertEquals("interrupted", e.getMessage());
			}
			assertTrue(new File(display, "old.jpg").isFile());
			assertEquals(4, PhotoMetadata.readIndex(display).size());

			source.beforeFetch = null;
			PhotoSync.Result resumed = sync.sync(display, originals, source, "teams", null, null, 30L);
			assertEquals(1, resumed.downloaded);
			assertEquals(1, resumed.removed);
			assertEquals(4, source.fetches);
			assertFalse(new File(display, "old.jpg").exists());
			assertTrue(new File(display, "other.jpg").isFile());
			assertEquals(4, PhotoMetadata.readIndex(display).size());
		} finally {
			deleteTree(display);
			deleteTree(originals);
		}
	}

	@Test
	public void createsIndexAtStartSavesAtFinishAndDoesNotRewriteUnchangedIndex() throws Exception {
		File display = Files.createTempDirectory("photo-checkpoint-start").toFile();
		File originals = Files.createTempDirectory("photo-checkpoint-start-originals").toFile();
		try {
			MemorySource source = new MemorySource("flickr://album/42");
			source.add("1", "Photo", "Owner", "4", "https://example.test/1.jpg",
					png(10, 10));
			source.beforeFetch = () -> {
				try {
					assertTrue(new File(display, PhotoMetadata.INDEX_NAME).isFile());
					assertTrue(PhotoMetadata.readIndex(display).isEmpty());
				} catch (IOException e) {
					throw new AssertionError(e);
				}
			};
			PhotoSync sync = new PhotoSync(() -> 0);
			sync.sync(display, originals, source, "teams", null, null, 10L);
			assertEquals(1, PhotoMetadata.readIndex(display).size());
			File index = new File(display, PhotoMetadata.INDEX_NAME);
			Files.setLastModifiedTime(index.toPath(), FileTime.fromMillis(12345000L));
			long modified = index.lastModified();
			sync.sync(display, originals, source, "teams", null, null, 20L);
			assertEquals(modified, index.lastModified());
			assertEquals(1, source.fetches);
		} finally {
			deleteTree(display);
			deleteTree(originals);
		}
	}

	@Test
	public void cancellationCheckpointsCompletedPhotosAndKeepsUnvisitedPhotos() throws Exception {
		File display = Files.createTempDirectory("photo-cancel-display").toFile();
		File originals = Files.createTempDirectory("photo-cancel-originals").toFile();
		try {
			MemorySource source = new MemorySource("flickr://album/42");
			source.add("old", "old", null, null, "https://example/old.png", png(10, 10));
			new PhotoSync().sync(display, originals, source, "test", null, null, 10L);
			PhotoMetadata old = PhotoMetadata.readIndex(display).get(0);
			source.photos.clear();
			source.add("new", "new", null, null, "https://example/new.png", png(10, 10));
			source.add("later", "later", null, null, "https://example/later.png", png(10, 10));
			source.beforeFetch = () -> {
				if (source.fetches == 3) {
					Thread.currentThread().interrupt();
				}
			};
			boolean[] completed = { false };
			try {
				new PhotoSync(() -> 0L, (photos, complete) -> completed[0] |= complete)
						.sync(display, originals, source, "test", null, null, 20L);
				org.junit.Assert.fail("Expected cancellation");
			} catch (java.io.InterruptedIOException expected) {
				assertTrue(Thread.currentThread().isInterrupted());
			} finally {
				Thread.interrupted();
			}
			List<PhotoMetadata> saved = PhotoMetadata.readIndex(display);
			assertEquals(2, saved.size());
			assertTrue(saved.stream().anyMatch(photo -> "new".equals(photo.getPhotoId())));
			assertTrue(new File(display, old.getFile()).isFile());
			assertFalse(completed[0]);
			assertEquals(0, originals.listFiles((folder, name) -> name.endsWith(".tmp")).length);
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
		private Runnable beforeFetch;

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
			if (beforeFetch != null) {
				beforeFetch.run();
			}
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
