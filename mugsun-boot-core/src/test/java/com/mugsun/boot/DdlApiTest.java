package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态建表安全（G78）回归：/system/gen/ddl/create 与元数据确认通道的标识符白名单 + 系统表前缀保护。
 * 断言矩阵：合法表建模→建表成功且物理表真实落库（重复建即报已存在）；
 * 受保护前缀（sys_/flow_）建模即 400；恶意列名（分号/空格/大写）400 且事务回滚不落孤儿元数据
 * （gen_table 列表查无残留——列级挂表级主键，表级无行即列级无残留）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DdlApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String TABLE_OK = "gen_it_ddl_ok_" + TS;
	private static final String TABLE_SYS_EVIL = "sys_evil_" + TS;
	private static final String TABLE_FLOW_EVIL = "flow_evil_" + TS;
	private static final String TABLE_BAD_COL = "gen_it_ddl_bad_" + TS;

	private String adminToken;

	@BeforeAll
	void loginAsAdmin() {
		adminToken = loginAdmin();
	}

	@Test
	void legalTableIsCreatedPhysically() {
		// 1) 确认建模（仅落元数据，build=false 不建表）
		long tableId = confirmModel(TABLE_OK, List.of(
			column("item_name", "String"), column("qty", "Integer")));
		assertThat(tableId).isPositive();

		// 2) 正向建表成功
		JsonNode created = readBody(post("/system/gen/ddl/create?tableId=" + tableId, null, adminToken));
		assertThat(created.path("code").asInt()).as("建表：" + created.path("msg").asText()).isEqualTo(200);
		assertThat(created.path("msg").asText()).isEqualTo("建表成功");

		// 3) 物理表真实落库的铁证：重复建表被「物理表已存在」拒绝（而非元数据空转）
		JsonNode duplicated = readBody(post("/system/gen/ddl/create?tableId=" + tableId, null, adminToken));
		assertThat(duplicated.path("code").asInt()).isEqualTo(400);
		assertThat(duplicated.path("msg").asText()).contains("物理表已存在");
	}

	@Test
	void protectedPrefixTablesAreRejected() {
		// 受保护系统表前缀：建模前置校验即拒（gen_table/gen_column 精确名单同口径保护）
		JsonNode sysEvil = readBody(post("/system/gen/ai/confirm",
			confirmBody(TABLE_SYS_EVIL, List.of(column("x", "String"))), adminToken));
		assertThat(sysEvil.path("code").asInt()).isEqualTo(400);
		assertThat(sysEvil.path("msg").asText()).contains("受保护的系统表");

		JsonNode flowEvil = readBody(post("/system/gen/ai/confirm",
			confirmBody(TABLE_FLOW_EVIL, List.of(column("x", "String"))), adminToken));
		assertThat(flowEvil.path("code").asInt()).isEqualTo(400);
		assertThat(flowEvil.path("msg").asText()).contains("受保护的系统表");

		// 不落孤儿元数据：gen_table 列表查无两张恶名表
		assertThat(listedTableNames()).doesNotContain(TABLE_SYS_EVIL, TABLE_FLOW_EVIL);
	}

	@Test
	void maliciousColumnNamesAreRejectedWithoutOrphans() {
		// 标识符白名单（^[a-z][a-z0-9_]{0,62}$）：分号注入 / 空格 / 大写一律 400，且事务回滚无残留
		for (String evilColumn : new String[]{"name;drop", "my col", "Name"}) {
			JsonNode r = readBody(post("/system/gen/ai/confirm",
				confirmBody(TABLE_BAD_COL, List.of(column(evilColumn, "String"))), adminToken));
			assertThat(r.path("code").asInt()).as("恶意列名应 400：" + evilColumn).isEqualTo(400);
			assertThat(r.path("msg").asText()).contains("非法列名");
		}
		assertThat(listedTableNames()).as("三次失败均不得落孤儿元数据").doesNotContain(TABLE_BAD_COL);
	}

	/** 受控通道同样拒非法表名（ai/confirm 与 ddl 共用 DdlService 守卫），此处验证非法表名本身被拦 */
	@Test
	void illegalTableNameIsRejected() {
		JsonNode r = readBody(post("/system/gen/ai/confirm",
			confirmBody("Gen_Inject_" + TS, List.of(column("x", "String"))), adminToken));
		assertThat(r.path("code").asInt()).isEqualTo(400);
		assertThat(r.path("msg").asText()).contains("非法");
	}

	// ---------- 前置构造辅助 ----------

	private Map<String, Object> column(String columnName, String javaType) {
		Map<String, Object> column = new HashMap<>();
		column.put("columnName", columnName);
		column.put("javaType", javaType);
		return column;
	}

	private Map<String, Object> confirmBody(String tableName, List<Map<String, Object>> columns) {
		Map<String, Object> table = new HashMap<>();
		table.put("tableName", tableName);
		table.put("tableComment", "集成测试 DDL 表");
		Map<String, Object> confirm = new HashMap<>();
		confirm.put("table", table);
		confirm.put("columns", columns);
		confirm.put("build", false);
		return confirm;
	}

	/** 确认建模（仅落元数据），返回表配置 id；调用方需确保表名/列名合法 */
	private long confirmModel(String tableName, List<Map<String, Object>> columns) {
		Map<String, Object> confirm = confirmBody(tableName, columns);
		JsonNode r = readBody(post("/system/gen/ai/confirm", confirm, adminToken));
		assertThat(r.path("code").asInt()).as("确认建模：" + r.path("msg").asText()).isEqualTo(200);
		return r.path("data").asLong();
	}

	/** 已导入元数据的全部表名（gen_table 行），孤儿残留断言依据 */
	private List<String> listedTableNames() {
		ResponseEntity<String> response = get("/system/gen/list", adminToken);
		JsonNode r = readBody(response);
		assertThat(r.path("code").asInt()).isEqualTo(200);
		List<String> names = new ArrayList<>();
		r.path("data").forEach(n -> names.add(n.path("tableName").asText()));
		return names;
	}
}
