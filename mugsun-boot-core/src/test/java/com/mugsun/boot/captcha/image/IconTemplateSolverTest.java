package com.mugsun.boot.captcha.image;

import com.mugsun.boot.captcha.CaptchaProperties;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ICON_CLICK 回归门（CI 必跑，超标即失败）：攻击者拿公开图标库（仓库 PNG + manifest）做模板匹配
 * （±30° 旋转、32/38px 缩放），分数取灰度 NCC 与饱和度 NCC 的较大者，按提示名依次找最佳位置；
 * 三个目标都落在命中半径内记为全解。
 * <p>
 * 实测（当前渲染器）：2026-10-14 200 题全解 4/200 = 2.0%、单目标 95/600 = 15.8%（早先 b2329c3 40 题 1/40 = 2.5%）。
 * 门槛 ≤ 20%（开源维护官 + 安全官确认）。图标库公开，ICON_CLICK 只能部分抵御自动求解：
 * 默认关闭、定位为辅助题型，不应作为唯一防线。R7-1 的「高对比渐变」方案把全解率推到 37.5%–48.5%，已放弃。
 */
class IconTemplateSolverTest {

	static final double MAX_FULL_SOLVE = 0.20;

	private record Tpl(int size, int[] dx, int[] dy) {
	}

	private static final Map<String, String> SLUG_BY_NAME = new HashMap<>();
	private static final Map<String, List<Tpl>> TPL = new HashMap<>();

	static {
		for (MultiTypeRenderer.ManifestEntry e : MultiTypeRenderer.loadManifest()) {
			SLUG_BY_NAME.put(e.name(), e.slug());
			SLUG_BY_NAME.put(e.nameEn(), e.slug());
		}
	}

	private static List<Tpl> templates(String slug) throws Exception {
		List<Tpl> cached = TPL.get(slug);
		if (cached != null) {
			return cached;
		}
		BufferedImage src;
		try (InputStream in = IconTemplateSolverTest.class.getResourceAsStream("/captcha/icons/" + slug + ".png")) {
			src = ImageIO.read(in);
		}
		List<Tpl> out = new ArrayList<>();
		for (int s : new int[]{32, 38}) {
			for (int deg = -30; deg <= 30; deg += 15) {
				BufferedImage t = new BufferedImage(s + 8, s + 8, BufferedImage.TYPE_INT_ARGB);
				Graphics2D g = t.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.rotate(Math.toRadians(deg), (s + 8) / 2.0, (s + 8) / 2.0);
				g.drawImage(src, 4, 4, s, s, null);
				g.dispose();
				// 采样点：模板内（alpha 高，记 +1）与模板外一圈（alpha 0，记 0），隔 2px 取一个
				List<int[]> pts = new ArrayList<>();
				for (int y = 0; y < s + 8; y += 2) {
					for (int x = 0; x < s + 8; x += 2) {
						pts.add(new int[]{x - (s + 8) / 2, y - (s + 8) / 2, (t.getRGB(x, y) >>> 24) > 128 ? 1 : 0});
					}
				}
				int[] dx = new int[pts.size()], dy = new int[pts.size()];
				for (int i = 0; i < pts.size(); i++) {
					dx[i] = pts.get(i)[0] * (pts.get(i)[2] == 1 ? 1 : 1);
					dy[i] = pts.get(i)[1];
				}
				int[] inside = pts.stream().mapToInt(p -> p[2]).toArray();
				out.add(new Tpl(s, dx, dy));
				MASK.put(out.get(out.size() - 1), inside);
			}
		}
		TPL.put(slug, out);
		return out;
	}

	private static final Map<Tpl, int[]> MASK = new java.util.IdentityHashMap<>();

	/** 以模板为 0/1 向量，与图像通道求 NCC */
	private static double ncc(double[][] ch, int cx, int cy, Tpl t, int[] m) {
		double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
		int n = m.length;
		for (int i = 0; i < n; i++) {
			double a = m[i], b = ch[cy + t.dy()[i]][cx + t.dx()[i]];
			sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b;
		}
		double va = saa - sa * sa / n, vb = sbb - sb * sb / n;
		return va > 1e-9 && vb > 1e-9 ? (sab - sa * sb / n) / Math.sqrt(va * vb) : 0;
	}

	static int[] locate(double[][] gray, double[][] sat, int w, int h, List<Tpl> tpls) {
		double best = -1;
		int bx = 0, by = 0;
		for (Tpl t : tpls) {
			int[] m = MASK.get(t);
			int half = (t.size() + 8) / 2 + 1;
			for (int y = half; y < h - half; y += 3) {
				for (int x = half; x < w - half; x += 3) {
					double sc = Math.max(Math.abs(ncc(gray, x, y, t, m)), Math.abs(ncc(sat, x, y, t, m)));
					if (sc > best) {
						best = sc;
						bx = x;
						by = y;
					}
				}
			}
		}
		return new int[]{bx, by};
	}

	@Test
	void iconClickOffByDefault() {
		assertThat(new CaptchaProperties().getDefaultTypes()).containsExactly("SLIDER").doesNotContain("ICON_CLICK");
	}

	@Test
	void templateSolverRegressionGate() throws Exception {
		CaptchaProperties props = new CaptchaProperties();
		MultiTypeRenderer r = new MultiTypeRenderer(props, new BackgroundLibrary(props));
		int n = Integer.getInteger("icon.solver.n", 200);
		int full = 0, singles = 0, targets = 0;
		for (int k = 0; k < n; k++) {
			MultiTypeRenderer.Click c = r.renderIconClick();
			String b64 = c.background().substring(c.background().indexOf(',') + 1);
			BufferedImage bg = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
			int w = bg.getWidth(), h = bg.getHeight();
			double[][] gray = new double[h][w], sat = new double[h][w];
			float[] hsb = new float[3];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int p = bg.getRGB(x, y);
					int R = (p >> 16) & 0xFF, G = (p >> 8) & 0xFF, B = p & 0xFF;
					gray[y][x] = 0.299 * R + 0.587 * G + 0.114 * B;
					Color.RGBtoHSB(R, G, B, hsb);
					sat[y][x] = hsb[1] * 255;
				}
			}
			boolean all = true;
			for (int i = 0; i < c.prompt().size(); i++) {
				String slug = SLUG_BY_NAME.get(c.prompt().get(i));
				int[] guess = locate(gray, sat, w, h, templates(slug));
				int[] t = c.answer().get(i);
				boolean hit = Math.hypot(guess[0] - t[0], guess[1] - t[1]) <= t[2];
				targets++;
				singles += hit ? 1 : 0;
				all &= hit;
			}
			full += all ? 1 : 0;
		}
		System.out.printf("ICON-TEMPLATE-SOLVER full=%d/%d (%.1f%%) single=%d/%d (%.1f%%)%n",
			full, n, 100.0 * full / n, singles, targets, 100.0 * singles / targets);
		assertThat((double) full / n).as("full-solve rate").isLessThanOrEqualTo(MAX_FULL_SOLVE);
	}
}
