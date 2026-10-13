package com.mugsun.boot.captcha.image;

import com.mugsun.boot.captcha.CaptchaProperties;
import org.junit.jupiter.api.Test;

import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** M1：假缺口与真缺口同轮廓，数量默认 2、可配置 */
class PuzzleDecoyTest {

	@Test
	void decoysHaveSameOutlineAndConfiguredCount() {
		CaptchaProperties props = new CaptchaProperties();
		assertThat(props.getSlider().getDecoyCount()).isEqualTo(2);
		CaptchaProperties.Slider s = props.getSlider();
		int body = s.getPieceSize();
		int knob = Math.max(6, body / 5);
		int boxW = body + knob * 2;
		int boxH = body + knob;
		Shape real = PuzzleRenderer.outline(0, 0, body, knob, false);
		Rectangle2D rb = real.getBounds2D();
		for (int count : new int[]{0, 1, 2}) {
			for (int i = 0; i < 200; i++) {
				int gapX = boxW + 12 + i % 40;
				List<Shape> d = PuzzleRenderer.decoys(gapX, s.getWidth(), s.getHeight(), body, knob, boxW, boxH, count);
				assertThat(d).hasSize(count);
				for (Shape x : d) {
					Rectangle2D b = x.getBounds2D();
					assertThat(b.getWidth()).isCloseTo(rb.getWidth(), org.assertj.core.data.Offset.offset(1e-6));
					assertThat(b.getHeight()).isCloseTo(rb.getHeight(), org.assertj.core.data.Offset.offset(1e-6));
					// 与「同一位置的真缺口轮廓」路径逐点一致（同 outline、同朝向、无旋转）
					int ox = (int) Math.round(b.getX() - rb.getX());
					int oy = (int) Math.round(b.getY() - rb.getY());
					assertThat(path(x)).isEqualTo(path(PuzzleRenderer.outline(ox, oy, body, knob, false)));
					assertThat(Math.abs(b.getX() - gapX)).isGreaterThanOrEqualTo(boxW);
				}
			}
		}
	}

	private static List<Double> path(Shape s) {
		List<Double> out = new java.util.ArrayList<>();
		double[] c = new double[6];
		for (java.awt.geom.PathIterator it = s.getPathIterator(null); !it.isDone(); it.next()) {
			int type = it.currentSegment(c);
			out.add((double) type);
			for (int i = 0; i < 6; i++) {
				out.add(Math.round(c[i] * 1000) / 1000.0);
			}
		}
		return out;
	}
}
