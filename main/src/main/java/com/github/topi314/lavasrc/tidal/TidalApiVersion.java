package com.github.topi314.lavasrc.tidal;

public enum TidalApiVersion {
	/**
	 * Use v2 when clientId and clientSecret are set, otherwise v1 when a token is set.
	 */
	AUTO,
	/**
	 * The unofficial api.tidal.com/v1 API, authenticated with a static token.
	 */
	V1,
	/**
	 * The official openapi.tidal.com/v2 API, authenticated with OAuth 2.0 client credentials.
	 */
	V2;

	public static TidalApiVersion fromString(String value) {
		if (value == null || value.isBlank()) {
			return AUTO;
		}
		for (var version : values()) {
			if (version.name().equalsIgnoreCase(value.trim())) {
				return version;
			}
		}
		throw new IllegalArgumentException("Invalid Tidal apiVersion '" + value + "', expected one of: auto, v1, v2");
	}
}
