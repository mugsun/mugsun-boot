package com.mugsun.boot.captcha.image;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * 出题随机源：缺口位置、假缺口、裁切等关键取值直接用 SecureRandom；
 * 大量像素噪声用由 SecureRandom 播种的 SplittableRandom（{@link #noise()}），兼顾不可预测与性能。
 */
final class Rng {

	private static final SecureRandom SECURE = new SecureRandom();

	private Rng() {
	}

	static RandomGenerator get() {
		return SECURE;
	}

	static RandomGenerator noise() {
		return new java.util.SplittableRandom(SECURE.nextLong());
	}
}
