package com.mugsun.boot.demo;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** demo 下日志数据源必须连主库：按主机（小写）、端口、库名比较，忽略查询参数；报错 URL 脱敏 */
class LogDataSourceRouterTest {

	private static MockEnvironment env(String primary, String log) {
		return new MockEnvironment()
			.withProperty("mybatis-flex.datasource.primary.url", primary)
			.withProperty("mybatis-flex.datasource.log.url", log);
	}

	@Test
	void sameHostPortDatabaseIgnoringCaseDefaultPortAndQuery() {
		assertThat(LogDataSourceRouter.sameDatabase("jdbc:postgresql://DB.Example:5432/mugsun",
			"jdbc:postgresql://db.example/mugsun?ApplicationName=log&sslmode=disable")).isTrue();
		assertThat(new LogDataSourceRouter(true, env("jdbc:postgresql://postgres:5432/mugsun",
			"jdbc:postgresql://POSTGRES/mugsun?tcpKeepAlive=true")).routed()).isTrue();
	}

	@Test
	void differentHostPortOrDatabaseIsRejected() {
		String p = "jdbc:postgresql://db:5432/mugsun";
		assertThat(LogDataSourceRouter.sameDatabase(p, "jdbc:postgresql://replica:5432/mugsun")).isFalse();
		assertThat(LogDataSourceRouter.sameDatabase(p, "jdbc:postgresql://db:5433/mugsun")).isFalse();
		assertThat(LogDataSourceRouter.sameDatabase(p, "jdbc:postgresql://db:5432/mugsun_track")).isFalse();
		assertThat(LogDataSourceRouter.sameDatabase(p, "jdbc:mysql://db:5432/mugsun")).isFalse();
		assertThat(LogDataSourceRouter.sameDatabase(p, null)).isFalse();
	}

	@Test
	void hostWithUnderscoreIsParsedFromAuthority() {
		assertThat(LogDataSourceRouter.parse("jdbc:postgresql://Mugsun_PG:5432/mugsun?sslmode=disable"))
			.containsExactly("postgresql", "mugsun_pg", "5432", "mugsun");
		assertThat(LogDataSourceRouter.sameDatabase("jdbc:postgresql://mugsun_pg/mugsun",
			"jdbc:postgresql://MUGSUN_PG:5432/mugsun?ApplicationName=log")).isTrue();
		assertThat(LogDataSourceRouter.sameDatabase("jdbc:postgresql://mugsun_pg:5432/mugsun",
			"jdbc:postgresql://mugsun_pg2:5432/mugsun")).isFalse();
		assertThat(new LogDataSourceRouter(true, env("jdbc:postgresql://mugsun_pg:5432/mugsun",
			"jdbc:postgresql://mugsun_pg/mugsun?tcpKeepAlive=true")).routed()).isTrue();
	}

	@Test
	void ipv6WithPortAndUserInfo() {
		assertThat(LogDataSourceRouter.parse("jdbc:postgresql://[::1]:5433/mugsun"))
			.containsExactly("postgresql", "[::1]", "5433", "mugsun");
		assertThat(LogDataSourceRouter.sameDatabase("jdbc:postgresql://[::1]/mugsun",
			"jdbc:postgresql://[::1]:5432/mugsun?sslmode=disable")).isTrue();
		assertThat(LogDataSourceRouter.parse("jdbc:postgresql://[::1]x/mugsun")).isNull();
		assertThat(LogDataSourceRouter.parse("jdbc:postgresql://user@db:5432/mugsun")).as("user@host 不解析").isNull();
		assertThat(LogDataSourceRouter.sameDatabase("jdbc:postgresql://user@db:5432/mugsun",
			"jdbc:postgresql://db:5432/mugsun")).isFalse();
	}

	@Test
	void unparsableUrlsFallBackToExactMatch() {
		String multi = "jdbc:postgresql://a:5432,b:5432/mugsun";
		assertThat(LogDataSourceRouter.sameDatabase(multi, multi)).isTrue();
		assertThat(LogDataSourceRouter.sameDatabase(multi, "jdbc:postgresql://a:5432/mugsun")).isFalse();
	}

	@Test
	void mismatchFailsStartupWithMaskedUrls() {
		MockEnvironment e = env("jdbc:postgresql://db:5432/mugsun?password=TopSecret1",
			"jdbc:postgresql://replica:5432/mugsun?password=TopSecret2");
		assertThatThrownBy(() -> new LogDataSourceRouter(true, e))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("必须连主库").hasMessageContaining("replica")
			.hasMessageNotContaining("TopSecret1").hasMessageNotContaining("TopSecret2");
		assertThat(new LogDataSourceRouter(false, e).routed()).as("非 demo 不检查").isFalse();
	}
}
