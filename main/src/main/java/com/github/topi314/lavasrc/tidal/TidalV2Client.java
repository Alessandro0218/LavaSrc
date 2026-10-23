package com.github.topi314.lavasrc.tidal;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the official openapi.tidal.com/v2 API (JSON:API), authenticated with OAuth 2.0 client credentials.
 */
public class TidalV2Client implements TidalApiClient {

	public static final String API_BASE = "https://openapi.tidal.com/v2";
	static final long DEFAULT_RETRY_AFTER_MS = 1000;
	static final long MAX_RETRY_AFTER_MS = 5000;

	private static final String TRACK_INCLUDES = "&include=artists&include=albums&include=albums.coverArt";
	private static final String ITEMS_INCLUDES = "&include=items&include=items.artists&include=items.albums&include=items.albums.coverArt";
	private static final Logger log = LoggerFactory.getLogger(TidalV2Client.class);

	private final TidalV2TokenManager tokenManager;
	private final String countryCode;
	private final Transport transport;
	private final Sleeper sleeper;
	private final Clock clock;

	public TidalV2Client(HttpInterfaceManager httpInterfaceManager, TidalV2TokenManager tokenManager, String countryCode) {
		this(tokenManager, countryCode, httpTransport(httpInterfaceManager), Thread::sleep, Clock.systemUTC());
	}

	TidalV2Client(TidalV2TokenManager tokenManager, String countryCode, Transport transport, Sleeper sleeper, Clock clock) {
		this.tokenManager = tokenManager;
		this.countryCode = countryCode;
		this.transport = transport;
		this.sleeper = sleeper;
		this.clock = clock;
	}

	@Override
	public TidalApiVersion getVersion() {
		return TidalApiVersion.V2;
	}

	@Override
	public TidalTrackData getTrack(String trackId) throws IOException {
		var json = this.getJson(API_BASE + "/tracks/" + encode(trackId) + "?countryCode=" + this.countryCode + TRACK_INCLUDES);
		if (json == null) {
			return null;
		}
		return TidalV2Parser.parseTrackDocument(json);
	}

	@Override
	public TidalCollectionData getAlbum(String albumId, int maxItems) throws IOException {
		var json = this.getJson(API_BASE + "/albums/" + encode(albumId) + "?countryCode=" + this.countryCode + "&include=artists&include=coverArt");
		if (json == null) {
			return null;
		}
		var tracks = this.getItems("/albums/" + encode(albumId), maxItems);
		return TidalV2Parser.parseAlbum(json, tracks);
	}

	@Override
	public TidalCollectionData getPlaylist(String playlistId, int maxItems) throws IOException {
		var json = this.getJson(API_BASE + "/playlists/" + encode(playlistId) + "?countryCode=" + this.countryCode + "&include=coverArt&include=curators");
		if (json == null) {
			return null;
		}
		var tracks = this.getItems("/playlists/" + encode(playlistId), maxItems);
		return TidalV2Parser.parsePlaylist(json, tracks);
	}

	@Override
	public List<TidalTrackData> getMix(String mixId, int maxItems) throws IOException {
		// mixes are playlists of type MIX in v2 and share their ids with v1
		return this.getItems("/playlists/" + encode(mixId), maxItems);
	}

	@Override
	public List<TidalTrackData> search(String query, int limit) throws IOException {
		var json = this.getJson(API_BASE + "/searchResults?filter%5Bquery%5D=" + encode(query) + "&countryCode=" + this.countryCode
			+ "&include=tracks&include=tracks.artists&include=tracks.albums&include=tracks.albums.coverArt");
		if (json == null) {
			return List.of();
		}
		return TidalV2Parser.parseSearch(json, limit);
	}

	@Override
	public String getTrackMixId(String trackId) throws IOException {
		var json = this.getJson(API_BASE + "/tracks/" + encode(trackId) + "/relationships/radio?countryCode=" + this.countryCode);
		if (json == null) {
			return null;
		}
		return TidalV2Parser.parseRadioMixId(json);
	}

	/**
	 * Follows the {@code links.next} cursor of a {@code relationships/items} endpoint until {@code maxItems} tracks are loaded.
	 */
	private List<TidalTrackData> getItems(String resourcePath, int maxItems) throws IOException {
		var tracks = new ArrayList<TidalTrackData>();
		var url = API_BASE + resourcePath + "/relationships/items?countryCode=" + this.countryCode + ITEMS_INCLUDES;
		while (url != null && tracks.size() < maxItems) {
			var json = this.getJson(url);
			if (json == null || json.get("data").values().isEmpty()) {
				break;
			}
			tracks.addAll(TidalV2Parser.parseItemsPage(json));
			url = resolveLink(TidalV2Parser.nextLink(json));
		}
		return tracks.size() > maxItems ? new ArrayList<>(tracks.subList(0, maxItems)) : tracks;
	}

	/**
	 * @return the parsed document, or {@code null} when the resource does not exist (HTTP 400/404)
	 */
	JsonBrowser getJson(String url) throws IOException {
		var authRetried = false;
		var rateLimitRetried = false;
		while (true) {
			var accessToken = this.tokenManager.getAccessToken();
			var response = this.transport.get(url, accessToken);
			var status = response.statusCode;

			if (status >= 200 && status < 300) {
				return JsonBrowser.parse(response.body);
			}
			if (status == 400 || status == 404) {
				log.debug("Tidal v2 resource not found ({}): {} {}", status, stripQuery(url), response.body);
				return null;
			}
			if (status == 401 && !authRetried) {
				authRetried = true;
				this.tokenManager.invalidate(accessToken);
				continue;
			}
			if (status == 429 && !rateLimitRetried) {
				rateLimitRetried = true;
				var delay = retryAfterMillis(response.retryAfter, this.clock);
				log.warn("Tidal v2 rate limit reached, retrying in {} ms", delay);
				try {
					this.sleeper.sleep(delay);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new InterruptedIOException("Interrupted while waiting for the Tidal v2 rate limit");
				}
				continue;
			}

			var retryable = status == 401 || status == 403 || status == 429 || status >= 500;
			log.debug("Tidal v2 request failed ({}): {} {}", status, stripQuery(url), response.body);
			throw new TidalApiException("Tidal v2 request to " + stripQuery(url) + " failed with HTTP " + status, status, retryable);
		}
	}

	/**
	 * Parses a Retry-After header (seconds or HTTP date), capped at {@link #MAX_RETRY_AFTER_MS} so a loading thread is never blocked for long.
	 */
	static long retryAfterMillis(String retryAfter, Clock clock) {
		long delay = DEFAULT_RETRY_AFTER_MS;
		if (retryAfter != null && !retryAfter.isBlank()) {
			try {
				delay = Long.parseLong(retryAfter.trim()) * 1000;
			} catch (NumberFormatException e) {
				try {
					var date = ZonedDateTime.parse(retryAfter.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
					delay = Duration.between(clock.instant(), date.toInstant()).toMillis();
				} catch (DateTimeParseException ignored) {
				}
			}
		}
		return Math.max(0, Math.min(delay, MAX_RETRY_AFTER_MS));
	}

	private static String resolveLink(String link) {
		if (link == null || link.isEmpty()) {
			return null;
		}
		return link.startsWith("http") ? link : API_BASE + link;
	}

	private static String stripQuery(String url) {
		var index = url.indexOf('?');
		return index == -1 ? url : url.substring(0, index);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static Transport httpTransport(HttpInterfaceManager httpInterfaceManager) {
		return (url, accessToken) -> {
			var request = new HttpGet(url);
			request.setHeader("Authorization", "Bearer " + accessToken);
			request.setHeader("Accept", "application/vnd.api+json");
			try (var response = httpInterfaceManager.getInterface().execute(request)) {
				var retryAfter = response.getFirstHeader("Retry-After");
				var body = response.getEntity() == null ? "" : IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
				return new Response(response.getStatusLine().getStatusCode(), retryAfter == null ? null : retryAfter.getValue(), body);
			}
		};
	}

	@FunctionalInterface
	interface Transport {
		Response get(String url, String accessToken) throws IOException;
	}

	@FunctionalInterface
	interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}

	static class Response {
		final int statusCode;
		final String retryAfter;
		final String body;

		Response(int statusCode, String retryAfter, String body) {
			this.statusCode = statusCode;
			this.retryAfter = retryAfter;
			this.body = body;
		}
	}
}
