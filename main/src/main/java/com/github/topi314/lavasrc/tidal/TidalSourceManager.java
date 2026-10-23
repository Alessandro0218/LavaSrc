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
	private final TidalApiClient client;
	private int searchLimit = 6;

	public TidalSourceManager(String[] providers, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, String tidalToken) {
		this(countryCode, audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers), tidalToken);
	}

	public TidalSourceManager(String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver, String tidalToken) {
		super(audioPlayerManager, mirroringAudioTrackResolver);
		this.countryCode = (countryCode == null || countryCode.isEmpty()) ? "US" : countryCode;
		if (tidalToken == null || tidalToken.isEmpty()) {
			throw new IllegalArgumentException("Tidal token must be provided");
		}
		this.httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
		this.client = new TidalV1Client(this.httpInterfaceManager, tidalToken, this.countryCode);
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit;
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
		var tracks = this.buildTracks(this.client.search(query, this.searchLimit));
		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}
		return new BasicAudioPlaylist("Tidal Search: " + query, tracks, null, true);
	}

	private AudioItem getRecommendations(String trackId) throws IOException {
		var mixId = this.client.getTrackMixId(trackId);
		if (mixId == null) {
			return AudioReference.NO_TRACK;
		}
		return this.getMix(mixId);
	}

	private AudioItem getAlbumOrPlaylist(String itemId, ExtendedAudioPlaylist.Type type) throws IOException {
		var collection = type == ExtendedAudioPlaylist.Type.PLAYLIST
			? this.client.getPlaylist(itemId, PLAYLIST_MAX_PAGE_ITEMS)
			: this.client.getAlbum(itemId, ALBUM_MAX_PAGE_ITEMS);
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
		var data = this.client.getTrack(trackId);
		if (data == null || data.durationMs <= 0) {
			return AudioReference.NO_TRACK;
		}
		return this.buildTrack(data);
	}

	public AudioItem getMix(String mixId) throws IOException {
		var tracks = this.buildTracks(this.client.getMix(mixId, PLAYLIST_MAX_PAGE_ITEMS));
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
}
