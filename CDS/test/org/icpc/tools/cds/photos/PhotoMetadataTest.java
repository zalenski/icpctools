package org.icpc.tools.cds.photos;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class PhotoMetadataTest {
	@Test
	public void writesAndReadsSidecarAndIndex() throws Exception {
		File folder = Files.createTempDirectory("photo-meta").toFile();
		try {
			PhotoMetadata meta = new PhotoMetadata("flickr", "flickr://album/42", "8593497123", "Say \"hi\"",
					"Lidia Example", "4", "https://live.staticflickr.com/8387/8593497123_o.jpg", 1700000000000L,
					"flickr-album_42-8593497123.jpg");
			File image = new File(folder, meta.getFile());
			assertTrue(image.createNewFile());

			PhotoMetadata.writeSidecar(image, meta);
			PhotoMetadata.writeIndex(folder, Arrays.asList(meta));

			PhotoMetadata sidecar = PhotoMetadata.readSidecar(image);
			assertEquals("flickr", sidecar.getSourceType());
			assertEquals("flickr://album/42", sidecar.getSourceUri());
			assertEquals("8593497123", sidecar.getPhotoId());
			assertEquals("Say \"hi\"", sidecar.getTitle());
			assertEquals("Lidia Example", sidecar.getOwner());
			assertEquals("4", sidecar.getLicense());
			assertEquals("https://live.staticflickr.com/8387/8593497123_o.jpg", sidecar.getOriginalUrl());
			assertEquals(1700000000000L, sidecar.getFetchTime());
			assertEquals("flickr-album_42-8593497123.jpg", sidecar.getFile());
			assertEquals("flickr-album_42-8593497123.jpg.json", PhotoMetadata.sidecarFile(image).getName());

			List<PhotoMetadata> index = PhotoMetadata.readIndex(folder);
			assertEquals(1, index.size());
			assertEquals("8593497123", index.get(0).getPhotoId());
			assertEquals(1700000000000L, index.get(0).getFetchTime());
			assertTrue(PhotoMetadata.readIndex(new File(folder, "missing")).isEmpty());
		} finally {
			deleteTree(folder);
		}
	}

	private static void deleteTree(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteTree(child);
			}
		}
		file.delete();
	}
}
