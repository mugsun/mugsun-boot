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
			double jy = java.util.concurrent.ThreadLocalRandom.current().nextDouble(0, 0.5);
			pts.add(new TrackPoint(travel * (1 - Math.pow(1 - k, 3)), (i * 7) % 3 - 1 + jy, (long) (dur * k)));
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

	/** 审计 5：点选按出题时记录的底图宽度判定，题目有效期内改配置不影响 */
	@Test
	void clickJudgesAgainstStoredBgWidth() {
		ChallengeVO c = issue(CaptchaType.ICON_CLICK, "pointer");
		List<VerifyRequest.Point> pts = new ArrayList<>();
		for (String s : svc.peekAnswer(c.captchaId())[0].split(";")) {
			String[] p = s.split(",");
			pts.add(new VerifyRequest.Point((double) Integer.parseInt(p[0]), (double) Integer.parseInt(p[1])));
		}
		assertThat(svc.peekAnswer(c.captchaId())[1]).isEqualTo("ICON_CLICK");
		// 改成 1000 宽后，按配置判会把 display=320 判为过小（< 宽/2）且比例错；按记录的 320 判应通过
		props.getSlider().setWidth(1000);
		props.getSlider().setHeight(560);
		assertThat(pass(req(c.captchaId(), "pointer", 1500, new VerifyRequest.Answer(null, null, pts, null), null, null)))
			.isTrue();
	}

	/** 审计 3：英文界面不出汉字点选，回退到其他启用题型；只启用了 WORD_CLICK 时仍出它 */
	@Test
	void englishLocaleSkipsWordClick() {
		java.util.Locale en = java.util.Locale.ENGLISH;
		for (int i = 0; i < 40; i++) {
			ChallengeVO c = svc.challenge("login", "web", "000000", "pointer", IP,
				EnumSet.of(CaptchaType.WORD_CLICK, CaptchaType.ICON_CLICK), "000000", en);
			assertThat(c.type()).isEqualTo("ICON_CLICK");
			assertThat(c.promptNames()).allSatisfy(n -> assertThat(n).matches("[a-z ]+"));
		}
		assertThat(svc.challenge("login", "web", "000000", "pointer", IP, EnumSet.of(CaptchaType.WORD_CLICK), "000000", en)
			.type()).isEqualTo("WORD_CLICK");
		assertThat(svc.challenge("login", "web", "000000", "pointer", IP, EnumSet.of(CaptchaType.ICON_CLICK), "000000",
			java.util.Locale.SIMPLIFIED_CHINESE).promptNames()).allSatisfy(n -> assertThat(n).doesNotMatch("[a-z ]+"));
		assertThat(SliderCaptchaService.forLocale(EnumSet.of(CaptchaType.SLIDER, CaptchaType.WORD_CLICK), false))
			.contains(CaptchaType.WORD_CLICK);
	}

	/** 审计 2：清单每个图标都有 name_en */
	@Test
	void manifestHasEnglishNames() {
		assertThat(MultiTypeRenderer.loadManifest()).isNotEmpty()
			.allSatisfy(e -> assertThat(e.nameEn()).as(e.slug()).isNotBlank().matches("[a-z ]+"));
	}

	// ---------------------------------------------------------------- ROTATE

	/** N6：ROTATE 失败计入失败锁定（pointer 与 keyboard 各自的上限），触发的那次回 40012，之后取题也被拒 */
	@Test
	void rotateFailuresCountTowardLockout() {
		for (String mode : List.of("pointer", "keyboard")) {
			props.getRateLimit().setVerifyFailMax(3);
			props.getRateLimit().setKeyboardFailMax(3);
			List<String> locked = new ArrayList<>();
			BackgroundLibrary lib = new BackgroundLibrary(props);
			svc = new SliderCaptchaService(new MemoryCaptchaStore(now::get), props, new PuzzleRenderer(props, lib),
				new MultiTypeRenderer(props, lib), (ip, m, tenant, sec) -> locked.add(m), now::get);
			assertThat(rotate(90, mode)).as(mode + " fail 1").isFalse();
			assertThat(rotate(90, mode)).as(mode + " fail 2").isFalse();
			assertThat(locked).isEmpty();
			ChallengeVO c = issue(CaptchaType.ROTATE, mode);
			double wrong = Math.floorMod(Integer.parseInt(svc.peekAnswer(c.captchaId())[0]) + 90, 360);
			VerifyRequest r = "keyboard".equals(mode)
				? req(c.captchaId(), mode, 1200, new VerifyRequest.Answer(null, wrong, null, null), null,
					(int) Math.round(wrong / c.keyboardStep()))
				: req(c.captchaId(), mode, 900, new VerifyRequest.Answer(null, wrong, null, null), track(wrong / 360 * 320, 900), null);
			assertThatThrownBy(() -> svc.verify(r, IP, "JUnit")).isInstanceOfSatisfying(CaptchaException.class,
				e -> assertThat(e.getCode()).isEqualTo(CaptchaCodes.RATE_LIMITED));
			assertThat(locked).containsExactly(mode);
			assertThatThrownBy(() -> svc.challenge("login", "web", "000000", mode, IP, EnumSet.of(CaptchaType.ROTATE)))
				.isInstanceOfSatisfying(CaptchaException.class,
					e -> assertThat(e.getCode()).isEqualTo(CaptchaCodes.RATE_LIMITED));
		}
	}


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

	private boolean rotateWithTrack(double angleOff, double trackAngle) {
		ChallengeVO c = issue(CaptchaType.ROTATE, "pointer");
		double angle = Integer.parseInt(svc.peekAnswer(c.captchaId())[0]) + angleOff;
		return pass(req(c.captchaId(), "pointer", 900, new VerifyRequest.Answer(null, angle, null, null),
			track(trackAngle / 360 * 320, 900), null));
	}

	/** R1′：pointer 以 answer.angle 判对错（±10°），且 track 末点换算角 a_t ∈ [0.5·a − tol, a + tol] */
	@Test
	void rotateTrackEndMustBeConsistentWithAngle() {
		assertThat(props.getRotate().getToleranceDeg()).isEqualTo(10);
		for (int i = 0; i < 20; i++) {
			ChallengeVO c = issue(CaptchaType.ROTATE, "pointer");
			double a = Integer.parseInt(svc.peekAnswer(c.captchaId())[0]);
			VerifyRequest.Answer ans = new VerifyRequest.Answer(null, a, null, null);
			// +25° 不一致：拒绝（上轮这里是通过的）
			assertThat(pass(req(c.captchaId(), "pointer", 900, ans, track((a + 25) / 360 * 320, 900), null)))
				.as("a=%s, a_t=a+25", a).isFalse();
		}
		for (int i = 0; i < 20; i++) {
			ChallengeVO c = issue(CaptchaType.ROTATE, "pointer");
			double a = Integer.parseInt(svc.peekAnswer(c.captchaId())[0]);
			VerifyRequest.Answer ans = new VerifyRequest.Answer(null, a, null, null);
			// 远低于 0.5·a − tol（例如轨迹几乎不动）：拒绝
			assertThat(pass(req(c.captchaId(), "pointer", 900, ans, track(Math.max(0, 0.5 * a - 25) / 360 * 320, 900),
				null))).as("a=%s, a_t=0.5a-25", a).isFalse();
		}
		// 边界：a + tol、0.5·a − tol、全程 0.5 倍精调（a_t = 0.5·a）、完全一致 均通过
		for (double[] k : new double[][]{{1, 10}, {0.5, -10}, {0.5, 0}, {1, 0}}) {
			ChallengeVO c = issue(CaptchaType.ROTATE, "pointer");
			double a = Integer.parseInt(svc.peekAnswer(c.captchaId())[0]);
			double at = Math.max(0, k[0] * a + k[1]) - Math.signum(k[1]) * 0.01;
			assertThat(pass(req(c.captchaId(), "pointer", 900, new VerifyRequest.Answer(null, a, null, null),
				track(at / 360 * 320, 900), null))).as("a=%s, a_t=%s", a, at).isTrue();
		}
		// 刚出上界：拒绝
		ChallengeVO c = issue(CaptchaType.ROTATE, "pointer");
		double a = Integer.parseInt(svc.peekAnswer(c.captchaId())[0]);
		assertThat(pass(req(c.captchaId(), "pointer", 900, new VerifyRequest.Answer(null, a, null, null),
			track((a + 10.5) / 360 * 320, 900), null))).isFalse();
		// 角度错：轨迹一致也不过
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

	/** R1′：同一条 track 在去重窗口内只能用一次（重放录制轨迹失败），窗口过后可再用；窗口 ≤0 关闭 */
	@Test
	void rotateTrackReplayIsRejectedWithinWindow() {
		List<TrackPoint> recorded = null;
		ChallengeVO c = issue(CaptchaType.ROTATE, "pointer");
		double a = Integer.parseInt(svc.peekAnswer(c.captchaId())[0]);
		recorded = track(a / 360 * 320, 900);
		assertThat(pass(req(c.captchaId(), "pointer", 900, new VerifyRequest.Answer(null, a, null, null), recorded,
			null))).isTrue();
		int replays = 0;
		int tried = 0;
		while (tried < 3000 && replays < 2) {
			ChallengeVO r = issue(CaptchaType.ROTATE, "pointer");
			double ra = Integer.parseInt(svc.peekAnswer(r.captchaId())[0]);
			tried++;
			if (ra != a) {
				continue;
			}
			replays++;
			assertThat(pass(req(r.captchaId(), "pointer", 900, new VerifyRequest.Answer(null, ra, null, null), recorded,
				null))).as("replay").isFalse();
		}
		assertThat(replays).isPositive();
		assertThat(SliderCaptchaService.trackHash(recorded)).hasSize(64)
			.isEqualTo(SliderCaptchaService.trackHash(new ArrayList<>(recorded)));
		// 窗口过后可再用
		now.addAndGet(props.getRotate().getTrackReplayWindowSeconds() * 1000 + 1000);
		ChallengeVO later;
		do {
			later = issue(CaptchaType.ROTATE, "pointer");
		} while (Integer.parseInt(svc.peekAnswer(later.captchaId())[0]) != a);
		assertThat(pass(req(later.captchaId(), "pointer", 900, new VerifyRequest.Answer(null, a, null, null), recorded,
			null))).isTrue();
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
			// R3′：只启用 ICON_CLICK → 明确 40016，不静默降为 SLIDER
			assertThatThrownBy(() -> s.challenge("login", "web", "000000", "pointer", IP, EnumSet.of(CaptchaType.ICON_CLICK)))
				.isInstanceOf(CaptchaException.class)
				.satisfies(e -> assertThat(((CaptchaException) e).getCode()).isEqualTo(CaptchaCodes.TYPES_UNAVAILABLE));
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
			.containsEntry("icons", true).containsEntry("words", true);
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
		props.getClick().setWordsFile("file:" + own + "/words.txt");
		CaptchaAssetCheck priv = new CaptchaAssetCheck(props, new BackgroundLibrary(props), null);
		assertThat(priv.details()).containsEntry("background", false).containsEntry("rotate", false)
			.containsEntry("icons", false);
		assertThat(priv.builtinAssetsInUse()).isFalse();
	}

	/** R3′：40016 写审计，且按 (租户, IP) 10 分钟去重 */
	@Test
	void onlyUnavailableTypesReturns40016AndAuditsOnce() throws Exception {
		props.setIconDir("file:" + java.nio.file.Files.createTempDirectory("captcha-none") + "/missing/");
		List<String> audited = new ArrayList<>();
		BackgroundLibrary lib = new BackgroundLibrary(props);
		SliderCaptchaService s = new SliderCaptchaService(new MemoryCaptchaStore(now::get), props,
			new PuzzleRenderer(props, lib), new MultiTypeRenderer(props, lib), new CaptchaAuditSink() {
			@Override
			public void locked(String ip, String mode, String tenantId, long seconds) {
			}

			@Override
			public void typesUnavailable(String ip, String tenantId, String enabled) {
				audited.add(tenantId + ":" + enabled);
			}
		}, now::get);
		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> s.challenge("login", "web", "000000", "pointer", IP,
				EnumSet.of(CaptchaType.ICON_CLICK), "000000"))
				.isInstanceOf(CaptchaException.class)
				.satisfies(e -> assertThat(((CaptchaException) e).getCode()).isEqualTo(40016));
		}
		assertThat(audited).containsExactly("000000:[ICON_CLICK]");
		// 空集合 / null 仍按平台默认 SLIDER（不受影响）
		assertThat(s.challenge("login", "web", "000000", "pointer", IP, EnumSet.noneOf(CaptchaType.class)).type())
			.isEqualTo("SLIDER");
	}

	/** 私有词库：file: 读入、去掉字体子集外的词；读不到回落内置词库 */
	@Test
	void privateWordsFile() throws Exception {
		java.nio.file.Path f = java.nio.file.Files.createTempFile("words", ".txt");
		java.nio.file.Files.writeString(f, "# 私有\n一帆风顺\n一帆风顺\n龘龘龘\n两字\n五个字太长\n");
		props.getClick().setWordsFile("file:" + f);
		MultiTypeRenderer r = new MultiTypeRenderer(props, new BackgroundLibrary(props));
		assertThat(r.words()).containsExactly("一帆风顺");
		assertThat(r.renderWordClick().promptText()).isEqualTo("一帆风顺");
		props.getClick().setWordsFile("file:" + f + ".missing");
		assertThat(new MultiTypeRenderer(props, new BackgroundLibrary(props)).words()).hasSizeGreaterThan(400);
	}
}
