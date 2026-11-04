package org.icpc.tools.presentation.contest.internal.presentations;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.icpc.tools.contest.Trace;
import org.icpc.tools.contest.model.feed.ContestSource;
import org.icpc.tools.presentation.contest.internal.ImageHelper;
import org.icpc.tools.presentation.contest.internal.ImageScaler;
import org.icpc.tools.presentation.core.Presentation;

public class PhotoPresentation extends Presentation {
	private static final String[] HELP = new String[] { "This presentation will pan across a series of photos.",
			"Place photos on the CDS in {icpc.cds.config}/present/photos." };

	private static final int SECONDS_PER_PHOTO = 12;
	private static final int CACHE_SIZE = 2;

	private String mode = null;

	private BufferedImage image;
	private LoadedPhoto current;
	private final List<LoadedPhoto> images = new ArrayList<>(4);
	private int num;
	private String[] filenames;
	private Presentation.Job job;

	private Point2D.Float p1, p2;
	private float s1, s2;

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
				// One pass is enough to fill the cache. Metadata sidecars and the sync index are
				// not images and must not be shown.
				int attempts = 0;
				while (images.size() < CACHE_SIZE && attempts < filenames.length) {
					num++;
					if (num >= filenames.length)
						num = 0;
					attempts++;
					if (!isImageFile(filenames[num]))
						continue;

					try {
						LoadedPhoto loaded = new LoadedPhoto(
								ImageHelper.loadImage("/presentation/photos/" + filenames[num]));
						loaded.view(width, height);
						images.add(loaded);
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

		if (current != null)
			current.dispose();
		current = images.remove(0);
		image = current.view(width, height);

		setupImage();

		preloadImages();
	}

	@Override
	public void setSize(Dimension d) {
		int oldWidth = width;
		int oldHeight = height;
		super.setSize(d);
		if (current == null || (d.width == oldWidth && d.height == oldHeight))
			return;
		BufferedImage view = current.view(width, height);
		if (view != image) {
			image = view;
			setupImage();
		}
	}

	@Override
	public long getRepeat() {
		return SECONDS_PER_PHOTO * 1000L;
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
		if (current != null) {
			BufferedImage view = current.view(width, height);
			if (view != image) {
				image = view;
				setupImage();
			}
		}
		if (image == null || p1 == null || p2 == null) {
			paintHelp(g, HELP, "");
			return;
		}

		float dt = getRepeatTimeMs() / SECONDS_PER_PHOTO / 1000f;
		if (dt > 1f)
			dt = 1f;

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
		gg.scale(ds, ds);
		gg.translate(-dx, -dy);
		gg.drawImage(image, 0, 0, null);
		gg.dispose();
	}

	@Override
	public void setProperty(String value) {
		mode = value;
	}

	/**
	 * Pixel size of the bitmap used for panning: the screen-covering size times 1.1, matching
	 * {@code s1 = s2 * 1.1}. Returns null when that would not be a downscale.
	 */
	static Dimension scaledSize(int imageWidth, int imageHeight, int screenWidth, int screenHeight) {
		if (imageWidth <= 0 || imageHeight <= 0 || screenWidth <= 0 || screenHeight <= 0)
			return null;
		float cover = Math.max(screenWidth / (float) imageWidth, screenHeight / (float) imageHeight);
		float zoom = cover * 1.1f;
		if (zoom >= 1f)
			return null;
		int targetWidth = Math.round(imageWidth * zoom);
		int targetHeight = Math.round(imageHeight * zoom);
		if (targetWidth >= imageWidth || targetHeight >= imageHeight || targetWidth < 1 || targetHeight < 1)
			return null;
		return new Dimension(targetWidth, targetHeight);
	}

	private static final class LoadedPhoto {
		private final BufferedImage source;
		private BufferedImage view;
		private int forWidth = -1;
		private int forHeight = -1;

		private LoadedPhoto(BufferedImage source) {
			this.source = source;
		}

		private BufferedImage view(int screenWidth, int screenHeight) {
			if (view != null && forWidth == screenWidth && forHeight == screenHeight)
				return view;
			Dimension target = scaledSize(source.getWidth(), source.getHeight(), screenWidth, screenHeight);
			BufferedImage next = target == null ? source : ImageScaler.scaleDown(source, target.width, target.height);
			if (next == null)
				next = source;
			if (this.view != null && this.view != source && this.view != next)
				this.view.flush();
			this.view = next;
			forWidth = screenWidth;
			forHeight = screenHeight;
			return this.view;
		}

		private void dispose() {
			if (view != null && view != source)
				view.flush();
			if (source != null)
				source.flush();
		}
	}

	private static boolean isImageFile(String name) {
		if (name == null || name.isEmpty())
			return false;
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif")
				|| lower.endsWith(".bmp") || lower.endsWith(".webp");
	}
}