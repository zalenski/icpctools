package org.icpc.tools.cds.photos;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Locale;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Fits a photo inside optional pixel limits and writes the display copy. The scale is the same in
 * both axes, and a limit is never used to enlarge the photo.
 */
public final class PhotoScale {
	static final float JPEG_QUALITY = 0.9f;

	public static final class Size {
		public final int width;
		public final int height;

		public Size(int width, int height) {
			this.width = width;
			this.height = height;
		}
	}

	public static final class Written {
		public final int width;
		public final int height;

		public Written(int width, int height) {
			this.width = width;
			this.height = height;
		}
	}

	private PhotoScale() {
		// utility
	}

	/**
	 * Largest size that keeps the aspect ratio, stays within every limit that is set, and is no
	 * larger than the source. A null or non-positive limit is ignored. With no limits the source
	 * size is returned.
	 */
	public static Size fit(int width, int height, Integer maxWidth, Integer maxHeight) {
		if (width <= 0 || height <= 0) {
			return new Size(Math.max(width, 0), Math.max(height, 0));
		}

		int nw = width;
		int nh = height;
		if (isLimit(maxWidth) && maxWidth.intValue() < nw) {
			nw = maxWidth.intValue();
			nh = (int) ((long) height * maxWidth.intValue() / width);
			if (nh < 1) {
				nh = 1;
			}
		}
		if (isLimit(maxHeight) && maxHeight.intValue() < nh) {
			nh = maxHeight.intValue();
			nw = (int) ((long) width * maxHeight.intValue() / height);
			if (nw < 1) {
				nw = 1;
			}
		}
		if (nw > width) {
			nw = width;
		}
		if (nh > height) {
			nh = height;
		}
		if (nw < 1) {
			nw = 1;
		}
		if (nh < 1) {
			nh = 1;
		}
		return new Size(nw, nh);
	}

	/**
	 * Writes {@code displayFile} from {@code originalFile}. A copy that needs no scaling is the
	 * original bytes. Otherwise the image is scaled and encoded again: PNG stays PNG, and other
	 * photos are written as JPEG.
	 */
	public static Written writeDisplay(File originalFile, File displayFile, Integer maxWidth, Integer maxHeight)
			throws IOException {
		BufferedImage image = ImageIO.read(originalFile);
		if (image == null) {
			if (isLimit(maxWidth) || isLimit(maxHeight)) {
				throw new IOException("Could not read photo " + originalFile.getName());
			}
			copyFile(originalFile, displayFile);
			return new Written(0, 0);
		}
		try {
			Size size = fit(image.getWidth(), image.getHeight(), maxWidth, maxHeight);
			if (size.width == image.getWidth() && size.height == image.getHeight()) {
				copyFile(originalFile, displayFile);
				return new Written(size.width, size.height);
			}
			BufferedImage scaled = scale(image, size.width, size.height);
			try {
				writeEncoded(scaled, displayFile);
				return new Written(scaled.getWidth(), scaled.getHeight());
			} finally {
				if (scaled != image) {
					scaled.flush();
				}
			}
		} finally {
			image.flush();
		}
	}

	/**
	 * Downscales by half until one more step would pass the target, then one bicubic step to the
	 * exact size. Does not enlarge.
	 */
	static BufferedImage scale(BufferedImage src, int targetWidth, int targetHeight) {
		if (src == null) {
			return null;
		}
		if (targetWidth >= src.getWidth() || targetHeight >= src.getHeight()) {
			return src;
		}

		BufferedImage cur = src;
		int w = src.getWidth();
		int h = src.getHeight();
		while (w / 2 >= targetWidth && h / 2 >= targetHeight) {
			w /= 2;
			h /= 2;
			BufferedImage next = resample(cur, w, h);
			if (cur != src) {
				cur.flush();
			}
			cur = next;
		}
		if (cur.getWidth() != targetWidth || cur.getHeight() != targetHeight) {
			BufferedImage next = resample(cur, targetWidth, targetHeight);
			if (cur != src) {
				cur.flush();
			}
			cur = next;
		}
		return cur;
	}

	private static BufferedImage resample(BufferedImage src, int width, int height) {
		int type = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
		BufferedImage dst = new BufferedImage(width, height, type);
		Graphics2D g = dst.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.drawImage(src, 0, 0, width, height, null);
		} finally {
			g.dispose();
		}
		return dst;
	}

	private static void writeEncoded(BufferedImage image, File displayFile) throws IOException {
		String format = format(displayFile.getName());
		BufferedImage output = image;
		if (!"png".equals(format)) {
			output = toRgb(image);
		}

		File folder = displayFile.getParentFile();
		if (folder != null && !folder.exists() && !folder.mkdirs()) {
			throw new IOException("Could not create " + folder);
		}
		File tmp = File.createTempFile("photoscale-", ".img", folder);
		ImageWriter writer = null;
		ImageOutputStream out = null;
		try {
			Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
			if (!writers.hasNext()) {
				throw new IOException("No image writer for " + format);
			}
			writer = writers.next();
			out = ImageIO.createImageOutputStream(tmp);
			writer.setOutput(out);
			ImageWriteParam param = writer.getDefaultWriteParam();
			if ("jpg".equals(format) && param.canWriteCompressed()) {
				param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				param.setCompressionQuality(JPEG_QUALITY);
			}
			writer.write(null, new IIOImage(output, null, null), param);
			out.flush();
		} finally {
			if (writer != null) {
				writer.dispose();
			}
			if (out != null) {
				out.close();
			}
			if (output != image) {
				output.flush();
			}
		}
		try {
			move(tmp, displayFile);
		} finally {
			if (tmp.exists()) {
				tmp.delete();
			}
		}
	}

	private static BufferedImage toRgb(BufferedImage image) {
		if (image.getType() == BufferedImage.TYPE_INT_RGB && !image.getColorModel().hasAlpha()) {
			return image;
		}
		BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = rgb.createGraphics();
		try {
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
			g.drawImage(image, 0, 0, null);
		} finally {
			g.dispose();
		}
		return rgb;
	}

	private static String format(String name) {
		String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".png")) {
			return "png";
		}
		if (lower.endsWith(".gif")) {
			return "gif";
		}
		return "jpg";
	}

	private static void copyFile(File source, File dest) throws IOException {
		File folder = dest.getParentFile();
		if (folder != null && !folder.exists() && !folder.mkdirs()) {
			throw new IOException("Could not create " + folder);
		}
		File tmp = File.createTempFile("photocopy-", ".img", folder);
		try {
			Files.copy(source.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
			move(tmp, dest);
		} finally {
			if (tmp.exists()) {
				tmp.delete();
			}
		}
	}

	private static void move(File tmp, File dest) throws IOException {
		try {
			Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static boolean isLimit(Integer limit) {
		return limit != null && limit.intValue() > 0;
	}
}
