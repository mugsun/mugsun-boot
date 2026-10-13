package com.mugsun.boot.system.service;

import com.mugsun.boot.common.web.ClientIpResolver;
import com.mugsun.boot.system.entity.SysApiKey;
import com.mugsun.boot.system.entity.SysApiKeyLog;
import com.mugsun.boot.system.mapper.SysApiKeyLogMapper;
import com.mugsun.boot.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把一次密钥校验写成调用记录。失败只打服务日志，不影响校验结果返回。
 */
@Service
public class ApiKeyCallRecorder {

	private static final Logger log = LoggerFactory.getLogger(ApiKeyCallRecorder.class);

	private final SysApiKeyLogMapper logMapper;

	public ApiKeyCallRecorder(SysApiKeyLogMapper logMapper) {
		this.logMapper = logMapper;
	}

	public void record(SysApiKey key, String accessKey, boolean secretPresent, HttpServletRequest request,
					   boolean passed, String message, int costMs) {
		try {
			SysApiKeyLog row = new SysApiKeyLog();
			String tenant = key != null && key.getTenantId() != null && !key.getTenantId().isBlank()
				? key.getTenantId() : TenantContext.current();
			row.setTenantId(tenant);
			if (key != null) {
				row.setApiKeyId(key.getId());
				row.setKeyName(cut(key.getName(), 64));
			}
			row.setAccessKey(cut(accessKey, 64));
			row.setRequestUri(cut(request == null ? "" : request.getRequestURI(), 255));
			row.setMethod(cut(request == null ? "" : request.getMethod(), 16));
			row.setRemoteIp(cut(ipOf(request), 64));
			row.setUserAgent(cut(request == null ? "" : request.getHeader("User-Agent"), 512));
			row.setParams(cut(summary(accessKey, secretPresent), 1000));
			row.setCostMs(Math.max(costMs, 0));
			row.setStatus(passed ? 1 : 0);
			row.setMsg(cut(message, 255));
			if (tenant != null && !tenant.isBlank()) {
				TenantContext.execute(tenant, () -> logMapper.insert(row));
			} else {
				logMapper.insert(row);
			}
		} catch (Exception ex) {
			log.warn("密钥调用记录没有写入: {}", ex.getClass().getSimpleName());
		}
	}

	private static String summary(String accessKey, boolean secretPresent) {
		String ak = accessKey == null ? "" : accessKey;
		if (!secretPresent) {
			return "accessKey=" + ak;
		}
		return "accessKey=" + ak + "，SecretKey 已隐藏";
	}

	/** 不信任 X-Forwarded-For，除非 remoteAddr 属于可信代理（见 {@link ClientIpResolver}）。 */
	static String ipOf(HttpServletRequest request) {
		return ClientIpResolver.clientIp(request);
	}

	private static String cut(String value, int max) {
		if (value == null) {
			return "";
		}
		String text = value.trim();
		return text.length() <= max ? text : text.substring(0, max);
	}
}
