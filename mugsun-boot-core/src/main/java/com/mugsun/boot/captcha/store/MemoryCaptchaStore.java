package com.mugsun.boot.captcha.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** 单机内存存储：take 依赖 ConcurrentHashMap.remove 的返回值实现一次性 */
public class MemoryCaptchaStore implements CaptchaStore {

	private record Slot(String value, long expireAtMs) {
	}

	private final Map<String, Slot> slots = new ConcurrentHashMap<>();
	private final LongSupplier clock;
	private final AtomicLong ops = new AtomicLong();

	public MemoryCaptchaStore() {
		this(System::currentTimeMillis);
	}

	public MemoryCaptchaStore(LongSupplier clock) {
		this.clock = clock;
	}

	@Override
	public void put(String key, String value, long ttlSeconds) {
		slots.put(key, new Slot(value, clock.getAsLong() + Math.max(1, ttlSeconds) * 1000));
		sweepSometimes();
	}

	@Override
	public String take(String key) {
		Slot s = slots.remove(key);
		return alive(s) ? s.value() : null;
	}

	@Override
	public String peek(String key) {
		Slot s = slots.get(key);
		return alive(s) ? s.value() : null;
	}

	@Override
	public long increment(String key, long ttlSeconds) {
		long now = clock.getAsLong();
		Slot s = slots.compute(key, (k, old) -> {
			if (old == null || old.expireAtMs() <= now) {
				return new Slot("1", now + Math.max(1, ttlSeconds) * 1000);
			}
			return new Slot(String.valueOf(Long.parseLong(old.value()) + 1), old.expireAtMs());
		});
		return Long.parseLong(s.value());
	}

	@Override
	public long ttlSeconds(String key) {
		Slot s = slots.get(key);
		if (!alive(s)) {
			return 0;
		}
		return Math.max(1, (s.expireAtMs() - clock.getAsLong() + 999) / 1000);
	}

	@Override
	public void delete(String key) {
		slots.remove(key);
	}

	private boolean alive(Slot s) {
		return s != null && s.expireAtMs() > clock.getAsLong();
	}

	private void sweepSometimes() {
		if (ops.incrementAndGet() % 256 == 0) {
			long now = clock.getAsLong();
			slots.entrySet().removeIf(e -> e.getValue().expireAtMs() <= now);
		}
	}
}
