package com.mugsun.boot.track;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 「近 N 天」窗口按租户时区切日：today = now 在 zone 下的日期，窗口从 (today − N + 1) 的当地 0 点开始。
 *
 * @param zone      统计时区
 * @param today     当地今天
 * @param fromDate  窗口首日（当地）
 * @param fromInstant 窗口起点（首日当地 0 点）
 */
public record TrackDayWindow(ZoneId zone, LocalDate today, LocalDate fromDate, Instant fromInstant) {

	public static TrackDayWindow of(ZoneId zone, Instant now, int days) {
		LocalDate today = now.atZone(zone).toLocalDate();
		LocalDate from = today.minusDays(days - 1L);
		return new TrackDayWindow(zone, today, from, from.atStartOfDay(zone).toInstant());
	}

	/** 起点（timestamptz 列比较用） */
	public OffsetDateTime fromOffset() {
		return fromInstant.atOffset(ZoneOffset.UTC);
	}

	/** 起点的 UTC 墙钟（timestamp without time zone 且按 UTC 存的列比较用，如 bucket_time/start_time） */
	public LocalDateTime fromUtcWall() {
		return LocalDateTime.ofInstant(fromInstant, ZoneOffset.UTC);
	}

	/** 时区在窗口内恒为 UTC（可直接用按 UTC 日汇总的 track_stats_day） */
	public boolean isUtc() {
		return zone.getRules().isFixedOffset() && zone.getRules().getOffset(fromInstant).equals(ZoneOffset.UTC);
	}
}
