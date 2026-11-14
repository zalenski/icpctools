package org.icpc.tools.cds.photos;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;

import org.icpc.tools.contest.model.photos.PhotoMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PhotoCatalogStoreTest {
	@Test
	public void preservesManualFilesAndOtherImportsAndReusesAllocatedNames() throws Exception {
		File config = Files.createTempDirectory("photo-store").toFile();
		try {
			PhotoCatalogStore store = new PhotoCatalogStore(config);
			File folder = new File(config, "present/photos");
			assertTrue(folder.mkdirs());
			Files.writeString(new File(folder, "photo.jpg").toPath(), "manual");
			PhotoMetadata first = store.upload("photos", "first", metadata("first"), bytes("first"));
			PhotoMetadata second = store.upload("photos", "second", metadata("second"), bytes("second"));
			assertFalse(first.getFile().equals(second.getFile()));
			assertFalse(first.getFile().equals("photo.jpg"));
			assertEquals(first.getFile(), store.upload("photos", "first", metadata("first"), bytes("updated")).getFile());
			store.delete("photos", "first", "1");
			assertEquals(1, store.index("photos").size());
			assertEquals("second", store.index("photos").get(0).getImportId());
			assertEquals("second", Files.readString(new File(folder, second.getFile()).toPath()));
			assertEquals("manual", Files.readString(new File(folder, "photo.jpg").toPath()));
		} finally {
			deleteTree(config);
		}
	}

	@Test
	public void rejectsPathsAndOwnershipMismatchAndKeepsIndexOnInterruptedUpload() throws Exception {
		File config = Files.createTempDirectory("photo-store-invalid").toFile();
		try {
			PhotoCatalogStore store = new PhotoCatalogStore(config);
			PhotoMetadata first = store.upload("photos", "first", metadata("first"), bytes("original"));
			try {
				store.upload("photos", "second", metadata("first"), bytes("overwrite"));
				fail("Expected ownership rejection");
			} catch (IllegalArgumentException expected) {
				assertEquals(1, store.index("photos").size());
			}
			try {
				store.upload("photos", "first", metadata("first").withFile("../outside.jpg"), bytes("bad"));
				fail("Expected path rejection");
			} catch (IllegalArgumentException expected) {
				assertFalse(new File(config, "outside.jpg").exists());
			}
			try {
				store.upload("photos", "first", metadata("first"), new InputStream() {
					@Override
					public int read() throws IOException {
						throw new IOException("connection lost");
					}
				});
				fail("Expected interrupted upload");
			} catch (IOException expected) {
				assertEquals("connection lost", expected.getMessage());
			}
			File folder = new File(config, "present/photos");
			assertEquals("original", Files.readString(new File(folder, first.getFile()).toPath()));
			assertEquals(1, store.index("photos").size());
			for (String name : folder.list()) {
				assertFalse(name.endsWith(".tmp"));
			}
		} finally {
			deleteTree(config);
		}
	}

	private static PhotoMetadata metadata(String importId) {
		return new PhotoMetadata("test", "test:source", "1", "Title", "Owner", null,
				"https://example.test/photo.jpg", 1L, "photo.jpg").withImportId(importId);
	}

	private static InputStream bytes(String value) {
		return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
	}

	private static void deleteTree(File file) throws IOException {
		try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(file.toPath())) {
			for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
				Files.deleteIfExists(path);
			}
		}
	}
}
