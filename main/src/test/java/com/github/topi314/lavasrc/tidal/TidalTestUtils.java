package com.github.topi314.lavasrc.tidal;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class TidalTestUtils {

	private TidalTestUtils() {
	}

	static String fixture(String path) throws IOException {
		try (InputStream stream = TidalTestUtils.class.getResourceAsStream("/tidal/" + path)) {
			if (stream == null) {
				throw new IOException("Missing fixture " + path);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	static JsonBrowser json(String path) throws IOException {
		return JsonBrowser.parse(fixture(path));
	}
}
