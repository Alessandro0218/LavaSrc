package com.github.topi314.lavasrc.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.lavasrc.tidal")
@Component
public class TidalConfig {
	private String countryCode;
	private int searchLimit;
	private String token;
	private String clientId;
	private String clientSecret;
	private String webClientId;
	private String webClientSecret;
	private String refreshToken;
	private String apiVersion = "auto";
	private int playlistLoadLimit;

	public String getCountryCode() {
		return this.countryCode;
	}

	public void setCountryCode(String countryCode) {
		this.countryCode = countryCode;
	}

	public int getSearchLimit() {
		return this.searchLimit;
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit;
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	public String getClientId() {
		return this.clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public String getClientSecret() {
		return this.clientSecret;
	}

	public void setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
	}

	public String getWebClientId() {
		return this.webClientId;
	}

	public void setWebClientId(String webClientId) {
		this.webClientId = webClientId;
	}

	public String getWebClientSecret() {
		return this.webClientSecret;
	}

	public void setWebClientSecret(String webClientSecret) {
		this.webClientSecret = webClientSecret;
	}

	public String getRefreshToken() {
		return this.refreshToken;
	}

	public void setRefreshToken(String refreshToken) {
		this.refreshToken = refreshToken;
	}

	public int getPlaylistLoadLimit() {
		return this.playlistLoadLimit;
	}

	public void setPlaylistLoadLimit(int playlistLoadLimit) {
		this.playlistLoadLimit = playlistLoadLimit;
	}

	public String getApiVersion() {
		return this.apiVersion;
	}

	public void setApiVersion(String apiVersion) {
		this.apiVersion = apiVersion;
	}
}
