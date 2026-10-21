package com.mugsun.boot.demo;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 登录页/登录流程在 demo 下必须能用；普通写接口仍拒；日志数据源必须连主库 */
class DemoReadOnlyPolicyLoginPageTest {

	private final DemoReadOnlyPolicy policy = new DemoReadOnlyPolicy(false);

	@Test
	void loginPageAndLoginFlowAreAllowed() {
		assertThat(policy.isAllowed("GET", "/auth/social/sources", false)).as("登录页加载即调用").isTrue();
		assertThat(policy.isAllowed("GET", "/auth/captcha/config", false)).isTrue();
		assertThat(policy.isAllowed("GET", "/auth/sm2-public-key", false)).isTrue();
		for (String p : new String[]{"/auth/captcha/challenge", "/auth/captcha/verify", "/auth/login", "/auth/logout"}) {
			assertThat(policy.isAllowed("POST", p, false)).as(p).isTrue();
		}
	}

	@Test
	void socialExceptionIsExactAndReadOnly() {
		assertThat(policy.isAllowed("POST", "/auth/social/sources", false)).isFalse();
		assertThat(policy.isAllowed("GET", "/auth/social/sources/x", false)).isFalse();
		assertThat(policy.isAllowed("GET", "/auth/social/render/github", false)).isFalse();
		assertThat(policy.isAllowed("GET", "/auth/social/mock-authorize", false)).isFalse();
		assertThat(policy.isAllowed("POST", "/auth/social/login", false)).isFalse();
		assertThat(policy.isAllowed("GET", "/auth/social/sources", true)).as("方法覆盖仍拒").isFalse();
	}

	@Test
	void ordinaryWritesStillRejected() {
		assertThat(policy.isAllowed("POST", "/system/user/submit", false)).isFalse();
		assertThat(policy.isAllowed("POST", "/auth/update-info", false)).isFalse();
		assertThat(policy.isAllowed("DELETE", "/auth/social/unbind/github", false)).isFalse();
		assertThat(policy.isAllowed("POST", "/track/collect", false)).as("track-collect=false 时不放行").isFalse();
	}

	@Test
	void logDataSourceMustPointAtPrimary() {
		MockEnvironment same = new MockEnvironment()
			.withProperty("mybatis-flex.datasource.primary.url", "jdbc:postgresql://db:5432/mugsun")
			.withProperty("mybatis-flex.datasource.log.url", "jdbc:postgresql://db:5432/mugsun");
		assertThat(new LogDataSourceRouter(true, same).routed()).isTrue();
		MockEnvironment replica = new MockEnvironment()
			.withProperty("mybatis-flex.datasource.primary.url", "jdbc:postgresql://db:5432/mugsun")
			.withProperty("mybatis-flex.datasource.log.url", "jdbc:postgresql://replica:5432/mugsun");
		assertThatThrownBy(() -> new LogDataSourceRouter(true, replica))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("必须连主库");
		assertThat(new LogDataSourceRouter(false, replica).routed()).as("非 demo 不检查").isFalse();
	}
}
