package com.github.topi314.lavasrc.tidal;

import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("deprecation")
class TidalSourceManagerTest {

	private static final String[] PROVIDERS = {"ytsearch:%QUERY%"};
	private static final TidalTrackData TRACK = new TidalTrackData("75413016", "4:44", "JAY Z", 284_000, "QMJMT1701237",
		"https://tidal.com/browse/track/75413016", "https://resources.tidal.com/images/a/1280x1280.jpg", null, null, null);

	@Test
	void tokenOnlyConfigUsesV1WithoutFallbackLikeBefore() {
		var legacy = new TidalSourceManager(PROVIDERS, "US", unused -> null, "token");
		var legacyResolver = new TidalSourceManager("US", unused -> null, new DefaultMirroringAudioTrackResolver(PROVIDERS), "token");
		var auto = new TidalSourceManager(PROVIDERS, "US", unused -> null, "token", null, null, TidalApiVersion.AUTO);
		var autoBlankCredentials = new TidalSourceManager(PROVIDERS, "US", unused -> null, "token", "", "", TidalApiVersion.AUTO);

		for (var manager : List.of(legacy, legacyResolver, auto, autoBlankCredentials)) {
			assertEquals(TidalApiVersion.V1, manager.getActiveApiVersion());
			assertFalse(manager.hasV1Fallback());
			assertEquals("tidal", manager.getSourceName());
		}
	}

	@Test
	void legacyConstructorStillRequiresToken() {
		var e = assertThrows(IllegalArgumentException.class, () -> new TidalSourceManager(PROVIDERS, "US", unused -> null, null));
		assertEquals("Tidal token must be provided", e.getMessage());
	}

	@Test
	void autoPrefersV2AndFallsBackToV1OnlyWhenTokenIsSet() {
		var both = new TidalSourceManager(PROVIDERS, "US", unused -> null, "token", "id", "secret", TidalApiVersion.AUTO);
		assertEquals(TidalApiVersion.V2, both.getActiveApiVersion());
		assertTrue(both.hasV1Fallback());

		var v2Only = new TidalSourceManager(PROVIDERS, "US", unused -> null, null, "id", "secret", TidalApiVersion.AUTO);
		assertEquals(TidalApiVersion.V2, v2Only.getActiveApiVersion());
		assertFalse(v2Only.hasV1Fallback());
	}

	@Test
	void forcedVersionsNeverFallBackAndRequireTheirCredentials() {
		var v1 = new TidalSourceManager(PROVIDERS, "US", unused -> null, "token", "id", "secret", TidalApiVersion.V1);
		assertEquals(TidalApiVersion.V1, v1.getActiveApiVersion());
		assertFalse(v1.hasV1Fallback());

		var v2 = new TidalSourceManager(PROVIDERS, "US", unused -> null, "token", "id", "secret", TidalApiVersion.V2);
		assertEquals(TidalApiVersion.V2, v2.getActiveApiVersion());
		assertFalse(v2.hasV1Fallback());

		assertThrows(IllegalArgumentException.class, () -> new TidalSourceManager(PROVIDERS, "US", unused -> null, null, "id", "secret", TidalApiVersion.V1));
		assertThrows(IllegalArgumentException.class, () -> new TidalSourceManager(PROVIDERS, "US", unused -> null, "token", "id", null, TidalApiVersion.V2));
		assertThrows(IllegalArgumentException.class, () -> new TidalSourceManager(PROVIDERS, "US", unused -> null, null, null, null, TidalApiVersion.AUTO));
	}

	@Test
	void runtimeUpdatesSwitchVersionAndRejectInvalidConfigs() {
		var manager = new TidalSourceManager(PROVIDERS, "US", unused -> null, "token", null, null, TidalApiVersion.AUTO);

		manager.setClientIdSecret("id", "secret");
		assertEquals(TidalApiVersion.V2, manager.getActiveApiVersion());
		assertTrue(manager.hasV1Fallback());

		manager.setApiVersion(TidalApiVersion.V1);
		assertEquals(TidalApiVersion.V1, manager.getActiveApiVersion());

		// switching to v1 without a token must fail and keep the current configuration
		var v2Only = new TidalSourceManager(PROVIDERS, "US", unused -> null, null, "id", "secret", TidalApiVersion.AUTO);
		assertThrows(IllegalArgumentException.class, () -> v2Only.setApiVersion(TidalApiVersion.V1));
		assertEquals(TidalApiVersion.V2, v2Only.getActiveApiVersion());
	}

	@Test
	void parsesApiVersionStrings() {
		assertEquals(TidalApiVersion.AUTO, TidalApiVersion.fromString(null));
		assertEquals(TidalApiVersion.AUTO, TidalApiVersion.fromString(" "));
		assertEquals(TidalApiVersion.V1, TidalApiVersion.fromString("v1"));
		assertEquals(TidalApiVersion.V2, TidalApiVersion.fromString("V2"));
		assertThrows(IllegalArgumentException.class, () -> TidalApiVersion.fromString("v3"));
	}

	@Test
	void fallsBackToV1OnRetryableV2Errors() {
		var v1 = new FakeClient(TidalApiVersion.V1, null);
		var manager = new TidalSourceManager(new FakeClient(TidalApiVersion.V2, new TidalApiException("HTTP 503", 503, true)), v1);

		var item = manager.loadItem(null, new AudioReference("https://tidal.com/browse/track/75413016", null));

		assertInstanceOf(AudioTrack.class, item);
		assertEquals(1, v1.calls.get());
	}

	@Test
	void networkErrorsAlsoFallBack() {
		var v1 = new FakeClient(TidalApiVersion.V1, null);
		var manager = new TidalSourceManager(new FakeClient(TidalApiVersion.V2, new IOException("connection reset")), v1);

		assertInstanceOf(AudioTrack.class, manager.loadItem(null, new AudioReference("https://tidal.com/browse/track/75413016", null)));
		assertEquals(1, v1.calls.get());
	}

	@Test
	void doesNotFallBackOnResourceErrorsOrWithoutToken() {
		var v1 = new FakeClient(TidalApiVersion.V1, null);
		var notRetryable = new TidalSourceManager(new FakeClient(TidalApiVersion.V2, new TidalApiException("HTTP 406", 406, false)), v1);
		assertThrows(RuntimeException.class, () -> notRetryable.loadItem(null, new AudioReference("https://tidal.com/browse/track/75413016", null)));
		assertEquals(0, v1.calls.get());

		var noFallback = new TidalSourceManager(new FakeClient(TidalApiVersion.V2, new TidalApiException("HTTP 503", 503, true)), null);
		assertThrows(RuntimeException.class, () -> noFallback.loadItem(null, new AudioReference("https://tidal.com/browse/track/75413016", null)));
	}

	@Test
	void missingResourceIsNoTrackWithoutFallback() {
		var v1 = new FakeClient(TidalApiVersion.V1, null);
		var v2 = new FakeClient(TidalApiVersion.V2, null) {
			@Override
			public TidalTrackData getTrack(String trackId) {
				return null;
			}
		};
		var manager = new TidalSourceManager(v2, v1);

		assertSame(AudioReference.NO_TRACK, manager.loadItem(null, new AudioReference("https://tidal.com/browse/track/1", null)));
		assertEquals(0, v1.calls.get());
	}

	@Test
	void encodedTracksStillDecode() throws IOException {
		var manager = new TidalSourceManager(new FakeClient(TidalApiVersion.V2, null), null);
		var track = (TidalAudioTrack) manager.loadItem(null, new AudioReference("https://tidal.com/browse/track/75413016", null));

		var bytes = new ByteArrayOutputStream();
		manager.encodeTrack(track, new DataOutputStream(bytes));
		var decoded = (TidalAudioTrack) manager.decodeTrack(track.getInfo(), new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

		assertEquals(track.getAlbumName(), decoded.getAlbumName());
		assertEquals(track.getAlbumUrl(), decoded.getAlbumUrl());
		assertEquals(track.getArtistUrl(), decoded.getArtistUrl());
		assertEquals("75413016", decoded.getIdentifier());
		assertEquals("QMJMT1701237", decoded.getInfo().isrc);
	}

	@Test
	void legacyEncodingWithoutExtendedFieldsStillDecodes() throws IOException {
		var manager = new TidalSourceManager(new FakeClient(TidalApiVersion.V1, null), null);
		var info = manager.loadItem(null, new AudioReference("https://tidal.com/browse/track/75413016", null));

		var decoded = (TidalAudioTrack) manager.decodeTrack(((AudioTrack) info).getInfo(), new DataInputStream(new ByteArrayInputStream(new byte[0])));

		assertNull(decoded.getAlbumName());
		assertEquals("75413016", decoded.getIdentifier());
	}

	private static class FakeClient implements TidalApiClient {
		final TidalApiVersion version;
		final IOException error;
		final AtomicInteger calls = new AtomicInteger();

		FakeClient(TidalApiVersion version, IOException error) {
			this.version = version;
			this.error = error;
		}

		@Override
		public TidalApiVersion getVersion() {
			return this.version;
		}

		@Override
		public TidalTrackData getTrack(String trackId) throws IOException {
			this.calls.incrementAndGet();
			if (this.error != null) {
				throw this.error;
			}
			return new TidalTrackData(trackId, TRACK.title, TRACK.author, TRACK.durationMs, TRACK.isrc, TRACK.url, TRACK.artworkUrl,
				this.version == TidalApiVersion.V2 ? "4:44" : null, this.version == TidalApiVersion.V2 ? "https://tidal.com/browse/album/75413011" : null, null);
		}

		@Override
		public TidalCollectionData getAlbum(String albumId, int maxItems) {
			return null;
		}

		@Override
		public TidalCollectionData getPlaylist(String playlistId, int maxItems) {
			return null;
		}

		@Override
		public List<TidalTrackData> getMix(String mixId, int maxItems) {
			return List.of();
		}

		@Override
		public List<TidalTrackData> search(String query, int limit) {
			return List.of();
		}

		@Override
		public String getTrackMixId(String trackId) {
			return null;
		}
	}
}
