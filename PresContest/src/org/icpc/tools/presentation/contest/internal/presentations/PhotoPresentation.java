package org.icpc.tools.presentation.contest.internal.presentations;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.icpc.tools.contest.Trace;
import org.icpc.tools.contest.model.feed.ContestSource;
import org.icpc.tools.presentation.contest.internal.ImageScaler;
import org.icpc.tools.presentation.core.Presentation;

public class PhotoPresentation extends Presentation {
	private static final String[] HELP = new String[] { "This presentation will pan across a series of photos.",
			"Place photos on the CDS in {icpc.cds.config}/present/photos." };

	private static final int SECONDS_PER_PHOTO = 12;
	private static final int CACHE_SIZE = 2;

	/**
	 * Wait until the presentation size stops changing before reading photos again.
	 */
	static final long RESIZE_QUIET_MS = 200;

	private String mode = null;

	private volatile BufferedImage image;
	private volatile LoadedPhoto current;
	private final List<LoadedPhoto> images = new ArrayList<>(4);
	private int num;
	private String[] filenames;
	private Presentation.Job job;
	private int resizeGeneration;
	private boolean resizeScheduled;

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
				while (cachedCount() < CACHE_SIZE && attempts < filenames.length) {
					num++;
					if (num >= filenames.length)
						num = 0;
					attempts++;
					if (!isImageFile(filenames[num]))
						continue;

					String path = "/presentation/photos/" + filenames[num];
					try {
						File file = ContestSource.getInstance().getFile(path);
						if (file == null)
							throw new IOException("No image found");
						LoadedPhoto loaded = LoadedPhoto.load(file, width, height);
						if (!offer(loaded))
							loaded.dispose();
					} catch (Exception e) {
						Trace.trace(Trace.ERROR, "Could not load image " + filenames[num] + ": " + e.getMessage());
					}

					if (image == null)
						switchToNextImage();
				}
			}
		});
	}

	private int cachedCount() {
		synchronized (images) {
			return images.size();
		}
	}

	private boolean offer(LoadedPhoto loaded) {
		synchronized (images) {
			if (images.size() >= CACHE_SIZE)
				return false;
			images.add(loaded);
			return true;
		}
	}

	private void switchToNextImage() {
		LoadedPhoto next;
		synchronized (images) {
			if (images.isEmpty())
				return;
			next = images.remove(0);
		}

		LoadedPhoto previous = current;
		current = next;
		image = next.getView();
		if (previous != null)
			previous.dispose();

		if (image != null)
			setupImage();

		preloadImages();
	}

	@Override
	public void setSize(Dimension d) {
		int oldWidth = width;
		int oldHeight = height;
		super.setSize(d);
		if (d.width == oldWidth && d.height == oldHeight)
			return;
		if (current == null && cachedCount() == 0)
			return;
		scheduleResize();
	}

	/**
	 * Coalesce a burst of resizes into one reload, on a worker thread. The current view stays on
	 * screen until the new bitmap is ready.
	 */
	private void scheduleResize() {
		synchronized (this) {
			resizeGeneration++;
			if (resizeScheduled)
				return;
			resizeScheduled = true;
		}
		execute(new Runnable() {
			@Override
			public void run() {
				try {
					int applied = -1;
					while (true) {
						int seen;
						synchronized (PhotoPresentation.this) {
							seen = resizeGeneration;
						}
						Thread.sleep(RESIZE_QUIET_MS);
						int w;
						int h;
						synchronized (PhotoPresentation.this) {
							if (resizeGeneration != seen)
								continue;
							w = width;
							h = height;
							resizeScheduled = false;
						}
						rescaleLoaded(w, h);
						applied = seen;
						synchronized (PhotoPresentation.this) {
							if (resizeGeneration != applied) {
								resizeScheduled = true;
								continue;
							}
						}
						break;
					}
				} catch (InterruptedException e) {
					synchronized (PhotoPresentation.this) {
						resizeScheduled = false;
					}
					Thread.currentThread().interrupt();
				}
			}
		});
	}

	private void rescaleLoaded(int screenWidth, int screenHeight) {
		LoadedPhoto shown = current;
		LoadedPhoto[] pending;
		synchronized (images) {
			pending = images.toArray(new LoadedPhoto[images.size()]);
		}
		if (shown != null)
			shown.reload(screenWidth, screenHeight);
		for (int i = 0; i < pending.length; i++)
			pending[i].reload(screenWidth, screenHeight);
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
		LoadedPhoto shown = current;
		if (shown != null) {
			BufferedImage view = shown.getView();
			if (view != null && view != image) {
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

	/**
	 * The bitmap on screen, plus the local file it was read from. The full-resolution image is not
	 * retained.
	 */
	static final class LoadedPhoto {
		private final File file;
		private BufferedImage view;
		private int sourceWidth;
		private int sourceHeight;
		private int forWidth = -1;
		private int forHeight = -1;
		private boolean disposed;

		private LoadedPhoto(File file) {
			this.file = file;
		}

		static LoadedPhoto load(File file, int screenWidth, int screenHeight) throws IOException {
			LoadedPhoto photo = new LoadedPhoto(file);
			if (screenWidth <= 0 || screenHeight <= 0)
				return photo;
			if (!photo.reload(screenWidth, screenHeight))
				throw new IOException("Could not read " + file.getName());
			return photo;
		}

		/**
		 * @return false when a first read fails and there is no view to keep
		 */
		boolean reload(int screenWidth, int screenHeight) {
			if (screenWidth <= 0 || screenHeight <= 0)
				return view != null;
			synchronized (this) {
				if (disposed)
					return false;
				if (!needsReload(screenWidth, screenHeight))
					return true;
			}

			BufferedImage source = null;
			try {
				source = ImageIO.read(file);
				if (source == null)
					throw new IOException("Unknown image type");
				int sw = source.getWidth();
				int sh = source.getHeight();
				Dimension target = scaledSize(sw, sh, screenWidth, screenHeight);
				BufferedImage next = source;
				if (target != null) {
					BufferedImage scaled = ImageScaler.scaleDown(source, target.width, target.height);
					if (scaled != null && scaled != source)
						next = scaled;
				}
				if (next != source)
					source.flush();
				source = null;

				BufferedImage previous;
				synchronized (this) {
					if (disposed) {
						next.flush();
						return false;
					}
					previous = view;
					view = next;
					sourceWidth = sw;
					sourceHeight = sh;
					forWidth = screenWidth;
					forHeight = screenHeight;
				}
				if (previous != null && previous != next)
					previous.flush();
				return true;
			} catch (Exception e) {
				boolean kept;
				synchronized (this) {
					kept = view != null;
				}
				if (kept)
					Trace.trace(Trace.WARNING, "Could not rescale photo " + file.getName() + ": " + e.getMessage());
				return kept;
			} finally {
				if (source != null)
					source.flush();
			}
		}

		private boolean needsReload(int screenWidth, int screenHeight) {
			if (view != null && forWidth == screenWidth && forHeight == screenHeight)
				return false;
			if (view == null || sourceWidth <= 0 || sourceHeight <= 0)
				return true;
			Dimension target = scaledSize(sourceWidth, sourceHeight, screenWidth, screenHeight);
			if (target == null)
				return view.getWidth() != sourceWidth || view.getHeight() != sourceHeight;
			return view.getWidth() != target.width || view.getHeight() != target.height;
		}

		BufferedImage getView() {
			synchronized (this) {
				return view;
			}
		}

		private void dispose() {
			BufferedImage old;
			synchronized (this) {
				disposed = true;
				old = view;
				view = null;
			}
			if (old != null)
				old.flush();
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
