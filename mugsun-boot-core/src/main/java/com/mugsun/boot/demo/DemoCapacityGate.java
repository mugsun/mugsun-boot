package com.mugsun.boot.demo;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 演示环境埋点库容量闸门：埋点模块的容量巡检写入，{@link DemoLimitFilter} 读取。
 * 超过硬上限后拒绝新的匿名采集，直到每日重置（或运维清理）后巡检恢复。
 */
@Component
public class DemoCapacityGate {

	private final AtomicBoolean trackFull = new AtomicBoolean(false);
	private final AtomicLong trackBytes = new AtomicLong(-1);

	public boolean trackFull() {
		return trackFull.get();
	}

	public long trackBytes() {
		return trackBytes.get();
	}

	public void updateTrack(long bytes, boolean full) {
		trackBytes.set(bytes);
		trackFull.set(full);
	}
}
