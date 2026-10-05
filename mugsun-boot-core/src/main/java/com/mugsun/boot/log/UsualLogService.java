package com.mugsun.boot.log;

import com.mugsun.boot.log.entity.SysUsualLog;
import com.mugsun.boot.log.mapper.SysUsualLogMapper;
import com.mugsun.boot.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.regex.Pattern;

/**
 * 写入通用日志。口令、密钥一类字段会被换成「已隐藏」，写失败不影响原业务。
 */
@Service
public class UsualLogService {

	private static final Logger log = LoggerFactory.getLogger(UsualLogService.class);
	private static final Pattern SECRET = Pattern.compile(
		"(?i)(password|secret|token|passwd|pwd)\\s*[:=]\\s*\\S+");

	private final SysUsualLogMapper logMapper;

	public UsualLogService(SysUsualLogMapper logMapper) {
		this.logMapper = logMapper;
	}

	public void record(String level, String logId, String data, String tenantId) {
		try {
			HttpServletRequest request = currentRequest();
			SysUsualLog row = new SysUsualLog();
			String tenant = tenantId == null || tenantId.isBlank() ? TenantContext.current() : tenantId;
			row.setTenantId(tenant);
			row.setLogLevel(level);
			row.setLogId(cut(logId, 64));
			row.setLogData(cut(mask(data), 1000));
			if (request != null) {
				row.setRequestUri(cut(request.getRequestURI(), 255));
				row.setMethod(cut(request.getMethod(), 16));
				row.setRemoteIp(cut(request.getRemoteAddr(), 64));
				row.setUserAgent(cut(request.getHeader("User-Agent"), 512));
			}
			if (tenant != null && !tenant.isBlank()) {
				TenantContext.execute(tenant, () -> logMapper.insert(row));
			} else {
				logMapper.insert(row);
			}
		} catch (Exception ex) {
			log.warn("通用日志没有写入: {}", ex.getClass().getSimpleName());
		}
	}

	private static HttpServletRequest currentRequest() {
		if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
			return null;
		}
		return attrs.getRequest();
	}

	private static String mask(String data) {
		if (data == null || data.isBlank()) {
			return "未填写日志内容";
		}
		return SECRET.matcher(data).replaceAll("$1=已隐藏");
	}

	private static String cut(String value, int max) {
		if (value == null) {
			return "";
		}
		String text = value.trim();
		return text.length() <= max ? text : text.substring(0, max);
	}
}
