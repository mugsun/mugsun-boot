package com.mugsun.boot.app;

/**
 * 移动端通道常量：滑块/ticket 缓存键、限流与尺寸，禁止散落魔法值。
 */
public final class AppConstants {

	private AppConstants() {
	}

	/** 滑块缺口横坐标 Redis 前缀 */
	static final String SLIDER_X_KEY = "mugsun:app:slider:x:";
	/** 滑块校验通过后的一次性登录票 */
	static final String SLIDER_TICKET_KEY = "mugsun:app:slider:ticket:";
	/** 按 IP 的出题限流键 */
	static final String SLIDER_IP_KEY = "mugsun:app:slider:ip:";

	/** 题目/ticket 有效期（秒） */
	static final long SLIDER_TTL_SECONDS = 120L;
	/** 单 IP 每分钟最多出题次数 */
	static final int SLIDER_IP_LIMIT = 30;
	/** 缺口横坐标容差（像素） */
	static final int SLIDER_TOLERANCE_PX = 8;
	/** 轨迹最少采样点 */
	static final int SLIDER_MIN_TRACKS = 4;
	/** 拖动最短耗时（毫秒），防瞬移脚本 */
	static final long SLIDER_MIN_DURATION_MS = 180L;
	/** 拖动最长耗时（毫秒） */
	static final long SLIDER_MAX_DURATION_MS = 15000L;

	static final int PUZZLE_WIDTH = 310;
	static final int PUZZLE_HEIGHT = 155;
	static final int PIECE_SIZE = 48;

	static final int HOME_TODO_LIMIT = 5;
	static final int HOME_NOTICE_LIMIT = 3;
}
