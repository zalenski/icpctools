package org.icpc.tools.presentation.contest.internal.presentations;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.imageio.ImageIO;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class PhotoSizingTest {
	@Test
	public void keepsScaledViewAndReloadsFromTheFile() throws Exception {
		File file = Files.createTempFile("photo-view", ".png").toFile();
		try {
			writePng(file, 400, 300);
			PhotoPresentation.LoadedPhoto photo = PhotoPresentation.LoadedPhoto.load(file, 100, 100);
			Dimension target = PhotoPresentation.scaledSize(400, 300, 100, 100);
			assertNotNull(target);
			assertEquals(target.width, photo.getView().getWidth());
			assertEquals(target.height, photo.getView().getHeight());
			assertTrue(photo.getView().getWidth() < 400);

			Dimension larger = PhotoPresentation.scaledSize(400, 300, 200, 150);
			assertNotNull(larger);
			assertTrue(photo.reload(200, 150));
			assertEquals(larger.width, photo.getView().getWidth());
			assertEquals(larger.height, photo.getView().getHeight());

			BufferedImage kept = photo.getView();
			byte[] garbage = "not an image".getBytes("UTF-8");
			FileOutputStream out = new FileOutputStream(file);
			try {
				out.write(garbage);
			} finally {
				out.close();
			}
			assertTrue(photo.reload(80, 60));
			assertTrue(kept == photo.getView());
		} finally {
			file.delete();
		}
	}

	@Test
	public void samePixelSizeReusesTheViewAndOnlyTheLatestSizeIsKept() throws Exception {
		File file = Files.createTempFile("photo-size-reuse", ".png").toFile();
		try {
			writePng(file, 400, 300);
			PhotoPresentation.LoadedPhoto photo = PhotoPresentation.LoadedPhoto.load(file, 100, 100);
			BufferedImage first = photo.getView();
			// These viewports need the same scaled pixel dimensions.
			assertEquals(PhotoPresentation.scaledSize(400, 300, 100, 100),
					PhotoPresentation.scaledSize(400, 300, 101, 100));
			assertTrue(photo.reload(101, 100));
			assertSame(first, photo.getView());
			assertTrue(photo.reload(200, 150));
			BufferedImage second = photo.getView();
			assertNotSame(first, second);
			assertTrue(photo.reload(100, 100));
			assertNotSame(first, photo.getView());
			assertNotSame(second, photo.getView());
			assertEquals(first.getWidth(), photo.getView().getWidth());
			assertEquals(first.getHeight(), photo.getView().getHeight());
		} finally {
			file.delete();
		}
	}

	@Test
	public void failedResizeKeepsTheOldViewAndCanRetry() throws Exception {
		File file = Files.createTempFile("photo-resize-retry", ".png").toFile();
		try {
			writePng(file, 400, 300);
			PhotoPresentation.LoadedPhoto photo = PhotoPresentation.LoadedPhoto.load(file, 100, 100);
			BufferedImage first = photo.getView();
			Files.write(file.toPath(), new byte[] { 1, 2, 3 });
			assertTrue(photo.reload(200, 150));
			assertSame(first, photo.getView());
			writePng(file, 400, 300);
			assertTrue(photo.reload(200, 150));
			assertNotSame(first, photo.getView());
			Dimension expected = PhotoPresentation.scaledSize(400, 300, 200, 150);
			assertEquals(expected.width, photo.getView().getWidth());
			assertEquals(expected.height, photo.getView().getHeight());
		} finally {
			file.delete();
		}
	}

	@Test
	public void doesNotReadUntilTheScreenSizeIsKnown() throws Exception {
		File file = Files.createTempFile("photo-defer", ".png").toFile();
		try {
			writePng(file, 40, 30);
			PhotoPresentation.LoadedPhoto photo = PhotoPresentation.LoadedPhoto.load(file, 0, 0);
			assertNull(photo.getView());
			assertTrue(photo.reload(20, 20));
			assertNotNull(photo.getView());
		} finally {
			file.delete();
		}
	}

	@Test
	public void resizeKeepsDisplayedBitmapsAndRefreshesOnlyThePreloadQueue() throws Exception {
		File first = Files.createTempFile("photo-first", ".png").toFile();
		File second = Files.createTempFile("photo-second", ".png").toFile();
		PhotoPresentation presentation = new PhotoPresentation();
		try {
			writePng(first, 400, 300);
			writePng(second, 400, 300);
			FileLoader loader = new FileLoader();
			loader.files.put("/presentation/photos/a.png", first);
			loader.files.put("/presentation/photos/b.png", second);
			loader.files.put("/presentation/photos-cup/new.png", second);
			presentation.setPhotoLoader(loader);
			presentation.setSize(new Dimension(100, 100));
			presentation.setProperty("fade:1000");
			presentation.init();
			waitForCache(presentation);
			BufferedImage frame = new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = frame.createGraphics();
			try {
				presentation.paint(g);
				presentation.setRepeatTimeMs(11_500);
				presentation.paint(g);
				assertNotNull(pan(presentation, "outgoing"));
				waitForCache(presentation);
				BufferedImage current = pan(presentation, "current").image;
				BufferedImage outgoing = pan(presentation, "outgoing").image;
				presentation.setSize(new Dimension(200, 150));
				waitForCache(presentation);
				Dimension expected = PhotoPresentation.scaledSize(400, 300, 200, 150);
				assertSame(current, pan(presentation, "current").image);
				assertSame(outgoing, pan(presentation, "outgoing").image);
				presentation.setRepeatTimeMs(0);
				presentation.aboutToShow();
				presentation.setRepeatTimeMs(500);
				presentation.paint(g);
				presentation.setRepeatTimeMs(11_500);
				presentation.paint(g);
				assertEquals(expected.width, pan(presentation, "current").image.getWidth());
				BufferedImage old = pan(presentation, "current").image;
				presentation.setProperty("catalog:cup");
				waitForCache(presentation);
				assertFalse(presentation.isShowing(old));
				assertNull(pan(presentation, "outgoing"));
				assertEquals(expected.width, pan(presentation, "current").image.getWidth());
				assertEquals(1000, presentation.fadeMs());
				presentation.setRepeatTimeMs(0);
				presentation.paint(g);
				assertEquals(Color.BLUE.getRGB(), frame.getRGB(100, 75));
			} finally {
				g.dispose();
			}
		} finally {
			presentation.dispose();
			first.delete();
			second.delete();
		}
	}

	@Test
	public void oldSizeLoadCanStartShowingWhileLaterLoadsUseTheNewSize() throws Exception {
		File file = Files.createTempFile("photo-resize-race", ".png").toFile();
		CountDownLatch read = new CountDownLatch(1);
		CountDownLatch publish = new CountDownLatch(1);
		PhotoPresentation presentation = new PhotoPresentation();
		try {
			writePng(file, 400, 300);
			FileLoader loader = new FileLoader() {
				@Override
				public PhotoPresentation.LoadedPhoto load(String path, int width, int height) throws Exception {
					PhotoPresentation.LoadedPhoto photo = super.load(path, width, height);
					read.countDown();
					if (!publish.await(3, TimeUnit.SECONDS)) {
						throw new IllegalStateException("load was not released");
					}
					return photo;
				}
			};
			loader.files.put("/presentation/photos/a.png", file);
			presentation.setPhotoLoader(loader);
			presentation.setSize(new Dimension(100, 100));
			presentation.init();
			assertTrue(read.await(2, TimeUnit.SECONDS));
			presentation.setSize(new Dimension(200, 150));
			publish.countDown();
			waitForCache(presentation);
			Dimension previous = PhotoPresentation.scaledSize(400, 300, 100, 100);
			assertEquals(previous.width, pan(presentation, "current").image.getWidth());
			presentation.aboutToShow();
			waitForCache(presentation);
			Dimension expected = PhotoPresentation.scaledSize(400, 300, 200, 150);
			assertEquals(expected.width, pan(presentation, "current").image.getWidth());
			assertEquals(expected.height, pan(presentation, "current").image.getHeight());
		} finally {
			publish.countDown();
			presentation.dispose();
			file.delete();
		}
	}

	@Test
	public void preloadResizeDoesNotBlockPaintingAndCatchesUpWithTheLatestSize() throws Exception {
		File file = Files.createTempFile("photo-async-resize", ".png").toFile();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService caller = Executors.newSingleThreadExecutor();
		PhotoPresentation presentation = new PhotoPresentation();
		try {
			writePng(file, 800, 600);
			Thread testThread = Thread.currentThread();
			FileLoader loader = new FileLoader() {
				@Override
				public PhotoPresentation.LoadedPhoto resize(PhotoPresentation.LoadedPhoto photo, int width,
						int height) throws IOException {
					assertNotSame(testThread, Thread.currentThread());
					if (width == 200) {
						entered.countDown();
						try {
							if (!release.await(3, TimeUnit.SECONDS)) {
								throw new IOException("resize was not released");
							}
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							throw new IOException(e);
						}
					}
					return super.resize(photo, width, height);
				}
			};
			loader.files.put("/presentation/photos/a.png", file);
			presentation.setPhotoLoader(loader);
			presentation.setSize(new Dimension(100, 100));
			presentation.init();
			waitForCache(presentation);
			BufferedImage original = pan(presentation, "current").image;
			Future<?> resize = caller.submit(() -> presentation.setSize(new Dimension(200, 150)));
			resize.get(1, TimeUnit.SECONDS);
			assertTrue(entered.await(2, TimeUnit.SECONDS));
			assertSame(original, pan(presentation, "current").image);
			Future<?> paint = caller.submit(() -> {
				BufferedImage frame = new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB);
				Graphics2D g = frame.createGraphics();
				try {
					presentation.paint(g);
					assertEquals(Color.BLUE.getRGB(), frame.getRGB(100, 75));
				} finally {
					g.dispose();
				}
			});
			paint.get(1, TimeUnit.SECONDS);
			// A queued photo can become current while its replacement is still being read.
			caller.submit(() -> presentation.aboutToShow()).get(1, TimeUnit.SECONDS);
			BufferedImage showing = pan(presentation, "current").image;
			caller.submit(() -> presentation.setSize(new Dimension(300, 200))).get(1, TimeUnit.SECONDS);
			release.countDown();
			waitForCache(presentation);
			assertSame(showing, pan(presentation, "current").image);
			presentation.aboutToShow();
			waitForCache(presentation);
			Dimension expected = PhotoPresentation.scaledSize(800, 600, 300, 200);
			assertEquals(expected.width, pan(presentation, "current").image.getWidth());
			assertEquals(expected.height, pan(presentation, "current").image.getHeight());
		} finally {
			release.countDown();
			presentation.dispose();
			caller.shutdownNow();
			file.delete();
		}
	}

	@Test
	public void catalogSwitchAndDisposeDoNotWaitForAnObsoleteResize() throws Exception {
		for (boolean dispose : new boolean[] { false, true }) {
			File file = Files.createTempFile("photo-obsolete-resize", ".png").toFile();
			CountDownLatch entered = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			ExecutorService caller = Executors.newSingleThreadExecutor();
			PhotoPresentation presentation = new PhotoPresentation();
			try {
				writePng(file, 800, 600);
				FileLoader loader = new FileLoader() {
					@Override
					public PhotoPresentation.LoadedPhoto resize(PhotoPresentation.LoadedPhoto photo, int width,
							int height) throws IOException {
						entered.countDown();
						try {
							if (!release.await(3, TimeUnit.SECONDS)) {
								throw new IOException("resize was not released");
							}
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							throw new IOException(e);
						}
						return super.resize(photo, width, height);
					}
				};
				loader.files.put("/presentation/photos/a.png", file);
				loader.files.put("/presentation/photos-cup/b.png", file);
				presentation.setPhotoLoader(loader);
				presentation.setSize(new Dimension(100, 100));
				presentation.init();
				waitForCache(presentation);
				BufferedImage original = pan(presentation, "current").image;
				presentation.setSize(new Dimension(200, 150));
				assertTrue(entered.await(2, TimeUnit.SECONDS));
				caller.submit(() -> {
					if (dispose) {
						presentation.dispose();
					} else {
						presentation.setProperty("catalog:cup");
					}
				}).get(1, TimeUnit.SECONDS);
				release.countDown();
				if (dispose) {
					long until = System.currentTimeMillis() + 3000;
					while (presentation.isLoading() && System.currentTimeMillis() < until) {
						Thread.sleep(10);
					}
					assertFalse(presentation.isLoading());
					assertEquals(0, presentation.getCachedImageCount());
				} else {
					waitForCache(presentation);
					assertEquals("/presentation/photos-cup", presentation.getPhotoDirectory());
					assertFalse(presentation.isShowing(original));
					Dimension expected = PhotoPresentation.scaledSize(800, 600, 200, 150);
					assertEquals(expected.width, pan(presentation, "current").image.getWidth());
				}
			} finally {
				release.countDown();
				presentation.dispose();
				caller.shutdownNow();
				file.delete();
			}
		}
	}

	private static void waitForCache(PhotoPresentation presentation) throws Exception {
		long until = System.currentTimeMillis() + 3000;
		while (System.currentTimeMillis() < until) {
			if (!presentation.isLoading() && presentation.getCachedImageCount() > 0) {
				return;
			}
			Thread.sleep(10);
		}
		throw new AssertionError("photo cache did not fill");
	}

	private static PhotoPresentation.PhotoPan pan(PhotoPresentation presentation, String name) throws Exception {
		Field field = PhotoPresentation.class.getDeclaredField(name);
		field.setAccessible(true);
		return (PhotoPresentation.PhotoPan) field.get(presentation);
	}

	private static class FileLoader implements PhotoPresentation.PhotoLoader {
		final Map<String, File> files = new LinkedHashMap<>();

		@Override
		public String[] list(String directory) {
			List<String> names = new ArrayList<>();
			for (String path : files.keySet()) {
				if (path.startsWith(directory + "/")) {
					names.add(path.substring(directory.length() + 1));
				}
			}
			return names.toArray(new String[0]);
		}

		@Override
		public BufferedImage open(String path) {
			throw new AssertionError("file loader must use its local file");
		}

		@Override
		public PhotoPresentation.LoadedPhoto load(String path, int width, int height) throws Exception {
			return PhotoPresentation.LoadedPhoto.load(files.get(path), width, height);
		}
	}

	private static void writePng(File file, int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(Color.BLUE);
			g.fillRect(0, 0, width, height);
		} finally {
			g.dispose();
		}
		assertTrue(ImageIO.write(image, "png", file));
	}
}
