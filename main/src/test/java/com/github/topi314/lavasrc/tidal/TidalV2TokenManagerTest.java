package com.github.topi314.lavasrc.tidal;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TidalV2TokenManagerTest {

	private static final long EXPIRES_IN = 14400;

	@Test
	void cachesTokenUntilShortlyBeforeExpiry() throws Exception {
		var clock = new MutableClock();
		var fetcher = new CountingFetcher(EXPIRES_IN);
		var manager = new TidalV2TokenManager(fetcher, clock);

		assertEquals("token-1", manager.getAccessToken());
		clock.advance(Duration.ofSeconds(EXPIRES_IN - 61));
		assertEquals("token-1", manager.getAccessToken());
		assertEquals(1, fetcher.calls.get());

		clock.advance(Duration.ofSeconds(1));
		assertEquals("token-2", manager.getAccessToken());
		assertEquals(2, fetcher.calls.get());
	}

	@Test
	void invalidateForcesRefreshOnlyForTheCurrentToken() throws Exception {
		var manager = new TidalV2TokenManager(new CountingFetcher(EXPIRES_IN), new MutableClock());

		var first = manager.getAccessToken();
		manager.invalidate(first);
		var second = manager.getAccessToken();
		assertNotEquals(first, second);

		// a late 401 for the old token must not throw away the fresh one
		manager.invalidate(first);
		assertEquals(second, manager.getAccessToken());
	}

	@Test
	void concurrentCallersShareOneRefresh() throws Exception {
		var started = new CountDownLatch(1);
		var fetcher = new CountingFetcher(EXPIRES_IN) {
			@Override
			public TidalV2TokenManager.AccessToken fetch() throws java.io.IOException {
				try {
					started.await(5, TimeUnit.SECONDS);
					Thread.sleep(50);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return super.fetch();
			}
		};
		var manager = new TidalV2TokenManager(fetcher, new MutableClock());

		var executor = Executors.newFixedThreadPool(8);
		try {
			var futures = new ArrayList<Future<String>>();
			for (int i = 0; i < 8; i++) {
				futures.add(executor.submit(manager::getAccessToken));
			}
			started.countDown();
			for (var future : futures) {
				assertEquals("token-1", future.get(5, TimeUnit.SECONDS));
			}
		} finally {
			executor.shutdownNow();
		}
		assertEquals(1, fetcher.calls.get());
	}

	@Test
	void refreshDelayKeepsSixtySecondMargin() {
		assertEquals(Duration.ofSeconds(EXPIRES_IN - 60), TidalV2TokenManager.refreshDelay(EXPIRES_IN));
		// very short lived tokens are refreshed halfway instead of immediately
		assertEquals(Duration.ofSeconds(50), TidalV2TokenManager.refreshDelay(100));
		assertEquals(Duration.ZERO, TidalV2TokenManager.refreshDelay(0));
	}

	@Test
	void fetchErrorsPropagateAndAreRetriedOnNextCall() throws Exception {
		var calls = new AtomicInteger();
		var manager = new TidalV2TokenManager(() -> {
			if (calls.incrementAndGet() == 1) {
				throw new TidalApiException("Failed to obtain Tidal v2 access token: HTTP 401", 401, true);
			}
			return new TidalV2TokenManager.AccessToken("ok", EXPIRES_IN);
		}, new MutableClock());

		var e = assertThrows(TidalApiException.class, manager::getAccessToken);
		assertTrue(e.isRetryable());
		assertEquals("ok", manager.getAccessToken());
	}

	private static class CountingFetcher implements TidalV2TokenManager.TokenFetcher {
		final AtomicInteger calls = new AtomicInteger();
		final long expiresIn;

		CountingFetcher(long expiresIn) {
			this.expiresIn = expiresIn;
		}

		@Override
		public TidalV2TokenManager.AccessToken fetch() throws java.io.IOException {
			return new TidalV2TokenManager.AccessToken("token-" + calls.incrementAndGet(), expiresIn);
		}
	}

	static class MutableClock extends Clock {
		private Instant now = Instant.parse("2026-01-01T00:00:00Z");

		void advance(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now;
		}
	}
}
