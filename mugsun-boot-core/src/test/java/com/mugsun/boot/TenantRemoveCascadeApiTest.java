package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 删除租户：租户与组织数据（用户、用户-角色、部门、岗位、角色、角色-菜单、角色-部门、租户数据源）一起逻辑删除，
 * 行仍在库里、is_deleted = 1；其它租户的数据不受影响。
 */
class TenantRemoveCascadeApiTest extends AbstractIntegrationTest {

	private static Connection db() throws SQLException {
		return DriverManager.getConnection(primaryJdbcUrl(), primaryJdbcUsername(), primaryJdbcPassword());
	}

	private static long scalar(Connection c, String sql, Object... args) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) {
				ps.setObject(i + 1, args[i]);
			}
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	private static void exec(Connection c, String sql, Object... args) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) {
				ps.setObject(i + 1, args[i]);
			}
			ps.executeUpdate();
		}
	}

	private String createTenant(String adminToken, String name) {
		Map<String, Object> tenant = new HashMap<>();
		tenant.put("tenantName", name);
		JsonNode resp = readBody(post("/system/tenant/create", tenant, adminToken));
		assertThat(resp.path("code").asInt()).as("建租户：" + resp.path("msg").asText()).isEqualTo(200);
		return resp.path("data").asText();
	}

	@Test
	void removeTenantLogicallyDeletesOrgDataAndLinks() throws Exception {
		String adminToken = loginAdmin();
		String code = createTenant(adminToken, "级联删除测试");
		String other = createTenant(adminToken, "级联删除对照");
		long seq = System.nanoTime() % 1_000_000_000L;
		long rmId = 9_100_000_000_000_000_000L + seq * 10;
		long tenantId;
		long otherRoleMenu;
		try (Connection c = db()) {
			tenantId = scalar(c, "SELECT id FROM sys_tenant WHERE tenant_code = ? AND is_deleted = 0", code);
			long roleId = scalar(c, "SELECT id FROM sys_role WHERE tenant_id = ? AND is_deleted = 0", code);
			long deptId = scalar(c, "SELECT id FROM sys_dept WHERE tenant_id = ? AND is_deleted = 0", code);
			long otherRoleId = scalar(c, "SELECT id FROM sys_role WHERE tenant_id = ? AND is_deleted = 0", other);
			exec(c, "INSERT INTO sys_role_menu (id, role_id, menu_id, is_deleted) VALUES (?, ?, 1, 0)", rmId, roleId);
			exec(c, "INSERT INTO sys_role_dept (id, role_id, dept_id, is_deleted) VALUES (?, ?, ?, 0)", rmId + 1, roleId, deptId);
			exec(c, "INSERT INTO sys_tenant_datasource (id, tenant_code, ds_url, ds_username, ds_password, isolation_type, status, is_deleted) "
				+ "VALUES (?, ?, 'jdbc:postgresql://127.0.0.1:1/none', 'u', 'p', 1, 0, 0)", rmId + 2, code);
			otherRoleMenu = rmId + 3;
			exec(c, "INSERT INTO sys_role_menu (id, role_id, menu_id, is_deleted) VALUES (?, ?, 1, 0)", otherRoleMenu, otherRoleId);
		}

		JsonNode removed = readBody(post("/system/tenant/remove", List.of(tenantId), adminToken));
		assertThat(removed.path("code").asInt()).as("删租户：" + removed.path("msg").asText()).isEqualTo(200);

		try (Connection c = db()) {
			// 逻辑删除：行都还在，is_deleted = 1，没有一条未删除的
			for (String[] t : new String[][]{
				{"sys_tenant", "tenant_code"}, {"sys_user", "tenant_id"}, {"sys_dept", "tenant_id"},
				{"sys_post", "tenant_id"}, {"sys_role", "tenant_id"}, {"sys_tenant_datasource", "tenant_code"}}) {
				assertThat(scalar(c, "SELECT count(*) FROM " + t[0] + " WHERE " + t[1] + " = ?", code)).as(t[0] + " 行仍在").isPositive();
				assertThat(scalar(c, "SELECT count(*) FROM " + t[0] + " WHERE " + t[1] + " = ? AND is_deleted = 0", code)).as(t[0] + " 已逻辑删除").isZero();
			}
			String roleOfTenant = "role_id IN (SELECT id FROM sys_role WHERE tenant_id = ?)";
			for (String link : new String[]{"sys_role_menu", "sys_role_dept"}) {
				assertThat(scalar(c, "SELECT count(*) FROM " + link + " WHERE " + roleOfTenant, code)).as(link + " 行仍在").isPositive();
				assertThat(scalar(c, "SELECT count(*) FROM " + link + " WHERE is_deleted = 0 AND " + roleOfTenant, code)).as(link + " 已逻辑删除").isZero();
			}
			assertThat(scalar(c, "SELECT count(*) FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE tenant_id = ?)", code)).isPositive();
			assertThat(scalar(c, "SELECT count(*) FROM sys_user_role WHERE is_deleted = 0 AND user_id IN (SELECT id FROM sys_user WHERE tenant_id = ?)", code)).isZero();
			// 其它租户不受影响
			assertThat(scalar(c, "SELECT count(*) FROM sys_tenant WHERE tenant_code = ? AND is_deleted = 0", other)).isEqualTo(1);
			assertThat(scalar(c, "SELECT count(*) FROM sys_role_menu WHERE id = ? AND is_deleted = 0", otherRoleMenu)).isEqualTo(1);
			assertThat(scalar(c, "SELECT count(*) FROM sys_user WHERE tenant_id = ? AND is_deleted = 0", other)).isPositive();
		}
	}

	@org.springframework.beans.factory.annotation.Autowired
	private com.mugsun.boot.system.service.TenantService tenantService;

	@Test
	void createAfterDeleteDoesNotReuseTenantCode() throws Exception {
		String adminToken = loginAdmin();
		String code = createTenant(adminToken, "编号复用测试");
		long tenantId;
		try (Connection c = db()) {
			tenantId = scalar(c, "SELECT id FROM sys_tenant WHERE tenant_code = ? AND is_deleted = 0", code);
		}
		JsonNode removed = readBody(post("/system/tenant/remove", List.of(tenantId), adminToken));
		assertThat(removed.path("code").asInt()).as("删租户：" + removed.path("msg").asText()).isEqualTo(200);

		// 让随机数先抽到刚删掉的编号：genCode 必须跳过它
		Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(tenantService);
		Object original = org.springframework.test.util.ReflectionTestUtils.getField(target, "random");
		int deleted = Integer.parseInt(code);
		org.springframework.test.util.ReflectionTestUtils.setField(target, "random", new java.util.random.RandomGenerator() {
			private boolean first = true;

			@Override
			public long nextLong() {
				return ((java.util.random.RandomGenerator) original).nextLong();
			}

			@Override
			public int nextInt(int bound) {
				if (first) {
					first = false;
					return deleted - 100000;
				}
				return ((java.util.random.RandomGenerator) original).nextInt(bound);
			}
		});
		try {
			String next = createTenant(adminToken, "编号复用测试-新");
			assertThat(next).as("删除后新建不会拿到同一编号").isNotEqualTo(code);
			try (Connection c = db()) {
				assertThat(scalar(c, "SELECT count(*) FROM sys_tenant WHERE tenant_code = ?", code)).isEqualTo(1);
			}
		} finally {
			org.springframework.test.util.ReflectionTestUtils.setField(target, "random", original);
		}
	}

	@Test
	void createSkipsCodeAlreadyTakenAndInitialisesDefaults() throws Exception {
		String adminToken = loginAdmin();
		int taken = 600000 + (int) (System.nanoTime() % 300000);
		String takenCode = String.valueOf(taken);
		try (Connection c = db()) {
			exec(c, "INSERT INTO sys_tenant (id, tenant_code, tenant_name, is_deleted) VALUES (?, ?, '预占编号', 0)",
				9_200_000_000_000_000_000L + taken, takenCode);
		}
		Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(tenantService);
		Object original = org.springframework.test.util.ReflectionTestUtils.getField(target, "random");
		org.springframework.test.util.ReflectionTestUtils.setField(target, "random", new java.util.random.RandomGenerator() {
			private boolean first = true;

			@Override
			public long nextLong() {
				return ((java.util.random.RandomGenerator) original).nextLong();
			}

			@Override
			public int nextInt(int bound) {
				if (first) {
					first = false;
					return taken - 100000;
				}
				return ((java.util.random.RandomGenerator) original).nextInt(bound);
			}
		});
		String code;
		try {
			code = createTenant(adminToken, "编号已被占用");
		} finally {
			org.springframework.test.util.ReflectionTestUtils.setField(target, "random", original);
		}
		assertThat(code).as("先抽到的编号已被占用，换了一个").isNotEqualTo(takenCode);
		try (Connection c = db()) {
			// 新租户的默认数据完整
			assertThat(scalar(c, "SELECT count(*) FROM sys_tenant WHERE tenant_code = ? AND is_deleted = 0", code)).isEqualTo(1);
			for (String t : new String[]{"sys_dept", "sys_post", "sys_role", "sys_user"}) {
				assertThat(scalar(c, "SELECT count(*) FROM " + t + " WHERE tenant_id = ? AND is_deleted = 0", code)).as(t).isEqualTo(1);
			}
			assertThat(scalar(c, "SELECT count(*) FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id "
				+ "JOIN sys_role r ON r.id = ur.role_id WHERE u.tenant_id = ? AND r.tenant_id = ?", code, code)).isEqualTo(1);
			// 被占用的编号下没有孤儿默认数据，预占的租户行原样保留
			assertThat(scalar(c, "SELECT count(*) FROM sys_tenant WHERE tenant_code = ?", takenCode)).isEqualTo(1);
			for (String t : new String[]{"sys_dept", "sys_post", "sys_role", "sys_user"}) {
				assertThat(scalar(c, "SELECT count(*) FROM " + t + " WHERE tenant_id = ?", takenCode)).as(t + " 无孤儿").isZero();
			}
		}
	}
}
