package com.github.topi314.lavasrc.tidal;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts Tidal v2 JSON:API documents into the common Tidal model.
 * Related resources (artists, albums, artworks) are resolved from the document's {@code included} array.
 */
final class TidalV2Parser {

	static final int TRACK_ARTWORK_SIZE = 1280;
	static final int COLLECTION_ARTWORK_SIZE = 1080;

	private static final String SHARING_LINK_TYPE = "TIDAL_SHARING";
	private static final String BROWSE_URL = "https://tidal.com/browse/";

	private TidalV2Parser() {
	}

	static TidalTrackData parseTrackDocument(JsonBrowser document) {
		return parseTrack(document.get("data"), Included.of(document));
	}

	/**
	 * Parses one page of an album or playlist {@code relationships/items} document. Non-track items (videos) are skipped.
	 */
	static List<TidalTrackData> parseItemsPage(JsonBrowser document) {
		return resolveTracks(document.get("data"), Included.of(document), Integer.MAX_VALUE);
	}

	static List<TidalTrackData> parseSearch(JsonBrowser document, int limit) {
		var searchResult = document.get("data").index(0);
		return resolveTracks(searchResult.get("relationships").get("tracks").get("data"), Included.of(document), limit);
	}

	static TidalCollectionData parseAlbum(JsonBrowser document, List<TidalTrackData> tracks) {
		var album = document.get("data");
		var included = Included.of(document);
		var attributes = album.get("attributes");
		var artists = included.related(album, "artists");
		var author = artists.isEmpty() ? null : artists.get(0).get("attributes").get("name").text();
		return new TidalCollectionData(
			attributes.get("title").text(),
			resourceUrl(album, "album"),
			artworkUrl(included.related(album, "coverArt"), COLLECTION_ARTWORK_SIZE),
			author,
			(int) attributes.get("numberOfItems").asLong(tracks.size()),
			tracks
		);
	}

	static TidalCollectionData parsePlaylist(JsonBrowser document, List<TidalTrackData> tracks) {
		var playlist = document.get("data");
		var included = Included.of(document);
		var attributes = playlist.get("attributes");
		var curators = included.related(playlist, "curators");
		var author = curators.isEmpty() ? null : curators.get(0).get("attributes").get("name").text();
		return new TidalCollectionData(
			attributes.get("name").text(),
			resourceUrl(playlist, "playlist"),
			artworkUrl(included.related(playlist, "coverArt"), COLLECTION_ARTWORK_SIZE),
			author,
			(int) attributes.get("numberOfItems").asLong(tracks.size()),
			tracks
		);
	}

	/**
	 * @return the id of the first playlist in a track's {@code relationships/radio} document
	 */
	static String parseRadioMixId(JsonBrowser document) {
		for (var identifier : document.get("data").values()) {
			if ("playlists".equals(identifier.get("type").text())) {
				return identifier.get("id").text();
			}
		}
		return null;
	}

	static String nextLink(JsonBrowser document) {
		return document.get("links").get("next").text();
	}

	static long parseDurationMs(String isoDuration) {
		if (isoDuration == null || isoDuration.isEmpty()) {
			return 0;
		}
		try {
			return Duration.parse(isoDuration).toMillis();
		} catch (DateTimeParseException e) {
			return 0;
		}
	}

	static TidalTrackData parseTrack(JsonBrowser track, Included included) {
		if (!"tracks".equals(track.get("type").text())) {
			return null;
		}
		var id = track.get("id").text();
		var attributes = track.get("attributes");

		var artists = included.related(track, "artists");
		var author = artists.stream()
			.map(artist -> artist.get("attributes").get("name").text())
			.filter(name -> name != null && !name.isEmpty())
			.collect(Collectors.joining(", "));
		var artistUrl = artists.isEmpty() ? null : resourceUrl(artists.get(0), "artist");

		var albums = included.related(track, "albums");
		String albumName = null;
		String albumUrl = null;
		String artworkUrl = null;
		if (!albums.isEmpty()) {
			var album = albums.get(0);
			albumName = album.get("attributes").get("title").text();
			albumUrl = resourceUrl(album, "album");
			artworkUrl = artworkUrl(included.related(album, "coverArt"), TRACK_ARTWORK_SIZE);
		}

		return new TidalTrackData(
			id,
			attributes.get("title").text(),
			author.isEmpty() ? "unknown" : author,
			parseDurationMs(attributes.get("duration").text()),
			attributes.get("isrc").text(),
			resourceUrl(track, "track"),
			artworkUrl == null ? TidalV1Client.DEFAULT_ARTWORK_URL : artworkUrl,
			albumName,
			albumUrl,
			artistUrl
		);
	}

	/**
	 * Picks the artwork file matching the preferred width, or the largest one available.
	 */
	static String artworkUrl(List<JsonBrowser> artworks, int preferredSize) {
		String bestUrl = null;
		long bestWidth = -1;
		for (var artwork : artworks) {
			for (var file : artwork.get("attributes").get("files").values()) {
				var href = file.get("href").text();
				if (href == null) {
					continue;
				}
				var width = file.get("meta").get("width").asLong(0);
				if (width == preferredSize) {
					return href;
				}
				if (width > bestWidth) {
					bestWidth = width;
					bestUrl = href;
				}
			}
		}
		return bestUrl;
	}

	private static String resourceUrl(JsonBrowser resource, String browseType) {
		for (var link : resource.get("attributes").get("externalLinks").values()) {
			if (SHARING_LINK_TYPE.equals(link.get("meta").get("type").text())) {
				var href = link.get("href").text();
				if (href != null) {
					return href;
				}
			}
		}
		var id = resource.get("id").text();
		return id == null ? null : BROWSE_URL + browseType + "/" + id;
	}

	private static List<TidalTrackData> resolveTracks(JsonBrowser identifiers, Included included, int limit) {
		var tracks = new ArrayList<TidalTrackData>();
		for (var identifier : identifiers.values()) {
			if (tracks.size() >= limit) {
				break;
			}
			var resource = included.get(identifier);
			if (resource == null) {
				continue;
			}
			var track = parseTrack(resource, included);
			if (track != null) {
				tracks.add(track);
			}
		}
		return tracks;
	}

	static final class Included {
		private final Map<String, JsonBrowser> resources = new HashMap<>();

		static Included of(JsonBrowser document) {
			var included = new Included();
			for (var resource : document.get("included").values()) {
				included.resources.put(key(resource), resource);
			}
			return included;
		}

		JsonBrowser get(JsonBrowser identifier) {
			return this.resources.get(key(identifier));
		}

		List<JsonBrowser> related(JsonBrowser resource, String relationship) {
			var related = new ArrayList<JsonBrowser>();
			for (var identifier : resource.get("relationships").get(relationship).get("data").values()) {
				var included = this.get(identifier);
				if (included != null) {
					related.add(included);
				}
			}
			return related;
		}

		private static String key(JsonBrowser resource) {
			return resource.get("type").text() + ":" + resource.get("id").text();
		}
	}
}
