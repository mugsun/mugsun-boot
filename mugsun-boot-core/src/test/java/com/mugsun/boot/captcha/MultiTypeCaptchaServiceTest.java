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

	/** pointer：track 末点换算角度与 answer.angle 的差按 tolerance-deg（10°）判，不再借用滑块的 6 原始像素 */
	@Test
	void rotateTrackToleranceIsInDegrees() {
		for (double trackOff : new double[]{9, -9}) {
			ChallengeVO c = issue(CaptchaType.ROTATE, "pointer");
			double angle = Integer.parseInt(svc.peekAnswer(c.captchaId())[0]);
			assertThat(pass(req(c.captchaId(), "pointer", 900, new VerifyRequest.Answer(null, angle, null, null),
				track((angle + trackOff) / 360 * 320, 900), null))).isTrue();
		}
		ChallengeVO c = issue(CaptchaType.ROTATE, "pointer");
		double angle = Integer.parseInt(svc.peekAnswer(c.captchaId())[0]);
		assertThat(pass(req(c.captchaId(), "pointer", 900, new VerifyRequest.Answer(null, angle, null, null),
			track((angle + 12) / 360 * 320, 900), null))).isFalse();
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
		assertThat(c.count()).isEqualTo(3);
		assertThat(c.promptImage()).startsWith("data:image/png");
		assertThat(t == CaptchaType.WORD_CLICK ? c.prompt() : c.promptNames()).hasSize(3);
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
}
