package com.mugsun.boot.auth;

import com.mugsun.boot.config.DeployProfiles;
import com.mugsun.core.tool.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 关闭国密（密码明文提交）时的 TLS 闸门：生产环境下登录、注册、改密、重置密码的请求必须走 HTTPS。
 * <p>判定用 {@link HttpServletRequest#isSecure()}，不直接读 X-Forwarded-Proto 头（客户端可以伪造）。
 * 部署在反向代理后面时须配置 {@code server.forward-headers-strategy=native}（或 framework），
 * 由容器只对受信代理（Tomcat 默认内网地址）采信 X-Forwarded-Proto；没配则 isSecure 恒为 false，请求被拒。
 * 拿不到当前请求时同样拒绝（fail-closed）。
 */
@Component
public class PlaintextPasswordTlsGate {

	private final Environment environment;

	public PlaintextPasswordTlsGate(Environment environment) {
		this.environment = environment;
	}

	public void requireTls() {
		if (!DeployProfiles.isProduction(environment)) {
			return;
		}
		RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
		HttpServletRequest request = attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
		if (request == null || !request.isSecure()) {
			throw new InsecureTransportException("当前未启用国密传输，密码只能通过 HTTPS 提交。请改用 https 访问；"
				+ "部署在反向代理后面时，请配置 server.forward-headers-strategy=native");
		}
	}

	/** 明文口令走了非 HTTPS：调用方的「解密失败归一」不得吞掉它 */
	public static final class InsecureTransportException extends ServiceException {
		public InsecureTransportException(String message) {
			super(message);
		}
	}
}
