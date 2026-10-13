package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mybatisflex.core.datasource.DataSourceKey;
import com.mybatisflex.core.row.Db;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 概览卡片 / 页面 / 性能 / 漏斗 / 留存 按租户时区切日（默认 Asia/Shanghai）：
 * 事件落在当地 0 点前 1 秒（= UTC 前一天 15:59:59）与 0 点后 1 秒，days=1 只能看到 0 点之后的。
 * 按 UTC 切日时两者同属一个 UTC 日（UTC 08:00 后运行时），断言会失败。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackTimezoneBoundaryApiTest extends AbstractTrackIntegrationTest {

	private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
	private static final String APP = "it-tz-dash";
	private static final String APP_RET = "it-tz-ret";

	@Autowired
	private TrackEventStore store;

	private String adminToken;
	private LocalDate localToday;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		seedApp(APP);
		seedApp(APP_RET);
		localToday = LocalDate.now(SHANGHAI);
		long midnight = localToday.atStartOfDay(SHANGHAI).toInstant().toEpochMilli();
		long before = midnight - 1000L;
		long after = midnight + 1000L;
		String x = UUID.randomUUID().toString();
		String y = UUID.randomUUID().toString();
		String r = UUID.randomUUID().toString();
		store.insertEvents(List.of(
			ev(APP, "$pageview", before, x, "/before", "{}"),
			ev(APP, "$pageview", after, y, "/after", "{}"),
			ev(APP, "$web_vitals", before, x, "/before", "{\"metric\":\"lcp\",\"value\":100}"),
			ev(APP, "$web_vitals", after, y, "/after", "{\"metric\":\"lcp\",\"value\":100}"),
			// 漏斗：x 的第 1 步在 0 点前（今天窗口外），y 两步都在 0 点后
			ev(APP, "tz_step_a", before, x, "/before", "{}"),
			ev(APP, "tz_step_b", after + 10, x, "/after", "{}"),
			ev(APP, "tz_step_a", after + 20, y, "/after", "{}"),
			ev(APP, "tz_step_b", after + 30, y, "/after", "{}"),
			// 留存：r 当地昨天 23:59:59 首访、今天 00:00:01 回访 → 昨天 cohort 的 D+1
			ev(APP_RET, "$pageview", before, r, "/r", "{}"),
			ev(APP_RET, "$pageview", after, r, "/r", "{}")));
	}

	@Test
	void overviewCardsTodayStartAtLocalMidnight() {
		JsonNode cards = readBodyAssertOk(get("/system/track/overview?appKey=" + APP + "&days=1", adminToken)).path("cards");
		assertThat(cards.path("pv").asLong()).as("只算 0 点后的 PV").isEqualTo(1L);
		assertThat(cards.path("uv").asLong()).as("0 点后活跃的 x、y").isEqualTo(2L);
		assertThat(cards.path("eventCount").asLong()).as("0 点后 pv/vitals/step_b/step_a/step_b").isEqualTo(5L);
		JsonNode two = readBodyAssertOk(get("/system/track/overview?appKey=" + APP + "&days=2", adminToken)).path("cards");
		assertThat(two.path("pv").asLong()).isEqualTo(2L);
		assertThat(two.path("sessionCount").asLong()).as("每个当地日的活跃会话数之和：昨天 x，今天 x、y").isEqualTo(3L);
	}

	@Test
	void pagesTodayStartAtLocalMidnight() {
		JsonNode pages = readBodyAssertOk(get("/system/track/pages?appKey=" + APP + "&days=1", adminToken));
		List<String> paths = new ArrayList<>();
		pages.forEach(p -> paths.add(p.path("pagePath").asText()));
		assertThat(paths).contains("/after").doesNotContain("/before");
	}

	@Test
	void vitalsTodayStartAtLocalMidnight() {
		JsonNode before = readBodyAssertOk(get("/system/track/vitals?appKey=" + APP + "&days=1&routePath=/before", adminToken));
		assertThat(before.size()).as("0 点前的性能样本不在今天").isZero();
		JsonNode after = readBodyAssertOk(get("/system/track/vitals?appKey=" + APP + "&days=1&routePath=/after", adminToken));
		assertThat(after.size()).isEqualTo(1);
		assertThat(after.get(0).path("metric").asText()).isEqualTo("lcp");
		assertThat(after.get(0).path("count").asLong()).isEqualTo(1L);
		JsonNode both = readBodyAssertOk(get("/system/track/vitals?appKey=" + APP + "&days=2", adminToken));
		assertThat(both.get(0).path("count").asLong()).isEqualTo(2L);
	}

	@Test
	void funnelWindowStartsAtLocalMidnight() {
		JsonNode steps = readBodyAssertOk(get("/system/track/funnel?appKey=" + APP
			+ "&steps=tz_step_a,tz_step_b&days=1", adminToken)).path("steps");
		assertThat(steps.get(0).path("count").asLong()).as("x 的第 1 步在 0 点前，不进今天的漏斗").isEqualTo(1L);
		assertThat(steps.get(1).path("count").asLong()).isEqualTo(1L);
		JsonNode two = readBodyAssertOk(get("/system/track/funnel?appKey=" + APP
			+ "&steps=tz_step_a,tz_step_b&days=2", adminToken)).path("steps");
		assertThat(two.get(0).path("count").asLong()).isEqualTo(2L);
		assertThat(two.get(1).path("count").asLong()).isEqualTo(2L);
	}

	@Test
	void retentionDaysAreLocalDates() {
		JsonNode rows = readBodyAssertOk(get("/system/track/retention?appKey=" + APP_RET + "&days=2", adminToken)).path("rows");
		assertThat(rows.size()).isEqualTo(1);
		JsonNode row = rows.get(0);
		assertThat(row.path("cohortDate").asText()).as("首访日 = 当地昨天").isEqualTo(localToday.minusDays(1).toString());
		assertThat(row.path("cohortSize").asLong()).isEqualTo(1L);
		assertThat(row.path("retained").path("1").asLong()).as("当地 0 点后 1 秒算次日回访").isEqualTo(1L);
	}

	private void seedApp(String appKey) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " create_time, update_time, is_deleted) VALUES (?, ?, ?, 'web', ?, 100, 1, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO UPDATE SET tenant_id = EXCLUDED.tenant_id, enabled = 1",
			IdUtil.getSnowflakeNextId(), appKey, "时区测试-" + appKey, PLATFORM_TENANT));
	}

	private TrackIngestEvent ev(String appKey, String name, long ms, String distinctId, String path, String props) {
		TrackIngestEvent e = new TrackIngestEvent();
		e.setEventId(UUID.randomUUID().toString());
		e.setAppKey(appKey);
		e.setEventName(name);
		e.setClientTsMs(ms);
		e.setTsMs(ms);
		e.setReceivedAtMs(ms);
		e.setClockSkewed(0);
		e.setDistinctId(distinctId);
		e.setSessionId("s" + distinctId.substring(1));
		e.setTenantId(PLATFORM_TENANT);
		e.setUrlPath(path);
		e.setRoutePath(path);
		e.setDevice("desktop");
		e.setPropsJson(props);
		return e;
	}

	private JsonNode readBodyAssertOk(ResponseEntity<String> resp) {
		JsonNode body = readBody(resp);
		assertThat(body.path("code").asInt()).as(body.path("msg").asText()).isEqualTo(200);
		return body.path("data");
	}
}
