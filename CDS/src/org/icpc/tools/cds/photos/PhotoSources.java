package org.icpc.tools.cds.photos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The photo sources the CDS syncs into {@code present/photos}.
 * <p>
 * This is the only list the background sync reads. Saving {@code cdsConfig.xml} reloads it, and a
 * future admin or client request can call {@link #update(Settings)} with a new list. Both take
 * effect on the next sync cycle, without restarting the CDS. An in-memory update stays in effect
 * until the {@code <photos>} section in the config file actually changes.
 */
public final class PhotoSources {
	public static final int DEFAULT_REFRESH_SECONDS = 300;
	public static final int MIN_REFRESH_SECONDS = 15;

	public static final class Source {
		private final String uri;
		private final String apiKey;

		public Source(String uri, String apiKey) {
			this.uri = uri;
			this.apiKey = apiKey;
		}

		public String getUri() {
			return uri;
		}

		/**
		 * Key for this source, or {@code null} to use {@link Settings#getApiKey()}.
		 */
		public String getApiKey() {
			return apiKey;
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof Source))
				return false;
			Source other = (Source) obj;
			return eq(uri, other.uri) && eq(apiKey, other.apiKey);
		}

		@Override
		public int hashCode() {
			return (uri == null ? 0 : uri.hashCode()) * 31 + (apiKey == null ? 0 : apiKey.hashCode());
		}
	}

	public static final class Settings {
		public static final Settings NONE = new Settings(DEFAULT_REFRESH_SECONDS, null, Collections.<Source> emptyList());

		private final int refreshSeconds;
		private final String apiKey;
		private final List<Source> sources;

		public Settings(int refreshSeconds, String apiKey, List<Source> sources) {
			this.refreshSeconds = refreshSeconds;
			this.apiKey = apiKey;
			if (sources == null || sources.isEmpty())
				this.sources = Collections.emptyList();
			else
				this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
		}

		public int getRefreshSeconds() {
			return refreshSeconds;
		}

		public String getApiKey() {
			return apiKey;
		}

		public List<Source> getSources() {
			return sources;
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof Settings))
				return false;
			Settings other = (Settings) obj;
			return refreshSeconds == other.refreshSeconds && eq(apiKey, other.apiKey) && sources.equals(other.sources);
		}

		@Override
		public int hashCode() {
			return refreshSeconds * 31 + (apiKey == null ? 0 : apiKey.hashCode()) + sources.hashCode();
		}
	}

	private static final PhotoSources INSTANCE = new PhotoSources();

	private Settings fileSettings = Settings.NONE;
	private Settings effective = Settings.NONE;

	public PhotoSources() {
		// public so tests can exercise a list that is not the process-wide one
	}

	public static PhotoSources getInstance() {
		return INSTANCE;
	}

	public synchronized Settings get() {
		return effective;
	}

	/**
	 * Replace the list used by the sync. Does not restart the CDS.
	 */
	public synchronized void update(Settings settings) {
		effective = settings == null ? Settings.NONE : settings;
	}

	/**
	 * Apply the {@code <photos>} section from cdsConfig.xml. A repeated load of the same section
	 * keeps a newer {@link #update(Settings)} in place. A real change to that section replaces it.
	 */
	public synchronized void reloadFromConfig(Settings parsed) {
		Settings next = parsed == null ? Settings.NONE : parsed;
		if (next.equals(fileSettings))
			return;
		fileSettings = next;
		effective = next;
	}

	public static Settings parse(Element element) {
		if (element == null)
			return Settings.NONE;

		int refresh = DEFAULT_REFRESH_SECONDS;
		String refreshText = attribute(element, "refresh");
		if (refreshText != null) {
			try {
				refresh = Integer.parseInt(refreshText.trim());
			} catch (NumberFormatException e) {
				refresh = DEFAULT_REFRESH_SECONDS;
			}
		}
		if (refresh < MIN_REFRESH_SECONDS)
			refresh = MIN_REFRESH_SECONDS;

		List<Source> sources = new ArrayList<>();
		NodeList nodes = element.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if (!(node instanceof Element))
				continue;
			Element child = (Element) node;
			if (!"source".equals(child.getNodeName()))
				continue;
			String uri = attribute(child, "uri");
			if (uri == null)
				continue;
			sources.add(new Source(uri, attribute(child, "apiKey")));
		}
		return new Settings(refresh, attribute(element, "apiKey"), sources);
	}

	private static String attribute(Element element, String name) {
		if (!element.hasAttribute(name))
			return null;
		String value = element.getAttribute(name);
		if (value == null)
			return null;
		value = value.trim();
		if (value.isEmpty())
			return null;
		return value;
	}

	private static boolean eq(String a, String b) {
		if (a == null)
			return b == null;
		return a.equals(b);
	}
}
