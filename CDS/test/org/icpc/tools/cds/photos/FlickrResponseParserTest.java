package org.icpc.tools.cds.photos;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(JUnit4.class)
public class FlickrResponseParserTest {
	@Test
	public void parsesFixture() throws Exception {
		File fixture = fixture();
		String json = new String(Files.readAllBytes(fixture.toPath()), StandardCharsets.UTF_8);
		FlickrResponseParser.Page page = FlickrResponseParser.parse(json);

		assertEquals(1, page.page);
		assertEquals(2, page.pages);
		assertEquals(4, page.total);
		assertEquals(3, page.photos.size());

		Photo original = page.photos.get(0);
		assertEquals("8593497123", original.getId());
		assertEquals("Say \"hi\"", original.getTitle());
		assertEquals("Lidia Example", original.getOwner());
		assertEquals("4", original.getLicense());
		assertEquals("https://live.staticflickr.com/8387/8593497123_o.jpg", original.getOriginalUrl());

		Photo large = page.photos.get(1);
		assertEquals("8593497124", large.getId());
		assertEquals("https://live.staticflickr.com/1/k.jpg", large.getOriginalUrl());
		assertEquals("Other Person", large.getOwner());
		assertEquals("0", large.getLicense());

		Photo web = page.photos.get(2);
		assertEquals("https://live.staticflickr.com/1/only-l.jpg", web.getOriginalUrl());
		assertEquals("Album Owner", web.getOwner());
		assertEquals("9", web.getLicense());
	}

	@Test
	public void apiFailureDoesNotLookLikeAnEmptyAlbum() {
		try {
			FlickrResponseParser.parse("{\"stat\":\"fail\",\"code\":1,\"message\":\"Photoset not found\"}");
			fail("Expected Flickr error");
		} catch (Exception e) {
			assertEquals("Photoset not found", e.getMessage());
		}
	}

	private static File fixture() {
		File nested = new File("CDS/test/org/icpc/tools/cds/photos/fixtures/flickr-getPhotos.json");
		if (nested.isFile())
			return nested;
		return new File("test/org/icpc/tools/cds/photos/fixtures/flickr-getPhotos.json");
	}
}
