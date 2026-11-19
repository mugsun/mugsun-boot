package com.mugsun.boot;

import cn.idev.excel.FastExcel;
import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.system.excel.RegionExcel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BOOT-071：行政区划的表没有 tenant_id，全平台共用一份。
 * 其他租户的管理员（持通配权限）只能读，写请求一律 403；平台租户照常维护。
 * 区划导入只给已有编码改名称，不能改挂到别的上级（改挂能让两条区划互为上级，从树上消失且删不掉）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformSharedContentWriteApiTest extends AbstractIntegrationTest {

	private static final String REFUSED = "这部分内容由平台统一维护";
	/** 区划编码只能是数字：用时间尾数做前缀，避开库里已有的编码和别的用例 */
	private static final String P = "97" + String.format("%06d", System.currentTimeMillis() % 1_000_000L);

	@Autowired
	private StringRedisTemplate redis;

	private String adminToken;
	private String tenantAdminToken;

	@BeforeAll
	void setupSecondTenant() {
		adminToken = loginAdmin();
		Map<String, Object> tenant = new HashMap<>();
		tenant.put("tenantName", "共用内容测试租户");
		tenant.put("contactUser", "集成测试");
		JsonNode createResp = readBody(post("/system/tenant/create", tenant, adminToken));
		assertThat(createResp.path("code").asInt()).as("建租户：" + createResp.path("msg").asText()).isEqualTo(200);
		String tenantCode = tenantCodeOf(createResp);
		assertThat(tenantCode).isNotBlank().isNotEqualTo(PLATFORM_TENANT);
		normalizeTenantAdminPassword(tenantCode);
		tenantAdminToken = login(tenantCode, ADMIN_USERNAME, ADMIN_PASSWORD);
	}

	@Test
	void tenantAdminReadsRegionsButCannotWrite() {
		String code = P + "01";
		assertOk(post("/system/region/submit", region(null, code, "0", "平台建的区划"), adminToken));
		long id = regionOf("0", code).path("id").asLong();

		// 读：其他租户照常能看到平台维护的区划
		JsonNode tree = readBody(get("/system/region/lazy-tree?parentCode=0", tenantAdminToken));
		assertThat(tree.path("code").asInt()).isEqualTo(200);
		assertThat(tree.path("data").findValuesAsText("code")).contains(code);

		// 写：新增、改名、删除、导入都被拒绝
		assertRefused(post("/system/region/submit", region(null, P + "02", "0", "租户建的区划"), tenantAdminToken));
		assertRefused(post("/system/region/submit", region(id, code, "0", "被租户改名"), tenantAdminToken));
		assertRefused(post("/system/region/remove/" + id, null, tenantAdminToken));
		assertRefused(upload("/system/region/import", xlsx(List.of(row(P + "03", "0", "租户导入的区划", 1))), tenantAdminToken));

		JsonNode after = regionOf("0", code);
		assertThat(after.path("name").asText()).as("区划没有被改名、没有被删").isEqualTo("平台建的区划");
		assertThat(regionOf("0", P + "02").isMissingNode()).as("租户的新增没有落库").isTrue();
		assertThat(regionOf("0", P + "03").isMissingNode()).as("租户的导入没有落库").isTrue();

		// 平台照常维护
		assertOk(post("/system/region/submit", region(id, code, "0", "平台改的名称"), adminToken));
		assertOk(post("/system/region/remove/" + id, null, adminToken));
	}

	@Test
	void regionImportRenamesExistingButCannotMoveIt() {
		String a = P + "11";
		String b = P + "12";
		String c = P + "13";
		assertOk(post("/system/region/submit", region(null, a, "0", "甲省"), adminToken));
		assertOk(post("/system/region/submit", region(null, b, a, "乙市"), adminToken));

		// 两行互挂：甲挂到乙下面、乙挂到甲下面，层级按「上级 + 1」填，以前能通过校验并成环
		clearRegionImportRepeatLock();
		JsonNode loop = readBody(upload("/system/region/import",
			xlsx(List.of(row(a, b, "甲省", 3), row(b, a, "乙市", 4))), adminToken));
		assertThat(loop.path("code").asInt()).isEqualTo(400);
		assertThat(loop.path("msg").asText()).contains("上级不能通过导入修改");
		assertThat(regionOf("0", a).path("level").asInt()).as("甲省还在顶级").isEqualTo(1);
		assertThat(regionOf(a, b).path("level").asInt()).as("乙市还挂在甲省下面").isEqualTo(2);

		// 正常导入：已有的只改名称，新编码按上级新增
		clearRegionImportRepeatLock();
		JsonNode ok = readBody(upload("/system/region/import",
			xlsx(List.of(row(a, "0", "甲省（改名）", 1), row(c, b, "丙县", 3))), adminToken));
		clearRegionImportRepeatLock();
		assertThat(ok.path("code").asInt()).as(ok.path("msg").asText()).isEqualTo(200);
		assertThat(ok.path("data").asInt()).isEqualTo(2);
		assertThat(regionOf("0", a).path("name").asText()).isEqualTo("甲省（改名）");
		assertThat(regionOf(b, c).path("level").asInt()).isEqualTo(3);

		for (String[] pair : new String[][]{{b, c}, {a, b}, {"0", a}}) {
			assertOk(post("/system/region/remove/" + regionOf(pair[0], pair[1]).path("id").asLong(), null, adminToken));
		}
	}

	private void assertOk(ResponseEntity<String> response) {
		JsonNode body = readBody(response);
		assertThat(body.path("code").asInt()).as(body.path("msg").asText()).isEqualTo(200);
	}

	private void assertRefused(ResponseEntity<String> response) {
		assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(403);
		JsonNode body = readBody(response);
		assertThat(body.path("code").asInt()).isEqualTo(403);
		assertThat(body.path("msg").asText()).contains(REFUSED);
	}

	private static Map<String, Object> region(Long id, String code, String parentCode, String name) {
		Map<String, Object> region = new HashMap<>();
		if (id != null) {
			region.put("id", id);
		}
		region.put("code", code);
		region.put("parentCode", parentCode);
		region.put("name", name);
		region.put("sort", 9999);
		return region;
	}

	/** 用平台管理员在某个上级下按编码找区划，找不到返回 MissingNode */
	private JsonNode regionOf(String parentCode, String code) {
		JsonNode tree = readBody(get("/system/region/lazy-tree?parentCode=" + parentCode, adminToken));
		assertThat(tree.path("code").asInt()).isEqualTo(200);
		for (JsonNode node : tree.path("data")) {
			if (code.equals(node.path("code").asText())) {
				return node;
			}
		}
		return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
	}

	private static RegionExcel row(String code, String parentCode, String name, int level) {
		RegionExcel row = new RegionExcel();
		row.setCode(code);
		row.setParentCode(parentCode);
		row.setName(name);
		row.setLevel(level);
		return row;
	}

	private static byte[] xlsx(List<RegionExcel> rows) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		FastExcel.write(out, RegionExcel.class).sheet("行政区划").doWrite(rows);
		return out.toByteArray();
	}

	/**
	 * 导入接口的防重复提交按「用户 + 方法」判重，文件内容不算：同一个管理员 3 秒内再传会被挡。
	 * 上传前后清掉这把锁，本类连着传两次、紧跟着跑的其他测试类再传，都不用等。
	 */
	private void clearRegionImportRepeatLock() {
		Set<String> keys = redis.keys("mugsun:repeat:*SysRegionController.importData:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	private ResponseEntity<String> upload(String url, byte[] content, String token) {
		HttpHeaders headers = authHeaders(token);
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return "region.xlsx";
			}
		});
		return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
	}
}
