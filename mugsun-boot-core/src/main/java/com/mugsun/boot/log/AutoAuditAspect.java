package com.mugsun.boot.log;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.log.entity.SysOperLog;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;

/**
 * 写接口自动审计切面：所有 Controller 的写操作（POST/PUT/DELETE）**无需手工注解**自动留痕。
 * <ul><li>已标 {@link OperationLog} 的方法由 {@link OperationLogAspect} 处理，此处排除避免重复；</li>
 * <li>密码类字段自动脱敏、超长参数截断、异步落库（并接入哈希链+签名防篡改）。</li></ul>
 */
@Aspect
@Component
public class AutoAuditAspect {

	private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

	private final OperationLogService logService;
	private final ObjectMapper objectMapper;

	public AutoAuditAspect(OperationLogService logService, ObjectMapper objectMapper) {
		this.logService = logService;
		this.objectMapper = objectMapper;
	}

	@Around("execution(* com.mugsun.boot..*Controller.*(..)) "
		+ "&& !@annotation(com.mugsun.boot.log.OperationLog)")
	public Object around(ProceedingJoinPoint point) throws Throwable {
		HttpServletRequest req = currentRequest();
		String httpMethod = req == null ? null : req.getMethod();
		// 仅写操作自动留痕；读操作（GET）不记
		if (httpMethod == null || !WRITE_METHODS.contains(httpMethod.toUpperCase())) {
			return point.proceed();
		}
		long start = System.currentTimeMillis();
		SysOperLog log = new SysOperLog();
		log.setTitle("自动留痕");
		log.setMethod(point.getSignature().getDeclaringTypeName() + "." + point.getSignature().getName());
		log.setRequestMethod(httpMethod);
		log.setRequestUri(req.getRequestURI());
		log.setIp(req.getRemoteAddr());
		log.setParams(AuditMask.maskAndTruncate(objectMapper, point.getArgs()));
		fillOperator(log);
		int status = 1;
		String error = null;
		try {
			return point.proceed();
		} catch (Throwable e) {
			status = 0;
			error = e.getMessage();
			throw e;
		} finally {
			log.setStatus(status);
			log.setErrorMsg(error);
			log.setDuration(System.currentTimeMillis() - start);
			logService.saveAsync(log);
		}
	}

	/** 参数序列化 + 密码脱敏 + 超长截断；不可序列化时降级 Arrays.toString */
	private HttpServletRequest currentRequest() {
		try {
			ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
			return attr == null ? null : attr.getRequest();
		} catch (Exception e) {
			return null;
		}
	}

	private void fillOperator(SysOperLog log) {
		try {
			Object loginId = StpUtil.getLoginIdDefaultNull();
			if (loginId != null) {
				log.setOperator(loginId.toString());
			}
		} catch (Exception ignore) {
		}
	}
}
