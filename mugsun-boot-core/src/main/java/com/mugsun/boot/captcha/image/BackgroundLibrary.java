package com.mugsun.boot.captcha.image;

import com.mugsun.boot.captcha.CaptchaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 底图库：从配置目录加载 jpg/png 并预缩放缓存；目录为空时生成纯色占位。
 * 每次取图做随机裁切与亮度扰动，降低图库哈希比对的可行性。
 */
@Component
public class BackgroundLibrary {

	private static final Logger log = LoggerFactory.getLogger(BackgroundLibrary.class);
	private static final String[] PATTERNS = {"*.jpg", "*.jpeg", "*.png", "*.JPG", "*.JPEG", "*.PNG"};
	/** 预缩放留出的裁切余量 */
	private static final double OVERSCAN = 1.2;
	private static final int PLACEHOLDER_VARIANTS = 6;

	private final CaptchaProperties props;
	private final AtomicInteger cursor = new AtomicInteger();
	private volatile List<BufferedImage> cache = List.of();
	private volatile boolean placeholder = true;
	private volatile long loadedAtMs;

	public BackgroundLibrary(CaptchaProperties props) {
		this.props = props;
	}

	/** 当前是否在用占位图（目录为空或不可读） */
	public boolean isPlaceholder() {
		ensureLoaded();
		return placeholder;
	}

	public int size() {
		ensureLoaded();
		return cache.size();
	}

	/** 取一张 width×height 的底图副本（可直接在上面绘制） */
	public BufferedImage next(int width, int height) {
		ensureLoaded();
		List<BufferedImage> pool = cache;
		BufferedImage src;
		if ("round-robin".equalsIgnoreCase(props.getBackground().getRotate())) {
			src = pool.get(Math.floorMod(cursor.getAndIncrement(), pool.size()));
		} else {
			src = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
		}
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		int ox = src.getWidth() > width ? rnd.nextInt(src.getWidth() - width + 1) : 0;
		int oy = src.getHeight() > height ? rnd.nextInt(src.getHeight() - height + 1) : 0;
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(src, -ox, -oy, null);
		g.dispose();
		float gain = 0.94f + rnd.nextFloat() * 0.12f;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int p = out.getRGB(x, y);
				out.setRGB(x, y, scale(p, gain));
			}
		}
		return out;
	}

	private void ensureLoaded() {
		long reload = props.getBackground().getReloadSeconds();
		long now = System.currentTimeMillis();
		if (loadedAtMs != 0 && (reload <= 0 || now - loadedAtMs < reload * 1000)) {
			return;
		}
		synchronized (this) {
			if (loadedAtMs != 0 && (reload <= 0 || now - loadedAtMs < reload * 1000)) {
				return;
			}
			reload();
			loadedAtMs = now;
		}
	}

	/** 重新扫描目录（目录内容变化后可主动调用） */
	public synchronized void reload() {
		CaptchaProperties.Slider s = props.getSlider();
		int tw = (int) Math.ceil(s.getWidth() * OVERSCAN);
		int th = (int) Math.ceil(s.getHeight() * OVERSCAN);
		List<BufferedImage> loaded = new ArrayList<>();
		String dir = props.getBackground().getDir();
		if (dir != null && !dir.isBlank()) {
			String base = dir.endsWith("/") ? dir : dir + "/";
			PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
			for (String p : PATTERNS) {
				try {
					for (Resource r : resolver.getResources(base + p)) {
						try (InputStream in = r.getInputStream()) {
							BufferedImage img = ImageIO.read(in);
							if (img != null) {
								loaded.add(cover(img, tw, th));
							}
						} catch (Exception e) {
							log.warn("验证码底图读取失败，已跳过：{}", r.getDescription());
						}
					}
				} catch (Exception ignored) {
					// 目录不存在：落到占位
				}
			}
		}
		if (loaded.isEmpty()) {
			Color base = parseColor(props.getBackground().getPlaceholderColor());
			for (int i = 0; i < PLACEHOLDER_VARIANTS; i++) {
				loaded.add(placeholderImage(tw, th, base, new Random(31L * i + 7)));
			}
			placeholder = true;
		} else {
			placeholder = false;
		}
		cache = List.copyOf(loaded);
		log.info("验证码底图已加载：{} 张{}", loaded.size(), placeholder ? "（纯色占位）" : "");
	}

	/** 等比铺满后居中裁切到目标尺寸 */
	static BufferedImage cover(BufferedImage src, int tw, int th) {
		double k = Math.max((double) tw / src.getWidth(), (double) th / src.getHeight());
		int w = (int) Math.ceil(src.getWidth() * k);
		int h = (int) Math.ceil(src.getHeight() * k);
		BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.drawImage(src, (tw - w) / 2, (th - h) / 2, w, h, null);
		g.dispose();
		return out;
	}

	/** 纯色占位：底色 + 低对比度色块，避免整片纯色让缺口边缘过于醒目 */
	static BufferedImage placeholderImage(int w, int h, Color base, Random rnd) {
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(base);
		g.fillRect(0, 0, w, h);
		for (int i = 0; i < 14; i++) {
			int d = 20 + rnd.nextInt(Math.max(21, h));
			int shade = rnd.nextInt(30) - 15;
			g.setColor(new Color(clamp(base.getRed() + shade), clamp(base.getGreen() + shade),
				clamp(base.getBlue() + shade), 90));
			g.fillOval(rnd.nextInt(w) - d / 2, rnd.nextInt(h) - d / 2, d, d);
		}
		g.dispose();
		return out;
	}

	static Color parseColor(String hex) {
		try {
			return Color.decode(hex == null ? "#E6F4FF" : hex.trim());
		} catch (NumberFormatException e) {
			return new Color(0xE6F4FF);
		}
	}

	private static int scale(int rgb, float k) {
		int r = clamp(Math.round(((rgb >> 16) & 0xFF) * k));
		int gr = clamp(Math.round(((rgb >> 8) & 0xFF) * k));
		int b = clamp(Math.round((rgb & 0xFF) * k));
		return (r << 16) | (gr << 8) | b;
	}

	static int clamp(int v) {
		return Math.max(0, Math.min(255, v));
	}
}
