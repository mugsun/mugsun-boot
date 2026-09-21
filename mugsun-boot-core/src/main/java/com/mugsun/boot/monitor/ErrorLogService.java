package com.mugsun.boot.monitor;

import com.mugsun.boot.monitor.entity.SysErrorLog;
import com.mugsun.boot.monitor.mapper.SysErrorLogMapper;
import com.mugsun.boot.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 错误日志异步落库：未捕获异常持久化，支撑处理闭环（认领/忽略）与清理保留期。
 */
@Service
public class ErrorLogService {

	private static final Logger log = LoggerFactory.getLogger(ErrorLogService.class);

	private final SysErrorLogMapper errorLogMapper;

	public ErrorLogService(SysErrorLogMapper errorLogMapper) {
		this.errorLogMapper = errorLogMapper;
	}

	@Async
	public void saveAsync(SysErrorLog record) {
		try {
			errorLogMapper.insertSelective(record);
		} catch (Exception e) {
			try {
				// 无租户上下文（如未认证请求）→ 忽略隔离补插，保证错误日志不丢（tenant_id 空）
				TenantContext.ignore(() -> errorLogMapper.insertSelective(record));
			} catch (Exception ex) {
				log.warn("错误日志落库失败 uri={}：{}", record.getRequestUri(), ex.getMessage());
			}
		}
	}
}
