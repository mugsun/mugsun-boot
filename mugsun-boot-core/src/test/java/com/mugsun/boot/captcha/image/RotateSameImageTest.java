package com.mugsun.boot.captcha.image;

import com.mugsun.boot.captcha.CaptchaProperties;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Base64;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** 旋转验证码：piece 与 background 同图挖圆；孔内不留原像素；对抗 NCC 求解命中率受限；字段不变 */
class RotateSameImageTest {

	private static BufferedImage gradient(int w, int h) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				img.setRGB(x, y, ((x * 255 / (w - 1)) << 16) | ((y * 255 / (h - 1)) << 8) | ((x + y) * 255 / (w + h - 2)));
			}
		}
		return img;
	}

	private static int ch(int p, int shift) {
		return (p >> shift) & 0xFF;
	}

	@Test
	void pieceGeometryIsCentreOfSameImageRotatedByAppliedAngle() {
		int w = 320, h = 180, d = 120, applied = 75;
		BufferedImage base = gradient(w, h);
		BufferedImage piece = MultiTypeRenderer.cutPiece(base, d, applied);
		int x0 = (w - d) / 2, y0 = (h - d) / 2;
		double c = d / 2.0, rad = Math.toRadians(applied);
		int checked = 0;
		for (int py = 0; py < d; py += 3) {
			for (int px = 0; px < d; px += 3) {
				double dx = px + 0.5 - c, dy = py + 0.5 - c;
				if (Math.hypot(dx, dy) > c - 4) {
					continue;
				}
				double sx = c + dx * Math.cos(rad) + dy * Math.sin(rad);
				double sy = c - dx * Math.sin(rad) + dy * Math.cos(rad);
				int p = piece.getRGB(px, py), o = base.getRGB(x0 + (int) Math.floor(sx), y0 + (int) Math.floor(sy));
				for (int s : new int[]{16, 8, 0}) {
					assertThat(Math.abs(ch(p, s) - ch(o, s))).as("piece(%d,%d)", px, py).isLessThanOrEqualTo(6);
				}
				checked++;
			}
		}
		assertThat(checked).isGreaterThan(800);
	}

	@Test
	void holeIsOpaqueAndOutsideIsUntouched() {
		int w = 320, h = 180, d = 120;
		BufferedImage base = gradient(w, h);
		BufferedImage bg = MultiTypeRenderer.holeBackground(base, d);
		int fill = MultiTypeRenderer.ROTATE_HOLE_FILL.getRGB() & 0xFFFFFF;
		double cx = w / 2.0, cy = h / 2.0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				double r = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
				if (r < d / 2.0 + MultiTypeRenderer.ROTATE_HOLE_MARGIN_PX - 3) {
					assertThat(bg.getRGB(x, y) & 0xFFFFFF).as("hole(%d,%d)", x, y).isEqualTo(fill);
				} else if (r > d / 2.0 + MultiTypeRenderer.ROTATE_HOLE_MARGIN_PX + 3) {
					assertThat(bg.getRGB(x, y)).isEqualTo(base.getRGB(x, y));
				}
			}
		}
	}

	@Test
	void disguiseChangesPieceAndFeathersEdge() {
		int d = 120;
		BufferedImage piece = MultiTypeRenderer.cutPiece(gradient(320, 180), d, 90);
		BufferedImage raw = MultiTypeRenderer.cutPiece(gradient(320, 180), d, 90);
		MultiTypeRenderer.disguisePiece(piece, new SplittableRandom(7));
		double diff = 0;
		int n = 0;
		for (int y = 20; y < d - 20; y++) {
			for (int x = 20; x < d - 20; x++) {
				int a = piece.getRGB(x, y), b = raw.getRGB(x, y);
				for (int s : new int[]{16, 8, 0}) {
					diff += Math.abs(ch(a, s) - ch(b, s));
					n++;
				}
			}
		}
		assertThat(diff / n).as("mean channel shift").isGreaterThan(8);
		// 外缘 1px 处 alpha 明显低于内部
		assertThat(piece.getRGB(d / 2, 1) >>> 24).isLessThan(100);
		assertThat(piece.getRGB(d / 2, 10) >>> 24).isEqualTo(255);
	}

	@Test
	void nccSolverRarelyFindsAnswerAndFieldsUnchanged() throws Exception {
		assertThat(Arrays.stream(MultiTypeRenderer.Rotate.class.getRecordComponents()).map(RecordComponent::getName))
			.containsExactly("background", "piece", "bgWidth", "bgHeight", "pieceSize", "answerAngle");
		CaptchaProperties props = new CaptchaProperties();
		MultiTypeRenderer r = new MultiTypeRenderer(props, new BackgroundLibrary(props));
		String out = System.getProperty("rotate.sample.dir");
		int n = Integer.getInteger("rotate.adversarial.n", 100);
		int hits = 0, hitsCentre = 0;
		for (int i = 0; i < n; i++) {
			MultiTypeRenderer.Rotate rot = r.renderRotate();
			BufferedImage bg = decode(rot.background());
			BufferedImage piece = decode(rot.piece());
			assertThat(bg.getWidth()).isEqualTo(rot.bgWidth());
			assertThat(bg.getHeight()).isEqualTo(rot.bgHeight());
			assertThat(piece.getWidth()).isEqualTo(rot.pieceSize());
			assertThat(rot.answerAngle() % props.getRotate().getKeyboardStep()).isZero();
			if (hit(solve(bg, piece), rot.answerAngle())) {
				hits++;
			}
			if (hit(solveAtCentre(bg, piece), rot.answerAngle())) {
				hitsCentre++;
			}
			if (out != null && i == 0) {
				new File(out).mkdirs();
				ImageIO.write(bg, "png", new File(out, "offset-background.png"));
				ImageIO.write(piece, "png", new File(out, "offset-piece.png"));
				ImageIO.write(preview(bg, piece, rot.answerAngle()), "png", new File(out, "offset-solved.png"));
			}
		}
		System.out.printf("ROTATE-ADVERSARIAL offset=%d hole-ring=%d/%d centre-ring=%d/%d%n",
			props.getRotate().getHoleOffsetPx(), hits, n, hitsCentre, n);
		// 目标 < 20%（两种攻击：按孔定位 / 按 piece 显示位置）；当前为临时阈值，见 MAX_ADVERSARIAL_HIT_RATE
		assertThat(Math.max(hits, hitsCentre)).as("NCC solver within ±10° (hole %d, centre %d of %d)", hits, hitsCentre, n)
			.isLessThanOrEqualTo((int) Math.floor(n * MAX_ADVERSARIAL_HIT_RATE));
	}

	private static boolean hit(int guess, int answer) {
		int err = Math.abs(guess - answer) % 360;
		return Math.min(err, 360 - err) <= 10;
	}

	/** 偏移–命中率曲线（手动：-Drotate.offset.curve=0,4,6,8,10,12,15 -Drotate.adversarial.n=100） */
	@Test
	void offsetCurve() throws Exception {
		String spec = System.getProperty("rotate.offset.curve");
		org.junit.jupiter.api.Assumptions.assumeTrue(spec != null);
		int n = Integer.getInteger("rotate.adversarial.n", 100);
		for (String o : spec.split(",")) {
			CaptchaProperties props = new CaptchaProperties();
			props.getRotate().setHoleOffsetPx(Integer.parseInt(o.trim()));
			MultiTypeRenderer r = new MultiTypeRenderer(props, new BackgroundLibrary(props));
			int a = 0, b = 0;
			for (int i = 0; i < n; i++) {
				MultiTypeRenderer.Rotate rot = r.renderRotate();
				BufferedImage bg = decode(rot.background()), piece = decode(rot.piece());
				String out = System.getProperty("rotate.sample.dir");
				if (out != null && i == 0) {
					new File(out).mkdirs();
					ImageIO.write(bg, "png", new File(out, "offset-" + o.trim() + "-background.png"));
					ImageIO.write(piece, "png", new File(out, "offset-" + o.trim() + "-piece.png"));
					ImageIO.write(preview(bg, piece, rot.answerAngle()), "png", new File(out, "offset-" + o.trim() + "-solved.png"));
				}
				a += hit(solve(bg, piece), rot.answerAngle()) ? 1 : 0;
				b += hit(solveAtCentre(bg, piece), rot.answerAngle()) ? 1 : 0;
			}
			System.out.printf("ROTATE-CURVE offset=%s n=%d hole-ring=%d centre-ring=%d%n", o.trim(), n, a, b);
		}
	}

	/**
	 * KNOWN GAP / TEMPORARY: target is < 20% for both attacks. Measured with hole offsets 0–15px it stays at 38–63%
	 * (see offsetCurve and the PR), so this keeps #35's temporary regression guard (≤ 65%) until a design reaches the target.
	 */
	static final double MAX_ADVERSARIAL_HIT_RATE = 0.65;

	/** R5-1: with the hole opaque, hole-only NCC (the review script's method) must show no clear peak; hits near chance */
	@Test
	void holeOnlyNccHasNoPeak() throws Exception {
		CaptchaProperties props = new CaptchaProperties();
		MultiTypeRenderer r = new MultiTypeRenderer(props, new BackgroundLibrary(props));
		int n = 60, hits = 0;
		double maxGap = 0, sumGap = 0, maxBest = -1;
		for (int i = 0; i < n; i++) {
			MultiTypeRenderer.Rotate rot = r.renderRotate();
			double[] res = solveHoleOnly(decode(rot.background()), decode(rot.piece()));
			int err = Math.abs((int) res[0] - rot.answerAngle()) % 360;
			if (Math.min(err, 360 - err) <= 10) {
				hits++;
			}
			double gap = res[1] - res[2];
			maxGap = Math.max(maxGap, gap);
			sumGap += gap;
			maxBest = Math.max(maxBest, res[1]);
		}
		System.out.printf("ROTATE-HOLE-ONLY hits=%d/%d (%.1f%%, chance~5.8%%) bestNccMax=%.4f gapMean=%.4f gapMax=%.4f%n",
			hits, n, 100.0 * hits / n, maxBest, sumGap / n, maxGap);
		assertThat(maxBest).as("hole-only NCC best").isLessThan(0.2);
		assertThat(maxGap).as("best minus second-best").isLessThan(0.05);
		assertThat(hits).as("hole-only hits near chance").isLessThanOrEqualTo(n * 15 / 100);
	}

	@Test
	void platformDefaultTypesStaySliderOnly() {
		assertThat(new CaptchaProperties().getDefaultTypes()).containsExactly("SLIDER").doesNotContain("ROTATE");
	}

	/** Hole-interior NCC only: returns {best angle, best score, second-best score more than 15° away} */
	static double[] solveHoleOnly(BufferedImage bg, BufferedImage piece) {
		int d = piece.getWidth(), x0 = (bg.getWidth() - d) / 2, y0 = (bg.getHeight() - d) / 2;
		double c = d / 2.0;
		double[] sc = new double[360];
		for (int a = 0; a < 360; a += 2) {
			BufferedImage p = rotated(piece, a);
			double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
			int m = 0;
			for (int y = 0; y < d; y++) {
				for (int x = 0; x < d; x++) {
					if (Math.hypot(x + 0.5 - c, y + 0.5 - c) > c - 4) {
						continue;
					}
					double g1 = gray(p.getRGB(x, y)), g2 = gray(bg.getRGB(x0 + x, y0 + y));
					sa += g1; sb += g2; saa += g1 * g1; sbb += g2 * g2; sab += g1 * g2;
					m++;
				}
			}
			double va = saa - sa * sa / m, vb = sbb - sb * sb / m;
			sc[a] = va > 1e-6 && vb > 1e-6 ? (sab - sa * sb / m) / Math.sqrt(va * vb) : 0;
		}
		int best = 0;
		for (int a = 0; a < 360; a += 2) {
			if (sc[a] > sc[best]) {
				best = a;
			}
		}
		double second = -1;
		for (int a = 0; a < 360; a += 2) {
			int e = Math.abs(a - best);
			if (Math.min(e, 360 - e) > 15) {
				second = Math.max(second, sc[a]);
			}
		}
		return new double[] {best, sc[best], second};
	}

	/**
	 * 移植自安全评审的 NCC 求解器：piece 转一整圈（步长 2°），每个角度对 piece 圆盘与孔内区域求 NCC，
	 * 再对 piece 外缘环与孔外环的角度剖面求 NCC，取两项之和最大的角度（即预测的顺时针回正角）。
	 */
	/** 攻击 A：从底图量出孔（填充色像素的质心），取孔外紧邻 6px 环 */
	static int solve(BufferedImage bg, BufferedImage piece) {
		double[] hc = locateHole(bg);
		int d = piece.getWidth();
		return solve(bg, piece, hc[0], hc[1], d / 2.0 + MultiTypeRenderer.ROTATE_HOLE_MARGIN_PX + 2, false);
	}

	/** 攻击 B：假定 piece 属于底图中心（前端显示位置），取中心外 2–8px 环，跳过被孔填充遮住的像素 */
	static int solveAtCentre(BufferedImage bg, BufferedImage piece) {
		int d = piece.getWidth();
		return solve(bg, piece, bg.getWidth() / 2.0, bg.getHeight() / 2.0, d / 2.0 + 2, true);
	}

	static boolean isFill(int p) {
		int f = MultiTypeRenderer.ROTATE_HOLE_FILL.getRGB();
		return Math.abs(ch(p, 16) - ch(f, 16)) <= 14 && Math.abs(ch(p, 8) - ch(f, 8)) <= 14 && Math.abs(ch(p, 0) - ch(f, 0)) <= 14;
	}

	static double[] locateHole(BufferedImage bg) {
		double sx = 0, sy = 0;
		int n = 0;
		for (int y = 0; y < bg.getHeight(); y++) {
			for (int x = 0; x < bg.getWidth(); x++) {
				if (isFill(bg.getRGB(x, y))) {
					sx += x + 0.5;
					sy += y + 0.5;
					n++;
				}
			}
		}
		return n == 0 ? new double[] {bg.getWidth() / 2.0, bg.getHeight() / 2.0} : new double[] {sx / n, sy / n};
	}

	/** 以 (cx, cy) 为 piece 中心：圆盘 NCC + piece 外缘环与 ro..ro+6 环的角度剖面 NCC，取和最大的角度 */
	static int solve(BufferedImage bg, BufferedImage piece, double cx, double cy, double ro, boolean skipFill) {
		int d = piece.getWidth();
		int x0 = (int) Math.round(cx - d / 2.0), y0 = (int) Math.round(cy - d / 2.0);
		double c = d / 2.0;
		int bins = 180;
		double[] outer = new double[bins];
		int[] oc = new int[bins];
		for (int y = Math.max(0, y0 - 30); y < Math.min(bg.getHeight(), y0 + d + 30); y++) {
			for (int x = Math.max(0, x0 - 30); x < Math.min(bg.getWidth(), x0 + d + 30); x++) {
				double dx = x - x0 + 0.5 - c, dy = y - y0 + 0.5 - c, rr = Math.hypot(dx, dy);
				if (rr > ro && rr < ro + 6 && !(skipFill && isFill(bg.getRGB(x, y)))) {
					int b = bin(dx, dy, bins);
					outer[b] += gray(bg.getRGB(x, y));
					oc[b]++;
				}
			}
		}
		avg(outer, oc);
		double best = -9;
		int bestA = 0;
		for (int a = 0; a < 360; a += 2) {
			BufferedImage p = rotated(piece, a);
			double[] inner = new double[bins];
			int[] ic = new int[bins];
			double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
			int m = 0;
			for (int y = 0; y < d; y++) {
				for (int x = 0; x < d; x++) {
					int pv = p.getRGB(x, y);
					if ((pv >>> 24) < 16) {
						continue;
					}
					double dx = x + 0.5 - c, dy = y + 0.5 - c, rr = Math.hypot(dx, dy);
					double g1 = gray(pv);
					if (rr > c - 9 && rr < c - 1) {
						int b = bin(dx, dy, bins);
						inner[b] += g1;
						ic[b]++;
					}
					int bx = Math.max(0, Math.min(bg.getWidth() - 1, x0 + x)), by = Math.max(0, Math.min(bg.getHeight() - 1, y0 + y));
					double g2 = gray(bg.getRGB(bx, by));
					sa += g1; sb += g2; saa += g1 * g1; sbb += g2 * g2; sab += g1 * g2;
					m++;
				}
			}
			avg(inner, ic);
			double va = saa - sa * sa / m, vb = sbb - sb * sb / m;
			double disc = va > 1e-6 && vb > 1e-6 ? (sab - sa * sb / m) / Math.sqrt(va * vb) : 0;
			double score = disc + ncc(inner, outer);
			if (score > best) {
				best = score;
				bestA = a;
			}
		}
		return bestA;
	}

	private static int bin(double dx, double dy, int bins) {
		return (int) Math.min(bins - 1, (Math.atan2(dy, dx) + Math.PI) / (2 * Math.PI) * bins);
	}

	private static void avg(double[] v, int[] c) {
		double mean = 0;
		int k = 0;
		for (int i = 0; i < v.length; i++) {
			if (c[i] > 0) {
				v[i] /= c[i];
				mean += v[i];
				k++;
			}
		}
		mean = k == 0 ? 0 : mean / k;
		for (int i = 0; i < v.length; i++) {
			if (c[i] == 0) {
				v[i] = mean;
			}
		}
	}

	private static double ncc(double[] a, double[] b) {
		double ma = Arrays.stream(a).average().orElse(0), mb = Arrays.stream(b).average().orElse(0);
		double ab = 0, aa = 0, bb = 0;
		for (int i = 0; i < a.length; i++) {
			ab += (a[i] - ma) * (b[i] - mb);
			aa += (a[i] - ma) * (a[i] - ma);
			bb += (b[i] - mb) * (b[i] - mb);
		}
		return aa > 1e-9 && bb > 1e-9 ? ab / Math.sqrt(aa * bb) : 0;
	}

	private static double gray(int p) {
		return 0.299 * ch(p, 16) + 0.587 * ch(p, 8) + 0.114 * ch(p, 0);
	}

	private static BufferedImage decode(String dataOrB64) throws Exception {
		String b64 = dataOrB64.contains(",") ? dataOrB64.substring(dataOrB64.indexOf(',') + 1) : dataOrB64;
		return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
	}

	private static BufferedImage rotated(BufferedImage piece, int angle) {
		int d = piece.getWidth();
		BufferedImage o = new BufferedImage(d, d, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = o.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.rotate(Math.toRadians(angle), d / 2.0, d / 2.0);
		g.drawImage(piece, 0, 0, null);
		g.dispose();
		return o;
	}

	private static BufferedImage preview(BufferedImage bg, BufferedImage piece, int answer) {
		BufferedImage o = new BufferedImage(bg.getWidth(), bg.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = o.createGraphics();
		g.drawImage(bg, 0, 0, null);
		g.drawImage(rotated(piece, answer), (bg.getWidth() - piece.getWidth()) / 2, (bg.getHeight() - piece.getHeight()) / 2, null);
		g.dispose();
		return o;
	}
}
