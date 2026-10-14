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

import static org.assertj.core.api.Assertions.assertThat;

/** 旋转验证码：piece 与 background 来自同一张图（同图挖圆），且下发字段不变 */
class RotateSameImageTest {

	private static final double A = ROTATE_MASK_ALPHA();

	private static double ROTATE_MASK_ALPHA() {
		return MultiTypeRenderer.ROTATE_MASK.getAlpha() / 255.0;
	}

	/** 平滑渐变底图：双线性插值误差小，便于逐像素比对 */
	private static BufferedImage gradient(int w, int h) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int r = x * 255 / (w - 1);
				int g = y * 255 / (h - 1);
				int b = (x + y) * 255 / (w + h - 2);
				img.setRGB(x, y, (r << 16) | (g << 8) | b);
			}
		}
		return img;
	}

	private static int ch(int p, int shift) {
		return (p >> shift) & 0xFF;
	}

	@Test
	void pieceIsCentreOfSameImageRotatedByAppliedAngle() {
		int w = 320, h = 180, d = 120, applied = 75;
		BufferedImage base = gradient(w, h);
		BufferedImage[] pair = MultiTypeRenderer.composeRotate(base, d, applied);
		BufferedImage bg = pair[0];
		BufferedImage piece = pair[1];
		assertThat(piece.getWidth()).isEqualTo(d);
		int x0 = (w - d) / 2, y0 = (h - d) / 2;
		double c = d / 2.0, rad = Math.toRadians(applied);
		int checked = 0;
		for (int py = 0; py < d; py += 3) {
			for (int px = 0; px < d; px += 3) {
				double dx = px + 0.5 - c, dy = py + 0.5 - c;
				if (Math.hypot(dx, dy) > c - 4) {
					continue;
				}
				// 逆旋转回原图坐标（Java2D 正角为屏幕顺时针）
				double sx = c + dx * Math.cos(rad) + dy * Math.sin(rad);
				double sy = c - dx * Math.sin(rad) + dy * Math.cos(rad);
				int bx = x0 + (int) Math.floor(sx), by = y0 + (int) Math.floor(sy);
				int p = piece.getRGB(px, py);
				int o = base.getRGB(bx, by);
				assertThat(p >>> 24).isEqualTo(255);
				for (int s : new int[]{16, 8, 0}) {
					assertThat(Math.abs(ch(p, s) - ch(o, s))).as("piece(%d,%d)", px, py).isLessThanOrEqualTo(6);
				}
				// background 同一位置是原图盖半透明遮罩：可由遮罩反推回原图
				int q = bg.getRGB(bx, by);
				int m = MultiTypeRenderer.ROTATE_MASK.getRGB();
				for (int s : new int[]{16, 8, 0}) {
					double unmasked = (ch(q, s) - A * ch(m, s)) / (1 - A);
					assertThat(Math.abs(unmasked - ch(o, s))).isLessThanOrEqualTo(3.5);
				}
				checked++;
			}
		}
		assertThat(checked).isGreaterThan(800);
		// 圆外：background 就是原图，没有整图白色盖层
		assertThat(piece.getRGB(1, 1) >>> 24).isZero();
		for (int[] pt : new int[][]{{2, 2}, {w - 3, 2}, {2, h - 3}, {w - 3, h - 3}, {x0 - 10, h / 2}}) {
			assertThat(bg.getRGB(pt[0], pt[1])).isEqualTo(base.getRGB(pt[0], pt[1]));
		}
	}

	@Test
	void renderedPairMatchesAtAnswerAngleOnlyAndFieldsUnchanged() throws Exception {
		assertThat(Arrays.stream(MultiTypeRenderer.Rotate.class.getRecordComponents()).map(RecordComponent::getName))
			.containsExactly("background", "piece", "bgWidth", "bgHeight", "pieceSize", "answerAngle");
		CaptchaProperties props = new CaptchaProperties();
		MultiTypeRenderer r = new MultiTypeRenderer(props, new BackgroundLibrary(props));
		String out = System.getProperty("rotate.sample.dir");
		for (int i = 0; i < 5; i++) {
			MultiTypeRenderer.Rotate rot = r.renderRotate();
			BufferedImage bg = decode(rot.background());
			BufferedImage piece = decode(rot.piece());
			assertThat(bg.getWidth()).isEqualTo(rot.bgWidth());
			assertThat(bg.getHeight()).isEqualTo(rot.bgHeight());
			assertThat(piece.getWidth()).isEqualTo(rot.pieceSize());
			assertThat(rot.answerAngle() % props.getRotate().getKeyboardStep()).isZero();
			double right = diff(bg, piece, rot.answerAngle());
			double wrong = diff(bg, piece, rot.answerAngle() + 90);
			assertThat(right).as("diff at answer angle").isLessThan(14);
			assertThat(right * 1.8).as("answer angle must be clearly best: %s vs %s", right, wrong).isLessThan(wrong);
			if (out != null && i == 0) {
				new File(out).mkdirs();
				ImageIO.write(bg, "png", new File(out, "fixed-background.png"));
				ImageIO.write(piece, "png", new File(out, "fixed-piece.png"));
				ImageIO.write(preview(bg, piece, rot.answerAngle()), "png", new File(out, "fixed-solved.png"));
			}
		}
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

	/** piece 转 angle 后与 background 中心（反推遮罩）的平均通道差 */
	private static double diff(BufferedImage bg, BufferedImage piece, int angle) {
		BufferedImage p = rotated(piece, angle);
		int d = p.getWidth(), x0 = (bg.getWidth() - d) / 2, y0 = (bg.getHeight() - d) / 2;
		int m = MultiTypeRenderer.ROTATE_MASK.getRGB();
		double sum = 0;
		int n = 0;
		for (int y = 0; y < d; y++) {
			for (int x = 0; x < d; x++) {
				if (Math.hypot(x + 0.5 - d / 2.0, y + 0.5 - d / 2.0) > d / 2.0 - 6) {
					continue;
				}
				int a = p.getRGB(x, y), q = bg.getRGB(x0 + x, y0 + y);
				for (int s : new int[]{16, 8, 0}) {
					sum += Math.abs((ch(q, s) - A * ch(m, s)) / (1 - A) - ch(a, s));
				}
				n += 3;
			}
		}
		return sum / n;
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
