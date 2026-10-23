package com.github.topi314.lavasrc.tidal;

import java.util.List;

/**
 * API-version independent representation of a Tidal album or playlist.
 */
public class TidalCollectionData {
	public final String name;
	public final String url;
	public final String artworkUrl;
	public final String author;
	public final int totalTracks;
	public final List<TidalTrackData> tracks;

	public TidalCollectionData(String name, String url, String artworkUrl, String author, int totalTracks, List<TidalTrackData> tracks) {
		this.name = name;
		this.url = url;
		this.artworkUrl = artworkUrl;
		this.author = author;
		this.totalTracks = totalTracks;
		this.tracks = tracks;
	}
}
