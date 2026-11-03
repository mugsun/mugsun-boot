package com.mugsun.boot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #70：口令含特殊字符时 uri 必须编码且能被 Lettuce 回解析。 */
class JetCacheRedisUriTest {

	@ParameterizedTest
	@ValueSource(strings = {"a@b", "a%b", "a/b", "a:b", "a?b", "a#b", "p@ss:w/rd%xx", "a b", "x&y=z"})
	void specialPasswordRoundTrips(String password) {
		String uri = JetCacheRedisUri.build("redis.example", 6379, password, 4);
		assertThat(uri).startsWith("redis://:").contains("@redis.example:6379/4");
		assertThat(uri).doesNotContain(":@" + password + "@");
		assertThat(uri).doesNotContain(":" + password + "@");
		// 未编码的危险字符不得出现在 userinfo 段
		String userinfo = uri.substring("redis://:".length(), uri.indexOf('@'));
		assertThat(userinfo).doesNotContain("@");
		assertThat(userinfo).doesNotContain("/");
		assertThat(userinfo).doesNotContain("?");
		assertThat(userinfo).doesNotContain("#");
		if (password.contains("%") && !password.equals(userinfo)) {
			assertThat(userinfo).contains("%25");
		}
		JetCacheRedisUri.assertRoundTrip(uri, "redis.example", 6379, password, 4);
	}

	@Test
	void atPercentSlashEncodedInUri() {
		String uri = JetCacheRedisUri.build("h", 6379, "p@ss/%x", 0);
		assertThat(uri).isEqualTo("redis://:p%40ss%2F%25x@h:6379/0");
	}

	@Test
	void emptyPasswordOmitsAuth() {
		assertThat(JetCacheRedisUri.build("localhost", 6379, "", 3))
			.isEqualTo("redis://localhost:6379/3");
		assertThat(JetCacheRedisUri.build("localhost", 6379, null, 3))
			.isEqualTo("redis://localhost:6379/3");
	}

	@Test
	void rawUnencodedAtFailsRoundTripGuard() {
		// 模拟旧配置：口令裸拼，Lettuce 会把 host 解析错
		assertThatThrownBy(() ->
			JetCacheRedisUri.assertRoundTrip("redis://:p@ss@redis:6379/0", "redis", 6379, "p@ss", 0))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("拒绝启动");
	}

	@Test
	void blankHostRejected() {
		assertThatThrownBy(() -> JetCacheRedisUri.build("  ", 6379, "x", 0))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
