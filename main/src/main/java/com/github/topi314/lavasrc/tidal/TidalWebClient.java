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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Client for the private api.tidal.com API used by TIDAL's own apps, authenticated as a user session.
 * Catalog lookups go to its v1 endpoints (offset paging, up to {@link #PAGE_SIZE} items per request, the server's maximum,
 * {@link #PARALLEL_PAGES} pages at a time), search to its v2 endpoint.
 */
public class TidalWebClient implements TidalApiClient {

	public static final String API_BASE = "https://api.tidal.com";
	static final int PAGE_SIZE = 100;
	static final String LOCALE = "en_US";
	static final String DEVICE_TYPE = "DESKTOP";
	static final int PARALLEL_PAGES = 5;

	private static final Logger log = LoggerFactory.getLogger(TidalWebClient.class);
	// shared by every client, so the pages in flight to TIDAL stay bounded however many playlists load at once
	private static final ExecutorService SHARED_PAGE_POOL = Executors.newFixedThreadPool(PARALLEL_PAGES, runnable -> {
		var thread = new Thread(runnable, "tidal-web-pages");
		thread.setDaemon(true);
		return thread;
	});

	private final ExecutorService pagePool = SHARED_PAGE_POOL;

	private final TidalV2TokenManager tokenManager;
	private final String countryCode;
	private final TidalV2Client.Transport transport;
	private final TidalV2Client.Sleeper sleeper;
	private final Clock clock;

	public TidalWebClient(HttpInterfaceManager httpInterfaceManager, TidalV2TokenManager tokenManager, String countryCode) {
		this(tokenManager, countryCode, httpTransport(httpInterfaceManager), Thread::sleep, Clock.systemUTC());
	}

	TidalWebClient(TidalV2TokenManager tokenManager, String countryCode, TidalV2Client.Transport transport, TidalV2Client.Sleeper sleeper, Clock clock) {
		this.tokenManager = tokenManager;
		this.countryCode = countryCode;
		this.transport = transport;
		this.sleeper = sleeper;
		this.clock = clock;
	}

	@Override
	public TidalApiVersion getVersion() {
		return TidalApiVersion.WEB;
	}

	@Override
	public TidalTrackData getTrack(String trackId) throws IOException {
		var json = this.getJson(API_BASE + "/v1/tracks/" + encode(trackId) + "?countryCode=" + this.countryCode);
		if (json == null) {
			return null;
		}
		return TidalV1Client.parseTrack(json);
	}

	@Override
	public String getTrackMixId(String trackId) throws IOException {
		var json = this.getJson(API_BASE + "/v1/tracks/" + encode(trackId) + "?countryCode=" + this.countryCode);
		if (json == null) {
			return null;
		}
		return json.get("mixes").get("TRACK_MIX").text();
	}

	@Override
	public TidalCollectionData getAlbum(String albumId, int maxItems) throws IOException {
		return this.getCollection("album", albumId, maxItems);
	}

	@Override
	public TidalCollectionData getPlaylist(String playlistId, int maxItems) throws IOException {
		return this.getCollection("playlist", playlistId, maxItems);
	}

	@Override
	public List<TidalTrackData> getMix(String mixId, int maxItems) throws IOException {
		return this.getItems("/v1/mixes/" + encode(mixId) + "/items", maxItems);
	}

	@Override
	public List<TidalTrackData> search(String query, int limit) throws IOException {
		var json = this.getJson(API_BASE + "/v2/search/?query=" + encode(query) + "&limit=" + limit + "&offset=0&types=TRACKS"
			+ "&includeUserPlaylists=false&supportsUserData=false&countryCode=" + this.countryCode + "&locale=" + LOCALE + "&deviceType=" + DEVICE_TYPE);
		if (json == null) {
			return List.of();
		}
		var tracks = parseItems(json.get("tracks").get("items"));
		return tracks.size() > limit ? new ArrayList<>(tracks.subList(0, limit)) : tracks;
	}

	private TidalCollectionData getCollection(String type, String id, int maxItems) throws IOException {
		var info = this.getJson(API_BASE + "/v1/" + type + "s/" + encode(id) + "?countryCode=" + this.countryCode);
		if (info == null) {
			return null;
		}
		return TidalV1Client.parseCollection(info, type, this.getItems("/v1/" + type + "s/" + encode(id) + "/items", maxItems));
	}

	/**
	 * Loads the first page, then the remaining ones (known from {@code totalNumberOfItems}) in parallel, in offset order.
	 */
	private List<TidalTrackData> getItems(String resourcePath, int maxItems) throws IOException {
		var first = this.getPage(resourcePath, 0, Math.min(PAGE_SIZE, maxItems));
		if (first == null || first.get("items").values().isEmpty()) {
			return new ArrayList<>();
		}
		var tracks = new ArrayList<>(parseItems(first.get("items")));
		var step = first.get("items").values().size();
		var end = (int) Math.min(maxItems, first.get("totalNumberOfItems").asLong(0));

		var pages = new ArrayList<Future<JsonBrowser>>();
		for (var offset = step; offset < end; offset += step) {
			var pageOffset = offset;
			var limit = Math.min(step, end - offset);
			pages.add(this.pagePool.submit(() -> this.getPage(resourcePath, pageOffset, limit)));
		}
		try {
			for (var page : pages) {
				var json = page.get();
				if (json == null) {
					break;
				}
				tracks.addAll(parseItems(json.get("items")));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Interrupted while loading Tidal pages");
		} catch (ExecutionException e) {
			if (e.getCause() instanceof IOException) {
				throw (IOException) e.getCause();
			}
			throw new IOException(e.getCause());
		} finally {
			pages.forEach(page -> page.cancel(true));
		}
		return tracks;
	}

	private JsonBrowser getPage(String resourcePath, int offset, int limit) throws IOException {
		return this.getJson(API_BASE + resourcePath + "?countryCode=" + this.countryCode + "&limit=" + limit + "&offset=" + offset);
	}

	/**
	 * Parses a list of tracks, either bare or wrapped as {@code {"type": "track", "item": {...}}}. Videos and unplayable entries are skipped.
	 */
	static List<TidalTrackData> parseItems(JsonBrowser items) {
		var tracks = new ArrayList<TidalTrackData>();
		for (var entry : items.values()) {
			var type = entry.get("type").text();
			if (type != null && !type.equalsIgnoreCase("track")) {
				continue;
			}
			var track = TidalV1Client.parseTrack(entry.get("item").isNull() ? entry : entry.get("item"));
			if (track != null) {
				tracks.add(track);
			}
		}
		return tracks;
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
				log.debug("Tidal web resource not found ({}): {} {}", status, stripQuery(url), response.body);
				return null;
			}
			if (status == 401 && !authRetried) {
				authRetried = true;
				this.tokenManager.invalidate(accessToken);
				continue;
			}
			if (status == 429 && !rateLimitRetried) {
				rateLimitRetried = true;
				var delay = TidalV2Client.retryAfterMillis(response.retryAfter, this.clock);
				log.warn("Tidal web rate limit reached, retrying in {} ms", delay);
				try {
					this.sleeper.sleep(delay);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new InterruptedIOException("Interrupted while waiting for the Tidal web rate limit");
				}
				continue;
			}

			var retryable = status == 401 || status == 403 || status == 429 || status >= 500;
			log.debug("Tidal web request failed ({}): {} {}", status, stripQuery(url), response.body);
			throw new TidalApiException("Tidal web request to " + stripQuery(url) + " failed with HTTP " + status, status, retryable);
		}
	}

	private static String stripQuery(String url) {
		var index = url.indexOf('?');
		return index == -1 ? url : url.substring(0, index);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static TidalV2Client.Transport httpTransport(HttpInterfaceManager httpInterfaceManager) {
		return (url, accessToken) -> {
			var request = new HttpGet(url);
			request.setHeader("Authorization", "Bearer " + accessToken);
			request.setHeader("Accept", "application/json");
			try (var response = httpInterfaceManager.getInterface().execute(request)) {
				var retryAfter = response.getFirstHeader("Retry-After");
				var body = response.getEntity() == null ? "" : IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
				return new TidalV2Client.Response(response.getStatusLine().getStatusCode(), retryAfter == null ? null : retryAfter.getValue(), body);
			}
		};
	}
}
