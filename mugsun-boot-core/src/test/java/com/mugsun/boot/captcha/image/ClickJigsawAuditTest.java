package com.mugsun.boot.captcha.image;

import com.mugsun.boot.captcha.CaptchaProperties;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 管理前端工程师全量审计的后端项：图标无白盘、笔画少的字小角度、拼图至少 3 块错位 */
class ClickJigsawAuditTest {

	private final CaptchaProperties props = new CaptchaProperties();
	private final MultiTypeRenderer r = new MultiTypeRenderer(props, new BackgroundLibrary(props));

	private static BufferedImage decode(String data) throws Exception {
		String b64 = data.substring(data.indexOf(',') + 1);
		return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
	}

	private static double gray(int p) {
		return 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
	}

	/**
	 * 对抗：简单的“亮斑检测器”（白盘的典型特征）——按 0.7×边长半径的圆盘均值减外环均值打分，取不重叠的前 k 个峰，
	 * 看有多少落在真实图标（目标 + 干扰）上。去掉白盘后不能再一找一个准。
	 */
	@Test
	void iconSpotsAreNotTriviallyLocatable() throws Exception {
		int n = Integer.getInteger("icon.adversarial.n", 40);
		int found = 0, total = 0;
		for (int t = 0; t < n; t++) {
			MultiTypeRenderer.Click c = r.renderIconClick();
			BufferedImage bg = decode(c.background());
			List<int[]> truth = new ArrayList<>(c.answer());
			truth.addAll(c.decoys());
			int k = truth.size();
			int w = bg.getWidth(), h = bg.getHeight();
			double[][] g = new double[h][w];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					g[y][x] = gray(bg.getRGB(x, y));
				}
			}
			int rin = (int) (c.itemSize() * 0.7), rout = rin + 6;
			List<double[]> cand = new ArrayList<>();
			for (int y = rout; y < h - rout; y += 3) {
				for (int x = rout; x < w - rout; x += 3) {
					double si = 0, so = 0;
					int ni = 0, no = 0;
					for (int dy = -rout; dy <= rout; dy += 2) {
						for (int dx = -rout; dx <= rout; dx += 2) {
							double d = Math.hypot(dx, dy);
							if (d <= rin) {
								si += g[y + dy][x + dx];
								ni++;
							} else if (d <= rout) {
								so += g[y + dy][x + dx];
								no++;
							}
						}
					}
					cand.add(new double[]{si / ni - so / no, x, y});
				}
			}
			cand.sort((a, b) -> Double.compare(b[0], a[0]));
			List<double[]> picks = new ArrayList<>();
			for (double[] cd : cand) {
				if (picks.size() == k) {
					break;
				}
				if (picks.stream().noneMatch(p -> Math.hypot(p[1] - cd[1], p[2] - cd[2]) < 2 * rin)) {
					picks.add(cd);
				}
			}
			for (int[] tr : truth) {
				total++;
				if (picks.stream().anyMatch(p -> Math.hypot(p[1] - tr[0], p[2] - tr[1]) <= tr[2])) {
					found++;
				}
			}
		}
		System.out.printf("ICON-BRIGHT-SPOT recall=%d/%d (%.1f%%)%n", found, total, 100.0 * found / total);
		assertThat((double) found / total).as("bright-spot detector recall").isLessThan(0.5);
	}

	@Test
	void lowStrokeCharactersRotateLess() {
		assertThat(MultiTypeRenderer.isLowStroke("十")).isTrue();
		assertThat(MultiTypeRenderer.isLowStroke("人")).isTrue();
		assertThat(MultiTypeRenderer.isLowStroke("帆")).isFalse();
	}

	@Test
	void jigsawHasAtLeastThreeTilesOutOfPlace() {
		for (int i = 0; i < 60; i++) {
			MultiTypeRenderer.Jigsaw j = r.renderJigsaw();
			int[] a = j.answer();
			int off = 0;
			for (int c = 0; c < a.length; c++) {
				if (a[c] != c) {
					off++;
				}
			}
			assertThat(off).isGreaterThanOrEqualTo(3);
		}
	}
}
