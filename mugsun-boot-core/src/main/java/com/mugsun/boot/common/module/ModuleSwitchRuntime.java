package com.mugsun.boot.common.module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 运行时判定系统参数开关：非法值按关闭处理，1/0 照常生效；两者都限频打警告（同一键、同一值默认 10 分钟一次），
 * 避免每个请求都刷日志。
 */
public final class ModuleSwitchRuntime {

	private static final Logger log = LoggerFactory.getLogger(ModuleSwitchRuntime.class);
	static final Duration WARN_INTERVAL = Duration.ofMinutes(10);
	private static final int MAX_TRACKED = 64;

	private static final ModuleSwitchRuntime SHARED = new ModuleSwitchRuntime(System::nanoTime, WARN_INTERVAL);

	private final LongSupplier nanoClock;
	private final long intervalNanos;
	private final Map<String, Long> lastWarn = new ConcurrentHashMap<>();

	ModuleSwitchRuntime(LongSupplier nanoClock, Duration interval) {
		this.nanoClock = nanoClock;
		this.intervalNanos = interval.toNanos();
	}

	/** 进程内共享实例（限频状态全局共享） */
	public static ModuleSwitchRuntime shared() {
		return SHARED;
	}

	/** 按 {@link ModuleSwitch} 规则判定开关；需要时限频告警 */
	public boolean enabled(String paramKey, String raw) {
		ModuleSwitch.State state = ModuleSwitch.parse(raw);
		if (!state.legal()) {
			warn(paramKey, raw, "系统参数 {} 的值 {} 不合法，模块按关闭处理。" + ModuleSwitch.MSG_INVALID);
		} else if (state.deprecated()) {
			warn(paramKey, raw, "系统参数 {} 写成了 {}：" + ModuleSwitch.MSG_DEPRECATED);
		}
		return state.enabled();
	}

	/** 返回本次是否真的打了日志（供测试） */
	boolean warn(String paramKey, String raw, String pattern) {
		String k = paramKey + "\u0000" + raw;
		long now = nanoClock.getAsLong();
		if (lastWarn.size() > MAX_TRACKED) {
			lastWarn.clear();
		}
		boolean[] fire = {false};
		lastWarn.compute(k, (key, prev) -> {
			if (prev == null || now - prev >= intervalNanos) {
				fire[0] = true;
				return now;
			}
			return prev;
		});
		if (fire[0]) {
			log.warn(pattern, paramKey, ModuleSwitch.quote(raw));
		}
		return fire[0];
	}
}
