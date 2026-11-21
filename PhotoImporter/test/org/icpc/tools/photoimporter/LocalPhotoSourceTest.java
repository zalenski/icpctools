package org.icpc.tools.photoimporter;

import static org.junit.Assert.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

import org.icpc.tools.contest.model.photos.PhotoMetadata;
import org.junit.Test;

public class LocalPhotoSourceTest {
	@Test
	public void readsOriginalsDirectlyAndRegeneratesOnlyWhenInputsOrLimitsChange() throws Exception {
		Path work = Files.createTempDirectory("local-photo-incremental");
		try {
			Path input = Files.createDirectory(work.resolve("input"));
			Path image = input.resolve("photo.png");
			png(image, 40, 20);
			Path cache = Files.createDirectory(work.resolve("cache"));
			Path originals = work.resolve("originals");
			sync(input, cache, originals, "test", 20, 10L);
			PhotoMetadata first = PhotoMetadata.readIndex(cache.toFile()).get(0);
			assertNull(first.getOriginalFile());
			assertFalse(Files.exists(originals));
			assertEquals(20, ImageIO.read(cache.resolve(first.getFile()).toFile()).getWidth());
			assertEquals(40, ImageIO.read(image.toFile()).getWidth());
			byte[] index = Files.readAllBytes(cache.resolve(PhotoMetadata.INDEX_NAME));
			java.nio.file.attribute.FileTime displayTime = Files.getLastModifiedTime(cache.resolve(first.getFile()));
			sync(input, cache, originals, "test", 20, 20L);
			assertArrayEquals(index, Files.readAllBytes(cache.resolve(PhotoMetadata.INDEX_NAME)));
			assertEquals(displayTime, Files.getLastModifiedTime(cache.resolve(first.getFile())));
			sync(input, cache, originals, "test", 10, 30L);
			assertEquals(10, ImageIO.read(cache.resolve(first.getFile()).toFile()).getWidth());
			png(image, 60, 20);
			sync(input, cache, originals, "test", null, 40L);
			PhotoMetadata changed = PhotoMetadata.readIndex(cache.toFile()).get(0);
			assertEquals(60, changed.getWidth());
			assertEquals(40L, changed.getFetchTime());
		} finally {
			delete(work);
		}
	}

	@Test
	public void cachesValidAndInvalidDecodingResultsAcrossRestarts() throws Exception {
		Path work = Files.createTempDirectory("local-photo-index");
		ProbeSpi probe = new ProbeSpi();
		IIORegistry.getDefaultInstance().registerServiceProvider(probe);
		try {
			Path input = Files.createDirectory(work.resolve("input"));
			Path nested = Files.createDirectory(input.resolve("nested"));
			Files.writeString(nested.resolve("good.png"), "ICPCtest+");
			Files.writeString(input.resolve("bad.png"), "ICPCtest!");
			Files.writeString(input.resolve("notes.txt"), "not an image");
			Files.writeString(input.resolve("metadata.json"), "ignored");
			Files.writeString(input.resolve("upload.tmp"), "ignored");
			Path cache = Files.createDirectory(work.resolve("cache"));
			sync(input, cache, work.resolve("originals"), "test", 20, 10L);
			assertEquals(2, probe.reads.get());
			assertEquals(1, PhotoMetadata.readIndex(cache.toFile()).size());
			String observations = Files.readString(cache.resolve("local-index-test.json"));
			assertTrue(observations.contains("nested/good.png"));
			assertTrue(observations.contains("invalid"));
			assertFalse(observations.contains("metadata.json"));
			assertFalse(observations.contains("upload.tmp"));
			sync(input, cache, work.resolve("originals"), "test", 20, 20L);
			assertEquals("Unchanged valid and invalid files must not be decoded again", 2, probe.reads.get());
			assertEquals(observations, Files.readString(cache.resolve("local-index-test.json")));
			// Invalid files are checked again after their fingerprint changes.
			Files.writeString(input.resolve("bad.png"), "ICPCtest+repaired");
			sync(input, cache, work.resolve("originals"), "test", 20, 30L);
			assertEquals(3, probe.reads.get());
			assertEquals(2, PhotoMetadata.readIndex(cache.toFile()).size());
		} finally {
			IIORegistry.getDefaultInstance().deregisterServiceProvider(probe);
			delete(work);
		}
	}

	@Test
	public void changingFileKeepsLastGoodCopyAndIsRetriedOnNextScan() throws Exception {
		Path work = Files.createTempDirectory("local-photo-changing");
		ProbeSpi probe = new ProbeSpi();
		IIORegistry.getDefaultInstance().registerServiceProvider(probe);
		try {
			Path input = work.resolve("photo.png");
			Files.writeString(input, "ICPCtest+");
			Path cache = Files.createDirectory(work.resolve("cache"));
			sync(input, cache, work.resolve("originals"), "test", 20, 10L);
			PhotoMetadata previous = PhotoMetadata.readIndex(cache.toFile()).get(0);
			byte[] good = Files.readAllBytes(cache.resolve(previous.getFile()));
			Files.writeString(input, "ICPCtest+changed");
			probe.onRead = () -> {
				try {
					Files.writeString(input, "ICPCtest+changed again while reading");
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			};
			assertEquals(1, sync(input, cache, work.resolve("originals"), "test", 10, 20L).failed);
			assertArrayEquals(good, Files.readAllBytes(cache.resolve(previous.getFile())));
			assertEquals(10L, PhotoMetadata.readIndex(cache.toFile()).get(0).getFetchTime());
			assertTrue(Files.readString(cache.resolve("local-index-test.json")).contains("error"));
			probe.onRead = () -> { };
			assertEquals(0, sync(input, cache, work.resolve("originals"), "test", 10, 30L).failed);
			assertEquals(10, ImageIO.read(cache.resolve(previous.getFile()).toFile()).getWidth());
			assertEquals(30L, PhotoMetadata.readIndex(cache.toFile()).get(0).getFetchTime());
		} finally {
			IIORegistry.getDefaultInstance().deregisterServiceProvider(probe);
			delete(work);
		}
	}

	@Test
	public void corruptReplacementAndMissingDirectoryPreserveLastGoodPhotos() throws Exception {
		Path work = Files.createTempDirectory("local-photo-errors");
		try {
			Path input = Files.createDirectory(work.resolve("input"));
			Path image = input.resolve("photo.png");
			png(image, 40, 20);
			Path cache = Files.createDirectory(work.resolve("cache"));
			sync(input, cache, work.resolve("originals"), "test", null, 10L);
			PhotoMetadata good = PhotoMetadata.readIndex(cache.toFile()).get(0);
			byte[] bytes = Files.readAllBytes(cache.resolve(good.getFile()));
			Files.writeString(image, "not a photo anymore");
			assertEquals(1, sync(input, cache, work.resolve("originals"), "test", null, 20L).failed);
			assertArrayEquals(bytes, Files.readAllBytes(cache.resolve(good.getFile())));
			byte[] index = Files.readAllBytes(cache.resolve(PhotoMetadata.INDEX_NAME));
			Files.move(input, work.resolve("offline"));
			try {
				sync(input, cache, work.resolve("originals"), "test", null, 30L);
				fail("Missing source must not be treated as an empty directory");
			} catch (IOException expected) {
				assertArrayEquals(index, Files.readAllBytes(cache.resolve(PhotoMetadata.INDEX_NAME)));
			}
			assertArrayEquals(bytes, Files.readAllBytes(cache.resolve(good.getFile())));
		} finally {
			delete(work);
		}
	}

	@Test
	public void completeScanPrunesOnlyMissingPhotosOwnedByThisImport() throws Exception {
		Path work = Files.createTempDirectory("local-photo-ownership");
		try {
			Path first = Files.createDirectory(work.resolve("first"));
			Path second = Files.createDirectory(work.resolve("second"));
			png(first.resolve("photo.png"), 40, 20);
			png(second.resolve("photo.png"), 40, 20);
			Path cache = Files.createDirectory(work.resolve("cache"));
			Files.writeString(cache.resolve("manual.jpg"), "manual");
			sync(first, cache, work.resolve("originals"), "first", 20, 10L);
			sync(second, cache, work.resolve("originals"), "second", 20, 20L);
			assertEquals(2, PhotoMetadata.readIndex(cache.toFile()).size());
			Files.delete(first.resolve("photo.png"));
			assertEquals(1, sync(first, cache, work.resolve("originals"), "first", 20, 30L).removed);
			List<PhotoMetadata> remaining = PhotoMetadata.readIndex(cache.toFile());
			assertEquals(1, remaining.size());
			assertEquals("second", remaining.get(0).getImportId());
			assertTrue(Files.isRegularFile(cache.resolve(remaining.get(0).getFile())));
			assertEquals("manual", Files.readString(cache.resolve("manual.jpg")));
		} finally {
			delete(work);
		}
	}

	@Test
	public void interruptionSavesCompletedWorkAndKeepsUnvisitedPhotos() throws Exception {
		Path work = Files.createTempDirectory("local-photo-stop");
		ProbeSpi probe = new ProbeSpi();
		IIORegistry.getDefaultInstance().registerServiceProvider(probe);
		try {
			Path input = Files.createDirectory(work.resolve("input"));
			Files.writeString(input.resolve("old.png"), "ICPCtest+");
			Path cache = Files.createDirectory(work.resolve("cache"));
			sync(input, cache, work.resolve("originals"), "test", 20, 10L);
			Files.writeString(input.resolve("a-new.png"), "ICPCtest+");
			Files.writeString(input.resolve("b-later.png"), "ICPCtest+");
			probe.onRead = () -> {
				if (probe.reads.get() == 3) {
					Thread.currentThread().interrupt();
				}
			};
			boolean[] complete = { false };
			try {
				new PhotoSync(() -> 0L, (photos, finished) -> complete[0] |= finished)
						.sync(cache.toFile(), work.resolve("originals").toFile(),
								new LocalPhotoSource(input.toUri()), "test", 20, null, 20L);
				fail("Expected cancellation");
			} catch (java.io.InterruptedIOException expected) {
				assertTrue(Thread.currentThread().isInterrupted());
			} finally {
				Thread.interrupted();
			}
			List<PhotoMetadata> saved = PhotoMetadata.readIndex(cache.toFile());
			assertEquals(2, saved.size());
			assertTrue(saved.stream().anyMatch(photo -> "old.png".equals(photo.getPhotoId())));
			assertTrue(saved.stream().anyMatch(photo -> "a-new.png".equals(photo.getPhotoId())));
			assertFalse(complete[0]);
			probe.onRead = () -> { };
			sync(input, cache, work.resolve("originals"), "test", 20, 30L);
			assertEquals(3, PhotoMetadata.readIndex(cache.toFile()).size());
			assertEquals("Only the interrupted image should be decoded on retry", 4, probe.reads.get());
			assertEquals(0, cache.toFile().listFiles((folder, name) -> name.startsWith("local-photo-")).length);
		} finally {
			Thread.interrupted();
			IIORegistry.getDefaultInstance().deregisterServiceProvider(probe);
			delete(work);
		}
	}

	@Test
	public void sourceDisappearingAfterListingDoesNotPruneMissingPhotos() throws Exception {
		Path work = Files.createTempDirectory("local-photo-incomplete-scan");
		try {
			Path input = Files.createDirectory(work.resolve("input"));
			png(input.resolve("a.png"), 40, 20);
			png(input.resolve("b.png"), 40, 20);
			Path cache = Files.createDirectory(work.resolve("cache"));
			sync(input, cache, work.resolve("originals"), "test", 20, 10L);
			byte[] index = Files.readAllBytes(cache.resolve(PhotoMetadata.INDEX_NAME));
			Files.delete(input.resolve("a.png"));
			try {
				new PhotoSync(() -> 0L, (photos, complete) -> { }, progress -> {
					if ("preparing".equals(progress.phase()) && Files.exists(input)) {
						try {
							Files.move(input, work.resolve("offline"));
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					}
				}).sync(cache.toFile(), work.resolve("originals").toFile(),
						new LocalPhotoSource(input.toUri()), "test", 20, null, 20L);
				fail("Source disappearance must prevent pruning");
			} catch (IOException expected) {
				assertArrayEquals(index, Files.readAllBytes(cache.resolve(PhotoMetadata.INDEX_NAME)));
			}
			for (PhotoMetadata photo : PhotoMetadata.readIndex(cache.toFile())) {
				assertTrue(Files.isRegularFile(cache.resolve(photo.getFile())));
			}
		} finally {
			delete(work);
		}
	}

	@Test
	public void changedLimitsReuseIdenticalOutputWithoutDecodingOrCopying() throws Exception {
		Path work = Files.createTempDirectory("local-photo-reuse-size");
		ProbeSpi probe = new ProbeSpi();
		IIORegistry.getDefaultInstance().registerServiceProvider(probe);
		try {
			Path input = work.resolve("photo.png");
			Files.writeString(input, "ICPCtest+");
			Path cache = Files.createDirectory(work.resolve("cache"));
			Path originals = work.resolve("originals");
			sync(input, cache, originals, "test", 100, 10L);
			org.icpc.tools.contest.model.feed.JSONParser.JsonObject observation =
					(org.icpc.tools.contest.model.feed.JSONParser.JsonObject) PhotoMetadata.readObject(
							cache.resolve("local-index-test.json").toFile()).getArray("files")[0];
			assertEquals(40, observation.getInt("originalWidth"));
			assertEquals(20, observation.getInt("originalHeight"));
			PhotoMetadata first = PhotoMetadata.readIndex(cache.toFile()).get(0);
			Path display = cache.resolve(first.getFile());
			java.nio.file.attribute.FileTime unchangedTime = Files.getLastModifiedTime(display);
			sync(input, cache, originals, "test", 200, 20L);
			PhotoMetadata reused = PhotoMetadata.readIndex(cache.toFile()).get(0);
			assertEquals(1, probe.reads.get());
			assertEquals(unchangedTime, Files.getLastModifiedTime(display));
			assertEquals(10L, reused.getFetchTime());
			assertEquals(Integer.valueOf(200), reused.getMaxWidth());
			assertTrue(first.sameImage(reused));
			// Regenerate once when the actual output changes.
			sync(input, cache, originals, "test", 20, 30L);
			assertEquals(2, probe.reads.get());
			unchangedTime = Files.getLastModifiedTime(display);
			// A different width limit still yields 20x10 because height is limiting.
			new PhotoSync().sync(cache.toFile(), originals.toFile(), new LocalPhotoSource(input.toUri()),
					"test", 30, 10, 40L);
			reused = PhotoMetadata.readIndex(cache.toFile()).get(0);
			assertEquals(2, probe.reads.get());
			assertEquals(unchangedTime, Files.getLastModifiedTime(display));
			assertEquals(20, reused.getWidth());
			assertEquals(10, reused.getHeight());
			assertEquals(30L, reused.getFetchTime());
			assertEquals(Integer.valueOf(30), reused.getMaxWidth());
			assertEquals(Integer.valueOf(10), reused.getMaxHeight());
		} finally {
			IIORegistry.getDefaultInstance().deregisterServiceProvider(probe);
			delete(work);
		}
	}

	private static PhotoSync.Result sync(Path input, Path cache, Path originals, String id, Integer width, long now)
			throws IOException {
		return new PhotoSync().sync(cache.toFile(), originals.toFile(), new LocalPhotoSource(input.toUri()),
				id, width, null, now);
	}

	private static void png(Path path, int width, int height) throws IOException {
		assertTrue(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", path.toFile()));
	}

	private static void delete(Path root) throws IOException {
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
				Files.deleteIfExists(path);
			}
		}
	}

	/** A deterministic decoder that can change its input while PhotoScale is reading it. */
	private static final class ProbeSpi extends ImageReaderSpi {
		final AtomicInteger reads = new AtomicInteger();
		Runnable onRead = () -> { };

		ProbeSpi() {
			names = new String[] { "icpc-local-test" };
			inputTypes = new Class<?>[] { ImageInputStream.class };
		}

		@Override
		public boolean canDecodeInput(Object source) throws IOException {
			ImageInputStream input = (ImageInputStream) source;
			input.mark();
			try {
				byte[] magic = new byte[8];
				return input.read(magic) == 8 && "ICPCtest".equals(new String(magic, java.nio.charset.StandardCharsets.UTF_8));
			} finally {
				input.reset();
			}
		}

		@Override
		public ImageReader createReaderInstance(Object extension) {
			return new ImageReader(this) {
				@Override
				public int getNumImages(boolean search) { return 1; }
				@Override
				public int getWidth(int index) { return 40; }
				@Override
				public int getHeight(int index) { return 20; }
				@Override
				public Iterator<ImageTypeSpecifier> getImageTypes(int index) {
					return List.of(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB)).iterator();
				}
				@Override
				public IIOMetadata getStreamMetadata() { return null; }
				@Override
				public IIOMetadata getImageMetadata(int index) { return null; }
				@Override
				public BufferedImage read(int index, ImageReadParam param) throws IOException {
					reads.incrementAndGet();
					ImageInputStream input = (ImageInputStream) getInput();
					input.seek(8);
					if (input.read() == '!') {
						throw new IIOException("Invalid test image");
					}
					onRead.run();
					return new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
				}
			};
		}

		@Override
		public String getDescription(Locale locale) { return "Local photo test decoder"; }
	}
}
