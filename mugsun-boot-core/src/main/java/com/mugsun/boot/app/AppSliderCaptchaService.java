package com.mugsun.boot.app;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.app.dto.AppSliderCheckDTO;
import com.mugsun.boot.app.dto.AppSliderGenerateVO;
import com.mugsun.boot.app.dto.AppSliderTrackPoint;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 拼图滑块：服务端出题（缺口横坐标入 Redis），校验轨迹与落点后再签发一次性登录票。
 * 响应不含答案；开发环境也不回显横坐标。
 */
@Service
public class AppSliderCaptchaService {

	private final StringRedisTemplate redis;

	public AppSliderCaptchaService(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public AppSliderGenerateVO generate(String clientIp) {
		assertIpQuota(clientIp);
		int w = AppConstants.PUZZLE_WIDTH;
		int h = AppConstants.PUZZLE_HEIGHT;
		int size = AppConstants.PIECE_SIZE;
		int margin = 12;
		int x = ThreadLocalRandom.current().nextInt(size + margin, w - size - margin);
		int y = ThreadLocalRandom.current().nextInt(18, h - size - 18);

		BufferedImage background = paintBackground(w, h);
		Area pieceShape = pieceShape(x, y, size);
		BufferedImage slider = new BufferedImage(size + 8, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D sg = slider.createGraphics();
		enableQuality(sg);
		sg.translate(4 - x, 0);
		sg.setClip(pieceShape);
		sg.drawImage(background, 0, 0, null);
		sg.setClip(null);
		sg.setColor(new Color(255, 255, 255, 200));
		sg.setStroke(new BasicStroke(2f));
		sg.draw(pieceShape);
		sg.dispose();

		Graphics2D bg = background.createGraphics();
		enableQuality(bg);
		bg.setColor(new Color(15, 23, 42, 140));
		bg.fill(pieceShape);
		bg.setColor(new Color(3, 105, 161, 220));
		bg.setStroke(new BasicStroke(2f));
		bg.draw(pieceShape);
		bg.dispose();

		String id = IdUtil.fastSimpleUUID();
		redis.opsForValue().set(AppConstants.SLIDER_X_KEY + id, String.valueOf(x),
			Duration.ofSeconds(AppConstants.SLIDER_TTL_SECONDS));
		return new AppSliderGenerateVO(id, toPng(background), toPng(slider), w, h, size);
	}

	public String check(AppSliderCheckDTO dto, String clientIp) {
		assertIpQuota(clientIp);
		if (dto == null || dto.captchaId() == null || dto.captchaId().isBlank()) {
			throw new ServiceException("请完成滑动验证");
		}
		String key = AppConstants.SLIDER_X_KEY + dto.captchaId();
		String expectedRaw = redis.opsForValue().get(key);
		redis.delete(key);
		if (expectedRaw == null || expectedRaw.isBlank()) {
			throw new ServiceException("验证已过期，请重试");
		}
		int expected;
		try {
			expected = Integer.parseInt(expectedRaw);
		} catch (NumberFormatException e) {
			throw new ServiceException("验证已过期，请重试");
		}
		if (Math.abs(dto.moveX() - expected) > AppConstants.SLIDER_TOLERANCE_PX) {
			throw new ServiceException("请将滑块对齐缺口");
		}
		List<AppSliderTrackPoint> tracks = dto.tracks();
		if (tracks == null || tracks.size() < AppConstants.SLIDER_MIN_TRACKS) {
			throw new ServiceException("请将滑块对齐缺口");
		}
		long duration = dto.endTime() - dto.startTime();
		if (duration < AppConstants.SLIDER_MIN_DURATION_MS || duration > AppConstants.SLIDER_MAX_DURATION_MS) {
			throw new ServiceException("请将滑块对齐缺口");
		}
		String ticket = IdUtil.fastSimpleUUID();
		redis.opsForValue().set(AppConstants.SLIDER_TICKET_KEY + ticket, "1",
			Duration.ofSeconds(AppConstants.SLIDER_TTL_SECONDS));
		return ticket;
	}

	/** 登录核销：一次性、原子删除 */
	public void consumeTicket(String ticket) {
		if (ticket == null || ticket.isBlank()) {
			throw new ServiceException("请完成滑动验证");
		}
		String key = AppConstants.SLIDER_TICKET_KEY + ticket;
		Boolean gone = redis.delete(key);
		if (!Boolean.TRUE.equals(gone)) {
			throw new ServiceException("请完成滑动验证");
		}
	}

	/** 集成测试读取缺口横坐标；未挂 HTTP。 */
	public int peekExpectedX(String captchaId) {
		String raw = redis.opsForValue().get(AppConstants.SLIDER_X_KEY + captchaId);
		if (raw == null) {
			throw new ServiceException("验证码不存在");
		}
		return Integer.parseInt(raw);
	}

	private void assertIpQuota(String clientIp) {
		String ip = (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp;
		String key = AppConstants.SLIDER_IP_KEY + ip;
		Long n = redis.opsForValue().increment(key);
		if (n != null && n == 1L) {
			redis.expire(key, Duration.ofMinutes(1));
		}
		if (n != null && n > AppConstants.SLIDER_IP_LIMIT) {
			throw new ServiceException("验证过于频繁，请稍后再试");
		}
	}

	private static BufferedImage paintBackground(int w, int h) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		enableQuality(g);
		for (int y = 0; y < h; y++) {
			float t = y / (float) h;
			int r = (int) (15 + (3 - 15) * t);
			int gr = (int) (23 + (105 - 23) * t);
			int b = (int) (42 + (161 - 42) * t);
			g.setColor(new Color(r, gr, b));
			g.drawLine(0, y, w, y);
		}
		g.setColor(new Color(255, 255, 255, 28));
		for (int i = 0; i < 8; i++) {
			int cx = ThreadLocalRandom.current().nextInt(w);
			int cy = ThreadLocalRandom.current().nextInt(h);
			int rad = ThreadLocalRandom.current().nextInt(18, 55);
			g.fill(new Ellipse2D.Float(cx - rad, cy - rad, rad * 2f, rad * 2f));
		}
		g.setColor(new Color(255, 255, 255, 36));
		g.setStroke(new BasicStroke(1f));
		for (int gx = 20; gx < w; gx += 24) {
			g.drawLine(gx, 0, gx, h);
		}
		g.dispose();
		return img;
	}

	private static Area pieceShape(int x, int y, int size) {
		int bump = size / 4;
		Area area = new Area(new RoundRectangle2D.Float(x, y, size, size, 8, 8));
		area.add(new Area(new Ellipse2D.Float(x + size - bump / 2f, y + size / 2f - bump, bump * 1.2f, bump * 2f)));
		area.subtract(new Area(new Ellipse2D.Float(x - bump, y + size / 2f - bump, bump * 1.2f, bump * 2f)));
		return area;
	}

	private static void enableQuality(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
	}

	private static String toPng(BufferedImage img) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(img, "png", out);
			return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
		} catch (Exception e) {
			throw new ServiceException("验证码生成失败");
		}
	}
}
