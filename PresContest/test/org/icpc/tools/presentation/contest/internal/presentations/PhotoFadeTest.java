package org.icpc.tools.presentation.contest.internal.presentations;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class PhotoFadeTest {
	@Test
	public void fadeAlphaFallsFromOpaqueToClear() {
		assertEquals(1f, PhotoPresentation.PhotoPan.fadeAlpha(0), 0f);
		assertEquals(1f, PhotoPresentation.PhotoPan.fadeAlpha(-20), 0f);
		assertEquals(0.5f, PhotoPresentation.PhotoPan.fadeAlpha(500), 0.0001f);
		assertEquals(0f, PhotoPresentation.PhotoPan.fadeAlpha(1000), 0f);
		assertEquals(0f, PhotoPresentation.PhotoPan.fadeAlpha(2500), 0f);
	}

	@Test
	public void kenBurnsCoversThePhotoAndTheFollowingFade() {
		long span = PhotoPresentation.PhotoPan.motionSpanMs();
		assertEquals(12_000L + 1000L, span);

		assertEquals(0f, PhotoPresentation.PhotoPan.motionDt(0), 0f);
		assertEquals(1000f / span, PhotoPresentation.PhotoPan.motionDt(1000), 0.0001f);
		// still short of the end when the next photo is cut in
		float atCut = PhotoPresentation.PhotoPan.motionDt(12_000L);
		assertEquals(12_000f / span, atCut, 0.0001f);
		assertTrue(atCut < 1f);
		// the outgoing photo finishes the same path as the fade ends
		assertEquals(atCut, PhotoPresentation.PhotoPan.motionDt(12_000L), 0f);
		assertEquals(1f, PhotoPresentation.PhotoPan.motionDt(span), 0f);
		assertEquals(1f, PhotoPresentation.PhotoPan.motionDt(span + 50), 0f);
	}

	@Test
	public void customFadeFollowsTheProperty() {
		PhotoPresentation photos = new PhotoPresentation();
		photos.setProperty("fade:2500");
		assertEquals(2500, photos.fadeMs());

		long span = PhotoPresentation.PhotoPan.motionSpanMs(photos.fadeMs());
		assertEquals(12_000L + 2500L, span);
		assertEquals(1f, PhotoPresentation.PhotoPan.fadeAlpha(0, 2500), 0f);
		assertEquals(0.5f, PhotoPresentation.PhotoPan.fadeAlpha(1250, 2500), 0.0001f);
		assertEquals(0f, PhotoPresentation.PhotoPan.fadeAlpha(2500, 2500), 0f);
		assertEquals(12_000f / span, PhotoPresentation.PhotoPan.motionDt(12_000L, 2500), 0.0001f);
		assertEquals(1f, PhotoPresentation.PhotoPan.motionDt(span, 2500), 0f);

		photos.setProperty("fade:nope");
		assertEquals(2500, photos.fadeMs());
		photos.setProperty("fade:-30");
		assertEquals(0, photos.fadeMs());
		photos.setProperty("fade:50000");
		assertEquals(12_000, photos.fadeMs());
		assertEquals(12_000L + 12_000L, PhotoPresentation.PhotoPan.motionSpanMs(photos.fadeMs()));
	}

	@Test
	public void zeroFadeIsAHardCut() {
		PhotoPresentation photos = new PhotoPresentation();
		photos.setProperty("fade:0");
		assertEquals(0, photos.fadeMs());
		assertEquals(12_000L, PhotoPresentation.PhotoPan.motionSpanMs(0));
		assertEquals(0f, PhotoPresentation.PhotoPan.motionDt(0, 0), 0f);
		assertEquals(1f, PhotoPresentation.PhotoPan.motionDt(12_000L, 0), 0.0001f);
		assertEquals(0f, PhotoPresentation.PhotoPan.fadeAlpha(0, 0), 0f);
		assertEquals(0f, PhotoPresentation.PhotoPan.fadeAlpha(1, 0), 0f);
	}

	@Test
	public void outgoingDrawFadesTowardTheImageUnderneath() {
		BufferedImage photo = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
		Graphics2D pg = photo.createGraphics();
		pg.setColor(Color.WHITE);
		pg.fillRect(0, 0, 4, 4);
		pg.dispose();

		Point2D.Float origin = new Point2D.Float();
		PhotoPresentation.PhotoPan pan = new PhotoPresentation.PhotoPan(photo, origin, origin, 1f, 1f);

		BufferedImage dest = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = dest.createGraphics();
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, 2, 2);
		pan.draw(g, 0f, null, 0f);
		assertEquals(Color.BLACK.getRGB(), dest.getRGB(0, 0));

		pan.draw(g, 0f, null, 0.5f);
		int faded = dest.getRGB(0, 0) & 0xFF;
		assertTrue(faded > 64 && faded < 192);
		g.dispose();
	}
}
