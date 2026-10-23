package com.github.topi314.lavasrc.tidal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static com.github.topi314.lavasrc.tidal.TidalTestUtils.json;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixtures under tidal/v2 are real openapi.tidal.com/v2 responses (countryCode=IT).
 */
class TidalV2ParserTest {

	private static final String JAY_Z = "JAŸ-Z";
	private static final String COVER_4_44 = "https://resources.tidal.com/images/7c298a27/caec/4058/9a7b/5f34cfd2c857/";

	@Test
	void parsesTrackWithIncludedArtistAlbumAndArtwork() throws IOException {
		var track = TidalV2Parser.parseTrackDocument(json("v2/track.json"));

		assertNotNull(track);
		assertEquals("75413016", track.id);
		assertEquals("4:44", track.title);
		assertEquals(JAY_Z, track.author);
		assertEquals(284_000L, track.durationMs);
		assertEquals("QMJMT1701237", track.isrc);
		assertEquals("https://tidal.com/browse/track/75413016", track.url);
		assertEquals(COVER_4_44 + "1280x1280.jpg", track.artworkUrl);
		assertEquals("4:44", track.albumName);
		assertEquals("https://tidal.com/browse/album/75413011", track.albumUrl);
		assertEquals("https://tidal.com/browse/artist/7804", track.artistUrl);
	}

	@Test
	void parsesAlbumMetadata() throws IOException {
		var tracks = List.<TidalTrackData>of();
		var album = TidalV2Parser.parseAlbum(json("v2/album.json"), tracks);

		assertEquals("4:44", album.name);
		assertEquals("https://tidal.com/browse/album/75413011", album.url);
		assertEquals(COVER_4_44 + "1080x1080.jpg", album.artworkUrl);
		assertEquals(JAY_Z, album.author);
		assertEquals(13, album.totalTracks);
	}

	@Test
	void parsesItemsPageInOrderAndJoinsArtists() throws IOException {
		var tracks = TidalV2Parser.parseItemsPage(json("v2/album_items.json"));

		assertEquals(13, tracks.size());
		assertEquals("75413012", tracks.get(0).id);
		assertEquals("Kill Jay Z", tracks.get(0).title);
		assertEquals(178_000L, tracks.get(0).durationMs);
		assertEquals(JAY_Z + ", Gloria Carter", tracks.get(2).author);
		// this page was requested without items.albums, so the album and its artwork are unknown
		assertNull(tracks.get(0).albumName);
		assertEquals(TidalV1Client.DEFAULT_ARTWORK_URL, tracks.get(0).artworkUrl);
	}

	@Test
	void skipsVideoItems() throws IOException {
		var tracks = TidalV2Parser.parseItemsPage(json("v2/album_items_with_video.json"));

		assertEquals(12, tracks.size());
		assertFalse(tracks.stream().anyMatch(track -> track.id.equals("75413013")));
	}

	@Test
	void parsesPlaylistItemsPageWithNestedIncludesAndNextLink() throws IOException {
		var document = json("v2/playlist_items_page1.json");
		var tracks = TidalV2Parser.parseItemsPage(document);

		assertEquals(20, tracks.size());
		var first = tracks.get(0);
		assertEquals("75413016", first.id);
		assertEquals("QMJMT1701237", first.isrc);
		assertEquals("4:44", first.albumName);
		assertEquals(COVER_4_44 + "1280x1280.jpg", first.artworkUrl);
		assertTrue(TidalV2Parser.nextLink(document).startsWith("/playlists/001fdb7dd3214d7c96032f0c0594e1/relationships/items?"));
		assertTrue(TidalV2Parser.nextLink(document).contains("page%5Bcursor%5D="));
	}

	@Test
	void parsesMixPlaylistMetadata() throws IOException {
		var mix = TidalV2Parser.parsePlaylist(json("v2/mix.json"), List.of());

		assertEquals("4:44", mix.name);
		assertEquals("https://listen.tidal.com/mix/001fdb7dd3214d7c96032f0c0594e1", mix.url);
		assertNull(mix.author);
		assertNotNull(mix.artworkUrl);
	}

	@Test
	void parsesSearchResultsInRankOrderRespectingLimit() throws IOException {
		var tracks = TidalV2Parser.parseSearch(json("v2/search.json"), 3);

		assertEquals(List.of("159462028", "209199383", "37831739"), tracks.stream().map(track -> track.id).collect(Collectors.toList()));
		assertEquals("Animals", tracks.get(0).title);
		assertEquals("Architects", tracks.get(0).author);
		assertEquals(244_000L, tracks.get(0).durationMs);
		assertNotNull(tracks.get(0).albumName);

		assertEquals(15, TidalV2Parser.parseSearch(json("v2/search.json"), 50).size());
	}

	@Test
	void parsesRadioMixId() throws IOException {
		assertEquals("001fdb7dd3214d7c96032f0c0594e1", TidalV2Parser.parseRadioMixId(json("v2/track_radio.json")));
	}

	@Test
	void parsesIsoDurations() {
		assertEquals(178_000L, TidalV2Parser.parseDurationMs("PT2M58S"));
		assertEquals(3_723_500L, TidalV2Parser.parseDurationMs("PT1H2M3.5S"));
		assertEquals(46 * 60_000L + 17_000L, TidalV2Parser.parseDurationMs("PT46M17S"));
		assertEquals(0L, TidalV2Parser.parseDurationMs(null));
		assertEquals(0L, TidalV2Parser.parseDurationMs(""));
		assertEquals(0L, TidalV2Parser.parseDurationMs("3:20"));
	}
}
