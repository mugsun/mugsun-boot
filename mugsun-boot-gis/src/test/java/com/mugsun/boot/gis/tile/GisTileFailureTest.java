package com.mugsun.boot.gis.tile;

import com.mugsun.boot.gis.GisConstants;
import com.mugsun.boot.gis.xyz.GisXyzGuard;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class GisTileFailureTest {

	@Test
	void statusCodesFollowTheContract() {
		assertThat(GisTileFailure.UPSTREAM_STATUS.status()).isEqualTo(502);
		assertThat(GisTileFailure.BAD_CONTENT_TYPE.status()).isEqualTo(502);
		assertThat(GisTileFailure.TIMEOUT.status()).isEqualTo(504);
		assertThat(GisTileFailure.BLOCKED_ADDRESS.status()).isEqualTo(403);
		assertThat(GisTileFailure.HOST_NOT_ALLOWED.status()).isEqualTo(403);
		assertThat(GisTileFailure.NOT_CONFIGURED.status()).isEqualTo(404);
	}

	@Test
	void exceptionsAreClassified() {
		assertThat(GisTileFailure.of(new HttpTimeoutException("t"))).isEqualTo(GisTileFailure.TIMEOUT);
		assertThat(GisTileFailure.of(new HttpConnectTimeoutException("t"))).isEqualTo(GisTileFailure.TIMEOUT);
		assertThat(GisTileFailure.of(new IOException("wrap", new HttpTimeoutException("t"))))
			.isEqualTo(GisTileFailure.TIMEOUT);
		assertThat(GisTileFailure.of(new ServiceException(GisXyzGuard.MSG_PRIVATE)))
			.isEqualTo(GisTileFailure.BLOCKED_ADDRESS);
		assertThat(GisTileFailure.of(new ServiceException(GisXyzGuard.MSG_HOST)))
			.isEqualTo(GisTileFailure.HOST_NOT_ALLOWED);
		assertThat(GisTileFailure.of(new ServiceException(GisUpstreamHttpMessages.ILLEGAL_HOST)))
			.isEqualTo(GisTileFailure.HOST_NOT_ALLOWED);
		assertThat(GisTileFailure.of(new ServiceException(GisUpstreamHttpMessages.TOO_LARGE)))
			.isEqualTo(GisTileFailure.TOO_LARGE);
		assertThat(GisTileFailure.of(new ServiceException(GisConstants.MSG_MVT_ZXY)))
			.isEqualTo(GisTileFailure.OUT_OF_RANGE);
		assertThat(GisTileFailure.of(new java.net.ConnectException("refused")))
			.isEqualTo(GisTileFailure.UPSTREAM_UNREACHABLE);
	}

	record Line(String provider, String host, GisTileFailure reason, int status, int z, long suppressed) {
	}

	@Test
	void warnIsRateLimitedPerHostAndReason() {
		AtomicLong now = new AtomicLong(0);
		List<Line> out = new ArrayList<>();
		GisTileFailureLog log = new GisTileFailureLog(60_000, now::get,
			(p, h, r, s, z, sup) -> out.add(new Line(p, h, r, s, z, sup)));
		for (int i = 0; i < 50; i++) {
			log.record("xyz", "tile.openstreetmap.org", GisTileFailure.BLOCKED_ADDRESS, 0, 5);
		}
		log.record("xyz", "tile.openstreetmap.org", GisTileFailure.TIMEOUT, 0, 5);
		log.record("xyz", "a.example.com", GisTileFailure.BLOCKED_ADDRESS, 0, 5);
		assertThat(out).hasSize(3);
		now.addAndGet(60_000);
		log.record("xyz", "tile.openstreetmap.org", GisTileFailure.BLOCKED_ADDRESS, 0, 6);
		assertThat(out).hasSize(4);
		assertThat(out.get(3).suppressed()).isEqualTo(49);
	}

	@Test
	void expectedConditionsAreNotLoggedAndHostIsSanitised() {
		List<Line> out = new ArrayList<>();
		GisTileFailureLog log = new GisTileFailureLog(60_000, () -> 0L,
			(p, h, r, s, z, sup) -> out.add(new Line(p, h, r, s, z, sup)));
		log.record("xyz", "h", GisTileFailure.OUT_OF_RANGE, 0, 20);
		log.record("amap", "h", GisTileFailure.NOT_CONFIGURED, 0, 3);
		assertThat(out).isEmpty();
		log.record("xyz", "evil.com\nFAKE LOG key=abc", GisTileFailure.UPSTREAM_STATUS, 500, 3);
		assertThat(out.get(0).host()).isEqualTo("invalid");
	}
}
