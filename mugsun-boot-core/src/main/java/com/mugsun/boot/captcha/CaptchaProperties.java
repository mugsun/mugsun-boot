package com.mugsun.boot.captcha;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 行为验证码配置（前缀 {@code mugsun.captcha}）。旧数字码沿用 enabled / show-code / expire-seconds。
 */
@Component
@ConfigurationProperties(prefix = "mugsun.captcha")
public class CaptchaProperties {

	/** 全局开关（prod 默认 true） */
	private boolean enabled = true;
	/** 验证码类型，仅由服务端配置决定：SLIDER（默认）| IMAGE（旧数字码，保留一个版本） */
	private String type = "SLIDER";
	/** 存储：redis（默认，集群）| memory（单机/开发；prod profile 禁用） */
	private String store = "redis";
	private final Slider slider = new Slider();
	private final Background background = new Background();
	private final RateLimit rateLimit = new RateLimit();

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public String getType() { return type; }
	public void setType(String type) { this.type = type; }
	public String getStore() { return store; }
	public void setStore(String store) { this.store = store; }
	public Slider getSlider() { return slider; }
	public Background getBackground() { return background; }
	public RateLimit getRateLimit() { return rateLimit; }

	public boolean isSliderType() {
		return !"IMAGE".equalsIgnoreCase(type == null ? "" : type.trim());
	}

	public static class Slider {
		/** 底图原始尺寸 */
		private int width = 320;
		private int height = 180;
		/** 拼图块主体边长（不含凸起） */
		private int pieceSize = 46;
		/** 挑战有效期（秒） */
		private long expireSeconds = 120;
		/** captchaToken 有效期（秒） */
		private long tokenExpireSeconds = 120;
		/** 落点容差（底图原始像素） */
		private int tolerancePx = 6;
		/** 服务端计时下限：verify 到达时间 - 签发时间 ≥ 该值 */
		private long minDurationMs = 300;
		private long maxDurationMs = 15000;
		private int minTrackPoints = 8;
		private int maxTrackPoints = 400;
		/** 键盘模式：时长下限、按键次数区间 */
		private long keyboardMinDurationMs = 600;
		private int keyboardMinKeyPresses = 3;
		private int keyboardMaxKeyPresses = 300;
		/** 键盘模式每次按键位移（底图原始像素），challenge 下发给前端 */
		private int keyboardStepPx = 4;
		/** 假缺口数量（0–2） */
		private int decoyCount = 1;
		/** token 是否强制绑定 IP / UA 哈希（默认仅记录） */
		private boolean bindIp = false;
		private boolean bindUa = false;

		public int getWidth() { return width; }
		public void setWidth(int width) { this.width = width; }
		public int getHeight() { return height; }
		public void setHeight(int height) { this.height = height; }
		public int getPieceSize() { return pieceSize; }
		public void setPieceSize(int pieceSize) { this.pieceSize = pieceSize; }
		public long getExpireSeconds() { return expireSeconds; }
		public void setExpireSeconds(long expireSeconds) { this.expireSeconds = expireSeconds; }
		public long getTokenExpireSeconds() { return tokenExpireSeconds; }
		public void setTokenExpireSeconds(long tokenExpireSeconds) { this.tokenExpireSeconds = tokenExpireSeconds; }
		public int getTolerancePx() { return tolerancePx; }
		public void setTolerancePx(int tolerancePx) { this.tolerancePx = tolerancePx; }
		public long getMinDurationMs() { return minDurationMs; }
		public void setMinDurationMs(long minDurationMs) { this.minDurationMs = minDurationMs; }
		public long getMaxDurationMs() { return maxDurationMs; }
		public void setMaxDurationMs(long maxDurationMs) { this.maxDurationMs = maxDurationMs; }
		public int getMinTrackPoints() { return minTrackPoints; }
		public void setMinTrackPoints(int minTrackPoints) { this.minTrackPoints = minTrackPoints; }
		public int getMaxTrackPoints() { return maxTrackPoints; }
		public void setMaxTrackPoints(int maxTrackPoints) { this.maxTrackPoints = maxTrackPoints; }
		public long getKeyboardMinDurationMs() { return keyboardMinDurationMs; }
		public void setKeyboardMinDurationMs(long v) { this.keyboardMinDurationMs = v; }
		public int getKeyboardMinKeyPresses() { return keyboardMinKeyPresses; }
		public void setKeyboardMinKeyPresses(int v) { this.keyboardMinKeyPresses = v; }
		public int getKeyboardMaxKeyPresses() { return keyboardMaxKeyPresses; }
		public void setKeyboardMaxKeyPresses(int v) { this.keyboardMaxKeyPresses = v; }
		public int getKeyboardStepPx() { return keyboardStepPx; }
		public void setKeyboardStepPx(int v) { this.keyboardStepPx = v; }
		public int getDecoyCount() { return decoyCount; }
		public void setDecoyCount(int decoyCount) { this.decoyCount = decoyCount; }
		public boolean isBindIp() { return bindIp; }
		public void setBindIp(boolean bindIp) { this.bindIp = bindIp; }
		public boolean isBindUa() { return bindUa; }
		public void setBindUa(boolean bindUa) { this.bindUa = bindUa; }
	}

	public static class Background {
		/** 背景图目录（classpath: 或 file:），支持 jpg/jpeg/png；为空时用纯色占位 */
		private String dir = "classpath:captcha/bg/";
		/** 轮换：random | round-robin */
		private String rotate = "random";
		/** 目录重新扫描间隔（秒），0 = 只在启动时加载 */
		private long reloadSeconds = 600;
		/** 占位底色 */
		private String placeholderColor = "#E6F4FF";

		public String getDir() { return dir; }
		public void setDir(String dir) { this.dir = dir; }
		public String getRotate() { return rotate; }
		public void setRotate(String rotate) { this.rotate = rotate; }
		public long getReloadSeconds() { return reloadSeconds; }
		public void setReloadSeconds(long reloadSeconds) { this.reloadSeconds = reloadSeconds; }
		public String getPlaceholderColor() { return placeholderColor; }
		public void setPlaceholderColor(String placeholderColor) { this.placeholderColor = placeholderColor; }
	}

	public static class RateLimit {
		/** 单 IP 每分钟可取挑战次数 */
		private int challengePerMinute = 30;
		/** 每秒挑战生成全局上限（redis 存储时跨节点计数；memory 存储时为单节点） */
		private int globalChallengePerSecond = 200;
		/** 指针模式：窗口内失败上限 */
		private int verifyFailMax = 5;
		/** 键盘模式：窗口内失败上限（更严） */
		private int keyboardFailMax = 3;
		/** 键盘模式：单 IP 每分钟校验次数上限 */
		private int keyboardVerifyPerMinute = 6;
		private long failWindowSeconds = 600;
		private long lockSeconds = 600;
		/** 发送短信验证码：单手机号每天上限、单 IP 每小时上限（叠加原有同号 60s 节流） */
		private int smsPhonePerDay = 10;
		private int smsIpPerHour = 20;
		/** 发送短信短窗口频率限制（对所有 sms-code 请求计数，含无 token；60 秒窗口自动恢复） */
		private int smsPhonePerMinute = 5;
		private int smsIpPerMinute = 30;

		public int getSmsPhonePerMinute() { return smsPhonePerMinute; }
		public void setSmsPhonePerMinute(int v) { this.smsPhonePerMinute = v; }
		public int getSmsIpPerMinute() { return smsIpPerMinute; }
		public void setSmsIpPerMinute(int v) { this.smsIpPerMinute = v; }

		public int getChallengePerMinute() { return challengePerMinute; }
		public void setChallengePerMinute(int v) { this.challengePerMinute = v; }
		public int getGlobalChallengePerSecond() { return globalChallengePerSecond; }
		public void setGlobalChallengePerSecond(int v) { this.globalChallengePerSecond = v; }
		public int getVerifyFailMax() { return verifyFailMax; }
		public void setVerifyFailMax(int v) { this.verifyFailMax = v; }
		public int getKeyboardFailMax() { return keyboardFailMax; }
		public void setKeyboardFailMax(int v) { this.keyboardFailMax = v; }
		public int getKeyboardVerifyPerMinute() { return keyboardVerifyPerMinute; }
		public void setKeyboardVerifyPerMinute(int v) { this.keyboardVerifyPerMinute = v; }
		public long getFailWindowSeconds() { return failWindowSeconds; }
		public void setFailWindowSeconds(long v) { this.failWindowSeconds = v; }
		public int getSmsPhonePerDay() { return smsPhonePerDay; }
		public void setSmsPhonePerDay(int v) { this.smsPhonePerDay = v; }
		public int getSmsIpPerHour() { return smsIpPerHour; }
		public void setSmsIpPerHour(int v) { this.smsIpPerHour = v; }
		public long getLockSeconds() { return lockSeconds; }
		public void setLockSeconds(long v) { this.lockSeconds = v; }
	}
}
