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

	@Test
	public void keepsOnlyPhotosWithTheTag() throws Exception {
		String json = "{\"stat\":\"ok\",\"photoset\":{\"page\":1,\"pages\":1,\"total\":3,\"photo\":["
				+ "{\"id\":\"1\",\"url_o\":\"https://x/1.jpg\",\"tags\":\"eventcontest eventteamphotos\"},"
				+ "{\"id\":\"2\",\"url_o\":\"https://x/2.jpg\",\"tags\":\"eventteamphotosextra\"},"
				+ "{\"id\":\"3\",\"url_o\":\"https://x/3.jpg\"}]}}";

		FlickrResponseParser.Page page = FlickrResponseParser.parse(json, "eventteamphotos");
		assertEquals(1, page.photos.size());
		assertEquals("1", page.photos.get(0).getId());

		assertEquals(3, FlickrResponseParser.parse(json).photos.size());
	}

	@Test
	public void normalizesTagsLikeFlickr() {
		assertEquals("eventteamphotos", FlickrPhotoSource.normalizeTag("event$Team Photos"));
		assertEquals("eventteamphotos", FlickrPhotoSource.normalizeTag("  eventteamphotos "));
		assertEquals(null, FlickrPhotoSource.normalizeTag("$ "));
		assertEquals(null, FlickrPhotoSource.normalizeTag(null));
	}

	@Test
	public void taggedSourceHasItsOwnUri() {
		assertEquals("flickr://album/42", new FlickrPhotoSource("flickr://album/42", "k").getUri());
		assertEquals("flickr://album/42?tag=eventteamphotos",
				new FlickrPhotoSource("flickr://album/42", "k", "event$Team Photos").getUri());
	}

	private static File fixture() {
		File nested = new File("CDS/test/org/icpc/tools/cds/photos/fixtures/flickr-getPhotos.json");
		if (nested.isFile()) {
			return nested;
		}
		return new File("test/org/icpc/tools/cds/photos/fixtures/flickr-getPhotos.json");
	}
}
