package com.github.topi314.lavasrc.tidal;

import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

public class TidalSourceManager extends MirroringAudioSourceManager implements HttpConfigurable {

	public static final Pattern URL_PATTERN = Pattern.compile("https?://(?:(?:listen|www)\\.)?tidal\\.com/(?:browse/)?(?<type>album|track|playlist|mix)/(?<id>[a-zA-Z0-9\\-]+)(?:/.*)?(?:\\?.*)?");
	public static final String SEARCH_PREFIX = "tdsearch:";
	public static final String RECOMMENDATIONS_PREFIX = "tdrec:";
	public static final String PUBLIC_API_BASE = TidalV1Client.PUBLIC_API_BASE;
	public static final int PLAYLIST_MAX_PAGE_ITEMS = 750;
	public static final int ALBUM_MAX_PAGE_ITEMS = 120;

	private static final Logger log = LoggerFactory.getLogger(TidalSourceManager.class);

	private final HttpInterfaceManager httpInterfaceManager;
	private final String countryCode;
	private volatile Clients clients;
	private String token;
	private String clientId;
	private String clientSecret;
	private TidalWebSession webSession;
	private TidalApiVersion apiVersion;
	private int searchLimit = 6;
	private int playlistLoadLimit = PLAYLIST_MAX_PAGE_ITEMS;

	/**
	 * @deprecated use {@link #TidalSourceManager(String[], String, Function, String, String, String, TidalApiVersion)}
	 */
	@Deprecated
	public TidalSourceManager(String[] providers, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, String tidalToken) {
		this(countryCode, audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers), tidalToken);
	}

	/**
	 * Creates a source manager using the unofficial Tidal API v1.
	 *
	 * @deprecated use {@link #TidalSourceManager(String, Function, MirroringAudioTrackResolver, String, String, String, TidalApiVersion)}
	 */
	@Deprecated
	public TidalSourceManager(String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver, String tidalToken) {
		this(countryCode, audioPlayerManager, mirroringAudioTrackResolver, requireToken(tidalToken), null, null, null, TidalApiVersion.V1);
	}

	public TidalSourceManager(String[] providers, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, String tidalToken, String clientId, String clientSecret, TidalApiVersion apiVersion) {
		this(countryCode, audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers), tidalToken, clientId, clientSecret, apiVersion);
	}

	public TidalSourceManager(String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver, String tidalToken, String clientId, String clientSecret, TidalApiVersion apiVersion) {
		this(countryCode, audioPlayerManager, mirroringAudioTrackResolver, tidalToken, clientId, clientSecret, null, apiVersion);
	}

	public TidalSourceManager(String[] providers, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, String tidalToken, String clientId, String clientSecret, TidalWebSession webSession, TidalApiVersion apiVersion) {
		this(countryCode, audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers), tidalToken, clientId, clientSecret, webSession, apiVersion);
	}

	/**
	 * @param tidalToken   token for the unofficial API v1, may be null when clientId and clientSecret are set
	 * @param clientId     client id for the official API v2 (developer.tidal.com), may be null when a token is set
	 * @param clientSecret client secret for the official API v2, may be null when a token is set
	 * @param webSession   user session for the private API, may be null
	 * @param apiVersion   which API to use, {@link TidalApiVersion#AUTO} prefers web, then v2, and falls back to the next configured one
	 */
	public TidalSourceManager(String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver, String tidalToken, String clientId, String clientSecret, TidalWebSession webSession, TidalApiVersion apiVersion) {
		super(audioPlayerManager, mirroringAudioTrackResolver);
		this.countryCode = (countryCode == null || countryCode.isEmpty()) ? "US" : countryCode;
		this.httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
		this.update(tidalToken, clientId, clientSecret, webSession, apiVersion == null ? TidalApiVersion.AUTO : apiVersion);
	}

	// visible for testing
	TidalSourceManager(TidalApiClient primary, TidalApiClient fallback) {
		super((Function<Void, AudioPlayerManager>) unused -> null, null);
		this.countryCode = "US";
		this.httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
		this.clients = new Clients(primary, fallback);
	}

	private static String requireToken(String tidalToken) {
		if (tidalToken == null || tidalToken.isEmpty()) {
			throw new IllegalArgumentException("Tidal token must be provided");
		}
		return tidalToken;
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit;
	}

	/**
	 * Maximum number of tracks loaded from a playlist or mix.
	 */
	public void setPlaylistLoadLimit(int playlistLoadLimit) {
		this.playlistLoadLimit = playlistLoadLimit;
	}

	public synchronized void setToken(String token) {
		this.update(token, this.clientId, this.clientSecret, this.webSession, this.apiVersion);
	}

	public synchronized void setClientIdSecret(String clientId, String clientSecret) {
		this.update(this.token, clientId, clientSecret, this.webSession, this.apiVersion);
	}

	/**
	 * Updates the web session; null arguments keep their current value, so a new refresh token can be set on its own.
	 */
	public synchronized void setWebSession(String clientId, String clientSecret, String refreshToken) {
		var current = this.webSession == null ? new TidalWebSession(null, null, null) : this.webSession;
		this.update(this.token, this.clientId, this.clientSecret, current.with(clientId, clientSecret, refreshToken), this.apiVersion);
	}

	public synchronized void setApiVersion(TidalApiVersion apiVersion) {
		this.update(this.token, this.clientId, this.clientSecret, this.webSession, apiVersion);
	}

	/**
	 * @return the API version requests are sent to first
	 */
	public TidalApiVersion getActiveApiVersion() {
		return this.clients.primary.getVersion();
	}

	/**
	 * @return the API version a failed request is retried on, or {@code null} if there is none
	 */
	public TidalApiVersion getFallbackApiVersion() {
		var fallback = this.clients.fallback;
		return fallback == null ? null : fallback.getVersion();
	}

	boolean hasV1Fallback() {
		return this.getFallbackApiVersion() == TidalApiVersion.V1;
	}

	private synchronized void update(String token, String clientId, String clientSecret, TidalWebSession webSession, TidalApiVersion apiVersion) {
		// build the new clients first, so an invalid configuration leaves the current one in place
		var clients = this.createClients(token, clientId, clientSecret, webSession, apiVersion);
		this.clients = clients;
		this.token = token;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.webSession = webSession;
		this.apiVersion = apiVersion;
		log.info("Using Tidal API {}{}", versionName(clients.primary), clients.fallback != null ? " with fallback to " + versionName(clients.fallback) : "");
	}

	private static String versionName(TidalApiClient client) {
		return client.getVersion().name().toLowerCase();
	}

	private Clients createClients(String token, String clientId, String clientSecret, TidalWebSession webSession, TidalApiVersion apiVersion) {
		var hasToken = token != null && !token.isBlank();
		var hasClientCredentials = clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
		var hasWebSession = webSession != null && webSession.isComplete();

		if (apiVersion == TidalApiVersion.WEB || (apiVersion == TidalApiVersion.AUTO && hasWebSession)) {
			if (hasWebSession) {
				TidalApiClient fallback = null;
				if (hasClientCredentials) {
					fallback = this.createV2Client(clientId, clientSecret);
				} else if (hasToken) {
					fallback = this.createV1Client(token);
				}
				return new Clients(this.createWebClient(webSession), fallback);
			}
			// the session may be minted only after the node is up, so start on v2 instead of failing
			if (hasClientCredentials) {
				log.warn("Tidal apiVersion is web but no web session (webClientId + refreshToken) is configured, using API v2 until one is set");
				return new Clients(this.createV2Client(clientId, clientSecret), null);
			}
			throw new IllegalArgumentException("Tidal apiVersion is web but no web session (webClientId + refreshToken) is configured");
		}

		switch (apiVersion) {
			case V1:
				if (!hasToken) {
					throw new IllegalArgumentException("Tidal apiVersion is v1 but no token is configured");
				}
				return new Clients(this.createV1Client(token), null);
			case V2:
				if (!hasClientCredentials) {
					throw new IllegalArgumentException("Tidal apiVersion is v2 but clientId and clientSecret are not configured");
				}
				return new Clients(this.createV2Client(clientId, clientSecret), null);
			default:
				if (hasClientCredentials) {
					return new Clients(this.createV2Client(clientId, clientSecret), hasToken ? this.createV1Client(token) : null);
				}
				if (hasToken) {
					if ((clientId != null && !clientId.isBlank()) || (clientSecret != null && !clientSecret.isBlank())) {
						log.warn("Tidal clientId and clientSecret must both be set to use API v2, using API v1");
					}
					return new Clients(this.createV1Client(token), null);
				}
				throw new IllegalArgumentException("Tidal requires either clientId and clientSecret (API v2) or a token (API v1)");
		}
	}

	private TidalApiClient createV1Client(String token) {
		return new TidalV1Client(this.httpInterfaceManager, token, this.countryCode);
	}

	private TidalApiClient createV2Client(String clientId, String clientSecret) {
		return new TidalV2Client(this.httpInterfaceManager, new TidalV2TokenManager(this.httpInterfaceManager, clientId, clientSecret), this.countryCode);
	}

	private TidalApiClient createWebClient(TidalWebSession webSession) {
		return new TidalWebClient(this.httpInterfaceManager, TidalV2TokenManager.forWebSession(this.httpInterfaceManager, webSession), this.countryCode);
	}

	/**
	 * Runs the call against the primary client. When it fails for a reason unrelated to the resource
	 * (server error, authentication, rate limit, network) and a fallback is configured, the call is retried there.
	 */
	private <T> T call(ApiCall<T> call) throws IOException {
		var clients = this.clients;
		try {
			return call.apply(clients.primary);
		} catch (IOException e) {
			if (clients.fallback == null || !isRetryable(e)) {
				throw e;
			}
			log.warn("Tidal API {} request failed ({}), retrying with API {}", versionName(clients.primary), e.getMessage(), versionName(clients.fallback));
			return call.apply(clients.fallback);
		}
	}

	static boolean isRetryable(IOException e) {
		if (e instanceof TidalApiException) {
			return ((TidalApiException) e).isRetryable();
		}
		return !(e instanceof InterruptedIOException);
	}

	@Override
	public String getSourceName() {
		return "tidal";
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		var extendedAudioTrackInfo = super.decodeTrack(input);
		return new TidalAudioTrack(trackInfo, extendedAudioTrackInfo.albumName, extendedAudioTrackInfo.albumUrl, extendedAudioTrackInfo.artistUrl, extendedAudioTrackInfo.previewUrl, this);
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		try {
			var matcher = URL_PATTERN.matcher(reference.identifier);
			if (matcher.matches()) {
				var type = matcher.group("type");
				var id = matcher.group("id");

				switch (type) {
					case "album":
						return this.getAlbumOrPlaylist(id, ExtendedAudioPlaylist.Type.ALBUM);
					case "mix":
						return this.getMix(id);
					case "track":
						return this.getTrack(id);
					case "playlist":
						return this.getAlbumOrPlaylist(id, ExtendedAudioPlaylist.Type.PLAYLIST);
					default:
						return null;
				}
			}

			if (reference.identifier.startsWith(SEARCH_PREFIX)) {
				var query = reference.identifier.substring(SEARCH_PREFIX.length());
				if (query.isEmpty()) {
					throw new IllegalArgumentException("No query provided for search");
				}
				return this.getSearch(query);
			}

			if (reference.identifier.startsWith(RECOMMENDATIONS_PREFIX)) {
				var trackId = reference.identifier.substring(RECOMMENDATIONS_PREFIX.length());
				if (trackId.isEmpty()) {
					throw new IllegalArgumentException("No track ID provided for recommendations");
				}
				return this.getRecommendations(trackId);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	private AudioItem getSearch(String query) throws IOException {
		var tracks = this.buildTracks(this.call(client -> client.search(query, this.searchLimit)));
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}
		return new BasicAudioPlaylist("Tidal Search: " + query, tracks, null, true);
	}

	private AudioItem getRecommendations(String trackId) throws IOException {
		var mixId = this.call(client -> client.getTrackMixId(trackId));
		if (mixId == null) {
			return AudioReference.NO_TRACK;
		}
		return this.getMix(mixId);
	}

	private AudioItem getAlbumOrPlaylist(String itemId, ExtendedAudioPlaylist.Type type) throws IOException {
		var collection = this.call(client -> type == ExtendedAudioPlaylist.Type.PLAYLIST
			? client.getPlaylist(itemId, this.playlistLoadLimit)
			: client.getAlbum(itemId, ALBUM_MAX_PAGE_ITEMS));
		if (collection == null || collection.name == null || collection.url == null) {
			return AudioReference.NO_TRACK;
		}

		var tracks = this.buildTracks(collection.tracks);
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}
		return new TidalAudioPlaylist(collection.name, tracks, type, collection.url, collection.artworkUrl, collection.author, collection.totalTracks);
	}

	public AudioItem getTrack(String trackId) throws IOException {
		var data = this.call(client -> client.getTrack(trackId));
		if (data == null || data.durationMs <= 0) {
			return AudioReference.NO_TRACK;
		}
		return this.buildTrack(data);
	}

	public AudioItem getMix(String mixId) throws IOException {
		var tracks = this.buildTracks(this.call(client -> client.getMix(mixId, this.playlistLoadLimit)));
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}
		return new BasicAudioPlaylist("Mix: " + mixId, tracks, null, false);
	}

	private List<AudioTrack> buildTracks(List<TidalTrackData> data) {
		var tracks = new ArrayList<AudioTrack>();
		for (var track : data) {
			if (track.durationMs > 0) {
				tracks.add(this.buildTrack(track));
			}
		}
		return tracks;
	}

	private AudioTrack buildTrack(TidalTrackData data) {
		return new TidalAudioTrack(
			new AudioTrackInfo(data.title, data.author, data.durationMs, data.id, false, data.url, data.artworkUrl, data.isrc),
			data.albumName,
			data.albumUrl,
			data.artistUrl,
			null,
			this
		);
	}

	@Override
	public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
		httpInterfaceManager.configureRequests(configurator);
	}

	@Override
	public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
		httpInterfaceManager.configureBuilder(configurator);
	}

	@Override
	public void shutdown() {
		try {
			httpInterfaceManager.close();
		} catch (IOException e) {
			log.error("Failed to close HTTP interface manager", e);
		}
	}

	public HttpInterface getHttpInterface() {
		return httpInterfaceManager.getInterface();
	}

	@FunctionalInterface
	private interface ApiCall<T> {
		T apply(TidalApiClient client) throws IOException;
	}

	private static final class Clients {
		final TidalApiClient primary;
		final TidalApiClient fallback;

		Clients(TidalApiClient primary, TidalApiClient fallback) {
			this.primary = primary;
			this.fallback = fallback;
		}
	}
}
