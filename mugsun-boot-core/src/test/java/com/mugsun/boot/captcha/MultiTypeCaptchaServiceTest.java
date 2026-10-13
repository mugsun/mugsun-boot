package com.mugsun.boot.captcha;

import com.mugsun.boot.captcha.dto.ChallengeVO;
import com.mugsun.boot.captcha.dto.TrackPoint;
import com.mugsun.boot.captcha.dto.VerifyRequest;
import com.mugsun.boot.captcha.image.BackgroundLibrary;
import com.mugsun.boot.captcha.image.MultiTypeRenderer;
import com.mugsun.boot.captcha.image.PuzzleRenderer;
import com.mugsun.boot.captcha.store.MemoryCaptchaStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 多类型题型单测：每种类型 正确 / 容差边界 / 错误 / 重放；选型规则 */
class MultiTypeCaptchaServiceTest {

	private static final String IP = "203.0.113.9";
	private final AtomicLong now = new AtomicLong(1_760_000_000_000L);
	private CaptchaProperties props;
	private SliderCaptchaService svc;

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	@BeforeEach
	void setUp() {
		props = new CaptchaProperties();
		props.getRateLimit().setVerifyFailMax(1000);
		props.getRateLimit().setKeyboardFailMax(1000);
		props.getRateLimit().setKeyboardVerifyPerMinute(1000);
		props.getRateLimit().setChallengePerMinute(1000);
		props.getRateLimit().setKeyboardChallengePerMinute(1000);
		BackgroundLibrary lib = new BackgroundLibrary(props);
		svc = new SliderCaptchaService(new MemoryCaptchaStore(now::get), props, new PuzzleRenderer(props, lib),
			new MultiTypeRenderer(props, lib), (ip, mode, tenant, sec) -> { }, now::get);
	}

	private ChallengeVO issue(CaptchaType t, String mode) {
		ChallengeVO c = svc.challenge("login", "web", "000000", mode, IP, EnumSet.of(t));
		assertThat(c.type()).isEqualTo(t.name());
		return c;
	}

	private VerifyRequest req(String id, String mode, long dur, VerifyRequest.Answer a, List<TrackPoint> track,
							  Integer presses) {
		now.addAndGet(dur + 100);
		long end = now.get();
		return new VerifyRequest(id, mode, 320, end - dur, end, track, a, presses);
	}

	private static List<TrackPoint> track(double travel, long dur) {
		List<TrackPoint> pts = new ArrayList<>();
		for (int i = 0; i <= 24; i++) {
			double k = i / 24.0;
			pts.add(new TrackPoint(travel * (1 - Math.pow(1 - k, 3)), (double) ((i * 7) % 3 - 1), (long) (dur * k)));
		}
		return pts;
	}

	private boolean pass(VerifyRequest r) {
		try {
			return svc.verify(r, IP, "JUnit") != null;
		} catch (CaptchaException e) {
			return false;
		}
	}

	// ---------------------------------------------------------------- ROTATE

	private boolean rotate(double delta, String mode) {
		ChallengeVO c = issue(CaptchaType.ROTATE, mode);
		assertThat(c.piece()).startsWith("data:image/png");
		assertThat(c.pieceSize()).isPositive();
		double angle = Math.floorMod((int) Math.round(Integer.parseInt(svc.peekAnswer(c.captchaId())[0]) + delta), 360);
		if ("keyboard".equals(mode)) {
			return pass(req(c.captchaId(), mode, 1200, new VerifyRequest.Answer(null, angle, null, null), null,
				(int) Math.round(angle / c.keyboardStep())));
		}
		return pass(req(c.captchaId(), mode, 900, new VerifyRequest.Answer(null, angle, null, null),
			track(angle / 360 * 320, 900), null));
	}

	@Test
	void rotateAngleIsStepMultipleAndAtLeast30() {
		java.util.random.RandomGenerator g = java.util.random.RandomGenerator.of("L64X128MixRandom");
		for (int step : new int[]{1, 5, 7, 15}) {
			for (int i = 0; i < 2000; i++) {
				int a = MultiTypeRenderer.randomAngle(10, step, g);
				assertThat(a % step).isZero();
				assertThat(a).isBetween(30, 330);
			}
		}
		for (int i = 0; i < 20; i++) {
			ChallengeVO c = issue(CaptchaType.ROTATE, "keyboard");
			int answer = Integer.parseInt(svc.peekAnswer(c.captchaId())[0]);
			assertThat(answer % c.keyboardStep()).isZero();
			assertThat(answer).isBetween(30, 330);
		}
	}

	/** pointer：以 answer.angle 判对错（±10°）；track 末点与 answer.angle 不必一致（允许 0.5 倍低速精调） */
	@Test
	void rotateTrackEndNeedNotMatchAngle() {
		assertThat(props.getRotate().getToleranceDeg()).isEqualTo(10);
		for (double trackOff : new double[]{25, -25}) {
			ChallengeVO c = issue(CaptchaType.ROTATE, "pointer");
			double angle = Integer.parseInt(svc.peekAnswer(c.captchaId())[0]);
			assertThat(pass(req(c.captchaId(), "pointer", 900, new VerifyRequest.Answer(null, angle, null, null),
				track((angle + trackOff) / 360 * 320, 900), null))).isTrue();
		}
		ChallengeVO c = issue(CaptchaType.ROTATE, "pointer");
		double angle = Integer.parseInt(svc.peekAnswer(c.captchaId())[0]);
		assertThat(pass(req(c.captchaId(), "pointer", 900, new VerifyRequest.Answer(null, angle, null, null),
			track(angle / 360 * 320, 900), null))).isTrue();
		// 角度错：轨迹再像人也不过
		ChallengeVO c2 = issue(CaptchaType.ROTATE, "pointer");
		double wrong = Math.floorMod(Integer.parseInt(svc.peekAnswer(c2.captchaId())[0]) + 11, 360);
		assertThat(pass(req(c2.captchaId(), "pointer", 900, new VerifyRequest.Answer(null, wrong, null, null),
			track(wrong / 360 * 320, 900), null))).isFalse();
		// 轨迹不像人（点太少）仍失败
		ChallengeVO c3 = issue(CaptchaType.ROTATE, "pointer");
		double a3 = Integer.parseInt(svc.peekAnswer(c3.captchaId())[0]);
		assertThat(pass(req(c3.captchaId(), "pointer", 900, new VerifyRequest.Answer(null, a3, null, null),
			track(a3 / 360 * 320, 900).subList(0, 2), null))).isFalse();
	}

	@Test
	void rotateCorrectBoundaryWrongAndKeyboard() {
		assertThat(rotate(0, "pointer")).isTrue();
		assertThat(rotate(10, "pointer")).isTrue();
		assertThat(rotate(-10, "pointer")).isTrue();
		assertThat(rotate(12, "pointer")).isFalse();
		assertThat(rotate(-12, "pointer")).isFalse();
		assertThat(rotate(90, "pointer")).isFalse();
		assertThat(rotate(0, "keyboard")).isTrue();
		assertThat(rotate(40, "keyboard")).isFalse();
	}

	// ---------------------------------------------------------------- CLICK

	private boolean click(CaptchaType t, double offset, boolean swap) {
		ChallengeVO c = issue(t, "pointer");
		if (t == CaptchaType.WORD_CLICK) {
			assertThat(c.count()).isBetween(3, 4);
			assertThat(c.prompt()).hasSize(c.count());
			assertThat(c.hasDecoy()).isTrue();
		} else {
			assertThat(c.count()).isEqualTo(3);
			assertThat(c.promptNames()).hasSize(3);
			assertThat(c.prompt()).isNull();
			assertThat(c.hasDecoy()).isNull();
		}
		assertThat(c.promptImage()).startsWith("data:image/png");
		List<VerifyRequest.Point> pts = new ArrayList<>();
		for (String s : svc.peekAnswer(c.captchaId())[0].split(";")) {
			String[] p = s.split(",");
			double r = Integer.parseInt(p[2]);
			pts.add(new VerifyRequest.Point(Integer.parseInt(p[0]) + offset * r, (double) Integer.parseInt(p[1])));
		}
		if (swap) {
			pts.add(pts.remove(0));
		}
		return pass(req(c.captchaId(), "pointer", 1500, new VerifyRequest.Answer(null, null, pts, null), null, null));
	}

	@Test
	void wordClick() {
		assertThat(click(CaptchaType.WORD_CLICK, 0, false)).isTrue();
		assertThat(click(CaptchaType.WORD_CLICK, 0.95, false)).isTrue();
		assertThat(click(CaptchaType.WORD_CLICK, 1.1, false)).isFalse();
		assertThat(click(CaptchaType.WORD_CLICK, 0, true)).isFalse();
	}

	@Test
	void iconClick() {
		assertThat(click(CaptchaType.ICON_CLICK, 0, false)).isTrue();
		assertThat(click(CaptchaType.ICON_CLICK, 0.95, false)).isTrue();
		assertThat(click(CaptchaType.ICON_CLICK, 1.1, false)).isFalse();
		assertThat(click(CaptchaType.ICON_CLICK, 0, true)).isFalse();
	}

	@Test
	void manifestConfusableGroupsAreReadIncludingDesignerPairs() {
		java.util.Map<String, Set<String>> groups = new java.util.HashMap<>();
		for (MultiTypeRenderer.ManifestEntry e : MultiTypeRenderer.loadManifest()) {
			for (String g : e.confusable()) {
				groups.computeIfAbsent(g, k -> new HashSet<>()).add(e.slug());
			}
		}
		assertThat(groups).hasSizeGreaterThanOrEqualTo(17);
		assertThat(groups.values()).anySatisfy(v -> assertThat(v).contains("house", "phone"));
		assertThat(groups.values()).anySatisfy(v -> assertThat(v).contains("sun", "clock"));
	}

	@Test
	void pickedIconsNeverShareConfusableGroup() {
		MultiTypeRenderer r = new MultiTypeRenderer(props, new BackgroundLibrary(props));
		for (int i = 0; i < 2000; i++) {
			List<MultiTypeRenderer.Icon> icons = r.pickIcons(5, java.util.random.RandomGenerator.of("L64X128MixRandom"));
			Set<String> used = new HashSet<>();
			Set<String> slugs = new HashSet<>();
			for (MultiTypeRenderer.Icon icon : icons) {
				for (String g : icon.confusable()) {
					assertThat(used.add(g)).as("组 %s 同场两次：%s", g, icons).isTrue();
				}
				slugs.add(icon.slug());
			}
			assertThat(slugs.containsAll(Set.of("house", "phone"))).isFalse();
			assertThat(slugs.containsAll(Set.of("sun", "clock"))).isFalse();
		}
	}

	@Test
	void iconsNeverMixConfusableGroup() {
		MultiTypeRenderer r = new MultiTypeRenderer(props, new BackgroundLibrary(props));
		for (int i = 0; i < 50; i++) {
			MultiTypeRenderer.Click c = r.renderIconClick();
			assertThat(c.prompt()).doesNotHaveDuplicates();
		}
	}

	// ---------------------------------------------------------------- JIGSAW

	private boolean jigsaw(boolean swap) {
		ChallengeVO c = issue(CaptchaType.JIGSAW, "pointer");
		assertThat(c.tiles()).hasSize(4);
		assertThat(c.grid()).containsEntry("rows", 2).containsEntry("cols", 2);
		List<Integer> order = new ArrayList<>();
		for (String s : svc.peekAnswer(c.captchaId())[0].split(",")) {
			order.add(Integer.parseInt(s));
		}
		if (swap) {
			Integer a = order.get(0);
			order.set(0, order.get(1));
			order.set(1, a);
		}
		return pass(req(c.captchaId(), "pointer", 2000, new VerifyRequest.Answer(null, null, null, order), null, null));
	}

	@Test
	void jigsawRequiresDisplayWidthInRange() {
		for (int width : new int[]{259, 961}) {
			ChallengeVO c = issue(CaptchaType.JIGSAW, "pointer");
			assertThat(c.background()).isNull();
			assertThat(c.tiles()).allSatisfy(t -> assertThat(t).startsWith("data:image/jpeg"));
			assertThat(c.tileWidth()).isEqualTo(160);
			assertThat(c.tileHeight()).isEqualTo(90);
			List<Integer> order = new ArrayList<>();
			for (String s : svc.peekAnswer(c.captchaId())[0].split(",")) {
				order.add(Integer.parseInt(s));
			}
			now.addAndGet(2100);
			long end = now.get();
			assertThat(pass(new VerifyRequest(c.captchaId(), "pointer", width, end - 2000, end, null,
				new VerifyRequest.Answer(null, null, null, order), null))).isFalse();
		}
	}

	@Test
	void clickReturnsItemSize() {
		assertThat(issue(CaptchaType.WORD_CLICK, "pointer").itemSize()).isEqualTo(32);
		assertThat(issue(CaptchaType.ICON_CLICK, "pointer").itemSize()).isEqualTo(36);
	}

	@Test
	void jigsawCorrectAndWrong() {
		assertThat(jigsaw(false)).isTrue();
		assertThat(jigsaw(true)).isFalse();
	}

	// ---------------------------------------------------------------- 公共

	@Test
	void replayIsRejectedForEveryType() {
		ChallengeVO c = issue(CaptchaType.JIGSAW, "pointer");
		List<Integer> order = new ArrayList<>();
		for (String s : svc.peekAnswer(c.captchaId())[0].split(",")) {
			order.add(Integer.parseInt(s));
		}
		VerifyRequest r = req(c.captchaId(), "pointer", 2000, new VerifyRequest.Answer(null, null, null, order), null, null);
		assertThat(pass(r)).isTrue();
		assertThatThrownBy(() -> svc.verify(r, IP, "JUnit")).isInstanceOf(CaptchaException.class)
			.extracting(e -> ((CaptchaException) e).getCode()).isEqualTo(CaptchaCodes.EXPIRED);
	}

	@Test
	void tooFastIsRejected() {
		ChallengeVO c = issue(CaptchaType.JIGSAW, "pointer");
		List<Integer> order = new ArrayList<>();
		for (String s : svc.peekAnswer(c.captchaId())[0].split(",")) {
			order.add(Integer.parseInt(s));
		}
		now.addAndGet(150);
		long end = now.get();
		assertThat(pass(new VerifyRequest(c.captchaId(), "pointer", 320, end - 100, end, null,
			new VerifyRequest.Answer(null, null, null, order), null))).isFalse();
	}

	@Test
	void pickTypeOnlyFromEnabledAndKeyboardNeverFallsBackToDisabledSlider() {
		Set<CaptchaType> enabled = EnumSet.of(CaptchaType.ROTATE, CaptchaType.WORD_CLICK, CaptchaType.JIGSAW);
		Set<CaptchaType> seen = new HashSet<>();
		for (int i = 0; i < 300; i++) {
			seen.add(SliderCaptchaService.pickType(enabled, "pointer"));
			assertThat(SliderCaptchaService.pickType(enabled, "keyboard")).isEqualTo(CaptchaType.ROTATE);
		}
		assertThat(seen).isEqualTo(enabled);
		assertThat(SliderCaptchaService.pickType(EnumSet.of(CaptchaType.ICON_CLICK, CaptchaType.JIGSAW), "keyboard"))
			.isNull();
		assertThat(SliderCaptchaService.pickType(EnumSet.of(CaptchaType.WORD_CLICK), "keyboard")).isNull();
		assertThat(SliderCaptchaService.pickType(EnumSet.of(CaptchaType.SLIDER, CaptchaType.JIGSAW), "keyboard"))
			.isEqualTo(CaptchaType.SLIDER);
	}

	/** B5：租户只启用点选/拼图时 keyboard 取题 → 40015，写审计，不出任何题 */
	@Test
	void keyboardWithoutAccessibleTypeIsRejectedWith40015() {
		List<String> audited = new ArrayList<>();
		CaptchaAuditSink sink = new CaptchaAuditSink() {
			@Override
			public void locked(String ip, String mode, String tenant, long sec) {
			}

			@Override
			public void keyboardUnavailable(String ip, String tenantId) {
				audited.add(tenantId);
			}
		};
		BackgroundLibrary lib = new BackgroundLibrary(props);
		SliderCaptchaService s = new SliderCaptchaService(new MemoryCaptchaStore(now::get), props,
			new PuzzleRenderer(props, lib), new MultiTypeRenderer(props, lib), sink, now::get);
		assertThatThrownBy(() -> s.challenge("login", "web", "000000", "keyboard", IP, EnumSet.of(CaptchaType.WORD_CLICK)))
			.isInstanceOfSatisfying(CaptchaException.class,
				e -> assertThat(e.getCode()).isEqualTo(CaptchaCodes.KEYBOARD_UNAVAILABLE));
		assertThat(audited).containsExactly("000000");
		assertThat(s.challenge("login", "web", "000000", "pointer", IP, EnumSet.of(CaptchaType.WORD_CLICK)).type())
			.isEqualTo("WORD_CLICK");
	}

	/** B5：keyboard 取题单独限流（更严）并按租户计数 */
	@Test
	void keyboardChallengeHasStricterOwnLimitAndCounter() {
		props.getRateLimit().setKeyboardChallengePerMinute(2);
		issue(CaptchaType.SLIDER, "keyboard");
		issue(CaptchaType.ROTATE, "keyboard");
		assertThat(svc.keyboardChallengeCount("000000")).isEqualTo(2);
		assertThatThrownBy(() -> issue(CaptchaType.SLIDER, "keyboard"))
			.isInstanceOfSatisfying(CaptchaException.class,
				e -> assertThat(e.getCode()).isEqualTo(CaptchaCodes.RATE_LIMITED));
		// pointer 不受 keyboard 限额影响
		issue(CaptchaType.SLIDER, "pointer");
	}

	/** N3：出图并发达到上限 → 40012 */
	@Test
	void renderConcurrencyLimitReturnsRateLimited() throws Exception {
		props.setRenderMaxConcurrent(1);
		props.setRenderAcquireTimeoutMs(50);
		java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		BackgroundLibrary lib = new BackgroundLibrary(props);
		MultiTypeRenderer slow = new MultiTypeRenderer(props, lib) {
			@Override
			public Rotate renderRotate() {
				entered.countDown();
				try {
					release.await(5, java.util.concurrent.TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return super.renderRotate();
			}
		};
		SliderCaptchaService s = new SliderCaptchaService(new MemoryCaptchaStore(now::get), props,
			new PuzzleRenderer(props, lib), slow, (ip, mode, tenant, sec) -> { }, now::get);
		Thread t = new Thread(() -> s.challenge("login", "web", "000000", "pointer", IP, EnumSet.of(CaptchaType.ROTATE)));
		t.start();
		assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		assertThatThrownBy(() -> s.challenge("login", "web", "000000", "pointer", "203.0.113.10", EnumSet.of(CaptchaType.SLIDER)))
			.isInstanceOfSatisfying(CaptchaException.class,
				e -> assertThat(e.getCode()).isEqualTo(CaptchaCodes.RATE_LIMITED));
		release.countDown();
		t.join(5000);
		assertThat(s.challenge("login", "web", "000000", "pointer", IP, EnumSet.of(CaptchaType.SLIDER)).type())
			.isEqualTo("SLIDER");
	}

	// ---------------------------------------------------------------- v0.6.2

	@Test
	void wordLibraryIsLargeUniqueAndFullyCoveredByFont() {
		MultiTypeRenderer r = new MultiTypeRenderer(props, new BackgroundLibrary(props));
		List<String> words = r.words();
		assertThat(words).hasSizeGreaterThanOrEqualTo(400).doesNotHaveDuplicates();
		for (String w : words) {
			assertThat(w.length()).as(w).isBetween(3, 4);
			assertThat(w.chars().distinct().count()).as("词内重复字：%s", w).isEqualTo(w.length());
			assertThat(r.fontCovers(w)).as("字体缺字：%s", w).isTrue();
		}
		assertThat(r.fontCovers(r.charPool())).isTrue();
	}

	/** 捕获最近一次 WORD_CLICK 的完整出题结果（含干扰字位置，只供测试） */
	private static final class Capturing extends MultiTypeRenderer {
		volatile Click last;

		Capturing(CaptchaProperties p, BackgroundLibrary lib) {
			super(p, lib);
		}

		@Override
		public Click renderWordClick() {
			last = super.renderWordClick();
			return last;
		}
	}

	private SliderCaptchaService withRenderer(MultiTypeRenderer r) {
		BackgroundLibrary lib = new BackgroundLibrary(props);
		return new SliderCaptchaService(new MemoryCaptchaStore(now::get), props, new PuzzleRenderer(props, lib), r,
			(ip, mode, tenant, sec) -> { }, now::get);
	}

	private static List<VerifyRequest.Point> points(List<int[]> targets) {
		List<VerifyRequest.Point> pts = new ArrayList<>();
		for (int[] t : targets) {
			pts.add(new VerifyRequest.Point((double) t[0], (double) t[1]));
		}
		return pts;
	}

	@Test
	void wordClickInOrderPassesWrongOrderOrDecoyFails() {
		Capturing r = new Capturing(props, new BackgroundLibrary(props));
		SliderCaptchaService s = withRenderer(r);
		for (int round = 0; round < 20; round++) {
			for (int mode = 0; mode < 3; mode++) {
				ChallengeVO c = s.challenge("login", "web", "000000", "pointer", IP, EnumSet.of(CaptchaType.WORD_CLICK));
				MultiTypeRenderer.Click k = r.last;
				assertThat(k.decoys()).hasSizeBetween(1, 2);
				assertThat(k.answer()).hasSize(c.count());
				List<VerifyRequest.Point> pts = points(k.answer());
				if (mode == 1) {
					pts.add(pts.remove(0));
				} else if (mode == 2) {
					int[] d = k.decoys().get(0);
					pts.set(round % pts.size(), new VerifyRequest.Point((double) d[0], (double) d[1]));
				}
				now.addAndGet(1600);
				long end = now.get();
				boolean ok;
				try {
					ok = s.verify(new VerifyRequest(c.captchaId(), "pointer", 320, end - 1500, end, null,
						new VerifyRequest.Answer(null, null, pts, null), null), IP, "JUnit") != null;
				} catch (CaptchaException e) {
					ok = false;
				}
				assertThat(ok).as("mode %d word %s", mode, c.prompt()).isEqualTo(mode == 0);
			}
		}
	}

	/** 管理前端反馈：两两中心距 ≥ 两个命中半径之和 + 余量（字号 32 时 ≥ 58px） */
	@Test
	void clickItemsAreSpacedBeyondTwoHitRadii() {
		Capturing r = new Capturing(props, new BackgroundLibrary(props));
		for (int i = 0; i < 300; i++) {
			MultiTypeRenderer.Click k = i % 2 == 0 ? r.renderWordClick() : r.renderIconClick();
			List<int[]> all = new ArrayList<>(k.answer());
			all.addAll(k.decoys());
			for (int a = 0; a < all.size(); a++) {
				for (int b = a + 1; b < all.size(); b++) {
					int[] p = all.get(a);
					int[] q = all.get(b);
					assertThat(Math.hypot(p[0] - q[0], p[1] - q[1]))
						.isGreaterThanOrEqualTo(p[2] + q[2] + MultiTypeRenderer.SPACING_MARGIN);
				}
			}
		}
	}

	/** R2：keyboard 限流先于 40015 计数；40015 审计按 (租户, IP) 窗口去重 */
	@Test
	void keyboardUnavailableCountsRateLimitFirstAndAuditIsDeduplicated() {
		List<String> audited = new ArrayList<>();
		CaptchaAuditSink sink = new CaptchaAuditSink() {
			@Override
			public void locked(String ip, String mode, String tenant, long sec) {
			}

			@Override
			public void keyboardUnavailable(String ip, String tenantId) {
				audited.add(ip + "/" + tenantId);
			}
		};
		props.getRateLimit().setKeyboardChallengePerMinute(3);
		BackgroundLibrary lib = new BackgroundLibrary(props);
		SliderCaptchaService s = new SliderCaptchaService(new MemoryCaptchaStore(now::get), props,
			new PuzzleRenderer(props, lib), new MultiTypeRenderer(props, lib), sink, now::get);
		Set<CaptchaType> clickOnly = EnumSet.of(CaptchaType.WORD_CLICK);
		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> s.challenge("login", "web", "t1", "keyboard", IP, clickOnly, "t1"))
				.isInstanceOfSatisfying(CaptchaException.class,
					e -> assertThat(e.getCode()).isEqualTo(CaptchaCodes.KEYBOARD_UNAVAILABLE));
		}
		// 第 4 次：先撞 keyboard 限流（40012），不再走到 40015
		assertThatThrownBy(() -> s.challenge("login", "web", "t1", "keyboard", IP, clickOnly, "t1"))
			.isInstanceOfSatisfying(CaptchaException.class,
				e -> assertThat(e.getCode()).isEqualTo(CaptchaCodes.RATE_LIMITED));
		assertThat(audited).hasSize(1);
		// 另一 IP 单独记一条；窗口过后同一 IP 再记一条
		assertThatThrownBy(() -> s.challenge("login", "web", "t1", "keyboard", "198.51.100.7", clickOnly, "t1"))
			.isInstanceOf(CaptchaException.class);
		assertThat(audited).hasSize(2);
		now.addAndGet(601_000);
		assertThatThrownBy(() -> s.challenge("login", "web", "t1", "keyboard", IP, clickOnly, "t1"))
			.isInstanceOf(CaptchaException.class);
		assertThat(audited).hasSize(3);
	}

	/** R3：图标清单缺失/损坏 → 启动 WARN、ICON_CLICK 不可用，取题不抛异常 */
	@Test
	void brokenIconManifestDisablesIconClickWithoutThrowing() throws Exception {
		java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("captcha-icons-broken");
		java.nio.file.Files.writeString(dir.resolve("manifest.json"), "{ not json");
		for (String iconDir : new String[]{"file:" + dir + "/", "file:" + dir.resolve("missing") + "/"}) {
			props.setIconDir(iconDir);
			MultiTypeRenderer r = new MultiTypeRenderer(props, new BackgroundLibrary(props));
			assertThat(r.iconClickAvailable()).as(iconDir).isFalse();
			SliderCaptchaService s = withRenderer(r);
			Set<String> seen = new HashSet<>();
			for (int i = 0; i < 30; i++) {
				seen.add(s.challenge("login", "web", "000000", "pointer", IP,
					EnumSet.of(CaptchaType.ICON_CLICK, CaptchaType.JIGSAW)).type());
			}
			assertThat(seen).containsExactly("JIGSAW");
			assertThat(s.challenge("login", "web", "000000", "pointer", IP, EnumSet.of(CaptchaType.ICON_CLICK)).type())
				.isEqualTo("SLIDER");
		}
		props.setIconDir("classpath:captcha/icons/");
		assertThat(new MultiTypeRenderer(props, new BackgroundLibrary(props)).iconClickAvailable()).isTrue();
	}

	/** R4：keyboard 统计键用规范化租户码；非法/未解析的租户落到 _unknown */
	@Test
	void keyboardStatKeyUsesCanonicalTenant() {
		svc.challenge("login", "web", "AbC", "keyboard", IP, EnumSet.of(CaptchaType.SLIDER), "abc");
		svc.challenge("login", "web", "x", "keyboard", IP, EnumSet.of(CaptchaType.SLIDER), "bad key!");
		assertThat(svc.keyboardChallengeCount("abc")).isEqualTo(1);
		assertThat(svc.keyboardChallengeCount("AbC")).isZero();
		assertThat(svc.keyboardChallengeCount("_unknown")).isEqualTo(1);
	}

	/** 点选/拼图：0.4–30s 从第一次操作算；打开到第一次操作不计时，只受题目 TTL（expire-seconds，默认 120s）约束 */
	@Test
	void clickTimingStartsAtFirstInteractionAndTtlExpiryIs40010() {
		ChallengeVO c = issue(CaptchaType.JIGSAW, "pointer");
		assertThat(c.expireSeconds()).isEqualTo(120);
		List<Integer> order = new ArrayList<>();
		for (String x : svc.peekAnswer(c.captchaId())[0].split(",")) {
			order.add(Integer.parseInt(x));
		}
		now.addAndGet(90_000); // 打开后 90s 才开始操作，操作 2s
		long end = now.get();
		assertThat(pass(new VerifyRequest(c.captchaId(), "pointer", 320, end - 2000, end, null,
			new VerifyRequest.Answer(null, null, null, order), null))).isTrue();
		ChallengeVO c2 = issue(CaptchaType.JIGSAW, "pointer");
		List<Integer> order2 = new ArrayList<>();
		for (String x : svc.peekAnswer(c2.captchaId())[0].split(",")) {
			order2.add(Integer.parseInt(x));
		}
		now.addAndGet(121_000);
		long end2 = now.get();
		assertThatThrownBy(() -> svc.verify(new VerifyRequest(c2.captchaId(), "pointer", 320, end2 - 2000, end2, null,
			new VerifyRequest.Answer(null, null, null, order2), null), IP, "JUnit"))
			.isInstanceOfSatisfying(CaptchaException.class,
				e -> assertThat(e.getCode()).isEqualTo(CaptchaCodes.EXPIRED));
	}

	// ---------------------------------------------------------------- M1 / M4

	@Test
	void confusableCharsAreInFontAndPreferredAsDecoys() {
		Capturing r = new Capturing(props, new BackgroundLibrary(props));
		assertThat(r.confusablePool()).isNotEmpty();
		assertThat(r.fontCovers(r.confusablePool())).isTrue();
		int withSimilar = 0;
		for (int i = 0; i < 300; i++) {
			MultiTypeRenderer.Click k = r.renderWordClick();
			Set<String> similar = new HashSet<>();
			for (String ch : k.prompt()) {
				similar.addAll(r.confusableOf(ch));
			}
			k.prompt().forEach(similar::remove);
			assertThat(k.decoyChars()).doesNotContainAnyElementsOf(k.prompt());
			if (similar.size() >= 2) {
				withSimilar++;
				assertThat(similar).as("词 %s", k.promptText()).containsAll(k.decoyChars());
			}
		}
		assertThat(withSimilar).isGreaterThan(150);
	}

	@Test
	void sameSourceImageGivesDifferentPixelsEveryChallenge() throws Exception {
		java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(
			getClass().getResourceAsStream("/captcha/rotate/" + new org.springframework.core.io.support
				.PathMatchingResourcePatternResolver().getResources("classpath:captcha/rotate/*.jpg")[0].getFilename()));
		java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("captcha-one-bg");
		javax.imageio.ImageIO.write(src, "png", dir.resolve("only.png").toFile());
		props.getBackground().setDir("file:" + dir + "/");
		BackgroundLibrary lib = new BackgroundLibrary(props);
		java.awt.image.BufferedImage a = lib.next(320, 180);
		java.awt.image.BufferedImage b = lib.next(320, 180);
		int diff = 0;
		for (int y = 0; y < 180; y += 3) {
			for (int x = 0; x < 320; x += 3) {
				if (a.getRGB(x, y) != b.getRGB(x, y)) {
					diff++;
				}
			}
		}
		assertThat(diff).as("同一底图两题的不同像素数").isGreaterThan(60 * 107 / 2);
		java.awt.image.BufferedImage p1 = BackgroundLibrary.perturb(src, 160, 160, java.util.random.RandomGenerator.of("L64X128MixRandom"));
		java.awt.image.BufferedImage p2 = BackgroundLibrary.perturb(src, 160, 160, java.util.random.RandomGenerator.of("L64X128MixRandom"));
		assertThat(java.util.Arrays.equals(p1.getRGB(0, 0, 160, 160, null, 0, 160), p2.getRGB(0, 0, 160, 160, null, 0, 160)))
			.isFalse();
	}

	@Test
	void builtinAssetsRaiseFlagPrivateLibraryClearsIt() throws Exception {
		CaptchaAssetCheck builtin = new CaptchaAssetCheck(props, new BackgroundLibrary(props), null);
		assertThat(builtin.builtinAssetsInUse()).isTrue();
		assertThat(builtin.details()).containsEntry("background", true).containsEntry("rotate", true)
			.containsEntry("icons", true);
		// 拷贝自带文件到 file: 目录 → 按哈希仍判为自带
		java.nio.file.Path copy = java.nio.file.Files.createTempDirectory("captcha-copied");
		var first = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
			.getResources("classpath:captcha/rotate/*.jpg")[0];
		try (var in = first.getInputStream()) {
			java.nio.file.Files.copy(in, copy.resolve("x.jpg"));
		}
		props.getRotate().setDir("file:" + copy + "/");
		assertThat(new CaptchaAssetCheck(props, new BackgroundLibrary(props), null).details()).containsEntry("rotate", true);
		// 私有素材 → false
		java.nio.file.Path own = java.nio.file.Files.createTempDirectory("captcha-own");
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(400, 240, java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = img.createGraphics();
		g.setPaint(new java.awt.GradientPaint(0, 0, java.awt.Color.ORANGE, 400, 240, java.awt.Color.CYAN));
		g.fillRect(0, 0, 400, 240);
		g.dispose();
		javax.imageio.ImageIO.write(img, "png", own.resolve("own.png").toFile());
		props.getBackground().setDir("file:" + own + "/");
		props.getRotate().setDir("file:" + own + "/");
		props.setIconDir("file:" + own + "/");
		CaptchaAssetCheck priv = new CaptchaAssetCheck(props, new BackgroundLibrary(props), null);
		assertThat(priv.details()).containsEntry("background", false).containsEntry("rotate", false)
			.containsEntry("icons", false);
		assertThat(priv.builtinAssetsInUse()).isFalse();
	}
}
