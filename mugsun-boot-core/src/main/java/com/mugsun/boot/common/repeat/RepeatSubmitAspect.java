package com.mugsun.boot.common.repeat;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.core.tool.exception.ServiceException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 防重复提交切面：按 用户+方法+参数指纹 生成 Redis 锁，setIfAbsent 判重。
 */
@Aspect
@Component
public class RepeatSubmitAspect {

	private static final String KEY_PREFIX = "mugsun:repeat:";

	private final StringRedisTemplate redisTemplate;

	public RepeatSubmitAspect(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Around("@annotation(repeatSubmit)")
	public Object around(ProceedingJoinPoint point, RepeatSubmit repeatSubmit) throws Throwable {
		String key = KEY_PREFIX + buildTicket(point);
		long interval = repeatSubmit.interval();
		// interval=0 用安全兜底 TTL，防执行异常未删锁导致永久锁死
		Duration ttl = interval > 0 ? Duration.ofMillis(interval) : Duration.ofSeconds(30);

		Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
		if (!Boolean.TRUE.equals(acquired)) {
			throw new ServiceException(repeatSubmit.message());
		}
		try {
			return point.proceed();
		} finally {
			// interval=0：上次执行完才可再来，执行结束即释放锁
			if (interval == 0) {
				redisTemplate.delete(key);
			}
		}
	}

	/** ticket = 用户 + 方法全名 + 参数指纹（过滤 servlet/文件参数） */
	private String buildTicket(ProceedingJoinPoint point) {
		Object loginId = StpUtil.getLoginIdDefaultNull();
		String user = loginId == null ? "anon" : loginId.toString();
		String method = point.getSignature().getDeclaringTypeName() + "." + point.getSignature().getName();
		String args = Arrays.stream(point.getArgs())
			.filter(a -> !(a instanceof ServletRequest) && !(a instanceof ServletResponse) && !(a instanceof MultipartFile))
			.map(String::valueOf)
			.collect(Collectors.joining(","));
		return user + ":" + method + ":" + Integer.toHexString(args.hashCode());
	}
}
