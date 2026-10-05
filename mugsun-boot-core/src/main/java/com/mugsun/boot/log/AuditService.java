package com.mugsun.boot.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.log.entity.SysDataAudit;
import com.mugsun.boot.log.mapper.SysDataAuditMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 数据变更审计：记下前后镜像，以及这次改动来自哪个请求。
 * 在调用线程读取请求，避免异步之后拿不到方法、路径和来源地址。
 */
@Service
public class AuditService {

	private static final Logger log = LoggerFactory.getLogger(AuditService.class);

	private final SysDataAuditMapper auditMapper;
	private final ObjectMapper objectMapper;
	private final AuditDiffService auditDiffService;

	public AuditService(SysDataAuditMapper auditMapper, ObjectMapper objectMapper, AuditDiffService auditDiffService) {
		this.auditMapper = auditMapper;
		this.objectMapper = objectMapper;
		this.auditDiffService = auditDiffService;
	}

	public void record(String bizTable, String bizId, Object before, Object after, String operator) {
		try {
			HttpServletRequest request = currentRequest();
			SysDataAudit audit = new SysDataAudit();
			audit.setBizTable(bizTable);
			audit.setBizId(bizId);
			audit.setBeforeData(toJson(before));
			audit.setAfterData(toJson(after));
			audit.setChangeContent(toJson(auditDiffService.diff(before, after)));
			audit.setOperator(operator);
			audit.setAction(actionOf(before, after));
			if (request != null) {
				audit.setHttpMethod(cut(request.getMethod(), 16));
				audit.setRequestPath(cut(request.getRequestURI(), 255));
				audit.setClientIp(cut(request.getRemoteAddr(), 64));
			}
			auditMapper.insertSelective(audit);
		} catch (Exception e) {
			log.warn("数据变更审计落库失败 bizTable={} bizId={}", bizTable, bizId, e);
		}
	}

	private static String actionOf(Object before, Object after) {
		if (before == null && after != null) {
			return "create";
		}
		if (before != null && after == null) {
			return "delete";
		}
		return "update";
	}

	private static HttpServletRequest currentRequest() {
		if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
			return null;
		}
		return attrs.getRequest();
	}

	private static String cut(String value, int max) {
		if (value == null) {
			return null;
		}
		String text = value.trim();
		return text.length() <= max ? text : text.substring(0, max);
	}

	private String toJson(Object obj) {
		if (obj == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(obj);
		} catch (Exception e) {
			return String.valueOf(obj);
		}
	}
}
