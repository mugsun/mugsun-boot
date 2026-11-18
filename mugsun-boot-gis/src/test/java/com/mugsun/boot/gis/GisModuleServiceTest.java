package com.mugsun.boot.gis;

import com.mugsun.boot.system.service.ParamService;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * engine=java 只给平台管理员：这条路逐要素在 JVM 里加密、缓冲，开放给所有能查图层的人就成了拖垮服务的开关（BOOT-113）。
 */
class GisModuleServiceTest {

	private final GisModuleService module = new GisModuleService(mock(ParamService.class), mock(Environment.class));

	@Test
	@DisplayName("不带 engine 或 engine=postgis 走默认路径，不查身份")
	void defaultEngineNeedsNoAdmin() {
		try (MockedStatic<TenantContext> tenant = mockStatic(TenantContext.class)) {
			assertThat(module.forceJava(null)).isFalse();
			assertThat(module.forceJava("postgis")).isFalse();
			tenant.verifyNoInteractions();
		}
	}

	@Test
	@DisplayName("非平台管理员带 engine=java 返回 403，不是悄悄改走默认路径")
	void javaEngineForbiddenForOthers() {
		try (MockedStatic<TenantContext> tenant = mockStatic(TenantContext.class)) {
			tenant.when(TenantContext::isPlatformSuperAdmin).thenReturn(false);
			assertThatThrownBy(() -> module.forceJava(" JAVA "))
				.isInstanceOf(ForbiddenException.class)
				.hasMessageContaining("平台管理员");
		}
	}

	@Test
	@DisplayName("平台管理员可以强制走 Java 做诊断对比")
	void javaEngineAllowedForPlatformAdmin() {
		try (MockedStatic<TenantContext> tenant = mockStatic(TenantContext.class)) {
			tenant.when(TenantContext::isPlatformSuperAdmin).thenReturn(true);
			assertThat(module.forceJava("java")).isTrue();
		}
	}
}
