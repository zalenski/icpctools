package org.icpc.tools.cds.photos;

import java.util.Arrays;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import static org.junit.Assert.assertEquals;

@RunWith(JUnit4.class)
public class PhotoSourcesTest {
	@Test
	public void parsesConfigAndKeepsARuntimeUpdateUntilTheSectionChanges() throws Exception {
		PhotoSources.Settings defaults = PhotoSources.parse(photos("refresh-missing", null));
		assertEquals(PhotoSources.DEFAULT_REFRESH_SECONDS, defaults.getRefreshSeconds());
		assertEquals(1, defaults.getSources().size());

		PhotoSources.Settings clamped = PhotoSources.parse(photos("1", "file-key"));
		assertEquals(PhotoSources.MIN_REFRESH_SECONDS, clamped.getRefreshSeconds());
		assertEquals("file-key", clamped.getApiKey());
		assertEquals("flickr://album/9", clamped.getSources().get(0).getUri());
		assertEquals("source-key", clamped.getSources().get(0).getApiKey());

		PhotoSources sources = new PhotoSources();
		PhotoSources.Settings runtime = new PhotoSources.Settings(300, "runtime-key",
				Arrays.asList(new PhotoSources.Source("flickr://album/7", null)));
		sources.update(runtime);
		assertEquals("flickr://album/7", sources.get().getSources().get(0).getUri());

		// The file still has no photos section, so a reload must not drop the runtime list.
		sources.reloadFromConfig(PhotoSources.Settings.NONE);
		assertEquals("flickr://album/7", sources.get().getSources().get(0).getUri());

		PhotoSources.Settings fromFile = new PhotoSources.Settings(120, "file-key",
				Arrays.asList(new PhotoSources.Source("flickr://album/9", null)));
		sources.reloadFromConfig(fromFile);
		assertEquals("flickr://album/9", sources.get().getSources().get(0).getUri());

		sources.update(runtime);
		sources.reloadFromConfig(fromFile);
		assertEquals("flickr://album/7", sources.get().getSources().get(0).getUri());

		PhotoSources.Settings edited = new PhotoSources.Settings(120, "file-key",
				Arrays.asList(new PhotoSources.Source("flickr://album/10", null)));
		sources.reloadFromConfig(edited);
		assertEquals("flickr://album/10", sources.get().getSources().get(0).getUri());
	}

	@Test
	public void sourceLimitOverridesOneParentLimit() throws Exception {
		Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
		Element photos = document.createElement("photos");
		photos.setAttribute("maxWidth", "1920");
		photos.setAttribute("maxHeight", "1080");
		Element source = document.createElement("source");
		source.setAttribute("uri", "flickr://album/9");
		source.setAttribute("maxWidth", "800");
		photos.appendChild(source);

		PhotoSources.Settings settings = PhotoSources.parse(photos);
		assertEquals(Integer.valueOf(1920), settings.getMaxWidth());
		assertEquals(Integer.valueOf(1080), settings.getMaxHeight());
		assertEquals(Integer.valueOf(800), settings.getSources().get(0).getMaxWidth());
		assertEquals(Integer.valueOf(1080), settings.getSources().get(0).getMaxHeight());

		PhotoSources.Settings unset = PhotoSources.parse(photos("refresh-missing", null));
		assertEquals(null, unset.getMaxWidth());
		assertEquals(null, unset.getMaxHeight());
		assertEquals(null, unset.getSources().get(0).getMaxWidth());
		assertEquals(null, unset.getSources().get(0).getMaxHeight());
	}

	private static Element photos(String refresh, String apiKey) throws Exception {
		Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
		Element photos = document.createElement("photos");
		if (!"refresh-missing".equals(refresh)) {
			photos.setAttribute("refresh", refresh);
		}
		if (apiKey != null) {
			photos.setAttribute("apiKey", apiKey);
		}
		Element source = document.createElement("source");
		source.setAttribute("uri", "flickr://album/9");
		source.setAttribute("apiKey", "source-key");
		photos.appendChild(source);
		return photos;
	}
}
