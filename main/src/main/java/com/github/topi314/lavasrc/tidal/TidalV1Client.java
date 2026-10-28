package com.github.topi314.lavasrc.tidal;

import com.github.topi314.lavasrc.LavaSrcTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import org.apache.http.client.methods.HttpGet;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Client for the unofficial api.tidal.com/v1 API, authenticated with a static {@code x-tidal-token}.
 */
public class TidalV1Client implements TidalApiClient {

	public static final String PUBLIC_API_BASE = "https://api.tidal.com/v1/";
	static final String DEFAULT_ARTWORK_URL = "https://tidal.com/_nuxt/img/logos.d8ce10b.jpg";

	private static final String USER_AGENT = "TIDAL/3704 CFNetwork/1220.1 Darwin/20.3.0";

	private final HttpInterfaceManager httpInterfaceManager;
	private final String tidalToken;
	private final String countryCode;

	public TidalV1Client(HttpInterfaceManager httpInterfaceManager, String tidalToken, String countryCode) {
		this.httpInterfaceManager = httpInterfaceManager;
		this.tidalToken = tidalToken;
		this.countryCode = countryCode;
	}

	@Override
	public TidalApiVersion getVersion() {
		return TidalApiVersion.V1;
	}

	private JsonBrowser getJson(String uri) throws IOException {
		var request = new HttpGet(uri);
		request.setHeader("user-agent", USER_AGENT);
		request.setHeader("x-tidal-token", this.tidalToken);
		return LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
	}

	@Override
	public List<TidalTrackData> search(String query, int limit) throws IOException {
		var json = this.getJson(PUBLIC_API_BASE + "search?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&offset=0&limit=" + limit + "&countryCode=" + countryCode);
		if (json == null || json.get("tracks").get("items").isNull()) {
			return List.of();
		}
		return parseTracks(json.get("tracks").get("items"));
	}

	@Override
	public String getTrackMixId(String trackId) throws IOException {
		var json = this.getJson(PUBLIC_API_BASE + "tracks/" + trackId + "?countryCode=" + countryCode);
		if (json == null || json.isNull()) {
			return null;
		}
		return json.get("mixes").get("TRACK_MIX").text();
	}

	@Override
	public TidalTrackData getTrack(String trackId) throws IOException {
		var json = this.getJson(PUBLIC_API_BASE + "tracks/" + trackId + "?countryCode=" + countryCode);
		if (json == null || json.isNull()) {
			return null;
		}
		return parseTrack(json);
	}

	@Override
	public TidalCollectionData getAlbum(String albumId, int maxItems) throws IOException {
		return this.getAlbumOrPlaylist(albumId, "album", maxItems);
	}

	@Override
	public TidalCollectionData getPlaylist(String playlistId, int maxItems) throws IOException {
		return this.getAlbumOrPlaylist(playlistId, "playlist", maxItems);
	}

	@Override
	public List<TidalTrackData> getMix(String mixId, int maxItems) throws IOException {
		var json = this.getJson(PUBLIC_API_BASE + "mixes/" + mixId + "/items?countryCode=" + countryCode);
		if (json == null || json.get("items").isNull()) {
			return List.of();
		}
		return parseTrackItems(json);
	}

	private TidalCollectionData getAlbumOrPlaylist(String itemId, String type, int maxPageItems) throws IOException {
		var json = this.getJson(PUBLIC_API_BASE + type + "s/" + itemId + "/tracks?countryCode=" + countryCode + "&limit=" + maxPageItems);
		if (json == null || json.get("items").isNull()) {
			return null;
		}

		var items = parseTrackItems(json);
		if (items.isEmpty()) {
			return null;
		}

		var itemInfoJson = this.getJson(PUBLIC_API_BASE + type + "s/" + itemId + "?countryCode=" + countryCode);
		if (itemInfoJson == null) {
			return null;
		}
		return parseCollection(itemInfoJson, type, items);
	}

	/**
	 * @param type {@code album} or {@code playlist}
	 */
	static TidalCollectionData parseCollection(JsonBrowser itemInfoJson, String type, List<TidalTrackData> items) {
		String coverIdentifier;
		String artistName;
		if (type.equals("playlist")) {
			coverIdentifier = itemInfoJson.get("squareImage").text();
			artistName = itemInfoJson.get("promotedArtists").index(0).get("name").text();
		} else {
			coverIdentifier = itemInfoJson.get("cover").text();
			artistName = itemInfoJson.get("artists").index(0).get("name").text();
		}
		var title = itemInfoJson.get("title").text();
		var url = itemInfoJson.get("url").text();
		if (title == null || url == null) {
			return null;
		}
		var artworkUrl = coverIdentifier == null ? null : "https://resources.tidal.com/images/" + coverIdentifier.replaceAll("-", "/") + "/1080x1080.jpg";
		return new TidalCollectionData(title, url, artworkUrl, artistName, (int) itemInfoJson.get("numberOfTracks").asLong(0), items);
	}

	static List<TidalTrackData> parseTracks(JsonBrowser json) {
		var tracks = new ArrayList<TidalTrackData>();
		for (var audio : json.values()) {
			var parsedTrack = parseTrack(audio);
			if (parsedTrack != null) {
				tracks.add(parsedTrack);
			}
		}
		return tracks;
	}

	static List<TidalTrackData> parseTrackItems(JsonBrowser json) {
		var tracks = new ArrayList<TidalTrackData>();
		for (var audio : json.get("items").values()) {
			var parsedTrack = parseTrack(audio.get("item").isNull() ? audio : audio.get("item"));
			if (parsedTrack != null) {
				tracks.add(parsedTrack);
			}
		}
		return tracks;
	}

	static TidalTrackData parseTrack(JsonBrowser audio) {
		var id = audio.get("id").text();
		var duration = audio.get("duration").asLong(0) * 1000;
		if (duration == 0) {
			return null;
		}
		var title = audio.get("title").text();
		var originalUrl = audio.get("url").text();
		var artistName = audio.get("artists").values().stream().map(artist -> artist.get("name").text()).collect(Collectors.joining(", "));

		var coverIdentifier = audio.get("album").get("cover").text();
		String artworkUrl;
		if (coverIdentifier == null) {
			artworkUrl = DEFAULT_ARTWORK_URL;
		} else {
			artworkUrl = "https://resources.tidal.com/images/" + coverIdentifier.replaceAll("-", "/") + "/1280x1280.jpg";
		}
		var isrc = audio.get("isrc").text();
		return new TidalTrackData(id, title, artistName, duration, isrc, originalUrl, artworkUrl, null, null, null);
	}
}
