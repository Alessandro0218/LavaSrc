package com.github.topi314.lavasrc.tidal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static com.github.topi314.lavasrc.tidal.TidalTestUtils.fixture;
import static org.junit.jupiter.api.Assertions.*;

class TidalV2ClientTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

	private final List<String> requestedUrls = new ArrayList<>();
	private final List<String> usedTokens = new ArrayList<>();
	private final List<Long> sleeps = new ArrayList<>();
	private final Deque<TidalV2Client.Response> responses = new ArrayDeque<>();
	private int tokenCounter;

	private TidalV2Client client() {
		var tokenManager = new TidalV2TokenManager(() -> new TidalV2TokenManager.AccessToken("token-" + ++tokenCounter, 14400), CLOCK);
		return new TidalV2Client(tokenManager, "IT", (url, accessToken) -> {
			this.requestedUrls.add(url);
			this.usedTokens.add(accessToken);
			return this.responses.removeFirst();
		}, this.sleeps::add, CLOCK);
	}

	private void respond(int status, String body) {
		this.responses.add(new TidalV2Client.Response(status, null, body));
	}

	@Test
	void requestsTrackWithCountryCodeAndIncludes() throws IOException {
		respond(200, fixture("v2/track.json"));

		var track = client().getTrack("75413016");

		assertEquals("75413016", track.id);
		assertEquals("https://openapi.tidal.com/v2/tracks/75413016?countryCode=IT&include=artists&include=albums&include=albums.coverArt", requestedUrls.get(0));
	}

	@Test
	void retriesOnceWithFreshTokenOn401() throws IOException {
		respond(401, "");
		respond(200, fixture("v2/track.json"));

		assertNotNull(client().getTrack("75413016"));
		assertEquals(List.of("token-1", "token-2"), usedTokens);
	}

	@Test
	void secondConsecutive401IsRetryableError() {
		respond(401, "");
		respond(401, "");

		var e = assertThrows(TidalApiException.class, () -> client().getTrack("75413016"));
		assertEquals(401, e.getStatusCode());
		assertTrue(e.isRetryable());
		assertEquals(2, requestedUrls.size());
	}

	@Test
	void waitsForRetryAfterOn429() throws IOException {
		responses.add(new TidalV2Client.Response(429, "2", "{\"errors\":[{\"status\":\"429\"}]}"));
		respond(200, fixture("v2/track.json"));

		assertNotNull(client().getTrack("75413016"));
		assertEquals(List.of(2000L), sleeps);
	}

	@Test
	void repeated429IsRetryableErrorAfterOneWait() {
		responses.add(new TidalV2Client.Response(429, "1", ""));
		responses.add(new TidalV2Client.Response(429, "1", ""));

		var e = assertThrows(TidalApiException.class, () -> client().getTrack("75413016"));
		assertEquals(429, e.getStatusCode());
		assertTrue(e.isRetryable());
		assertEquals(1, sleeps.size());
	}

	@Test
	void retryAfterIsCappedAndParsed() {
		assertEquals(TidalV2Client.DEFAULT_RETRY_AFTER_MS, TidalV2Client.retryAfterMillis(null, CLOCK));
		assertEquals(TidalV2Client.DEFAULT_RETRY_AFTER_MS, TidalV2Client.retryAfterMillis("soon", CLOCK));
		assertEquals(3000L, TidalV2Client.retryAfterMillis("3", CLOCK));
		assertEquals(TidalV2Client.MAX_RETRY_AFTER_MS, TidalV2Client.retryAfterMillis("120", CLOCK));
		assertEquals(4000L, TidalV2Client.retryAfterMillis("Thu, 01 Jan 2026 00:00:04 GMT", CLOCK));
		assertEquals(0L, TidalV2Client.retryAfterMillis("-5", CLOCK));
	}

	@Test
	void missingOrInvalidResourceReturnsNull() throws IOException {
		respond(400, fixture("v2/error_invalid_id.json"));
		respond(404, "");

		var client = client();
		assertNull(client.getTrack("0"));
		assertNull(client.getTrackMixId("123"));
	}

	@Test
	void serverErrorIsRetryable() {
		respond(503, "");

		var e = assertThrows(TidalApiException.class, () -> client().getTrack("75413016"));
		assertTrue(e.isRetryable());
	}

	@Test
	void unexpectedClientErrorIsNotRetryable() {
		respond(406, "");

		var e = assertThrows(TidalApiException.class, () -> client().getTrack("75413016"));
		assertFalse(e.isRetryable());
	}

	@Test
	void followsNextLinksUntilMaxItems() throws IOException {
		respond(200, fixture("v2/playlist_items_page1.json"));
		respond(200, fixture("v2/album_items.json"));

		var tracks = client().getMix("001fdb7dd3214d7c96032f0c0594e1", 25);

		assertEquals(25, tracks.size());
		assertEquals(2, requestedUrls.size());
		assertTrue(requestedUrls.get(1).startsWith("https://openapi.tidal.com/v2/playlists/001fdb7dd3214d7c96032f0c0594e1/relationships/items?"));
		assertTrue(requestedUrls.get(1).contains("page%5Bcursor%5D="));
	}

	@Test
	void stopsPagingWhenMaxItemsReached() throws IOException {
		respond(200, fixture("v2/playlist_items_page1.json"));

		var tracks = client().getMix("001fdb7dd3214d7c96032f0c0594e1", 20);

		assertEquals(20, tracks.size());
		assertEquals(1, requestedUrls.size());
	}

	@Test
	void searchUsesQueryFilterAndLimit() throws IOException {
		respond(200, fixture("v2/search.json"));

		var tracks = client().search("animals architects", 6);

		assertEquals(6, tracks.size());
		assertTrue(requestedUrls.get(0).startsWith("https://openapi.tidal.com/v2/searchResults?filter%5Bquery%5D=animals+architects&countryCode=IT&include=tracks"));
	}

	@Test
	void recommendationsUseTrackRadio() throws IOException {
		respond(200, fixture("v2/track_radio.json"));

		assertEquals("001fdb7dd3214d7c96032f0c0594e1", client().getTrackMixId("75413016"));
		assertEquals("https://openapi.tidal.com/v2/tracks/75413016/relationships/radio?countryCode=IT", requestedUrls.get(0));
	}
}
