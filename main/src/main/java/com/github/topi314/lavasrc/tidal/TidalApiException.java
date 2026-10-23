package com.github.topi314.lavasrc.tidal;

import java.io.IOException;

/**
 * Error returned by the Tidal API. Retryable errors (server errors, authentication, rate limiting)
 * are unrelated to the requested resource and may succeed on another API version.
 */
public class TidalApiException extends IOException {

	private final int statusCode;
	private final boolean retryable;

	public TidalApiException(String message, int statusCode, boolean retryable) {
		super(message);
		this.statusCode = statusCode;
		this.retryable = retryable;
	}

	public int getStatusCode() {
		return this.statusCode;
	}

	public boolean isRetryable() {
		return this.retryable;
	}
}
