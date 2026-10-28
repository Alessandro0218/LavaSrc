package com.github.topi314.lavasrc.tidal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.github.topi314.lavasrc.tidal.TidalTestUtils.fixture;
import static org.junit.jupiter.api.Assertions.*;

class TidalWebClientTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

	private final List<String> requestedUrls = new ArrayList<>();
	private final List<String> usedTokens = new ArrayList<>();
	private final List<Long> sleeps = new ArrayList<>();
	private final Deque<TidalV2Client.Response> responses = new ArrayDeque<>();
	private int tokenCounter;

	private TidalWebClient client() {
		var tokenManager = new TidalV2TokenManager(() -> new TidalV2TokenManager.AccessToken("token-" + ++tokenCounter, 86400), CLOCK);
		return new TidalWebClient(tokenManager, "IT", (url, accessToken) -> {
			this.requestedUrls.add(url);
			this.usedTokens.add(accessToken);
			return this.responses.removeFirst();
		}, this.sleeps::add, CLOCK);
	}

	private void respond(int status, String body) {
		this.responses.add(new TidalV2Client.Response(status, null, body));
	}

	@Test
	void pagesPlaylistByOffsetAndSkipsVideos() throws IOException {
		respond(200, fixture("web/playlist.json"));
		respond(200, fixture("web/playlist_items_page1.json"));
		respond(200, fixture("web/playlist_items_page2.json"));

		var playlist = client().getPlaylist("36ea71a8-445e-41a4-82ab-6628c581535d", 750);

		assertEquals("Radio Eclectica", playlist.name);
		assertEquals("JAY Z", playlist.author);
		assertEquals(3, playlist.totalTracks);
		assertEquals("https://resources.tidal.com/images/b2a5e7f1/1111/2222/3333/444455556666/1080x1080.jpg", playlist.artworkUrl);
		assertEquals(List.of("75413016", "75413017", "75413018"), playlist.tracks.stream().map(track -> track.id).collect(Collectors.toList()));
		assertEquals(List.of(
			"https://api.tidal.com/v1/playlists/36ea71a8-445e-41a4-82ab-6628c581535d?countryCode=IT",
			"https://api.tidal.com/v1/playlists/36ea71a8-445e-41a4-82ab-6628c581535d/items?countryCode=IT&limit=100&offset=0",
			"https://api.tidal.com/v1/playlists/36ea71a8-445e-41a4-82ab-6628c581535d/items?countryCode=IT&limit=2&offset=2"
		), requestedUrls);
	}

	@Test
	void loadsRemainingPagesInParallelKeepingOffsetOrder() throws IOException {
		var byOffset = new ConcurrentHashMap<Integer, TidalV2Client.Response>();
		for (var offset = 0; offset < 9; offset += 2) {
			byOffset.put(offset, new TidalV2Client.Response(200, null, page(offset, Math.min(2, 9 - offset), 9)));
		}
		var urls = Collections.synchronizedList(new ArrayList<String>());
		var tokenManager = new TidalV2TokenManager(() -> new TidalV2TokenManager.AccessToken("token", 86400), CLOCK);
		var client = new TidalWebClient(tokenManager, "IT", (url, accessToken) -> {
			urls.add(url);
			// pages that finish late must not reorder the result
			var offset = Integer.parseInt(url.substring(url.lastIndexOf('=') + 1));
			if (offset == 2) {
				sleepQuietly(50);
			}
			return byOffset.get(offset);
		}, this.sleeps::add, CLOCK);

		var tracks = client.getMix("mix", 750);

		assertEquals(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8"), tracks.stream().map(track -> track.id).collect(Collectors.toList()));
		assertEquals(5, urls.size());
		assertTrue(urls.contains("https://api.tidal.com/v1/mixes/mix/items?countryCode=IT&limit=1&offset=8"));
	}

	@Test
	void failedPagePropagates() {
		var tokenManager = new TidalV2TokenManager(() -> new TidalV2TokenManager.AccessToken("token", 86400), CLOCK);
		var client = new TidalWebClient(tokenManager, "IT", (url, accessToken) -> url.endsWith("offset=0")
			? new TidalV2Client.Response(200, null, page(0, 2, 6))
			: url.endsWith("offset=2") ? new TidalV2Client.Response(503, null, "") : new TidalV2Client.Response(200, null, page(4, 2, 6)),
			this.sleeps::add, CLOCK);

		var e = assertThrows(TidalApiException.class, () -> client.getMix("mix", 750));
		assertEquals(503, e.getStatusCode());
	}

	private static String page(int offset, int count, int total) {
		var items = new StringBuilder();
		for (var i = offset; i < offset + count; i++) {
			if (items.length() > 0) {
				items.append(',');
			}
			items.append("{\"type\":\"track\",\"item\":{\"id\":").append(i).append(",\"title\":\"t").append(i)
				.append("\",\"duration\":100,\"isrc\":\"X").append(i).append("\",\"url\":\"u\",\"artists\":[],\"album\":{\"cover\":null}}}");
		}
		return "{\"limit\":" + count + ",\"offset\":" + offset + ",\"totalNumberOfItems\":" + total + ",\"items\":[" + items + "]}";
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	void stopsAtMaxItems() throws IOException {
		respond(200, fixture("web/playlist_items_page1.json"));

		var tracks = client().getMix("001fdb7dd3214d7c96032f0c0594e1", 2);

		assertEquals(1, tracks.size());
		assertEquals(List.of("https://api.tidal.com/v1/mixes/001fdb7dd3214d7c96032f0c0594e1/items?countryCode=IT&limit=2&offset=0"), requestedUrls);
	}

	@Test
	void searchesThroughTheV2SearchEndpoint() throws IOException {
		respond(200, fixture("web/search.json"));

		var tracks = client().search("Radio Eclectica - V1 Reshuffled", 1);

		assertEquals(1, tracks.size());
		assertEquals("Kill Jay Z", tracks.get(0).title);
		assertEquals("QMJMT1701229", tracks.get(0).isrc);
		assertEquals("https://api.tidal.com/v2/search/?query=Radio+Eclectica+-+V1+Reshuffled&limit=1&offset=0&types=TRACKS"
			+ "&includeUserPlaylists=false&supportsUserData=false&countryCode=IT&locale=en_US&deviceType=DESKTOP", requestedUrls.get(0));
	}

	@Test
	void readsTrackAndItsRadioMix() throws IOException {
		respond(200, fixture("v1/track.json"));
		respond(200, fixture("v1/track.json"));

		var client = client();
		assertEquals("JAY Z, Beyonce", client.getTrack("75413016").author);
		assertEquals("001fdb7dd3214d7c96032f0c0594e1", client.getTrackMixId("75413016"));
		assertEquals("https://api.tidal.com/v1/tracks/75413016?countryCode=IT", requestedUrls.get(0));
	}

	@Test
	void renewsTheSessionTokenOnceOn401() throws IOException {
		respond(401, "");
		respond(200, fixture("v1/track.json"));

		assertNotNull(client().getTrack("75413016"));
		assertEquals(List.of("token-1", "token-2"), usedTokens);
	}

	@Test
	void missingResourceIsNull() throws IOException {
		respond(404, "{\"status\":404,\"subStatus\":2001}");

		assertNull(client().getPlaylist("missing", 750));
		assertEquals(1, requestedUrls.size());
	}

	@Test
	void forbiddenIsRetryableSoTheFallbackTakesOver() {
		respond(403, "");

		var e = assertThrows(TidalApiException.class, () -> client().getTrack("75413016"));
		assertTrue(e.isRetryable());
		assertEquals(403, e.getStatusCode());
	}

	@Test
	void waitsForRetryAfterOnceOn429() throws IOException {
		this.responses.add(new TidalV2Client.Response(429, "2", ""));
		respond(200, fixture("v1/track.json"));

		assertNotNull(client().getTrack("75413016"));
		assertEquals(List.of(2000L), sleeps);
	}

	@Test
	void refreshGrantSendsClientSecretOnlyWhenSet() {
		var withoutSecret = TidalV2TokenManager.refreshTokenParams(new TidalWebSession("client", null, "refresh"));
		assertEquals(List.of("grant_type=refresh_token", "refresh_token=refresh", "client_id=client"), withoutSecret.stream().map(Object::toString).collect(Collectors.toList()));

		var withSecret = TidalV2TokenManager.refreshTokenParams(new TidalWebSession("client", "secret", "refresh"));
		assertEquals("client_secret=secret", withSecret.get(3).toString());
	}
}
