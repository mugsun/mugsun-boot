package com.mugsun.boot.security.csp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.demo.DemoConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * CSP 违规报告收集端：{@code POST /csp-report}（经 pc 的 Nginx 即 {@code /api/csp-report}），给 CSP 的
 * {@code report-uri} / {@code report-to} 用。
 * <ul>
 *   <li>匿名、无 Cookie/token 要求（浏览器发报告不带凭据）；只读演示模式下在只读过滤器的精确白名单里，
 *       并由 DemoLimitFilter 按 IP 限流（Bucket4j + Redis，默认 30 次/分钟）。</li>
 *   <li>请求体上限 8KB，超出 413；清洗后（URL 去 query/fragment，见 {@link CspReportSanitizer}）只写到
 *       日志器 {@code mugsun.csp-report}，不入库（演示库是只读账号）。</li>
 *   <li>恒返回 204，不回显任何内容。</li>
 * </ul>
 * 目前只在只读演示模式装配（限流依赖演示限流器）；普通部署要用时另行开放，见 PR 说明。
 */
@RestController
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "true")
public class CspReportController {

	static final int MAX_BODY = 8 * 1024;
	private static final Logger REPORT_LOG = LoggerFactory.getLogger("mugsun.csp-report");

	private final ObjectMapper objectMapper;

	public CspReportController(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@PostMapping(value = "/csp-report", consumes = {"application/csp-report", "application/reports+json"})
	public ResponseEntity<Void> report(HttpServletRequest request) throws IOException {
		if (request.getContentLengthLong() > MAX_BODY) {
			return ResponseEntity.status(413).build();
		}
		byte[] body;
		try (InputStream in = request.getInputStream()) {
			body = in.readNBytes(MAX_BODY + 1);
		}
		if (body.length > MAX_BODY) {
			return ResponseEntity.status(413).build();
		}
		List<Map<String, Object>> records = CspReportSanitizer.sanitize(objectMapper, body);
		for (Map<String, Object> r : records) {
			REPORT_LOG.warn("csp-violation {}", objectMapper.writeValueAsString(r));
		}
		return ResponseEntity.noContent().build();
	}
}
