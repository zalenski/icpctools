package org.icpc.tools.cds.photos;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Small HTTP helper for photo sources. Uses {@link java.net.HttpURLConnection} only.
 */
final class PhotoHttp {
	private static final int CONNECT_TIMEOUT_MS = 15000;
	private static final int READ_TIMEOUT_MS = 60000;

	private PhotoHttp() {
		// utility
	}

	static String get(String url) throws IOException {
		HttpURLConnection conn = open(url);
		try {
			int code = conn.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK)
				throw new IOException("HTTP " + code);
			return read(conn.getInputStream());
		} finally {
			conn.disconnect();
		}
	}

	static void download(String url, File destination) throws IOException {
		HttpURLConnection conn = open(url);
		InputStream in = null;
		OutputStream out = null;
		try {
			int code = conn.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK)
				throw new IOException("HTTP " + code);
			in = conn.getInputStream();
			out = new FileOutputStream(destination);
			byte[] buf = new byte[8192];
			int n = in.read(buf);
			while (n >= 0) {
				out.write(buf, 0, n);
				n = in.read(buf);
			}
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (IOException e) {
					// ignore
				}
			}
			if (out != null) {
				try {
					out.close();
				} catch (IOException e) {
					// ignore
				}
			}
			conn.disconnect();
		}
	}

	static String redact(String message) {
		if (message == null)
			return "";
		return message.replaceAll("(?i)api_key=[^&\\s\"]+", "api_key=redacted");
	}

	private static HttpURLConnection open(String url) throws IOException {
		HttpURLConnection conn;
		try {
			conn = (HttpURLConnection) new URI(url).toURL().openConnection();
		} catch (Exception e) {
			throw new IOException("Could not open photo URL");
		}
		conn.setRequestMethod("GET");
		conn.setInstanceFollowRedirects(true);
		conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
		conn.setReadTimeout(READ_TIMEOUT_MS);
		conn.setRequestProperty("User-Agent", "ICPC-CDS");
		conn.setRequestProperty("Accept", "*/*");
		return conn;
	}

	private static String read(InputStream in) throws IOException {
		Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
		StringBuilder sb = new StringBuilder();
		char[] buf = new char[4096];
		int n = reader.read(buf);
		while (n >= 0) {
			sb.append(buf, 0, n);
			n = reader.read(buf);
		}
		reader.close();
		return sb.toString();
	}
}
