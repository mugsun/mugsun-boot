package com.mugsun.boot.captcha.image;

import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * ICON_CLICK 每题 seeded 网格形变：公开库 PNG 经确定性位移后与模板不再重合，
 * 提示条与画布对同一 slug 使用同一 seed（见 {@link #iconSeed}）。
 * <p>
 * 不做 R7-1 那套高对比渐变/杂波/描边填色（曾把模板全解率推到 37–48%）。
 * 强度 0 时原样返回（便于对比/关闭）。
 */
final class IconSeedWarp {

	/** 控制网格单元数（边固定，内点随机位移） */
	static final int GRID = 3;

	/** 默认最大位移相对短边比例（经验值，可被配置覆盖） */
	static final double DEFAULT_STRENGTH = 0.14;

	/** 与 CaptchaProperties.Click.SEED_WARP_STRENGTH_MAX 一致 */
	static final double MAX_STRENGTH = 0.35;

	private IconSeedWarp() {
	}

	/** 由挑战 seed + 图标 slug 派生，保证同题提示条与画布一致 */
	static long iconSeed(long challengeSeed, String slug) {
		long h = challengeSeed ^ 0x9E3779B97F4A7C15L;
		h ^= (long) Objects.requireNonNull(slug, "slug").hashCode() * 0xC2B2AE3D27D4EB4FL;
		h ^= Long.rotateLeft(challengeSeed, 17);
		return h;
	}

	/**
	 * 对 ARGB 图标做双线性网格形变。{@code strength} ∈ [0, {@link #MAX_STRENGTH}]；
	 * ≤0 或空图直接返回原图（不复制）。
	 */
	static BufferedImage warp(BufferedImage src, long seed, double strength) {
		if (src == null) {
			throw new IllegalArgumentException("src");
		}
		double s = clamp(strength, 0, MAX_STRENGTH);
		if (s <= 1e-9) {
			return src;
		}
		int w = src.getWidth();
		int h = src.getHeight();
		if (w < 4 || h < 4) {
			return src;
		}
		RandomGenerator rnd = RandomGeneratorFactory.of("SplittableRandom").create(seed);
		double max = Math.min(w, h) * s;
		int gx = GRID;
		int gy = GRID;
		double[][] dx = new double[gy + 1][gx + 1];
		double[][] dy = new double[gy + 1][gx + 1];
		for (int j = 0; j <= gy; j++) {
			for (int i = 0; i <= gx; i++) {
				boolean border = i == 0 || j == 0 || i == gx || j == gy;
				if (border) {
					dx[j][i] = 0;
					dy[j][i] = 0;
				} else {
					dx[j][i] = (rnd.nextDouble() * 2 - 1) * max;
					dy[j][i] = (rnd.nextDouble() * 2 - 1) * max;
				}
			}
		}
		// 轻微描边扰动：对 alpha 边缘做 seeded 局部胀缩位移（仍属几何，非高对比填色）
		double strokeAmp = max * 0.25;
		double strokePhase = rnd.nextDouble() * Math.PI * 2;
		double strokeFreq = 2 + rnd.nextInt(3);

		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				double u = x * (double) gx / Math.max(1, w - 1);
				double v = y * (double) gy / Math.max(1, h - 1);
				int i0 = Math.min((int) Math.floor(u), gx - 1);
				int j0 = Math.min((int) Math.floor(v), gy - 1);
				double fu = u - i0;
				double fv = v - j0;
				double ddx = bilerp(dx, i0, j0, fu, fv);
				double ddy = bilerp(dy, i0, j0, fu, fv);
				// 径向描边微扰：沿相对中心方向推一点，破坏模板轮廓 NCC
				double cx = (w - 1) / 2.0;
				double cy = (h - 1) / 2.0;
				double rx = x - cx;
				double ry = y - cy;
				double ang = Math.atan2(ry, rx);
				double ripple = Math.sin(ang * strokeFreq + strokePhase) * strokeAmp;
				double len = Math.hypot(rx, ry);
				if (len > 1e-3) {
					ddx += ripple * rx / len;
					ddy += ripple * ry / len;
				}
				out.setRGB(x, y, sample(src, x - ddx, y - ddy));
			}
		}
		return out;
	}

	private static double bilerp(double[][] g, int i0, int j0, double fu, double fv) {
		double a = g[j0][i0];
		double b = g[j0][i0 + 1];
		double c = g[j0 + 1][i0];
		double d = g[j0 + 1][i0 + 1];
		double top = a + (b - a) * fu;
		double bot = c + (d - c) * fu;
		return top + (bot - top) * fv;
	}

	/** 双线性采样；越界透明 */
	static int sample(BufferedImage src, double x, double y) {
		int w = src.getWidth();
		int h = src.getHeight();
		int x0 = (int) Math.floor(x);
		int y0 = (int) Math.floor(y);
		int x1 = x0 + 1;
		int y1 = y0 + 1;
		if (x1 < 0 || y1 < 0 || x0 >= w || y0 >= h) {
			return 0;
		}
		double fx = x - x0;
		double fy = y - y0;
		int c00 = argb(src, x0, y0);
		int c10 = argb(src, x1, y0);
		int c01 = argb(src, x0, y1);
		int c11 = argb(src, x1, y1);
		return lerpArgb(c00, c10, c01, c11, fx, fy);
	}

	private static int argb(BufferedImage src, int x, int y) {
		if (x < 0 || y < 0 || x >= src.getWidth() || y >= src.getHeight()) {
			return 0;
		}
		return src.getRGB(x, y);
	}

	private static int lerpArgb(int c00, int c10, int c01, int c11, double fx, double fy) {
		return pack(
			lerp(lerp(chan(c00, 24), chan(c10, 24), fx), lerp(chan(c01, 24), chan(c11, 24), fx), fy),
			lerp(lerp(chan(c00, 16), chan(c10, 16), fx), lerp(chan(c01, 16), chan(c11, 16), fx), fy),
			lerp(lerp(chan(c00, 8), chan(c10, 8), fx), lerp(chan(c01, 8), chan(c11, 8), fx), fy),
			lerp(lerp(chan(c00, 0), chan(c10, 0), fx), lerp(chan(c01, 0), chan(c11, 0), fx), fy)
		);
	}

	private static int chan(int argb, int shift) {
		return (argb >>> shift) & 0xFF;
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private static int pack(double a, double r, double g, double b) {
		return (clampByte(a) << 24) | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
	}

	private static int clampByte(double v) {
		if (v <= 0) {
			return 0;
		}
		if (v >= 255) {
			return 255;
		}
		return (int) Math.round(v);
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
