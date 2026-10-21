package com.mugsun.boot.common.module;

import com.mugsun.boot.system.controller.SysParamController;
import com.mugsun.boot.system.entity.SysParam;
import com.mugsun.boot.system.mapper.SysParamMapper;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * 模块开关三层的取值表：同一张表逐行检查
 * <ol>
 *   <li>yml / 环境变量层（{@link ConditionalOnModuleSwitch}，严格）：开 / 关 / 启动失败；</li>
 *   <li>保存系统参数层（{@link SysParamController#submit} 与 {@link ModuleSwitch#checkParam}，宽松）：收 / 拒；</li>
 *   <li>运行时层（{@link ModuleSwitchRuntime}，宽松）：开 / 关。</li>
 * </ol>
 * 两层规则有意不同：空值、1/0、带空格的值在 yml / 环境变量层启动失败，在系统参数层合法。
 * 但凡两层都接受的值，开关结论必须相同；yml / 环境变量层接受的值，系统参数层也一定接受。
 */
class ModuleSwitchLayersTest {

	private static final String PROP = "mugsun.demo.enabled";

	enum Yml { ON, OFF, FAIL }

	static Stream<Arguments> table() {
		return Stream.of(
			// raw, yml/env, saveAccepted, runtimeOn, deprecated(系统参数层)
			Arguments.of(null, Yml.ON, true, true, false),
			Arguments.of("", Yml.FAIL, true, true, false),
			Arguments.of("   ", Yml.FAIL, true, true, false),
			Arguments.of("true", Yml.ON, true, true, false),
			Arguments.of("TRUE", Yml.ON, true, true, false),
			Arguments.of("True", Yml.ON, true, true, false),
			Arguments.of(" True ", Yml.FAIL, true, true, false),
			Arguments.of("true ", Yml.FAIL, true, true, false),
			Arguments.of("\ttrue\n", Yml.FAIL, true, true, false),
			Arguments.of("false", Yml.OFF, true, false, false),
			Arguments.of("FALSE", Yml.OFF, true, false, false),
			Arguments.of(" fAlSe ", Yml.FAIL, true, false, false),
			Arguments.of("1", Yml.FAIL, true, true, true),
			Arguments.of(" 1 ", Yml.FAIL, true, true, true),
			Arguments.of("0", Yml.FAIL, true, false, true),
			Arguments.of("yes", Yml.FAIL, false, false, false),
			Arguments.of("no", Yml.FAIL, false, false, false),
			Arguments.of("on", Yml.FAIL, false, false, false),
			Arguments.of("off", Yml.FAIL, false, false, false),
			Arguments.of("2", Yml.FAIL, false, false, false),
			Arguments.of("01", Yml.FAIL, false, false, false),
			Arguments.of("truee", Yml.FAIL, false, false, false),
			Arguments.of("tr ue", Yml.FAIL, false, false, false),
			Arguments.of("null", Yml.FAIL, false, false, false),
			Arguments.of("enabled", Yml.FAIL, false, false, false)
		);
	}

	// 不加 @Configuration：否则会被 MugsunApplication 的组件扫描收进集成测试上下文（见 TestComponentScanGuardTest）
	@ConditionalOnModuleSwitch(PROP)
	static class DemoModule {
		@Bean
		String demoMarker() {
			return "on";
		}
	}

	private static Yml ymlLayer(String raw) {
		Yml[] out = new Yml[1];
		new ApplicationContextRunner()
			.withInitializer(ctx -> {
				Map<String, Object> m = new HashMap<>();
				if (raw != null) {
					m.put(PROP, raw);
				}
				ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("t", m));
			})
			.withUserConfiguration(DemoModule.class)
			.run(ctx -> {
				if (ctx.getStartupFailure() != null) {
					assertThat(ctx.getStartupFailure()).rootCause()
						.hasMessageContaining(PROP)
						.hasMessageContaining(ModuleSwitch.envName(PROP))
						.hasMessageContaining(ModuleSwitch.quote(raw))
						.hasMessageContaining("请改成 true 或 false")
						.hasMessageContaining("删掉这个变量，按默认开启");
					out[0] = Yml.FAIL;
				} else {
					out[0] = ctx.containsBean("demoMarker") ? Yml.ON : Yml.OFF;
				}
			});
		return out[0];
	}

	/** 走真实的保存接口；值为空时接口本身要求必填，这里只看开关校验，所以空值直接按 checkParam 判 */
	private static boolean saveLayer(String raw) {
		boolean byCheck = ModuleSwitch.checkParam(ModuleSwitch.PARAM_AI, raw).legal();
		if (raw == null || raw.isBlank()) {
			return byCheck;
		}
		SysParamController controller = new SysParamController(mock(SysParamMapper.class), mock(ParamService.class));
		boolean byController = true;
		for (String key : ModuleSwitch.PARAM_KEYS) {
			SysParam p = new SysParam();
			p.setParamName("模块开关");
			p.setParamKey(key);
			p.setParamValue(raw);
			try {
				controller.submit(p);
			} catch (ServiceException e) {
				assertThat(e.getMessage()).isEqualTo(ModuleSwitch.MSG_INVALID);
				byController = false;
			}
		}
		assertThat(byController).as("接口与 checkParam 结论一致").isEqualTo(byCheck);
		return byController;
	}

	private static boolean runtimeLayer(String raw) {
		return new ModuleSwitchRuntime(System::nanoTime, Duration.ofMinutes(10)).enabled(ModuleSwitch.PARAM_GIS, raw);
	}

	@ParameterizedTest(name = "[{index}] raw={0} -> yml/env {1}, save {2}, runtime {3}")
	@MethodSource("table")
	@DisplayName("每层按各自规则判定；两层都接受的值结论相同")
	void layersBehaveAsIntended(String raw, Yml yml, boolean saveAccepted, boolean runtimeOn, boolean deprecated) {
		ModuleSwitch.State loose = ModuleSwitch.parse(raw);
		assertThat(loose.deprecated()).isEqualTo(deprecated);

		Yml ymlActual = ymlLayer(raw);
		boolean saveActual = saveLayer(raw);
		boolean runtimeActual = runtimeLayer(raw);

		assertThat(ymlActual).as("yml/环境变量层").isEqualTo(yml);
		assertThat(saveActual).as("保存层").isEqualTo(saveAccepted);
		assertThat(runtimeActual).as("运行时层").isEqualTo(runtimeOn);

		// 严格层的结论与 parseStrict 一致（模块服务读 yml 也用它）
		ModuleSwitch.State strict = ModuleSwitch.parseStrict(raw);
		assertThat(strict.legal()).isEqualTo(ymlActual != Yml.FAIL);
		if (strict.legal()) {
			assertThat(strict.enabled()).isEqualTo(ymlActual == Yml.ON);
		}

		// 系统参数层：保存拒收 ⇔ 非法 ⇒ 运行时按关闭
		if (!saveActual) {
			assertThat(runtimeActual).as("系统参数非法值运行时按关闭").isFalse();
		}
		// yml/env 接受的值，系统参数层必须也接受，且开关结论相同
		if (ymlActual != Yml.FAIL) {
			assertThat(saveActual).as("yml 接受 ⇒ 系统参数也接受").isTrue();
			assertThat(runtimeActual).as("两层都接受的值结论相同").isEqualTo(ymlActual == Yml.ON);
		}
	}

	@Test
	@DisplayName("yml/环境变量：属性不存在按开启，设置了但为空则启动失败")
	void absentVersusEmpty() {
		assertThat(ymlLayer(null)).isEqualTo(Yml.ON);
		assertThat(ymlLayer("")).isEqualTo(Yml.FAIL);
		assertThat(ModuleSwitch.parseStrict(null)).isEqualTo(ModuleSwitch.State.UNSET);
		assertThat(ModuleSwitch.parseStrict("")).isEqualTo(ModuleSwitch.State.INVALID);
		// 系统参数层：空值与不存在都按默认开启
		assertThat(runtimeLayer(null)).isTrue();
		assertThat(runtimeLayer("")).isTrue();
	}

	/** 模拟模块 yml 的 {@code enabled: ${MUGSUN_DEMO_ENABLED:true}}；envValue 为 null 表示环境变量未设置 */
	private static Yml ymlWithPlaceholder(String envValue) {
		Yml[] out = new Yml[1];
		new ApplicationContextRunner()
			.withInitializer(ctx -> {
				Map<String, Object> yml = new HashMap<>();
				yml.put(PROP, "${MUGSUN_DEMO_ENABLED:true}");
				ctx.getEnvironment().getPropertySources().addLast(new MapPropertySource("yml", yml));
				if (envValue != null) {
					Map<String, Object> env = new HashMap<>();
					env.put("MUGSUN_DEMO_ENABLED", envValue);
					ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("env", env));
				}
			})
			.withUserConfiguration(DemoModule.class)
			.run(ctx -> out[0] = ctx.getStartupFailure() != null ? Yml.FAIL
				: ctx.containsBean("demoMarker") ? Yml.ON : Yml.OFF);
		return out[0];
	}

	@Test
	@DisplayName("模块 yml 占位符：环境变量未设置按开启；设为空、1、带空格都启动失败；FALSE 关闭")
	void placeholderDefault() {
		assertThat(ymlWithPlaceholder(null)).isEqualTo(Yml.ON);
		assertThat(ymlWithPlaceholder("")).isEqualTo(Yml.FAIL);
		assertThat(ymlWithPlaceholder("1")).isEqualTo(Yml.FAIL);
		assertThat(ymlWithPlaceholder(" true")).isEqualTo(Yml.FAIL);
		assertThat(ymlWithPlaceholder("FALSE")).isEqualTo(Yml.OFF);
	}

	@Test
	@DisplayName("启动报错写出属性名、环境变量名、原值和改法")
	void strictErrorMessage() {
		assertThat(ModuleSwitch.envName("mugsun.ai.enabled")).isEqualTo("MUGSUN_AI_ENABLED");
		assertThat(ModuleSwitch.strictError("mugsun.ai.enabled", ""))
			.contains("mugsun.ai.enabled", "MUGSUN_AI_ENABLED", "''", "请改成 true 或 false", "删掉这个变量，按默认开启");
		assertThat(ModuleSwitch.strictError("mugsun.gis.enabled", " true")).contains("' true'", "MUGSUN_GIS_ENABLED");
	}

	@Test
	@DisplayName("非模块开关键不受约束")
	void otherKeysUntouched() {
		assertThat(ModuleSwitch.checkParam("sys.other", "yes")).isNull();
		assertThat(ModuleSwitch.checkParam(null, "yes")).isNull();
		assertThat(ModuleSwitch.checkParam(" ai.module.enabled ", "yes")).isEqualTo(ModuleSwitch.State.INVALID);
	}

	@Test
	@DisplayName("1/0 保存成功但提示已弃用")
	void legacySaveWarns() {
		SysParamController controller = new SysParamController(mock(SysParamMapper.class), mock(ParamService.class));
		SysParam p = new SysParam();
		p.setParamName("AI 开关");
		p.setParamKey(ModuleSwitch.PARAM_AI);
		p.setParamValue("0");
		assertThat(controller.submit(p).getMsg()).contains(ModuleSwitch.MSG_DEPRECATED);
		p.setId(null);
		p.setParamValue("false");
		assertThat(controller.submit(p).getMsg()).doesNotContain(ModuleSwitch.MSG_DEPRECATED);
	}

	@Test
	@DisplayName("ParamService.setValue 同样拒收非法开关值")
	void paramServiceRejects() {
		ParamService svc = new ParamService(mock(SysParamMapper.class), null);
		assertThatThrownBy(() -> svc.setValue(ModuleSwitch.PARAM_GIS, "yes"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("运行时告警限频：同键同值 10 分钟内只打一次，换值或过期后再打")
	void runtimeWarnRateLimited() {
		AtomicLong now = new AtomicLong(0);
		ModuleSwitchRuntime rt = new ModuleSwitchRuntime(now::get, Duration.ofMinutes(10));
		String p = "x {} {}";
		assertThat(rt.warn("ai.module.enabled", "yes", p)).isTrue();
		assertThat(rt.warn("ai.module.enabled", "yes", p)).isFalse();
		assertThat(rt.warn("ai.module.enabled", "on", p)).isTrue();
		assertThat(rt.warn("gis.module.enabled", "yes", p)).isTrue();
		now.addAndGet(Duration.ofMinutes(9).toNanos());
		assertThat(rt.warn("ai.module.enabled", "yes", p)).isFalse();
		now.addAndGet(Duration.ofMinutes(1).toNanos());
		assertThat(rt.warn("ai.module.enabled", "yes", p)).isTrue();
		assertThatCode(() -> rt.enabled("ai.module.enabled", "bad\r\nvalue")).doesNotThrowAnyException();
	}
}
