package org.icpc.tools.presentation.contest.internal.presentations;

import java.awt.AlphaComposite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.icpc.tools.contest.Trace;
import org.icpc.tools.contest.model.feed.ContestSource;
import org.icpc.tools.presentation.contest.internal.ImageHelper;
import org.icpc.tools.presentation.core.Presentation;

public class PhotoPresentation extends Presentation {
	private static final String[] HELP = new String[] { "This presentation will pan across a series of photos.",
			"Place photos on the CDS in {icpc.cds.config}/present/photos." };

	private static final int SECONDS_PER_PHOTO = 12;
	private static final int FADE_MS = 1000;
	private static final int PHOTO_MS = SECONDS_PER_PHOTO * 1000;
	private static final int MIN_DURATION_MS = 10;
	private static final int CACHE_SIZE = 2;

	private String mode = null;
	private int durationMs = PHOTO_MS;
	private int requestedFadeMs = FADE_MS;
	private int fadeMs = FADE_MS;

	private BufferedImage image;
	private final List<BufferedImage> images = new ArrayList<>(4);
	private int num;
	private String[] filenames;
	private Presentation.Job job;

	private Point2D.Float p1, p2;
	private float s1, s2;
	private PhotoPan outgoing;
	private boolean painted;

	@Override
	public void init() {
		execute(new Runnable() {
			@Override
			public void run() {
				try {
					filenames = ContestSource.getInstance().getDirectory("/presentation/photos");
				} catch (Exception e) {
					Trace.trace(Trace.ERROR, "Could not load present/photo contents: " + e.getMessage());
				}
				preloadImages();
			}
		});
	}

	@Override
	public void aboutToShow() {
		switchToNextImage();
	}

	private void preloadImages() {
		if (images.size() >= CACHE_SIZE)
			return;

		if (filenames == null || filenames.length == 0)
			return;

		if (job != null && !job.isComplete())
			return;

		job = execute(new Runnable() {
			@Override
			public void run() {
				while (images.size() < CACHE_SIZE) {
					num++;
					if (num >= filenames.length)
						num = 0;

					try {
						images.add(ImageHelper.loadImage("/presentation/photos/" + filenames[num]));
					} catch (Exception e) {
						Trace.trace(Trace.ERROR, "Could not load image " + filenames[num] + ": " + e.getMessage());
					}

					if (image == null)
						switchToNextImage();
				}
			}
		});
	}

	private void switchToNextImage() {
		if (images.isEmpty())
			return;

		BufferedImage old = image;
		image = images.remove(0);
		if (old != null && !retainOutgoing(old))
			old.flush();

		setupImage();

		preloadImages();
	}

	@Override
	public long getRepeat() {
		return durationMs;
	}

	protected void setupImage() {
		if (image == null)
			return;
		Dimension sd = getSize();
		Dimension id = new Dimension(image.getWidth(), image.getHeight());

		// determine the widest possible scaling for the final view
		s2 = Math.max((float) sd.width / id.width, (float) sd.height / id.height);

		// zoom in by 10% to start, unless it is greater than the pixel resolution
		s1 = s2 * 1.1f;

		// pick any corner to start
		p1 = new Point2D.Float();
		double r = Math.random();
		if (r >= 0.5)
			p1.x = id.width - (int) (sd.width / s1);
		if ((r >= 0.25 && r < 0.5) || r >= 0.75)
			p1.y = id.height - (int) (sd.height / s1);

		// pick any corner to finish
		p2 = new Point2D.Float();
		r = Math.random();
		if (r >= 0.5)
			p2.x = id.width - (int) (sd.width / s2);
		if ((r >= 0.25 && r < 0.5) || r >= 0.75)
			p2.y = id.height - (int) (sd.height / s2);
	}

	@Override
	public void paint(Graphics2D g) {
		if (image == null || p1 == null || p2 == null) {
			paintHelp(g, HELP, "");
			return;
		}

		long visible = getRepeatTimeMs();
		int duration = durationMs;
		int fade = fadeMs;
		PhotoPan.draw(g, image, p1, p2, s1, s2, PhotoPan.motionDt(visible, duration, fade), mode, 1f);
		painted = true;

		if (outgoing == null)
			return;
		if (fade <= 0 || visible >= fade) {
			outgoing.release();
			outgoing = null;
			return;
		}
		outgoing.draw(g, PhotoPan.motionDt(duration + visible, duration, fade), mode, PhotoPan.fadeAlpha(visible, fade));
	}

	private boolean retainOutgoing(BufferedImage old) {
		if (outgoing != null) {
			outgoing.release();
			outgoing = null;
		}
		if (fadeMs <= 0 || !painted || p1 == null || p2 == null)
			return false;
		outgoing = new PhotoPan(old, p1, p2, s1, s2);
		return true;
	}

	/**
	 * One photo's Ken Burns state. {@code dt} runs from 0 at the start of the fade-in to 1 at the
	 * end of the fade-out. That span is the photo {@code duration} plus the effective fade.
	 */
	static final class PhotoPan {
		final BufferedImage image;
		final Point2D.Float p1;
		final Point2D.Float p2;
		final float s1;
		final float s2;

		PhotoPan(BufferedImage image, Point2D.Float p1, Point2D.Float p2, float s1, float s2) {
			this.image = image;
			this.p1 = p1;
			this.p2 = p2;
			this.s1 = s1;
			this.s2 = s2;
		}

		void release() {
			image.flush();
		}

		void draw(Graphics2D g, float dt, String mode) {
			draw(g, dt, mode, 1f);
		}

		void draw(Graphics2D g, float dt, String mode, float alpha) {
			draw(g, image, p1, p2, s1, s2, dt, mode, alpha);
		}

		static long motionSpanMs() {
			return motionSpanMs(PHOTO_MS, FADE_MS);
		}

		static long motionSpanMs(int fadeMs) {
			return motionSpanMs(PHOTO_MS, fadeMs);
		}

		static long motionSpanMs(int durationMs, int fadeMs) {
			int fade = fadeMs < 0 ? 0 : fadeMs;
			return (long) durationMs + fade;
		}

		static float motionDt(long visibleMs) {
			return motionDt(visibleMs, PHOTO_MS, FADE_MS);
		}

		static float motionDt(long visibleMs, int fadeMs) {
			return motionDt(visibleMs, PHOTO_MS, fadeMs);
		}

		static float motionDt(long visibleMs, int durationMs, int fadeMs) {
			long span = motionSpanMs(durationMs, fadeMs);
			if (span <= 0)
				return 1f;
			if (visibleMs <= 0)
				return 0f;
			if (visibleMs >= span)
				return 1f;
			return visibleMs / (float) span;
		}

		static float fadeAlpha(long elapsedMs) {
			return fadeAlpha(elapsedMs, FADE_MS);
		}

		static float fadeAlpha(long elapsedMs, int fadeMs) {
			int fade = clampFadeMs(fadeMs);
			if (fade <= 0)
				return 0f;
			if (elapsedMs <= 0)
				return 1f;
			if (elapsedMs >= fade)
				return 0f;
			return 1f - elapsedMs / (float) fade;
		}

		static void draw(Graphics2D g, BufferedImage image, Point2D.Float p1, Point2D.Float p2, float s1, float s2,
				float dt, String mode, float alpha) {
			if (image == null || p1 == null || p2 == null || alpha <= 0f)
				return;

			double dx = p1.x * (1f - dt) + p2.x * dt;
			double dy = p1.y * (1f - dt) + p2.y * dt;
			double ds = s1 * (1f - dt) + s2 * dt;

			Graphics2D gg = (Graphics2D) g.create();
			gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			if ("h".equals(mode))
				gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			else if ("l".equals(mode))
				gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			else
				gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			gg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			if (alpha < 1f)
				gg.setComposite(AlphaComposite.SrcOver.derive(alpha));
			gg.scale(ds, ds);
			gg.translate(-dx, -dy);
			gg.drawImage(image, 0, 0, null);
			gg.dispose();
		}
	}

	int durationMs() {
		return durationMs;
	}

	int fadeMs() {
		return fadeMs;
	}

	/**
	 * Negative fades become a hard cut. Unparseable text leaves {@code current} unchanged. The
	 * half-duration cap is applied separately so a later duration change can restore the request.
	 */
	static int parseFadeMs(String raw, int current) {
		if (raw == null)
			return current;
		String text = raw.trim();
		if (text.isEmpty())
			return current;
		try {
			return clampFadeMs(Integer.parseInt(text));
		} catch (NumberFormatException e) {
			return current;
		}
	}

	static int clampFadeMs(int requested) {
		if (requested < 0)
			return 0;
		return requested;
	}

	static int parseDurationMs(String raw, int current) {
		if (raw == null)
			return current;
		String text = raw.trim();
		if (text.isEmpty())
			return current;
		try {
			return clampDurationMs(Integer.parseInt(text));
		} catch (NumberFormatException e) {
			return current;
		}
	}

	static int clampDurationMs(int requested) {
		if (requested < MIN_DURATION_MS)
			return MIN_DURATION_MS;
		return requested;
	}

	/**
	 * The fade-in at the start of a photo and the fade-out at the end must both fit in
	 * {@code duration} without overlapping, so the fade cannot exceed half the duration.
	 */
	private void applyFadeLimit() {
		int cap = durationMs / 2;
		if (requestedFadeMs > cap) {
			Trace.trace(Trace.WARNING, "Photo fade of " + requestedFadeMs + " ms clipped to " + cap
					+ " ms (half of the " + durationMs + " ms duration)");
			fadeMs = cap;
		} else
			fadeMs = requestedFadeMs;
	}

	@Override
	public void setProperty(String value) {
		if (value != null && value.startsWith("fade:")) {
			requestedFadeMs = parseFadeMs(value.substring(5), requestedFadeMs);
			applyFadeLimit();
			return;
		}
		if (value != null && value.startsWith("duration:")) {
			durationMs = parseDurationMs(value.substring("duration:".length()), durationMs);
			applyFadeLimit();
			return;
		}
		mode = value;
	}
}