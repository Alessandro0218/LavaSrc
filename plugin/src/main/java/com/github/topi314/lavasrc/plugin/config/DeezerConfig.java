package com.github.topi314.lavasrc.plugin.config;

import com.github.topi314.lavasrc.deezer.DeezerAudioTrack;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.lavasrc.deezer")
@Component
public class DeezerConfig {

	private String masterDecryptionKey;
	private String arl;
	private DeezerAudioTrack.TrackFormat[] formats;
	@Nullable
	private HttpProxyConfig proxy;
	// Only these hosts go through the proxy (e.g. the API hosts a CDN blocks by
	// IP); everything else, the audio downloads included, stays direct. Empty:
	// every request is proxied.
	@Nullable
	private String[] proxyHosts;

	public String getMasterDecryptionKey() {
		return this.masterDecryptionKey;
	}

	public void setMasterDecryptionKey(String masterDecryptionKey) {
		this.masterDecryptionKey = masterDecryptionKey;
	}

	public String getArl() {
		return this.arl;
	}

	public void setArl(String arl) {
		this.arl = arl;
	}

	public DeezerAudioTrack.TrackFormat[] getFormats() {
		return this.formats;
	}

	public void setFormats(DeezerAudioTrack.TrackFormat[] formats) {
		this.formats = formats;
	}

	public @Nullable HttpProxyConfig getProxy() {
		return this.proxy;
	}

	public void setProxy(@Nullable HttpProxyConfig proxy) {
		this.proxy = proxy;
	}

	public @Nullable String[] getProxyHosts() {
		return this.proxyHosts;
	}

	public void setProxyHosts(@Nullable String[] proxyHosts) {
		this.proxyHosts = proxyHosts;
	}

}
