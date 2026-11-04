package org.icpc.tools.cds.photos;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;

@RunWith(JUnit4.class)
public class PhotoScaleTest {
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
