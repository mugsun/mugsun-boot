package com.mugsun.boot.track.job;

import java.time.Clock;

/** 测试访问包内方法（时钟注入 / 时段判断） */
public final class TrackUvBackfillJobTestAccess {

	private TrackUvBackfillJobTestAccess() {
	}

	public static void setClock(TrackUvBackfillJob job, Clock clock) {
		job.setClock(clock);
	}

	public static boolean inWindow(TrackUvBackfillJob job) {
		return job.inWindow();
	}
}
