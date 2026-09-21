package com.mugsun.boot.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import com.mugsun.boot.common.constant.MonitorConstants;
import com.mugsun.boot.monitor.entity.SysApiLog;
import com.mugsun.boot.monitor.entity.SysErrorLog;
import com.mugsun.boot.monitor.mapper.SysApiLogMapper;
import com.mugsun.boot.monitor.mapper.SysErrorLogMapper;
import com.mugsun.boot.log.entity.SysOperLog;
import com.mugsun.boot.system.entity.SysParam;
import com.mugsun.boot.log.mapper.SysOperLogMapper;
import com.mugsun.boot.system.mapper.SysParamMapper;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G90 全局可观测集成测试：traceId 贯穿响应头/错误日志、访问日志（采样/慢接口/递归脱敏）、
 * 错误日志认领闭环、actuator 端点鉴权（Prometheus 指标）、数据库文档、在线终端 IP/UA、保留期清理。
 * <p>清理调度由测试直接调用 {@link LogCleanJob#cleanExpired()} 触发（不长时间 sleep 等调度）；
 * 采样率/慢阈值参数改动在 finally 中还原种子值，避免污染同库其他测试。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MonitorApiTest extends AbstractIntegrationTest {

	@Autowired
	private SysApiLogMapper apiLogMapper;
	@Autowired
	private SysErrorLogMapper errorLogMapper;
	@Autowired
	private SysOperLogMapper operLogMapper;
	@Autowired
	private SysParamMapper paramMapper;
	@Autowired
	private ParamService paramService;
	@Autowired
	private LogCleanJob logCleanJob;

	private String adminToken;
	/** 受检异常请求的 traceId（错误日志/访问日志关联断言用） */
	private String boomTraceId;
	private Long boomErrorId;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
	}

	@AfterAll
	void tearDown() {
		updateParam(MonitorConstants.PARAM_SAMPLE_RATE, String.valueOf(MonitorConstants.DEFAULT_SAMPLE_RATE));
		updateParam(MonitorConstants.PARAM_SLOW_MS, String.valueOf(MonitorConstants.DEFAULT_SLOW_MS));
	}

	// ============ ① traceId 贯穿：响应头 + /actuator/health 公开 ============

	@Test
	@Order(1)
	void traceIdHeaderAndHealthPublic() {
		ResponseEntity<String> health = get("/actuator/health", null);
		assertThat(health.getStatusCode().value()).isEqualTo(200);
		assertThat(health.getBody()).contains("UP");
		String traceId = health.getHeaders().getFirst(MonitorConstants.TRACE_HEADER);
		assertThat(traceId).as("响应头应携带 X-Trace-Id").isNotBlank();

		// /v3/api-docs 公开可访问（G63 契约，验证不被新过滤器链破坏）
		ResponseEntity<String> docs = get("/v3/api-docs", null);
		assertThat(docs.getStatusCode().value()).isEqualTo(200);
		assertThat(docs.getBody()).contains("openapi");

		// 不存在 URL → 404 映射（HTTP/R code 均 404）且不落错误日志（笔误/扫描器流量非系统故障）
		ResponseEntity<String> notFound = get("/system/no-such-endpoint-typo", adminToken);
		assertThat(notFound.getStatusCode().value()).isEqualTo(404);
		assertThat(readBody(notFound).path("code").asInt()).isEqualTo(404);
		String nfTrace = notFound.getHeaders().getFirst(MonitorConstants.TRACE_HEADER);
		sleep(1500);
		assertThat(errorRows(nfTrace)).as("404 资源不存在不应落错误日志").isEmpty();
	}

	// ============ ② 错误日志：受检异常落库、trace_id 与响应头一致、栈顶四元组 ============

	@Test
	@Order(2)
	void errorLogCapturedWithLocation() {
		// 带 token 触发：操作人/租户上下文齐备，管理端按本租户视图即可认领处理
		ResponseEntity<String> resp = get("/it/monitor/boom", adminToken);
		assertThat(resp.getStatusCode().value()).isEqualTo(500);
		boomTraceId = resp.getHeaders().getFirst(MonitorConstants.TRACE_HEADER);
		assertThat(boomTraceId).isNotBlank();

		// 错误日志（异步落库，轮询）：trace_id 与响应头一致 + 栈顶四元组定位到探针方法
		await(() -> !errorRows(boomTraceId).isEmpty(), "错误日志异步落库");
		SysErrorLog error = errorRows(boomTraceId).get(0);
		assertThat(error.getTraceId()).isEqualTo(boomTraceId);
		assertThat(error.getExceptionClass()).isEqualTo(IllegalStateException.class.getName());
		assertThat(error.getMessage()).contains("boom");
		assertThat(error.getLocationClass()).isEqualTo(MonitorTestController.class.getName());
		assertThat(error.getLocationFile()).isEqualTo("MonitorTestController.java");
		assertThat(error.getLocationMethod()).isEqualTo("boom");
		assertThat(error.getLocationLine()).isPositive();
		assertThat(error.getStacktrace()).contains("IllegalStateException");
		assertThat(error.getStatus()).isEqualTo(MonitorConstants.ERROR_STATUS_TODO);
		boomErrorId = error.getId();

		// 同一请求的访问日志（异步落库后 trace_id 非空：MDC 异步透传链路），标题回退为 uri（探针零注解）
		await(() -> !apiRows(boomTraceId).isEmpty(), "访问日志异步落库");
		SysApiLog api = apiRows(boomTraceId).get(0);
		assertThat(api.getTraceId()).isEqualTo(boomTraceId);
		assertThat(api.getTitle()).isEqualTo("/it/monitor/boom");
		assertThat(api.getMethod()).contains("MonitorTestController");
		assertThat(api.getStatus()).isEqualTo(500);
		assertThat(api.getErrorMsg()).contains("IllegalStateException");
	}

	// ============ ③ 访问日志：参数递归脱敏（password → ***） ============

	@Test
	@Order(3)
	void paramsRecursivelyMasked() {
		Map<String, Object> body = new HashMap<>();
		body.put("tenantId", PLATFORM_TENANT);
		body.put("username", "it-mask-nonexist");
		body.put("password", "it-secret-123");
		body.put("captchaUuid", "x");
		body.put("captchaCode", "x");
		body.put("clientId", CLIENT_WEB);
		ResponseEntity<String> resp = post("/auth/login", body, null);
		String traceId = resp.getHeaders().getFirst(MonitorConstants.TRACE_HEADER);
		assertThat(traceId).isNotBlank();

		await(() -> !apiRows(traceId).isEmpty(), "登录失败请求访问日志落库");
		SysApiLog api = apiRows(traceId).get(0);
		assertThat(api.getParams()).contains("\"password\":\"***\"");
		assertThat(api.getParams()).doesNotContain("it-secret-123");
		assertThat(api.getParams()).contains("it-mask-nonexist");
	}

	// ============ ④ 采样与慢接口：rate=0 时 GET 不记，慢接口必记且 slow=1 ============

	@Test
	@Order(4)
	void samplingAndSlowFlag() {
		updateParam(MonitorConstants.PARAM_SAMPLE_RATE, "0");
		updateParam(MonitorConstants.PARAM_SLOW_MS, "50");
		try {
			// rate=0：普通 GET 不记（等待足够长时间覆盖异步落库窗口后断言缺失）
			ResponseEntity<String> captcha = get("/auth/captcha", null);
			String sampledOut = captcha.getHeaders().getFirst(MonitorConstants.TRACE_HEADER);
			sleep(2000);
			assertThat(apiRows(sampledOut)).as("采样率 0 时 GET 不应产生访问日志").isEmpty();

			// 慢接口：耗时 300ms 远超阈值 50ms，必记且 slow=1（不受采样影响）
			ResponseEntity<String> slow = get("/it/monitor/sleep?ms=300", null);
			assertThat(slow.getStatusCode().value()).isEqualTo(200);
			String slowTrace = slow.getHeaders().getFirst(MonitorConstants.TRACE_HEADER);
			await(() -> !apiRows(slowTrace).isEmpty(), "慢接口访问日志落库");
			SysApiLog api = apiRows(slowTrace).get(0);
			assertThat(api.getSlow()).isEqualTo(1);
			assertThat(api.getDuration()).isGreaterThanOrEqualTo(50);
		} finally {
			updateParam(MonitorConstants.PARAM_SAMPLE_RATE, String.valueOf(MonitorConstants.DEFAULT_SAMPLE_RATE));
			updateParam(MonitorConstants.PARAM_SLOW_MS, String.valueOf(MonitorConstants.DEFAULT_SLOW_MS));
		}
	}

	// ============ ⑤ 错误日志认领闭环：0→1→2 含备注；page 无 token 401 ============

	@Test
	@Order(5)
	void errorLogHandleFlow() {
		assertThat(boomErrorId).as("② 已捕获错误日志主键").isNotNull();
		// 无 token → 401
		assertThat(get("/system/error-log/page?pageNum=1&pageSize=10", null).getStatusCode().value()).isEqualTo(401);

		// 非法状态被拒
		Map<String, Object> bad = new HashMap<>();
		bad.put("id", boomErrorId);
		bad.put("status", 5);
		assertThat(readBody(post("/system/error-log/handle", bad, adminToken)).path("code").asInt())
			.isNotEqualTo(200);

		// 0 → 1（已处理，含备注与认领人）
		Map<String, Object> done = new HashMap<>();
		done.put("id", boomErrorId);
		done.put("status", MonitorConstants.ERROR_STATUS_DONE);
		done.put("note", "已修复：探针异常");
		assertThat(readBody(post("/system/error-log/handle", done, adminToken)).path("code").asInt()).isEqualTo(200);
		SysErrorLog after = TenantContext.ignore(() -> errorLogMapper.selectOneById(boomErrorId));
		assertThat(after.getStatus()).isEqualTo(MonitorConstants.ERROR_STATUS_DONE);
		assertThat(after.getHandleNote()).isEqualTo("已修复：探针异常");
		assertThat(after.getHandleUser()).isNotBlank();
		assertThat(after.getHandleTime()).isNotNull();

		// 已处理记录不可被重复认领覆盖（乐观流转：仅待处理可认领）
		done.put("status", MonitorConstants.ERROR_STATUS_IGNORED);
		done.put("note", "重复异常忽略");
		assertThat(readBody(post("/system/error-log/handle", done, adminToken)).path("code").asInt())
			.isNotEqualTo(200);
		SysErrorLog ignored = TenantContext.ignore(() -> errorLogMapper.selectOneById(boomErrorId));
		assertThat(ignored.getStatus()).isEqualTo(MonitorConstants.ERROR_STATUS_DONE);

		// 管理端分页可按状态过滤（认领后 status=1 可见）
		JsonNode page = readBody(get("/system/error-log/page?pageNum=1&pageSize=10&status=1", adminToken));
		assertThat(page.path("code").asInt()).isEqualTo(200);
		assertThat(page.path("data").path("totalRow").asLong()).isGreaterThanOrEqualTo(1);
	}

	// ============ ⑥ actuator 鉴权：prometheus/metrics 需登录+权限码，health 公开 ============

	@Test
	@Order(6)
	void actuatorEndpointsGuarded() {
		// 无 token → 401
		assertThat(get("/actuator/prometheus", null).getStatusCode().value()).isEqualTo(401);
		assertThat(get("/actuator/metrics", null).getStatusCode().value()).isEqualTo(401);

		// 持权 token → 200 且 Prometheus 文本含 jvm_ 指标
		ResponseEntity<String> prometheus = get("/actuator/prometheus", adminToken);
		assertThat(prometheus.getStatusCode().value()).isEqualTo(200);
		assertThat(prometheus.getBody()).contains("jvm_");

		// 指标明细（服务监控页数据源）：jvm.memory.used 可拉取
		ResponseEntity<String> metric = get("/actuator/metrics/jvm.memory.used", adminToken);
		assertThat(metric.getStatusCode().value()).isEqualTo(200);
		assertThat(metric.getBody()).contains("measurements");
	}

	// ============ ⑦ 在线数据库文档 ============

	@Test
	@Order(7)
	void dbDocMarkdown() {
		assertThat(get("/system/monitor/db-doc", null).getStatusCode().value()).isEqualTo(401);
		JsonNode doc = readBody(get("/system/monitor/db-doc", adminToken));
		assertThat(doc.path("code").asInt()).isEqualTo(200);
		assertThat(doc.path("data").asText()).contains("# 数据库文档", "sys_api_log", "sys_error_log");
	}

	// ============ ⑧ 在线终端 IP/UA 展示（登录落终端扩展数据） ============

	@Test
	@Order(8)
	void onlineListShowsIpAndUa() {
		String token = loginAdmin();
		JsonNode list = readBody(get("/system/online/list", token));
		assertThat(list.path("code").asInt()).isEqualTo(200);
		// 安全红线：tokenValue 永不下发明文（仅 tokenMask 展示）
		for (JsonNode row : list.path("data")) {
			assertThat(row.has("tokenValue")).isFalse();
		}
		JsonNode mine = null;
		for (JsonNode row : list.path("data")) {
			if ("admin".equals(row.path("username").asText())
				&& "127.0.0.1".equals(row.path("ip").asText())) {
				mine = row;
				break;
			}
		}
		assertThat(mine).as("在线列表应含本次登录终端").isNotNull();
		assertThat(mine.path("ip").asText()).isEqualTo("127.0.0.1");
		assertThat(mine.has("userAgent")).isTrue();
	}

	// ============ ⑨ 保留期清理：过期物理删、未过期保留（直接调用调度体） ============

	@Test
	@Order(9)
	void cleanJobPurgesExpired() {
		// 造过期（40 天前）与新鲜行：api_log / error_log / oper_log 三类
		SysApiLog oldApi = newApiLog("it-clean-old");
		SysApiLog newApi = newApiLog("it-clean-new");
		SysErrorLog oldError = new SysErrorLog();
		oldError.setTraceId("it-clean-old");
		oldError.setStatus(0);
		TenantContext.execute(PLATFORM_TENANT, () -> {
			apiLogMapper.insertSelective(oldApi);
			apiLogMapper.insertSelective(newApi);
			errorLogMapper.insertSelective(oldError);
		});
		SysOperLog oldOper = new SysOperLog();
		oldOper.setTitle("清理测试");
		oldOper.setStatus(1);
		TenantContext.execute(PLATFORM_TENANT, () -> operLogMapper.insertSelective(oldOper));
		LocalDateTime expired = LocalDateTime.now().minusDays(40);
		Db.updateBySql("UPDATE sys_api_log SET create_time = ? WHERE id = ?", expired, oldApi.getId());
		Db.updateBySql("UPDATE sys_error_log SET create_time = ? WHERE id = ?", expired, oldError.getId());
		// oper_log 保留期独立于 api/error（默认 180 天，等保留证）：40 天应保留，200 天才清理
		Db.updateBySql("UPDATE sys_oper_log SET create_time = ? WHERE id = ?",
			LocalDateTime.now().minusDays(200), oldOper.getId());

		logCleanJob.cleanExpired();

		// 过期行物理删除（selectOneById 查无），未过期保留
		assertThat(TenantContext.ignore(() -> apiLogMapper.selectOneById(oldApi.getId()))).isNull();
		assertThat(TenantContext.ignore(() -> errorLogMapper.selectOneById(oldError.getId()))).isNull();
		assertThat(TenantContext.ignore(() -> operLogMapper.selectOneById(oldOper.getId()))).isNull();
		assertThat(TenantContext.ignore(() -> apiLogMapper.selectOneById(newApi.getId()))).isNotNull();
	}

	// ============ 内部工具 ============

	private SysApiLog newApiLog(String traceId) {
		SysApiLog api = new SysApiLog();
		api.setTraceId(traceId);
		api.setRequestMethod("GET");
		api.setRequestUri("/it/monitor/clean");
		api.setStatus(200);
		api.setSlow(0);
		return api;
	}

	private List<SysApiLog> apiRows(String traceId) {
		return TenantContext.ignore(() -> apiLogMapper.selectListByQuery(
			QueryWrapper.create().eq("trace_id", traceId)));
	}

	private List<SysErrorLog> errorRows(String traceId) {
		return TenantContext.ignore(() -> errorLogMapper.selectListByQuery(
			QueryWrapper.create().eq("trace_id", traceId)));
	}

	private void updateParam(String key, String value) {
		SysParam param = paramMapper.selectOneByQuery(QueryWrapper.create().eq("param_key", key));
		param.setParamValue(value);
		paramMapper.update(param);
		// JetCache 本地缓存按键失效，保证过滤器/调度立即读到新值
		paramService.evict(key);
	}

	private void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 短间隔轮询至条件成立（上限 15s），避免长时间 sleep */
	private void await(Supplier<Boolean> condition, String description) {
		long deadline = System.currentTimeMillis() + 15000;
		while (System.currentTimeMillis() < deadline) {
			if (Boolean.TRUE.equals(condition.get())) {
				return;
			}
			sleep(100);
		}
		assertThat(condition.get()).as(description).isTrue();
	}
}
