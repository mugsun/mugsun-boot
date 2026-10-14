package com.mugsun.boot.track;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ON CONFLICT DO NOTHING 只在只读演示模式生效；普通模式必须保留 upsert（会话累加、时间延展依赖它）。
 * 普通模式下多批次累加的端到端校验见 TrackCollectApiTest（event_count 跨批次累加到 2）。
 */
class TrackInsertOnlySqlTest {

	private static final List<String> EVENT_FIELDS = List.of("sessionUpsertSql", "identityUpsertSql", "eventDefUpsertSql");
	private static final List<String> REPLAY_FIELDS = List.of("replayUpsertSql", "sessionMarkReplaySql");

	@Test
	void normalModeKeepsUpsert() throws Exception {
		TrackEventStore events = new TrackEventStore(new DriverManagerDataSource(), false);
		TrackReplayStore replay = new TrackReplayStore(new DriverManagerDataSource(), false);
		for (String f : EVENT_FIELDS) {
			assertThat(sql(events, f)).as(f).contains("DO UPDATE SET").doesNotContain("DO NOTHING");
		}
		for (String f : REPLAY_FIELDS) {
			assertThat(sql(replay, f)).as(f).contains("DO UPDATE SET").doesNotContain("DO NOTHING");
		}
	}

	@Test
	void demoModeDowngradesToDoNothing() throws Exception {
		TrackEventStore events = new TrackEventStore(new DriverManagerDataSource(), true);
		TrackReplayStore replay = new TrackReplayStore(new DriverManagerDataSource(), true);
		for (String f : EVENT_FIELDS) {
			assertThat(sql(events, f)).as(f).endsWith(" DO NOTHING").doesNotContain("DO UPDATE");
		}
		for (String f : REPLAY_FIELDS) {
			assertThat(sql(replay, f)).as(f).endsWith(" DO NOTHING").doesNotContain("DO UPDATE");
		}
	}

	private static String sql(Object store, String field) throws Exception {
		Field f = store.getClass().getDeclaredField(field);
		f.setAccessible(true);
		return (String) f.get(store);
	}
}
