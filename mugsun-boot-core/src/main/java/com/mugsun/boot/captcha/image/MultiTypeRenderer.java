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

	/** WORD_CLICK 词库（3–4 字常用词语/成语，自行整理）；字体子集须覆盖其中全部字 */
	public static final String WORDS_RESOURCE = "/captcha/words.txt";
	/** 干扰字映射：字<TAB>形近字<TAB>同音字（自行整理）；字体子集须覆盖其中全部字 */
	public static final String CONFUSABLE_RESOURCE = "/captcha/confusable-chars.txt";
	/** 点选目标/干扰之间的最小间距 = 两个命中半径之和 + 该余量（原始像素） */
	public static final int SPACING_MARGIN = 6;
	/** 字号、图标边长的随机浮动上限（名义值 +3） */
	static final int SIZE_JITTER = 3;

	private final CaptchaProperties props;
	private final BackgroundLibrary library;
	private final Font font;
	private volatile List<BufferedImage> rotatePool;
	private volatile List<Icon> iconPool;
	private final List<String> words;
	private final String charPool;
	private final java.util.Map<String, List<String>> confusableChars;
	private final boolean iconClickAvailable;

	/** confusable：manifest.json 里该图标所属的易混组名（可属多组）；同一题内每组最多出现一个 */
	public record Icon(String slug, String name, String nameEn, Set<String> confusable, BufferedImage image) {
		/** 英文提示名；清单未给 name_en 时退回中文名 */
		public String name(boolean english) {
			return english && nameEn != null && !nameEn.isBlank() ? nameEn : name;
		}
	}

	public record Rotate(String background, String piece, int bgWidth, int bgHeight, int pieceSize, int answerAngle) {
	}

	/** 点选：answer 为每个目标中心与命中半径（原始像素），按提示顺序 */
	public record Click(String background, int bgWidth, int bgHeight, List<String> prompt, String promptImage,
						List<int[]> answer, int itemSize, String promptText, List<int[]> decoys,
						List<String> decoyChars) {
	}

	/** 拼图：tiles 按打乱后的位置给出；answer[cell] = 应放入该格的 tiles 下标 */
	public record Jigsaw(List<String> tiles, int bgWidth, int bgHeight, int rows, int cols, int tileWidth,
						 int tileHeight, int[] answer) {
	}

	public MultiTypeRenderer(CaptchaProperties props, BackgroundLibrary library) {
		this.props = props;
		this.library = library;
		this.font = loadFont();
		this.words = loadWords(props.getClick().getWordsFile());
		StringBuilder pool = new StringBuilder();
		words.stream().flatMapToInt(String::chars).distinct().sorted().forEach(c -> pool.append((char) c));
		this.charPool = pool.toString();
		this.confusableChars = loadConfusableChars();
		this.iconClickAvailable = validateIcons();
	}

	/** 启动时校验图标目录：manifest 可解析且可用图标足够（含易混约束）；否则 WARN 并把 ICON_CLICK 从可用题型中去掉 */
	private boolean validateIcons() {
		int need = props.getClick().getCount() + Math.max(0, props.getClick().getDecoys());
		try {
			List<Icon> pool = iconPool();
			if (pool.size() < need) {
				log.warn("验证码图标不可用（{} 下可用图标 {} 个，至少需要 {} 个，或 manifest.json 缺失/损坏）：ICON_CLICK 已停用",
					props.getIconDir(), pool.size(), need);
				return false;
			}
			pickIcons(need, Rng.get());
			return true;
		} catch (RuntimeException e) {
			log.warn("验证码图标校验失败（{}）：ICON_CLICK 已停用：{}", props.getIconDir(), e.getMessage());
			return false;
		}
	}

	/** ICON_CLICK 是否可出题（启动校验结果） */
	public boolean iconClickAvailable() {
		return iconClickAvailable;
	}

	/** 词库（只读） */
	public List<String> words() {
		return words;
	}

	/** 干扰字池 = 词库全部字（字体子集据此生成） */
	public String charPool() {
		return charPool;
	}

	/** 字体是否能显示 text 的每个字（测试用） */
	public boolean fontCovers(String text) {
		return font.canDisplayUpTo(text) == -1;
	}

	/** 某字的形近字 + 同音字（形近在前），无则空 */
	public List<String> confusableOf(String ch) {
		return confusableChars.getOrDefault(ch, List.of());
	}

	/** 映射表里出现的全部字（测试用：须都在字体里） */
	public String confusablePool() {
		StringBuilder sb = new StringBuilder();
		confusableChars.forEach((k, v) -> {
			sb.append(k);
			v.forEach(sb::append);
		});
		return sb.toString();
	}

	private static java.util.Map<String, List<String>> loadConfusableChars() {
		java.util.Map<String, List<String>> out = new java.util.HashMap<>();
		try (InputStream in = MultiTypeRenderer.class.getResourceAsStream(CONFUSABLE_RESOURCE)) {
			if (in != null) {
				for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
					if (line.isBlank() || line.startsWith("#")) {
						continue;
					}
					String[] f = line.split("\t", -1);
					List<String> list = new ArrayList<>();
					for (int i = 1; i < f.length; i++) {
						f[i].codePoints().forEach(cp -> {
							String c = new String(Character.toChars(cp));
							if (!c.isBlank() && !list.contains(c)) {
								list.add(c);
							}
						});
					}
					out.put(f[0].strip(), List.copyOf(list));
				}
			}
		} catch (Exception e) {
			log.warn("验证码干扰字映射读取失败：{}", e.getMessage());
		}
		return java.util.Map.copyOf(out);
	}

	/** 读词库：wordsFile 非空时读私有词库（classpath: 或 file:），失败/无可用词则 WARN 并回落内置词库 */
	private List<String> loadWords(String wordsFile) {
		if (wordsFile != null && !wordsFile.isBlank()) {
			try (InputStream in = new org.springframework.core.io.DefaultResourceLoader().getResource(wordsFile.strip())
				.getInputStream()) {
				List<String> all = parseWords(in);
				List<String> ok = all.stream().filter(w -> font.canDisplayUpTo(w) == -1).toList();
				if (ok.size() < all.size()) {
					log.warn("验证码私有词库 {} 中 {} 条含字体子集外的字，已忽略（需同时提供覆盖这些字的字体，或只用内置子集内的字）",
						wordsFile, all.size() - ok.size());
				}
				if (!ok.isEmpty()) {
					log.info("WORD_CLICK 使用私有词库 {}（{} 条）", wordsFile, ok.size());
					return List.copyOf(ok);
				}
				log.warn("验证码私有词库 {} 无可用词，回落内置词库", wordsFile);
			} catch (Exception e) {
				log.warn("验证码私有词库 {} 读取失败，回落内置词库：{}", wordsFile, e.getMessage());
			}
		}
		List<String> out = new ArrayList<>();
		try (InputStream in = MultiTypeRenderer.class.getResourceAsStream(WORDS_RESOURCE)) {
			if (in != null) {
				out.addAll(parseWords(in));
			}
		} catch (Exception e) {
			log.warn("验证码词库读取失败：{}", e.getMessage());
		}
		if (out.isEmpty()) {
			out.add("一帆风顺");
		}
		return List.copyOf(out);
	}

	/** 每行一个 3–4 字词；空行与 # 开头为注释；去重 */
	static List<String> parseWords(InputStream in) throws java.io.IOException {
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
			String w = line.strip();
			int n = w.codePointCount(0, w.length());
			if (!w.isEmpty() && !w.startsWith("#") && n >= 3 && n <= 4) {
				out.add(w);
			}
		}
		return new ArrayList<>(out);
	}

	/** 命中半径：max(min-radius-px, 0.75 × 字号/边长) */
	int hitRadius(int size) {
		return Math.max(props.getClick().getMinRadiusPx(), Math.round(size * 0.75f));
	}

	/** 两个点选元素中心的最小距离：2 × 最大命中半径 + SPACING_MARGIN */
	int minSpacing(int nominalSize) {
		return 2 * hitRadius(nominalSize + SIZE_JITTER) + SPACING_MARGIN;
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
		// 同图挖圆（参考 tianai StandardRotateImageCaptchaGenerator）：一张经扰动（随机缩放/裁切/镜像/调色）的 w×h 原图，
		// 中心 d×d 圆旋转 applied 度作 piece；background 的圆孔用不透明纯色填充（不保留原像素）并描细边。
		// piece 另做独立调色/模糊/噪点与边缘羽化，削弱逐像素和边缘环匹配（安全评审 R5-1/2/3）
		BufferedImage base = pool.isEmpty() ? library.next(w, h)
			: BackgroundLibrary.perturb(pool.get(rnd.nextInt(pool.size())), w, h, rnd);
		BufferedImage piece = cutPiece(base, d, applied);
		disguisePiece(piece, rnd);
		BufferedImage bg = holeBackground(base, d);
		noise(bg, 6);
		int answer = Math.floorMod(360 - applied, 360);
		return new Rotate(jpeg(bg), png(piece), w, h, d, answer);
	}

	/** 圆孔不透明填充色（与原图无关的中性灰）与描边颜色 */
	static final Color ROTATE_HOLE_FILL = new Color(206, 210, 218);
	static final Color ROTATE_OUTLINE = new Color(255, 255, 255, 220);
	/** 孔比 piece 半径多出的不透明边距（像素）：遮住紧贴圆边的原图像素，削弱孔外环与 piece 外缘的连续性匹配 */
	static final int ROTATE_HOLE_MARGIN_PX = 6;
	/** piece 边缘羽化宽度（像素） */
	static final int ROTATE_FEATHER_PX = 5;
	/** piece 独立噪点幅度（逐像素逐通道） */
	static final int ROTATE_PIECE_NOISE = 9;

	/** 底图中心 d×d 圆，绕圆心顺时针旋转 applied 度（纯几何，不含扰动） */
	static BufferedImage cutPiece(BufferedImage base, int d, int applied) {
		int x0 = (base.getWidth() - d) / 2;
		int y0 = (base.getHeight() - d) / 2;
		BufferedImage piece = new BufferedImage(d, d, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = piece.createGraphics();
		smooth(g);
		g.setClip(new Ellipse2D.Double(0, 0, d, d));
		g.rotate(Math.toRadians(applied), d / 2.0, d / 2.0);
		g.drawImage(base, -x0, -y0, null);
		g.dispose();
		return piece;
	}

	/** 底图拷贝，中心 d×d 圆孔不透明纯色填充（不留任何原像素）并描细边 */
	static BufferedImage holeBackground(BufferedImage base, int d) {
		int w = base.getWidth();
		int h = base.getHeight();
		BufferedImage bg = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = bg.createGraphics();
		g.drawImage(base, 0, 0, null);
		smooth(g);
		int m = ROTATE_HOLE_MARGIN_PX;
		Ellipse2D hole = new Ellipse2D.Double((w - d) / 2.0 - m, (h - d) / 2.0 - m, d + 2.0 * m, d + 2.0 * m);
		g.setColor(ROTATE_HOLE_FILL);
		g.fill(hole);
		g.setStroke(new BasicStroke(1.5f));
		g.setColor(ROTATE_OUTLINE);
		g.draw(hole);
		g.dispose();
		return bg;
	}

	/**
	 * piece 独立扰动：亮度 ±10–15%、色相 ±10–15°、高斯模糊 σ≈0.8、逐像素噪点 ±9，
	 * 外缘 ROTATE_FEATHER_PX 像素羽化（alpha 渐隐，颜色同步向填充色过渡）。
	 */
	static void disguisePiece(BufferedImage piece, RandomGenerator rnd) {
		int d = piece.getWidth();
		float bright = 1f + (rnd.nextBoolean() ? 1 : -1) * (0.10f + rnd.nextFloat() * 0.05f);
		float hue = (rnd.nextBoolean() ? 1 : -1) * (10f + rnd.nextFloat() * 5f) / 360f;
		float[] k = gaussian3(0.8);
		java.awt.image.BufferedImage blurred = new java.awt.image.ConvolveOp(new java.awt.image.Kernel(3, 3, k),
			java.awt.image.ConvolveOp.EDGE_NO_OP, null).filter(piece, null);
		RandomGenerator nz = Rng.noise();
		float[] hsb = new float[3];
		double c = d / 2.0;
		int fr = ROTATE_HOLE_FILL.getRed(), fg = ROTATE_HOLE_FILL.getGreen(), fb = ROTATE_HOLE_FILL.getBlue();
		for (int y = 0; y < d; y++) {
			for (int x = 0; x < d; x++) {
				int p = blurred.getRGB(x, y);
				int a = piece.getRGB(x, y) >>> 24;
				if (a == 0) {
					piece.setRGB(x, y, 0);
					continue;
				}
				Color.RGBtoHSB((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF, hsb);
				int rgb = Color.HSBtoRGB(hsb[0] + hue + 1f, hsb[1], Math.min(1f, hsb[2] * bright));
				int r = ((rgb >> 16) & 0xFF) + nz.nextInt(-ROTATE_PIECE_NOISE, ROTATE_PIECE_NOISE + 1);
				int g = ((rgb >> 8) & 0xFF) + nz.nextInt(-ROTATE_PIECE_NOISE, ROTATE_PIECE_NOISE + 1);
				int b = (rgb & 0xFF) + nz.nextInt(-ROTATE_PIECE_NOISE, ROTATE_PIECE_NOISE + 1);
				double edge = c - Math.hypot(x + 0.5 - c, y + 0.5 - c);
				double t = Math.max(0, Math.min(1, edge / ROTATE_FEATHER_PX));
				if (t < 1) {
					r = (int) Math.round(fr + (r - fr) * t);
					g = (int) Math.round(fg + (g - fg) * t);
					b = (int) Math.round(fb + (b - fb) * t);
					a = (int) Math.round(a * t);
				}
				piece.setRGB(x, y, (a << 24) | (BackgroundLibrary.clamp(r) << 16)
					| (BackgroundLibrary.clamp(g) << 8) | BackgroundLibrary.clamp(b));
			}
		}
	}

	private static float[] gaussian3(double sigma) {
		float[] k = new float[9];
		float sum = 0;
		for (int i = 0; i < 9; i++) {
			int dx = i % 3 - 1, dy = i / 3 - 1;
			k[i] = (float) Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma));
			sum += k[i];
		}
		for (int i = 0; i < 9; i++) {
			k[i] /= sum;
		}
		return k;
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

	/**
	 * 从词库抽一个 3–4 字词，按顺序点字；另混入 1–2 个不在该词里的干扰字（取自词库字集）。
	 * answer 只含词里的字（按顺序）；decoys 为干扰字位置（只供测试，不下发）。
	 */
	public Click renderWordClick() {
		RandomGenerator rnd = Rng.get();
		String word = words.get(rnd.nextInt(words.size()));
		List<String> prompt = new ArrayList<>();
		word.codePoints().forEach(cp -> prompt.add(new String(Character.toChars(cp))));
		int decoyCount = 1 + rnd.nextInt(2);
		List<String> decoys = new ArrayList<>();
		// 优先从题目各字的形近字/同音字里选干扰字（M4 部分落地：仅形近/同音干扰字；提示顺序打乱、提示图扰动未做），不够再从词库字集随机补
		List<String> similar = new ArrayList<>();
		for (String ch : prompt) {
			for (String c : confusableOf(ch)) {
				if (!prompt.contains(c) && !similar.contains(c)) {
					similar.add(c);
				}
			}
		}
		Collections.shuffle(similar, new java.util.Random(rnd.nextLong()));
		for (String c : similar) {
			if (decoys.size() < decoyCount && font.canDisplay(c.codePointAt(0))) {
				decoys.add(c);
			}
		}
		while (decoys.size() < decoyCount) {
			String c = String.valueOf(charPool.charAt(rnd.nextInt(charPool.length())));
			if (!prompt.contains(c) && !decoys.contains(c)) {
				decoys.add(c);
			}
		}
		int total = prompt.size() + decoys.size();
		int w = props.getSlider().getWidth();
		int h = props.getSlider().getHeight();
		BufferedImage bg = library.next(w, h);
		Graphics2D g = bg.createGraphics();
		smooth(g);
		distractLines(g, w, h, rnd);
		int size = 32;
		List<int[]> pos = scatter(total, w, h, hitRadius(size + SIZE_JITTER), minSpacing(size), rnd);
		List<int[]> answer = new ArrayList<>();
		List<int[]> decoyPos = new ArrayList<>();
		for (int i = 0; i < total; i++) {
			int[] p = pos.get(i);
			int fs = size - SIZE_JITTER + rnd.nextInt(2 * SIZE_JITTER + 1);
			String ch = i < prompt.size() ? prompt.get(i) : decoys.get(i - prompt.size());
			int maxDeg = isLowStroke(ch) ? 15 : 35;
			drawGlyph(g, ch, p[0], p[1], fs, rnd.nextInt(-maxDeg, maxDeg + 1), rnd);
			if (i < prompt.size()) {
				answer.add(new int[]{p[0], p[1], hitRadius(fs)});
			} else {
				decoyPos.add(new int[]{p[0], p[1], hitRadius(fs)});
			}
		}
		g.dispose();
		noise(bg, 8);
		BufferedImage pi = new BufferedImage(34 * prompt.size() + 8, 36, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = pi.createGraphics();
		smooth(pg);
		for (int i = 0; i < prompt.size(); i++) {
			drawGlyph(pg, prompt.get(i), 21 + i * 34, 18, 24, rnd.nextInt(-12, 13), rnd);
		}
		pg.dispose();
		return new Click(jpeg(bg), w, h, prompt, png(pi), answer, size, word, decoyPos, List.copyOf(decoys));
	}

	/** 笔画少于 3 的字（一、二、十、人…）大角度旋转后易认错（如 十/乂、人/入），只转 ±15° */
	static final String LOW_STROKE = "一乙二十丁厂七卜八人入儿匕几九刁了乃刀力又乂";

	static boolean isLowStroke(String ch) {
		return ch != null && ch.length() == 1 && LOW_STROKE.indexOf(ch.charAt(0)) >= 0;
	}

	public Click renderIconClick() {
		return renderIconClick(false);
	}

	/** 图标点选；english = 提示名用 name_en */
	public Click renderIconClick(boolean english) {
		CaptchaProperties.Click cfg = props.getClick();
		RandomGenerator rnd = Rng.get();
		int total = cfg.getCount() + Math.max(0, cfg.getDecoys());
		List<Icon> icons = pickIcons(total, rnd);
		int w = props.getSlider().getWidth();
		int h = props.getSlider().getHeight();
		BufferedImage bg = library.next(w, h);
		Graphics2D g = bg.createGraphics();
		smooth(g);
		// 干扰线先画，图标盖在上面（线不压图标，图标仍清晰）
		distractLines(g, w, h, rnd);
		int size = 36;
		List<int[]> pos = scatter(icons.size(), w, h, hitRadius(size + SIZE_JITTER), minSpacing(size), rnd);
		List<int[]> answer = new ArrayList<>();
		List<int[]> decoyPos = new ArrayList<>();
		for (int i = 0; i < icons.size(); i++) {
			int[] p = pos.get(i);
			int s = size - SIZE_JITTER + rnd.nextInt(2 * SIZE_JITTER + 1);
			// 不再垫白色圆盘（它让候选位置一眼可找）；改为贴合图标轮廓的柔和投影 + 细白描边
			AffineTransform old = g.getTransform();
			g.rotate(Math.toRadians(rnd.nextInt(-30, 31)), p[0], p[1]);
			BufferedImage img = icons.get(i).image();
			g.drawImage(silhouette(img, new Color(0, 0, 0, 90)), p[0] - s / 2 + 2, p[1] - s / 2 + 2, s, s, null);
			BufferedImage halo = silhouette(img, new Color(255, 255, 255, 150));
			for (int[] o : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
				g.drawImage(halo, p[0] - s / 2 + o[0], p[1] - s / 2 + o[1], s, s, null);
			}
			g.drawImage(img, p[0] - s / 2, p[1] - s / 2, s, s, null);
			g.setTransform(old);
			if (i < cfg.getCount()) {
				answer.add(new int[]{p[0], p[1], hitRadius(s)});
			} else {
				decoyPos.add(new int[]{p[0], p[1], hitRadius(s)});
			}
		}
		g.dispose();
		noise(bg, 8);
		List<String> names = new ArrayList<>();
		BufferedImage pi = new BufferedImage(34 * cfg.getCount() + 8, 36, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = pi.createGraphics();
		smooth(pg);
		for (int i = 0; i < cfg.getCount(); i++) {
			names.add(icons.get(i).name(english));
			pg.drawImage(icons.get(i).image(), 6 + i * 34, 3, 30, 30, null);
		}
		pg.dispose();
		return new Click(jpeg(bg), w, h, names, png(pi), answer, size, null, decoyPos, List.of());
	}

	/** 图标形状的单色剪影（保留 alpha） */
	static BufferedImage silhouette(BufferedImage src, Color c) {
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		int rgb = c.getRGB() & 0xFFFFFF;
		for (int y = 0; y < src.getHeight(); y++) {
			for (int x = 0; x < src.getWidth(); x++) {
				int a = (src.getRGB(x, y) >>> 24) * c.getAlpha() / 255;
				out.setRGB(x, y, (a << 24) | rgb);
			}
		}
		return out;
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
		} while (misplaced(perm) < Math.min(3, n));
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
			tileTint(copy, shuffler);
			noise(copy, 6);
			tiles.add(jpeg(copy));
			answer[src] = i;
		}
		return new Jigsaw(tiles, tw * cols, th * rows, rows, cols, tw, th, answer);
	}

	/** 拼图块各自独立的轻微色偏，打散接缝处的颜色连续性（M3 部分落地：仅块间色偏；接缝扰动、3×3 未做，JIGSAW 仍不宜单独作强题型） */
	private static void tileTint(BufferedImage img, java.util.Random r) {
		int dr = r.nextInt(9) - 4;
		int dg = r.nextInt(9) - 4;
		int db = r.nextInt(9) - 4;
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				int p = img.getRGB(x, y);
				img.setRGB(x, y, (BackgroundLibrary.clamp(((p >> 16) & 0xFF) + dr) << 16)
					| (BackgroundLibrary.clamp(((p >> 8) & 0xFF) + dg) << 8) | BackgroundLibrary.clamp((p & 0xFF) + db));
			}
		}
	}

	/** 不在原位的块数 */
	static int misplaced(List<Integer> perm) {
		int k = 0;
		for (int i = 0; i < perm.size(); i++) {
			if (perm.get(i) != i) {
				k++;
			}
		}
		return k;
	}


	// ------------------------------------------------------------------ helpers

	/** 均匀撒点：两两中心距离 ≥ minDist（调用方传 2 × 最大命中半径 + 余量），离边 ≥ edge */
	static List<int[]> scatter(int n, int w, int h, int edge, int minDist, RandomGenerator rnd) {
		for (int attempt = 0; attempt < 200; attempt++) {
			List<int[]> out = new ArrayList<>();
			for (int tries = 0; out.size() < n && tries < 400; tries++) {
				int x = rnd.nextInt(edge, w - edge);
				int y = rnd.nextInt(edge, h - edge);
				boolean ok = out.stream().allMatch(p -> Math.hypot(p[0] - x, p[1] - y) >= minDist);
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
	public record ManifestEntry(String slug, String name, String nameEn, Set<String> confusable) {
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
					out.add(new ManifestEntry(n.path("slug").asText(), n.path("name_zh").asText(),
						n.path("name_en").asText(""), Set.copyOf(groups)));
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
					out.add(new Icon(e.slug(), e.name(), e.nameEn(), e.confusable(), img));
				}
			} catch (Exception ex) {
				log.warn("验证码图标读取失败：{}", e.slug());
			}
		}
		return List.copyOf(out);
	}
}
