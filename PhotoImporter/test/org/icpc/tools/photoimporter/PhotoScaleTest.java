package org.icpc.tools.photoimporter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;

@RunWith(JUnit4.class)
public class PhotoScaleTest {
	@Test
	public void rejectsUndecodableInputEvenWithoutResizeAndPreservesDestination() throws Exception {
		java.nio.file.Path work = java.nio.file.Files.createTempDirectory("invalid-photo-scale");
		java.nio.file.Path input = work.resolve("input.png");
		java.nio.file.Path output = work.resolve("output.png");
		try {
			java.nio.file.Files.writeString(input, "not an image");
			java.nio.file.Files.writeString(output, "previous image");
			try {
				PhotoScale.writeDisplay(input.toFile(), output.toFile(), null, null);
				org.junit.Assert.fail("Must not copy undecodable input");
			} catch (PhotoScale.InvalidImageException expected) {
				assertEquals("previous image", java.nio.file.Files.readString(output));
			}
		} finally {
			java.nio.file.Files.deleteIfExists(input);
			java.nio.file.Files.deleteIfExists(output);
			java.nio.file.Files.deleteIfExists(work);
		}
	}

	@Test
	public void fitsInsideBothLimits() {
		PhotoScale.Size size = PhotoScale.fit(4000, 3000, Integer.valueOf(1920), Integer.valueOf(1080));
		assertEquals(1440, size.width);
		assertEquals(1080, size.height);
	}

	@Test
	public void fitsInsideOnlyTheWidthLimit() {
		PhotoScale.Size size = PhotoScale.fit(4000, 3000, Integer.valueOf(1920), null);
		assertEquals(1920, size.width);
		assertEquals(1440, size.height);
	}

	@Test
	public void fitsInsideOnlyTheHeightLimit() {
		PhotoScale.Size size = PhotoScale.fit(4000, 3000, null, Integer.valueOf(1080));
		assertEquals(1440, size.width);
		assertEquals(1080, size.height);
	}

	@Test
	public void leavesThePhotoUnscaledWhenNoLimitIsSet() {
		PhotoScale.Size size = PhotoScale.fit(4000, 3000, null, null);
		assertEquals(4000, size.width);
		assertEquals(3000, size.height);
	}

	@Test
	public void doesNotUpscale() {
		PhotoScale.Size size = PhotoScale.fit(800, 600, Integer.valueOf(1920), Integer.valueOf(1080));
		assertEquals(800, size.width);
		assertEquals(600, size.height);

		PhotoScale.Size widthOnly = PhotoScale.fit(800, 600, Integer.valueOf(1920), null);
		assertEquals(800, widthOnly.width);
		assertEquals(600, widthOnly.height);
	}
}
