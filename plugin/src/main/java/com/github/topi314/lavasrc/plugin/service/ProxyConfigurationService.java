package com.github.topi314.lavasrc.plugin.service;

import com.github.topi314.lavasrc.plugin.config.HttpProxyConfig;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.conn.DefaultRoutePlanner;
import org.apache.http.protocol.HttpContext;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProxyConfigurationService {

	private static final Logger log = LoggerFactory.getLogger(ProxyConfigurationService.class);

	public void configure(HttpConfigurable httpConfigurable, HttpProxyConfig proxyConfig) {
		configure(httpConfigurable, proxyConfig, null);
	}

	/**
	 * Same as {@link #configure(HttpConfigurable, HttpProxyConfig)}, but with
	 * {@code hosts} set only requests to those hosts go through the proxy.
	 */
	public void configure(HttpConfigurable httpConfigurable, HttpProxyConfig proxyConfig, @Nullable String[] hosts) {
		if (proxyConfig == null || proxyConfig.getUrl() == null) {
			return;
		}

		HttpHost httpHost = HttpHost.create(proxyConfig.getUrl());
		BasicCredentialsProvider credentialsProvider = createCredentialsProvider(proxyConfig, httpHost);
		Set<String> scoped = hosts == null ? Set.of() : Arrays.stream(hosts)
			.map(h -> h.trim().toLowerCase(Locale.ROOT))
			.filter(h -> !h.isEmpty())
			.collect(Collectors.toUnmodifiableSet());

		log.info("Configuring HTTP proxy {} with authentication: {}, for {}", httpHost, credentialsProvider != null,
			scoped.isEmpty() ? "every host" : scoped);

		httpConfigurable.configureBuilder(builder -> {

			if (scoped.isEmpty()) {
				builder.setProxy(httpHost);
			} else {
				builder.setRoutePlanner(new HostScopedProxyRoutePlanner(httpHost, scoped));
			}

			if (credentialsProvider != null) {
				builder.setDefaultCredentialsProvider(credentialsProvider);
			}

		});
	}

	protected BasicCredentialsProvider createCredentialsProvider(HttpProxyConfig proxyConfig, HttpHost httpHost) {
		String username = proxyConfig.getUsername();
		String password = proxyConfig.getPassword();

		if (username == null || password == null) {
			return null;
		}

		BasicCredentialsProvider provider = new BasicCredentialsProvider();

		provider.setCredentials(
			new AuthScope(httpHost),
			new UsernamePasswordCredentials(username, password)
		);

		return provider;
	}

	/** Routes the given hosts through the proxy and every other host directly. */
	static class HostScopedProxyRoutePlanner extends DefaultRoutePlanner {

		private final HttpHost proxy;
		private final Set<String> hosts;

		HostScopedProxyRoutePlanner(HttpHost proxy, Set<String> hosts) {
			super(null);
			this.proxy = proxy;
			this.hosts = hosts;
		}

		@Override
		protected HttpHost determineProxy(HttpHost target, HttpRequest request, HttpContext context) {
			return this.hosts.contains(target.getHostName().toLowerCase(Locale.ROOT)) ? this.proxy : null;
		}

	}

}
