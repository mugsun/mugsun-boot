package com.mugsun.boot.captcha.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.captcha.CaptchaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * ROTATE / WORD_CLICK / ICON_CLICK / JIGSAW 出题。只借鉴 tianai-captcha 的题型思路，代码与素材自有。
 * 所有答案以原始像素/角度返回给调用方写入存储，不进入图片元数据。
 */
@Component
public class MultiTypeRenderer {

	private static final Logger log = LoggerFactory.getLogger(MultiTypeRenderer.class);

	/** 点选文字字表：须全部包含在 captcha/font 子集内；去掉了形近字 */
	static final String WORDS = "山水花草木林森日月星云雨雪风天地人口手心目耳火土石田金禾米竹马牛羊鱼鸟虫贝龙虎兔猫狗鸡鸭鹅"
		+ "春夏秋冬东南西北上下左右中大小多少高长方圆红黄蓝绿白黑紫青光明早晚开关门窗桌椅书本笔纸画歌舞茶果瓜菜桥路车船城村家园"
		+ "海湖河江岛岸沙泉松柏梅兰菊荷桃李杏柳叶根灯伞帽衣鞋包钟表球琴棋旗塔湾港航";

	private final CaptchaProperties props;
	private final BackgroundLibrary library;
	private final Font font;
	private volatile List<BufferedImage> rotatePool;
	private volatile List<Icon> iconPool;

	/** confusable：manifest.json 里该图标所属的易混组名（可属多组）；同一题内每组最多出现一个 */
	public record Icon(String slug, String name, Set<String> confusable, BufferedImage image) {
	}

	public record Rotate(String background, String piece, int bgWidth, int bgHeight, int pieceSize, int answerAngle) {
	}

	/** 点选：answer 为每个目标中心与命中半径（原始像素），按提示顺序 */
	public record Click(String background, int bgWidth, int bgHeight, List<String> prompt, String promptImage,
						List<int[]> answer, int itemSize) {
	}

	/** 拼图：tiles 按打乱后的位置给出；answer[cell] = 应放入该格的 tiles 下标 */
	public record Jigsaw(List<String> tiles, int bgWidth, int bgHeight, int rows, int cols, int tileWidth,
						 int tileHeight, int[] answer) {
	}

	public MultiTypeRenderer(CaptchaProperties props, BackgroundLibrary library) {
		this.props = props;
		this.library = library;
		this.font = loadFont();
	}

	// ------------------------------------------------------------------ ROTATE

	public Rotate renderRotate() {
		CaptchaProperties.Slider s = props.getSlider();
		CaptchaProperties.Rotate cfg = props.getRotate();
		RandomGenerator rnd = Rng.get();
		int w = s.getWidth();
		int h = s.getHeight();
		int d = Math.max(60, Math.min(cfg.getPieceSize(), h - 4));
		int applied = randomAngle(cfg.getMinAngle(), cfg.getKeyboardStep(), rnd);
		List<BufferedImage> pool = rotatePool();
		BufferedImage src = pool.isEmpty() ? library.next(d, d) : pool.get(rnd.nextInt(pool.size()));
		BufferedImage piece = new BufferedImage(d, d, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = piece.createGraphics();
		smooth(g);
		g.setClip(new Ellipse2D.Double(0, 0, d, d));
		g.rotate(Math.toRadians(applied), d / 2.0, d / 2.0);
		g.drawImage(src, 0, 0, d, d, null);
		g.dispose();
		noise(piece, 5);
		BufferedImage bg = library.next(w, h);
		Graphics2D bgG = bg.createGraphics();
		smooth(bgG);
		bgG.setColor(new Color(255, 255, 255, 120));
		bgG.fillRect(0, 0, w, h);
		bgG.setColor(new Color(20, 30, 50, 60));
		bgG.fill(new Ellipse2D.Double((w - d) / 2.0 - 3, (h - d) / 2.0 - 3, d + 6, d + 6));
		bgG.dispose();
		noise(bg, 6);
		int answer = Math.floorMod(360 - applied, 360);
		return new Rotate(jpeg(bg), png(piece), w, h, d, answer);
	}

	/**
	 * 服务端施加的顺时针旋转角：keyboardStep 的整数倍，且落在 [minAngle, 360 − minAngle]（minAngle 至少 30°）。
	 * 于是纠正量 360 − applied 也是步长整数倍，键盘按整数次即可精确回正。
	 */
	public static int randomAngle(int minAngle, int keyboardStep, RandomGenerator rnd) {
		int step = Math.max(1, Math.min(90, keyboardStep));
		int min = Math.max(30, Math.min(150, minAngle));
		int lo = (min + step - 1) / step;
		int hi = (360 - min) / step;
		if (hi < lo) {
			step = 1;
			lo = min;
			hi = 360 - min;
		}
		return rnd.nextInt(lo, hi + 1) * step;
	}

	// ------------------------------------------------------------------ CLICK

	public Click renderWordClick() {
		CaptchaProperties.Click cfg = props.getClick();
		RandomGenerator rnd = Rng.get();
		int total = cfg.getCount() + Math.max(0, cfg.getDecoys());
		List<Character> chars = new ArrayList<>();
		for (char c : WORDS.toCharArray()) {
			if (!chars.contains(c)) {
				chars.add(c);
			}
		}
		Collections.shuffle(chars, new java.util.Random(rnd.nextLong()));
		List<Character> picked = chars.subList(0, total);
		int w = props.getSlider().getWidth();
		int h = props.getSlider().getHeight();
		BufferedImage bg = library.next(w, h);
		Graphics2D g = bg.createGraphics();
		smooth(g);
		distractLines(g, w, h, rnd);
		int size = 32;
		List<int[]> pos = scatter(total, w, h, size, rnd);
		List<int[]> answer = new ArrayList<>();
		for (int i = 0; i < total; i++) {
			int[] p = pos.get(i);
			int fs = size - 3 + rnd.nextInt(7);
			drawGlyph(g, String.valueOf(picked.get(i)), p[0], p[1], fs, rnd.nextInt(-35, 36), rnd);
			if (i < cfg.getCount()) {
				answer.add(new int[]{p[0], p[1], Math.max(cfg.getMinRadiusPx(), Math.round(fs * 0.75f))});
			}
		}
		g.dispose();
		noise(bg, 8);
		List<String> prompt = new ArrayList<>();
		for (int i = 0; i < cfg.getCount(); i++) {
			prompt.add(String.valueOf(picked.get(i)));
		}
		BufferedImage pi = new BufferedImage(34 * prompt.size() + 8, 36, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = pi.createGraphics();
		smooth(pg);
		for (int i = 0; i < prompt.size(); i++) {
			drawGlyph(pg, prompt.get(i), 21 + i * 34, 18, 24, rnd.nextInt(-12, 13), rnd);
		}
		pg.dispose();
		return new Click(jpeg(bg), w, h, prompt, png(pi), answer, size);
	}

	public Click renderIconClick() {
		CaptchaProperties.Click cfg = props.getClick();
		RandomGenerator rnd = Rng.get();
		int total = cfg.getCount() + Math.max(0, cfg.getDecoys());
		List<Icon> icons = pickIcons(total, rnd);
		int w = props.getSlider().getWidth();
		int h = props.getSlider().getHeight();
		BufferedImage bg = library.next(w, h);
		Graphics2D g = bg.createGraphics();
		smooth(g);
		int size = 36;
		List<int[]> pos = scatter(icons.size(), w, h, size + 6, rnd);
		List<int[]> answer = new ArrayList<>();
		for (int i = 0; i < icons.size(); i++) {
			int[] p = pos.get(i);
			int s = size - 2 + rnd.nextInt(6);
			g.setColor(new Color(255, 255, 255, 170));
			g.fill(new Ellipse2D.Double(p[0] - s * 0.7, p[1] - s * 0.7, s * 1.4, s * 1.4));
			AffineTransform old = g.getTransform();
			g.rotate(Math.toRadians(rnd.nextInt(-30, 31)), p[0], p[1]);
			g.drawImage(icons.get(i).image(), p[0] - s / 2, p[1] - s / 2, s, s, null);
			g.setTransform(old);
			if (i < cfg.getCount()) {
				answer.add(new int[]{p[0], p[1], Math.max(cfg.getMinRadiusPx(), Math.round(s * 0.75f))});
			}
		}
		distractLines(g, w, h, rnd);
		g.dispose();
		noise(bg, 8);
		List<String> names = new ArrayList<>();
		BufferedImage pi = new BufferedImage(34 * cfg.getCount() + 8, 36, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = pi.createGraphics();
		smooth(pg);
		for (int i = 0; i < cfg.getCount(); i++) {
			names.add(icons.get(i).name());
			pg.drawImage(icons.get(i).image(), 6 + i * 34, 3, 30, 30, null);
		}
		pg.dispose();
		return new Click(jpeg(bg), w, h, names, png(pi), answer, size);
	}

	/** 选图标：同一易混组（manifest confusable）最多一个；public 仅供测试 */
	public List<Icon> pickIcons(int total, RandomGenerator rnd) {
		List<Icon> pool = new ArrayList<>(iconPool());
		if (pool.size() < total) {
			throw new IllegalStateException("验证码图标不足：" + pool.size());
		}
		Collections.shuffle(pool, new java.util.Random(rnd.nextLong()));
		List<Icon> out = new ArrayList<>();
		Set<String> usedGroups = new HashSet<>();
		for (Icon icon : pool) {
			if (out.size() == total) {
				break;
			}
			if (Collections.disjoint(usedGroups, icon.confusable())) {
				usedGroups.addAll(icon.confusable());
				out.add(icon);
			}
		}
		if (out.size() < total) {
			throw new IllegalStateException("验证码图标不足以避开易混组合");
		}
		return out;
	}

	// ------------------------------------------------------------------ JIGSAW

	public Jigsaw renderJigsaw() {
		CaptchaProperties.Jigsaw cfg = props.getJigsaw();
		int rows = Math.max(2, cfg.getRows());
		int cols = Math.max(2, cfg.getCols());
		int w = props.getSlider().getWidth();
		int h = props.getSlider().getHeight();
		int tw = w / cols;
		int th = h / rows;
		BufferedImage bg = library.next(tw * cols, th * rows);
		noise(bg, 6);
		int n = rows * cols;
		List<Integer> perm = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			perm.add(i);
		}
		java.util.Random shuffler = new java.util.Random(Rng.get().nextLong());
		do {
			Collections.shuffle(perm, shuffler);
		} while (isIdentity(perm));
		// tiles[i] 显示原图第 perm[i] 块；正确排列：格 c 放 perm[i]==c 的那块 i
		List<String> tiles = new ArrayList<>();
		int[] answer = new int[n];
		for (int i = 0; i < n; i++) {
			int src = perm.get(i);
			BufferedImage t = bg.getSubimage((src % cols) * tw, (src / cols) * th, tw, th);
			BufferedImage copy = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = copy.createGraphics();
			g.drawImage(t, 0, 0, null);
			g.dispose();
			noise(copy, 4);
			tiles.add(jpeg(copy));
			answer[src] = i;
		}
		return new Jigsaw(tiles, tw * cols, th * rows, rows, cols, tw, th, answer);
	}

	private static boolean isIdentity(List<Integer> perm) {
		for (int i = 0; i < perm.size(); i++) {
			if (perm.get(i) != i) {
				return false;
			}
		}
		return true;
	}

	// ------------------------------------------------------------------ helpers

	/** 均匀撒点，保证两两中心距离 ≥ 2.2 × size，且离边 ≥ size */
	static List<int[]> scatter(int n, int w, int h, int size, RandomGenerator rnd) {
		for (int attempt = 0; attempt < 200; attempt++) {
			List<int[]> out = new ArrayList<>();
			for (int tries = 0; out.size() < n && tries < 400; tries++) {
				int x = rnd.nextInt(size, w - size);
				int y = rnd.nextInt(size, h - size);
				boolean ok = out.stream().allMatch(p -> Math.hypot(p[0] - x, p[1] - y) >= size * 1.6);
				if (ok) {
					out.add(new int[]{x, y});
				}
			}
			if (out.size() == n) {
				return out;
			}
		}
		throw new IllegalStateException("点选题布局失败");
	}

	private void drawGlyph(Graphics2D g, String text, int cx, int cy, int size, int deg, RandomGenerator rnd) {
		Font f = font.deriveFont(Font.BOLD, (float) size);
		GlyphVector gv = f.createGlyphVector(g.getFontRenderContext(), text);
		java.awt.geom.Rectangle2D b = gv.getVisualBounds();
		AffineTransform at = new AffineTransform();
		at.rotate(Math.toRadians(deg), cx, cy);
		at.translate(cx - b.getCenterX(), cy - b.getCenterY());
		java.awt.Shape shape = at.createTransformedShape(gv.getOutline());
		g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(255, 255, 255, 220));
		g.draw(shape);
		Color[] palette = {new Color(0x1D4ED8), new Color(0x7C3AED), new Color(0x0F766E), new Color(0xB91C1C),
			new Color(0x9A3412), new Color(0x1E293B)};
		g.setColor(palette[rnd.nextInt(palette.length)]);
		g.fill(shape);
	}

	private static void distractLines(Graphics2D g, int w, int h, RandomGenerator rnd) {
		for (int i = 0; i < 5; i++) {
			g.setStroke(new BasicStroke(1f + rnd.nextFloat() * 1.5f));
			g.setColor(new Color(rnd.nextInt(40, 200), rnd.nextInt(40, 200), rnd.nextInt(40, 200), 110));
			int x1 = rnd.nextInt(w);
			int y1 = rnd.nextInt(h);
			g.draw(new java.awt.geom.QuadCurve2D.Double(x1, y1, rnd.nextInt(w), rnd.nextInt(h), rnd.nextInt(w), rnd.nextInt(h)));
		}
		for (int i = 0; i < 40; i++) {
			g.setColor(new Color(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), 120));
			int r = 1 + rnd.nextInt(3);
			g.fillOval(rnd.nextInt(w), rnd.nextInt(h), r, r);
		}
	}

	private static void noise(BufferedImage img, int amp) {
		RandomGenerator rnd = Rng.noise();
		int w = img.getWidth();
		int h = img.getHeight();
		boolean alpha = img.getColorModel().hasAlpha();
		for (int i = 0, dots = w * h / 6; i < dots; i++) {
			int x = rnd.nextInt(w);
			int y = rnd.nextInt(h);
			int p = img.getRGB(x, y);
			if (alpha && (p >>> 24) == 0) {
				continue;
			}
			int d = rnd.nextInt(-amp, amp + 1);
			int r = BackgroundLibrary.clamp(((p >> 16) & 0xFF) + d);
			int gg = BackgroundLibrary.clamp(((p >> 8) & 0xFF) + d);
			int b = BackgroundLibrary.clamp((p & 0xFF) + d);
			img.setRGB(x, y, (p & 0xFF000000) | (r << 16) | (gg << 8) | b);
		}
	}

	private static void smooth(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setComposite(AlphaComposite.SrcOver);
	}

	private static String jpeg(BufferedImage img) {
		return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(PuzzleRenderer.toJpeg(img));
	}

	private static String png(BufferedImage img) {
		return "data:image/png;base64," + Base64.getEncoder().encodeToString(PuzzleRenderer.toPng(img));
	}

	private Font loadFont() {
		try (InputStream in = getClass().getResourceAsStream("/captcha/font/NotoSansSC-Bold-subset.ttf")) {
			if (in != null) {
				return Font.createFont(Font.TRUETYPE_FONT, in);
			}
		} catch (Exception e) {
			log.warn("验证码字体加载失败，使用系统字体：{}", e.getMessage());
		}
		return new Font(Font.SANS_SERIF, Font.BOLD, 32);
	}

	private List<BufferedImage> rotatePool() {
		List<BufferedImage> p = rotatePool;
		if (p == null) {
			synchronized (this) {
				if (rotatePool == null) {
					rotatePool = loadImages(props.getRotate().getDir(), "*.jpg", "*.png");
				}
				p = rotatePool;
			}
		}
		return p;
	}

	private List<Icon> iconPool() {
		List<Icon> p = iconPool;
		if (p == null) {
			synchronized (this) {
				if (iconPool == null) {
					iconPool = loadIcons(props.getIconDir());
				}
				p = iconPool;
			}
		}
		return p;
	}

	private static List<BufferedImage> loadImages(String dir, String... patterns) {
		List<BufferedImage> out = new ArrayList<>();
		String base = dir.endsWith("/") ? dir : dir + "/";
		PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
		for (String pattern : patterns) {
			try {
				for (Resource r : resolver.getResources(base + pattern)) {
					try (InputStream in = r.getInputStream()) {
						BufferedImage img = ImageIO.read(in);
						if (img != null) {
							out.add(img);
						}
					}
				}
			} catch (Exception e) {
				log.debug("验证码素材目录不可用：{}{}", base, pattern);
			}
		}
		return List.copyOf(out);
	}

	/** 图标清单条目：slug、中文名、易混组名（manifest.json 的 confusable，字符串或字符串数组） */
	public record ManifestEntry(String slug, String name, Set<String> confusable) {
	}

	/** 读取默认图标目录的 manifest.json */
	public static List<ManifestEntry> loadManifest() {
		return loadManifest("classpath:captcha/icons/");
	}

	/** 读取 &lt;iconDir&gt;/manifest.json；易混分组只来自清单，代码不写死 */
	public static List<ManifestEntry> loadManifest(String iconDir) {
		List<ManifestEntry> out = new ArrayList<>();
		try (InputStream in = RESOLVER.getResource(base(iconDir) + "manifest.json").getInputStream()) {
			if (in != null) {
				for (JsonNode n : new ObjectMapper().readTree(in)) {
					Set<String> groups = new java.util.LinkedHashSet<>();
					JsonNode c = n.path("confusable");
					if (c.isArray()) {
						c.forEach(x -> groups.add(x.asText()));
					} else if (c.isTextual() && !c.asText().isBlank()) {
						groups.add(c.asText());
					}
					out.add(new ManifestEntry(n.path("slug").asText(), n.path("name_zh").asText(), Set.copyOf(groups)));
				}
			}
		} catch (Exception e) {
			log.warn("验证码图标清单读取失败：{}", e.getMessage());
		}
		return List.copyOf(out);
	}

	private static String base(String dir) {
		String d = dir == null || dir.isBlank() ? "classpath:captcha/icons/" : dir.trim();
		return d.endsWith("/") ? d : d + "/";
	}

	private static final PathMatchingResourcePatternResolver RESOLVER = new PathMatchingResourcePatternResolver();

	private static List<Icon> loadIcons(String iconDir) {
		List<Icon> out = new ArrayList<>();
		for (ManifestEntry e : loadManifest(iconDir)) {
			if (!e.slug().matches("[a-z0-9_-]{1,32}")) {
				continue;
			}
			try (InputStream in = RESOLVER.getResource(base(iconDir) + e.slug() + ".png").getInputStream()) {
				BufferedImage img = ImageIO.read(in);
				if (img != null) {
					out.add(new Icon(e.slug(), e.name(), e.confusable(), img));
				}
			} catch (Exception ex) {
				log.warn("验证码图标读取失败：{}", e.slug());
			}
		}
		return List.copyOf(out);
	}
}
