package com.github.topi314.lavasrc.tidal;

import java.io.IOException;
import java.util.List;

/**
 * Metadata client for one version of the Tidal API.
 * Methods return {@code null} (or an empty list) when the requested resource does not exist.
 */
public interface TidalApiClient {

	TidalApiVersion getVersion();

	TidalTrackData getTrack(String trackId) throws IOException;

	TidalCollectionData getAlbum(String albumId, int maxItems) throws IOException;

	TidalCollectionData getPlaylist(String playlistId, int maxItems) throws IOException;

	List<TidalTrackData> getMix(String mixId, int maxItems) throws IOException;

	List<TidalTrackData> search(String query, int limit) throws IOException;

	/**
	 * @return the id of the track radio mix for the given track, or {@code null} if there is none
	 */
	String getTrackMixId(String trackId) throws IOException;
}
