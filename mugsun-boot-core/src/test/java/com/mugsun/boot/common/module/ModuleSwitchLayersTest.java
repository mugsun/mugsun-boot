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
import org.springframework.context.annotation.Configuration;
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
 * 模块开关三层一致性：同一张取值表，逐行检查
 * <ol>
 *   <li>yml / 环境变量层（{@link ConditionalOnModuleSwitch}）：开 / 关 / 启动失败；</li>
 *   <li>保存系统参数层（{@link SysParamController#submit} 与 {@link ModuleSwitch#checkParam}）：收 / 拒；</li>
 *   <li>运行时层（{@link ModuleSwitchRuntime}）：开 / 关。</li>
 * </ol>
 * 并断言三层互相吻合：启动失败 ⇔ 保存拒收 ⇔ 运行时按关闭；合法值三层开关结论相同。
 */
class ModuleSwitchLayersTest {

	private static final String PROP = "mugsun.demo.enabled";

	enum Yml { ON, OFF, FAIL }

	static Stream<Arguments> table() {
		return Stream.of(
			// raw, yml, saveAccepted, runtimeOn, deprecated
			Arguments.of(null, Yml.ON, true, true, false),
			Arguments.of("", Yml.ON, true, true, false),
			Arguments.of("   ", Yml.ON, true, true, false),
			Arguments.of("true", Yml.ON, true, true, false),
			Arguments.of("TRUE", Yml.ON, true, true, false),
			Arguments.of(" True ", Yml.ON, true, true, false),
			Arguments.of("\ttrue\n", Yml.ON, true, true, false),
			Arguments.of("false", Yml.OFF, true, false, false),
			Arguments.of("FALSE", Yml.OFF, true, false, false),
			Arguments.of(" fAlSe ", Yml.OFF, true, false, false),
			Arguments.of("1", Yml.ON, true, true, true),
			Arguments.of(" 1 ", Yml.ON, true, true, true),
			Arguments.of("0", Yml.OFF, true, false, true),
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

	@Configuration(proxyBeanMethods = false)
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
					assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining(PROP);
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

	@ParameterizedTest(name = "[{index}] raw={0} -> yml {1}, save {2}, runtime {3}")
	@MethodSource("table")
	@DisplayName("同一取值在 yml/环境变量、保存系统参数、运行时三层结论一致")
	void layersAgree(String raw, Yml yml, boolean saveAccepted, boolean runtimeOn, boolean deprecated) {
		ModuleSwitch.State state = ModuleSwitch.parse(raw);
		assertThat(state.deprecated()).isEqualTo(deprecated);

		Yml ymlActual = ymlLayer(raw);
		boolean saveActual = saveLayer(raw);
		boolean runtimeActual = runtimeLayer(raw);

		assertThat(ymlActual).as("yml/环境变量层").isEqualTo(yml);
		assertThat(saveActual).as("保存层").isEqualTo(saveAccepted);
		assertThat(runtimeActual).as("运行时层").isEqualTo(runtimeOn);

		// 互相吻合
		assertThat(ymlActual == Yml.FAIL).as("启动失败 ⇔ 保存拒收").isEqualTo(!saveActual);
		if (ymlActual == Yml.FAIL) {
			assertThat(runtimeActual).as("非法值运行时按关闭").isFalse();
		} else {
			assertThat(runtimeActual).as("合法值 yml 与运行时结论相同").isEqualTo(ymlActual == Yml.ON);
		}
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
