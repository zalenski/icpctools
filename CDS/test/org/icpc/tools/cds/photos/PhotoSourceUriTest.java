package org.icpc.tools.cds.photos;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(JUnit4.class)
public class PhotoSourceUriTest {
	@Test
	public void parsesFlickrAlbumUri() {
		PhotoSourceUris.Parsed parsed = PhotoSourceUris.parse("flickr://album/72157626617127115");
		assertEquals("flickr", parsed.scheme);
		assertEquals("72157626617127115", parsed.albumId);
		assertEquals("flickr://album/72157626617127115", parsed.canonicalUri);
	}

	@Test
	public void stripsTrailingSlashAndQuery() {
		PhotoSourceUris.Parsed parsed = PhotoSourceUris.parse("flickr://album/42/?extra=1");
		assertEquals("flickr://album/42", parsed.canonicalUri);
		assertEquals("42", parsed.albumId);
	}

	@Test
	public void parsesFlickrAlbumUrl() {
		PhotoSourceUris.Parsed parsed = PhotoSourceUris
				.parse("https://www.flickr.com/photos/some.user/albums/72157626617127115/");
		assertEquals("flickr", parsed.scheme);
		assertEquals("72157626617127115", parsed.albumId);
		assertEquals("flickr://album/72157626617127115", parsed.canonicalUri);
	}

	@Test
	public void parsesLegacySetsUrl() {
		PhotoSourceUris.Parsed parsed = PhotoSourceUris.parse("http://flickr.com/photos/x/sets/99?foo=1");
		assertEquals("flickr://album/99", parsed.canonicalUri);
	}

	@Test
	public void rejectsUnknownHostAndScheme() {
		assertInvalid("https://example.com/photos/a/albums/1");
		assertInvalid("dropbox://album/1");
		assertInvalid("flickr://photoset/1");
		assertInvalid("   ");
	}

	@Test
	public void registrySelectsFlickrFromEitherForm() {
		PhotoSource fromUri = PhotoSourceRegistry.create("flickr://album/42", "test-key");
		PhotoSource fromUrl = PhotoSourceRegistry.create("https://www.flickr.com/photos/icpc/albums/42", "test-key");

		assertTrue(fromUri instanceof FlickrPhotoSource);
		assertEquals("flickr", fromUri.getType());
		assertEquals("flickr://album/42", fromUri.getUri());
		assertEquals("flickr://album/42", fromUrl.getUri());
		assertEquals("42", ((FlickrPhotoSource) fromUri).getAlbumId());
	}

	private static void assertInvalid(String uri) {
		try {
			PhotoSourceUris.parse(uri);
			fail("Expected invalid URI: " + uri);
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage() != null && !e.getMessage().isEmpty());
		}
	}
}
