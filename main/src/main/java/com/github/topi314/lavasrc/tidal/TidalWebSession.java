package com.github.topi314.lavasrc.tidal;

/**
 * A TIDAL user session for the private API: the OAuth client the session was issued to and its refresh token.
 * The client secret is only needed by clients that have one; the apps' public clients do not.
 */
public class TidalWebSession {
	public final String clientId;
	public final String clientSecret;
	public final String refreshToken;

	public TidalWebSession(String clientId, String clientSecret, String refreshToken) {
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.refreshToken = refreshToken;
	}

	public boolean isComplete() {
		return isSet(this.clientId) && isSet(this.refreshToken);
	}

	public boolean hasClientSecret() {
		return isSet(this.clientSecret);
	}

	/**
	 * @return a copy with the non-null arguments replacing the current values
	 */
	public TidalWebSession with(String clientId, String clientSecret, String refreshToken) {
		return new TidalWebSession(
			clientId != null ? clientId : this.clientId,
			clientSecret != null ? clientSecret : this.clientSecret,
			refreshToken != null ? refreshToken : this.refreshToken
		);
	}

	private static boolean isSet(String value) {
		return value != null && !value.isBlank();
	}
}
