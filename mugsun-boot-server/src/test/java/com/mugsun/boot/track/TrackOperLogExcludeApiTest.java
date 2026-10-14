package com.mugsun.boot.track;

import com.mugsun.boot.AbstractIntegrationTest;
import com.mybatisflex.core.row.Db;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UX-112：埋点 SDK 上报接口 /track/collect、/track/replay 默认不进操作日志（自动留痕）；其他写接口照常留痕。
 */
class TrackOperLogExcludeApiTest extends AbstractIntegrationTest {

	@Test
	void sdkEndpointsAreNotAuditedButOtherWritesAre() throws Exception {
		Timestamp since = Timestamp.from(Instant.now().minusSeconds(1));
		String token = loginAdmin();
		post("/track/collect", Map.of("app_key", "it-operlog-exclude", "events", List.of()), null);
		post("/track/replay", Map.of("app_key", "it-operlog-exclude", "session_id", "x", "seq", 0), null);
		// 对照：普通写接口仍自动留痕（异步落库，留痕到了说明前两条也已处理完）
		post("/system/gis/scene/remove", List.of(-1L), token);
		await(() -> count("/system/gis/scene/remove", since) >= 1L);
		Thread.sleep(500);
		assertThat(count("/track/collect", since)).isZero();
		assertThat(count("/track/replay", since)).isZero();
	}

	private long count(String uri, Timestamp since) {
		return Db.selectOneBySql("SELECT count(*) AS c FROM sys_oper_log WHERE request_uri = ? AND create_time >= ?",
			uri, since).getLong("c");
	}

	private void await(BooleanSupplier cond) throws InterruptedException {
		Instant end = Instant.now().plus(Duration.ofSeconds(15));
		while (Instant.now().isBefore(end)) {
			if (cond.getAsBoolean()) {
				return;
			}
			Thread.sleep(200);
		}
		throw new AssertionError("对照写接口的操作日志未落库");
	}
}
