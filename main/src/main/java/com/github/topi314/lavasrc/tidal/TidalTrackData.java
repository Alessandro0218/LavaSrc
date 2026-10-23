package com.github.topi314.lavasrc.tidal;

/**
 * API-version independent representation of a Tidal track.
 */
public class TidalTrackData {
	public final String id;
	public final String title;
	public final String author;
	public final long durationMs;
	public final String isrc;
	public final String url;
	public final String artworkUrl;
	public final String albumName;
	public final String albumUrl;
	public final String artistUrl;

	public TidalTrackData(String id, String title, String author, long durationMs, String isrc, String url, String artworkUrl, String albumName, String albumUrl, String artistUrl) {
		this.id = id;
		this.title = title;
		this.author = author;
		this.durationMs = durationMs;
		this.isrc = isrc;
		this.url = url;
		this.artworkUrl = artworkUrl;
		this.albumName = albumName;
		this.albumUrl = albumUrl;
		this.artistUrl = artistUrl;
	}
}
