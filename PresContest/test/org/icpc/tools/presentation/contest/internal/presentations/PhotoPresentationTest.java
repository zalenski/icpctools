package org.icpc.tools.presentation.contest.internal.presentations;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

import javax.imageio.ImageIO;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class PhotoPresentationTest {
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
