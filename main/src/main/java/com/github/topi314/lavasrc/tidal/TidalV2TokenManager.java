package com.github.topi314.lavasrc.tidal;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Caches the OAuth 2.0 access token for the Tidal v2 API (client credentials) or a web session (refresh token).
 * The token is renewed shortly before it expires; concurrent callers share a single refresh.
 * Neither the token nor the client secret are ever logged.
 */
public class TidalV2TokenManager {

	public static final String TOKEN_URL = "https://auth.tidal.com/v1/oauth2/token";
	static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

	private static final Logger log = LoggerFactory.getLogger(TidalV2TokenManager.class);

	private final TokenFetcher fetcher;
	private final Clock clock;
	private final Object lock = new Object();
	private volatile CachedToken cachedToken;

	public TidalV2TokenManager(HttpInterfaceManager httpInterfaceManager, String clientId, String clientSecret) {
		this(httpFetcher(httpInterfaceManager, clientId, clientSecret), Clock.systemUTC());
	}

	TidalV2TokenManager(TokenFetcher fetcher, Clock clock) {
		this.fetcher = fetcher;
		this.clock = clock;
	}

	public String getAccessToken() throws IOException {
		var cached = this.cachedToken;
		if (cached != null && this.clock.instant().isBefore(cached.refreshAt)) {
			return cached.value;
		}
		synchronized (this.lock) {
			cached = this.cachedToken;
			if (cached != null && this.clock.instant().isBefore(cached.refreshAt)) {
				return cached.value;
			}
			log.debug("Tidal v2 access token is missing or about to expire, requesting a new one...");
			var token = this.fetcher.fetch();
			this.cachedToken = new CachedToken(token.value, this.clock.instant().plus(refreshDelay(token.expiresInSeconds)));
			return token.value;
		}
	}

	/**
	 * Drops the given token, so the next call requests a new one. A token that has already been replaced is left alone.
	 */
	public void invalidate(String token) {
		synchronized (this.lock) {
			var cached = this.cachedToken;
			if (cached != null && cached.value.equals(token)) {
				this.cachedToken = null;
			}
		}
	}

	static Duration refreshDelay(long expiresInSeconds) {
		var expiresIn = Duration.ofSeconds(Math.max(expiresInSeconds, 0));
		var margin = REFRESH_MARGIN.compareTo(expiresIn.dividedBy(2)) < 0 ? REFRESH_MARGIN : expiresIn.dividedBy(2);
		return expiresIn.minus(margin);
	}

	/**
	 * Creates a token manager that renews the access token of a user session (private API) with its refresh token.
	 * The refresh token itself is never rotated here: TIDAL keeps it valid until the session is revoked.
	 */
	public static TidalV2TokenManager forWebSession(HttpInterfaceManager httpInterfaceManager, TidalWebSession session) {
		return new TidalV2TokenManager(() -> requestToken(httpInterfaceManager, null, refreshTokenParams(session), "web session"), Clock.systemUTC());
	}

	static List<BasicNameValuePair> refreshTokenParams(TidalWebSession session) {
		var params = new ArrayList<BasicNameValuePair>();
		params.add(new BasicNameValuePair("grant_type", "refresh_token"));
		params.add(new BasicNameValuePair("refresh_token", session.refreshToken));
		params.add(new BasicNameValuePair("client_id", session.clientId));
		if (session.hasClientSecret()) {
			params.add(new BasicNameValuePair("client_secret", session.clientSecret));
		}
		return params;
	}

	private static TokenFetcher httpFetcher(HttpInterfaceManager httpInterfaceManager, String clientId, String clientSecret) {
		var basicAuth = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
		return () -> requestToken(httpInterfaceManager, basicAuth, List.of(new BasicNameValuePair("grant_type", "client_credentials")), "v2");
	}

	private static AccessToken requestToken(HttpInterfaceManager httpInterfaceManager, String basicAuth, List<BasicNameValuePair> params, String label) throws IOException {
		var request = new HttpPost(TOKEN_URL);
		if (basicAuth != null) {
			request.setHeader("Authorization", "Basic " + basicAuth);
		}
		request.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));
		try (var response = httpInterfaceManager.getInterface().execute(request)) {
			var statusCode = response.getStatusLine().getStatusCode();
			var body = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
			if (statusCode != 200) {
				var error = errorCode(body);
				throw new TidalApiException("Failed to obtain Tidal " + label + " access token: HTTP " + statusCode + (error == null ? "" : " (" + error + ")"), statusCode, true);
			}
			var json = JsonBrowser.parse(body);
			var accessToken = json.get("access_token").text();
			if (accessToken == null) {
				throw new TidalApiException("Tidal " + label + " token response did not contain an access token", statusCode, true);
			}
			return new AccessToken(accessToken, json.get("expires_in").asLong(0));
		}
	}

	// only the OAuth error code (e.g. "invalid_client") is surfaced, never the rest of the body
	private static String errorCode(String body) {
		try {
			return body.isEmpty() ? null : JsonBrowser.parse(body).get("error").text();
		} catch (IOException e) {
			return null;
		}
	}

	@FunctionalInterface
	interface TokenFetcher {
		AccessToken fetch() throws IOException;
	}

	static class AccessToken {
		final String value;
		final long expiresInSeconds;

		AccessToken(String value, long expiresInSeconds) {
			this.value = value;
			this.expiresInSeconds = expiresInSeconds;
		}
	}

	private static class CachedToken {
		final String value;
		final Instant refreshAt;

		CachedToken(String value, Instant refreshAt) {
			this.value = value;
			this.refreshAt = refreshAt;
		}
	}
}
