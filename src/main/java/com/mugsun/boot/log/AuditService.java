package com.mugsun.boot.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.log.entity.SysDataAudit;
import com.mugsun.boot.log.mapper.SysDataAuditMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 数据变更审计：异步记录业务数据的前后镜像。
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

	@Async
	public void record(String bizTable, String bizId, Object before, Object after, String operator) {
		try {
			SysDataAudit audit = new SysDataAudit();
			audit.setBizTable(bizTable);
			audit.setBizId(bizId);
			audit.setBeforeData(toJson(before));
			audit.setAfterData(toJson(after));
			audit.setChangeContent(toJson(auditDiffService.diff(before, after)));
			audit.setOperator(operator);
			auditMapper.insertSelective(audit);
		} catch (Exception e) {
			log.warn("数据变更审计落库失败 bizTable={} bizId={}", bizTable, bizId, e);
		}
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
