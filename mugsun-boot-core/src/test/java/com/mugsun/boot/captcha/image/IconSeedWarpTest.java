package com.mugsun.boot.captcha.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** #77 seed-warp：确定性、同 seed 画布/提示一致、强度 0 恒等 */
class IconSeedWarpTest {

	private static BufferedImage loadCat() throws Exception {
		try (InputStream in = IconSeedWarpTest.class.getResourceAsStream("/captcha/icons/cat.png")) {
			return ImageIO.read(in);
		}
	}

	@Test
	void sameSeedSamePixels() throws Exception {
		BufferedImage src = loadCat();
		long seed = IconSeedWarp.iconSeed(0xC0FFEEL, "cat");
		BufferedImage a = IconSeedWarp.warp(src, seed, 0.14);
		BufferedImage b = IconSeedWarp.warp(src, seed, 0.14);
		assertThat(a.getWidth()).isEqualTo(src.getWidth());
		assertThat(a.getHeight()).isEqualTo(src.getHeight());
		int diff = 0;
		for (int y = 0; y < a.getHeight(); y++) {
			for (int x = 0; x < a.getWidth(); x++) {
				if (a.getRGB(x, y) != b.getRGB(x, y)) {
					diff++;
				}
			}
		}
		assertThat(diff).isZero();
	}

	@Test
	void differentSeedChangesPixels() throws Exception {
		BufferedImage src = loadCat();
		BufferedImage a = IconSeedWarp.warp(src, IconSeedWarp.iconSeed(1L, "cat"), 0.14);
		BufferedImage b = IconSeedWarp.warp(src, IconSeedWarp.iconSeed(2L, "cat"), 0.14);
		int diff = 0;
		for (int y = 0; y < a.getHeight(); y++) {
			for (int x = 0; x < a.getWidth(); x++) {
				if (a.getRGB(x, y) != b.getRGB(x, y)) {
					diff++;
				}
			}
		}
		assertThat(diff).isGreaterThan(100);
	}

	@Test
	void zeroStrengthReturnsSameInstance() throws Exception {
		BufferedImage src = loadCat();
		assertThat(IconSeedWarp.warp(src, 99L, 0)).isSameAs(src);
	}

	@Test
	void iconSeedStableForSlug() {
		assertThat(IconSeedWarp.iconSeed(42L, "cat"))
			.isEqualTo(IconSeedWarp.iconSeed(42L, "cat"));
		assertThat(IconSeedWarp.iconSeed(42L, "cat"))
			.isNotEqualTo(IconSeedWarp.iconSeed(42L, "dog"));
	}
}
