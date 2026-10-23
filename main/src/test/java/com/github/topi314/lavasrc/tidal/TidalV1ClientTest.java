package com.github.topi314.lavasrc.tidal;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.github.topi314.lavasrc.tidal.TidalTestUtils.json;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The expected values follow the parsing rules of the original TidalSourceManager, which the v1 client must keep.
 */
class TidalV1ClientTest {

	@Test
	void parsesTrackLikeBefore() throws IOException {
		var track = TidalV1Client.parseTrack(json("v1/track.json"));

		assertNotNull(track);
		assertEquals("75413016", track.id);
		assertEquals("Kill Jay Z", track.title);
		assertEquals("JAY Z, Beyonce", track.author);
		assertEquals(178_000L, track.durationMs);
		assertEquals("QMJMT1701229", track.isrc);
		assertEquals("http://www.tidal.com/track/75413016", track.url);
		assertEquals("https://resources.tidal.com/images/7c298a27/caec/4058/9a7b/5f34cfd2c857/1280x1280.jpg", track.artworkUrl);
		assertNull(track.albumName);
		assertNull(track.albumUrl);
		assertNull(track.artistUrl);
	}

	@Test
	void parsesMixItemsSkippingZeroDurationAndUsingDefaultArtwork() throws IOException {
		var tracks = TidalV1Client.parseTrackItems(json("v1/mix_items.json"));

		assertEquals(2, tracks.size());
		assertEquals("75413016", tracks.get(0).id);
		assertEquals("3", tracks.get(1).id);
		assertEquals(TidalV1Client.DEFAULT_ARTWORK_URL, tracks.get(1).artworkUrl);
	}
}
