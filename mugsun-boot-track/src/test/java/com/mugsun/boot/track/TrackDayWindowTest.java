package com.mugsun.boot.track;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 按租户时区切日：UTC+8 0 点前后（= UTC 前一天 16:00）的窗口边界 */
class TrackDayWindowTest {

	private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

	@Test
	void oneSecondBeforeShanghaiMidnightIsStillYesterday() {
		TrackDayWindow w = TrackDayWindow.of(SH, Instant.parse("2026-10-12T15:59:59Z"), 1);
		assertThat(w.today()).isEqualTo(LocalDate.of(2026, 10, 12));
		assertThat(w.fromInstant()).isEqualTo(Instant.parse("2026-10-11T16:00:00Z"));
	}

	@Test
	void shanghaiMidnightStartsANewDay() {
		TrackDayWindow w = TrackDayWindow.of(SH, Instant.parse("2026-10-12T16:00:00Z"), 1);
		assertThat(w.today()).isEqualTo(LocalDate.of(2026, 10, 13));
		assertThat(w.fromInstant()).as("今天从当地 0 点开始").isEqualTo(Instant.parse("2026-10-12T16:00:00Z"));
		assertThat(w.fromUtcWall()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 12, 16, 0));
		assertThat(w.isUtc()).isFalse();
	}

	@Test
	void earlyMorningLocalIsStillPreviousUtcDateButLocalToday() {
		// 当地 10-13 07:30 = UTC 10-12 23:30：按 UTC 切日会把今天算成 10-12
		TrackDayWindow w = TrackDayWindow.of(SH, Instant.parse("2026-10-12T23:30:00Z"), 7);
		assertThat(w.today()).isEqualTo(LocalDate.of(2026, 10, 13));
		assertThat(w.fromDate()).isEqualTo(LocalDate.of(2026, 10, 7));
		assertThat(w.fromInstant()).isEqualTo(Instant.parse("2026-10-06T16:00:00Z"));
	}

	@Test
	void utcZoneKeepsUtcDays() {
		TrackDayWindow w = TrackDayWindow.of(ZoneOffset.UTC, Instant.parse("2026-10-12T23:30:00Z"), 7);
		assertThat(w.today()).isEqualTo(LocalDate.of(2026, 10, 12));
		assertThat(w.isUtc()).isTrue();
	}

	@Test
	void tenantZoneFallsBackToShanghai() {
		TrackTimezoneProperties p = new TrackTimezoneProperties();
		p.setTenants(Map.of("100001", "UTC", "100002", "Not/AZone"));
		assertThat(p.zoneFor(null)).isEqualTo(SH);
		assertThat(p.zoneFor("000000")).as("未配置用默认").isEqualTo(SH);
		assertThat(p.zoneFor("100001")).isEqualTo(ZoneId.of("UTC"));
		assertThat(p.zoneFor("100002")).as("无效时区回落默认").isEqualTo(SH);
	}
}
