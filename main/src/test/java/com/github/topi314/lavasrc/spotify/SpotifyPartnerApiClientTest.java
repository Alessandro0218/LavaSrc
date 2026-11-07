package com.github.topi314.lavasrc.spotify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SpotifyPartnerApiClientTest {

	private final ExecutorService pool = Executors.newFixedThreadPool(4);

	@AfterEach
	void shutdown() {
		this.pool.shutdownNow();
	}

	@Test
	void fetchAllMapsEachIdAndDropsMisses() throws IOException {
		var isrcs = SpotifyPartnerApiClient.fetchAll(List.of("a", "b", "c"), id -> id.equals("b") ? null : "ISRC-" + id, this.pool);

		assertEquals(Map.of("a", "ISRC-a", "c", "ISRC-c"), isrcs);
	}

	@Test
	void fetchAllRunsRequestsConcurrently() throws IOException {
		// every fetch waits until four are in flight at once, so a serial loop would time out and return no ISRC
		var started = new CountDownLatch(4);
		var inFlight = new AtomicInteger();
		var maxInFlight = new AtomicInteger();
		var isrcs = SpotifyPartnerApiClient.fetchAll(List.of("a", "b", "c", "d"), id -> {
			maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
			started.countDown();
			try {
				return started.await(5, TimeUnit.SECONDS) ? "ISRC-" + id : null;
			} catch (InterruptedException e) {
				return null;
			} finally {
				inFlight.decrementAndGet();
			}
		}, this.pool);

		assertEquals(4, isrcs.size());
		assertEquals(4, maxInFlight.get());
	}

	@Test
	void fetchAllWithNoIdsMakesNoRequests() throws IOException {
		var calls = new AtomicInteger();
		var isrcs = SpotifyPartnerApiClient.fetchAll(List.of(), id -> {
			calls.incrementAndGet();
			return "x";
		}, this.pool);

		assertTrue(isrcs.isEmpty());
		assertEquals(0, calls.get());
	}
}
