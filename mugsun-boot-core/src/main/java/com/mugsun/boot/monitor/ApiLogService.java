package com.mugsun.boot.monitor;

import com.mugsun.boot.monitor.entity.SysApiLog;
import com.mugsun.boot.monitor.mapper.SysApiLogMapper;
import com.mugsun.boot.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 访问日志异步落库（虚拟线程执行器）：全量请求流量，轻表无哈希链——
 * 刻意不复用 sys_oper_log 落库路径（其 SM3 哈希链 synchronized 串行点无法承受全量 GET 流量）。
 */
@Service
public class ApiLogService {

	private static final Logger log = LoggerFactory.getLogger(ApiLogService.class);

	private final SysApiLogMapper apiLogMapper;

	public ApiLogService(SysApiLogMapper apiLogMapper) {
		this.apiLogMapper = apiLogMapper;
	}

	@Async
	public void saveAsync(SysApiLog record) {
		clip(record);
		try {
			// 有租户上下文（经 TenantTaskDecorator 透传）→ 正常落库并打租户标
			apiLogMapper.insertSelective(record);
		} catch (Exception e) {
			try {
				// 无租户上下文（如未认证请求）→ 忽略隔离补插，保证访问日志不丢（tenant_id 空）
				TenantContext.ignore(() -> apiLogMapper.insertSelective(record));
			} catch (Exception ex) {
				// 访问日志是尽力而为通道：落库失败只告警，绝不反噬请求链路
				log.warn("访问日志落库失败 uri={}：{}", record.getRequestUri(), ex.getMessage());
			}
		}
	}

	/** 与 V55 列宽对齐，避免金仓/达梦 VARCHAR 严格截断导致访问日志整条失败 */
	private static void clip(SysApiLog r) {
		r.setTitle(cut(r.getTitle(), 255));
		r.setMethod(cut(r.getMethod(), 255));
		r.setRequestUri(cut(r.getRequestUri(), 512));
		r.setUserAgent(cut(r.getUserAgent(), 512));
		r.setErrorMsg(cut(r.getErrorMsg(), 512));
	}

	private static String cut(String s, int max) {
		if (s == null || s.length() <= max) {
			return s;
		}
		return s.substring(0, max);
	}
}
