package com.mugsun.boot.captcha.image;

import com.mugsun.boot.captcha.CaptchaProperties;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;

/**
 * 拼图出题：底图（每题随机缩放/裁切/镜像/调色）上挖一个真缺口 + decoy-count 个与真缺口同轮廓的假缺口（默认 2，0–3），
 * 切出对应拼图块（全高透明图，不暴露纵坐标）；拼图块另加噪声与色偏，不能与缺口内容逐像素匹配。
 * 返回值中的 {@code answerX} 是拼图块图片左边缘在底图原始像素中的横坐标，只入存储不下发。
 */
@Component
public class PuzzleRenderer {

	/** 拼图块图片左右留白 */
	static final int PAD = 2;
	/** 假缺口上限：320 宽底图横向最多再放 3 个不重叠的缺口 */
	static final int MAX_DECOYS = 3;

	private final CaptchaProperties props;
	private final BackgroundLibrary library;

	public PuzzleRenderer(CaptchaProperties props, BackgroundLibrary library) {
		this.props = props;
		this.library = library;
	}

	public record Puzzle(String background, String piece, int bgWidth, int bgHeight,
						 int pieceWidth, int pieceHeight, int answerX) {
	}

	public Puzzle render() {
		CaptchaProperties.Slider cfg = props.getSlider();
		int w = cfg.getWidth();
		int h = cfg.getHeight();
		int body = cfg.getPieceSize();
		int knob = Math.max(6, body / 5);
		int boxW = body + knob * 2;
		int boxH = body + knob;
		java.util.random.RandomGenerator rnd = Rng.get();

		int minX = boxW + 12;
		int maxX = w - boxW - 6;
		int gapX = rnd.nextInt(minX, Math.max(minX + 1, maxX));
		int gapY = rnd.nextInt(6, Math.max(7, h - boxH - 6));

		BufferedImage bg = library.next(w, h);
		Shape real = outline(gapX, gapY, body, knob, false);

		// 拼图块：先从干净底图抠出
		int pieceW = boxW + PAD * 2;
		BufferedImage piece = new BufferedImage(pieceW, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = piece.createGraphics();
		smooth(pg);
		pg.translate(PAD - gapX, 0);
		pg.setClip(real);
		pg.drawImage(bg, 0, 0, null);
		pg.setClip(null);
		jitterPiece(piece, rnd);
		pg.setStroke(new BasicStroke(1.6f));
		pg.setColor(new Color(255, 255, 255, 210));
		pg.draw(real);
		pg.dispose();

		Graphics2D g = bg.createGraphics();
		smooth(g);
		for (Shape decoy : decoys(gapX, w, h, body, knob, boxW, boxH, cfg.getDecoyCount())) {
			shade(g, decoy, 85 + rnd.nextInt(40));
		}
		shade(g, real, 85 + rnd.nextInt(40));
		g.dispose();
		sprinkleNoise(bg);

		return new Puzzle(dataUri("jpeg", toJpeg(bg)), dataUri("png", toPng(piece)), w, h, pieceW, h, gapX - PAD);
	}

	/** 拼图块：整体色偏（逐通道 ±10）+ 逐像素噪声（±9），只动不透明像素 */
	static void jitterPiece(BufferedImage piece, java.util.random.RandomGenerator rnd) {
		int dr = rnd.nextInt(-10, 11);
		int dg = rnd.nextInt(-10, 11);
		int db = rnd.nextInt(-10, 11);
		java.util.random.RandomGenerator n = Rng.noise();
		for (int y = 0; y < piece.getHeight(); y++) {
			for (int x = 0; x < piece.getWidth(); x++) {
				int p = piece.getRGB(x, y);
				int a = p >>> 24;
				if (a == 0) {
					continue;
				}
				int d = n.nextInt(-9, 10);
				int r = BackgroundLibrary.clamp(((p >> 16) & 0xFF) + dr + d);
				int g = BackgroundLibrary.clamp(((p >> 8) & 0xFF) + dg + d);
				int b = BackgroundLibrary.clamp((p & 0xFF) + db + d);
				piece.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
			}
		}
	}

	/** 拼图轮廓：圆角方块 + 顶部、右侧各一个凸起（flip=true 时换到底部、左侧，用于假缺口） */
	static Shape outline(int x, int y, int body, int knob, boolean flip) {
		double r = knob;
		double bx = x + (flip ? r : 0);
		double by = y + (flip ? 0 : r);
		Area a = new Area(new RoundRectangle2D.Double(bx, by, body, body, body / 6.0, body / 6.0));
		double cx = bx + body / 2.0;
		double cy = by + body / 2.0;
		if (flip) {
			a.add(new Area(new Ellipse2D.Double(cx - r, by + body - r * 0.4, r * 2, r * 2)));
			a.add(new Area(new Ellipse2D.Double(bx - r * 1.6, cy - r, r * 2, r * 2)));
		} else {
			a.add(new Area(new Ellipse2D.Double(cx - r, by - r * 1.6, r * 2, r * 2)));
			a.add(new Area(new Ellipse2D.Double(bx + body - r * 0.4, cy - r, r * 2, r * 2)));
		}
		return a;
	}

	/** 假缺口：与真缺口同轮廓（同 outline、同朝向、不旋转），横向互不重叠；数量 decoy-count（0–3） */
	static List<Shape> decoys(int gapX, int w, int h, int body, int knob, int boxW, int boxH, int count) {
		java.util.random.RandomGenerator rnd = Rng.get();
		int n = Math.max(0, Math.min(MAX_DECOYS, count));
		List<Shape> best = new ArrayList<>();
		// 在所有可行横坐标里随机挑；整轮放不满就重来，取放得最多的一轮
		for (int round = 0; round < 50 && best.size() < n; round++) {
			List<Shape> out = new ArrayList<>();
			List<Integer> taken = new ArrayList<>(List.of(gapX));
			while (out.size() < n) {
				List<Integer> free = new ArrayList<>();
				for (int x = 6; x <= Math.max(6, w - boxW - 6); x++) {
					final int cx = x;
					if (taken.stream().noneMatch(t -> Math.abs(t - cx) < boxW + 4)) {
						free.add(x);
					}
				}
				if (free.isEmpty()) {
					break;
				}
				int x = free.get(rnd.nextInt(free.size()));
				int y = rnd.nextInt(6, Math.max(7, h - boxH - 6));
				taken.add(x);
				out.add(outline(x, y, body, knob, false));
			}
			if (out.size() > best.size()) {
				best = out;
			}
		}
		return best;
	}

	private static void shade(Graphics2D g, Shape s, int alpha) {
		g.setComposite(AlphaComposite.SrcOver);
		g.setColor(new Color(10, 16, 30, alpha));
		g.fill(s);
		g.setStroke(new BasicStroke(1.4f));
		g.setColor(new Color(255, 255, 255, Math.min(255, alpha + 40)));
		g.draw(s);
	}

	/** 全图轻噪声：打散抗锯齿边缘的规律，增加模板匹配难度 */
	private static void sprinkleNoise(BufferedImage img) {
		java.util.random.RandomGenerator rnd = Rng.noise();
		int w = img.getWidth();
		int h = img.getHeight();
		int dots = w * h / 6;
		for (int i = 0; i < dots; i++) {
			int x = rnd.nextInt(w);
			int y = rnd.nextInt(h);
			int p = img.getRGB(x, y);
			int d = rnd.nextInt(-7, 8);
			int r = BackgroundLibrary.clamp(((p >> 16) & 0xFF) + d);
			int gg = BackgroundLibrary.clamp(((p >> 8) & 0xFF) + d);
			int b = BackgroundLibrary.clamp((p & 0xFF) + d);
			img.setRGB(x, y, (r << 16) | (gg << 8) | b);
		}
	}

	private static void smooth(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
	}

	private static String dataUri(String kind, byte[] bytes) {
		return "data:image/" + kind + ";base64," + Base64.getEncoder().encodeToString(bytes);
	}

	/** PNG：ImageIO 默认不写 tEXt/iTXt 等文本块，不带可推算坐标的元数据 */
	static byte[] toPng(BufferedImage img) {
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			ImageIO.write(img, "png", out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static byte[] toJpeg(BufferedImage img) {
		Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
		if (!it.hasNext()) {
			return toPng(img);
		}
		ImageWriter writer = it.next();
		try (ByteArrayOutputStream out = new ByteArrayOutputStream();
			 ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
			writer.setOutput(ios);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(0.85f);
			writer.write(null, new IIOImage(img, null, null), param);
			ios.flush();
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			writer.dispose();
		}
	}
}
