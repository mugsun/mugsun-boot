package com.mugsun.boot.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.row.Db;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UX-013：新库启动后平台租户 000000 有名称；补名称幂等、不覆盖已有名称；工作台租户分布按名称显示。
 */
class PlatformTenantNameApiTest extends AbstractIntegrationTest {

	@Autowired
	private DataInitializer initializer;

	@Test
	void platformTenantGetsNameIdempotently() {
		assertThat(name()).isEqualTo(DataInitializer.PLATFORM_TENANT_NAME);
		assertThat(rows()).isEqualTo(1L);

		JsonNode overview = readBody(get("/system/workbench/overview", loginAdmin()));
		boolean labelled = false;
		for (JsonNode item : overview.path("data").path("charts").path("tenantUser")) {
			assertThat(item.path("name").asText()).isNotEqualTo(PLATFORM_TENANT);
			labelled |= DataInitializer.PLATFORM_TENANT_NAME.equals(item.path("name").asText());
		}
		assertThat(labelled).as("租户分布横轴显示名称：" + overview).isTrue();
		// 用户状态图：除中文 name 外下发与语言无关的 key，前端按 key 翻译（英文界面不再显示「启用」）
		JsonNode userStatus = overview.path("data").path("charts").path("userStatus");
		assertThat(userStatus.size()).isPositive();
		for (JsonNode item : userStatus) {
			assertThat(item.path("key").asText()).isIn("enabled", "disabled");
		}

		try {
			Db.updateBySql("UPDATE sys_tenant SET tenant_name = '' WHERE tenant_code = ?", PLATFORM_TENANT);
			seed();
			assertThat(name()).isEqualTo(DataInitializer.PLATFORM_TENANT_NAME);

			Db.updateBySql("UPDATE sys_tenant SET tenant_name = '自定义平台' WHERE tenant_code = ?", PLATFORM_TENANT);
			seed();
			assertThat(name()).as("已有名称不覆盖").isEqualTo("自定义平台");
			seed();
			assertThat(rows()).as("重复执行不多插").isEqualTo(1L);
		} finally {
			Db.updateBySql("UPDATE sys_tenant SET tenant_name = ? WHERE tenant_code = ?",
				DataInitializer.PLATFORM_TENANT_NAME, PLATFORM_TENANT);
		}
	}

	private void seed() {
		TenantContext.ignore(() -> {
			initializer.seedPlatformTenantName();
			return null;
		});
	}

	private String name() {
		return Db.selectOneBySql("SELECT tenant_name FROM sys_tenant WHERE tenant_code = ? AND is_deleted = 0",
			PLATFORM_TENANT).getString("tenant_name");
	}

	private long rows() {
		return Db.selectOneBySql("SELECT count(*) AS c FROM sys_tenant WHERE tenant_code = ? AND is_deleted = 0",
			PLATFORM_TENANT).getLong("c");
	}
}
