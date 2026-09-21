package com.mugsun.boot.common.perm;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaIgnore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.TreeSet;

/**
 * 权限资源自动登记（启动期扫描）：遍历全部请求映射，收集显式 {@link SaCheckPermission} 权限码形成资源目录，
 * 并审计 /system/** 写接口的覆盖情况——显式带码 / 兜底派生（{@link PermissionGuardInterceptor} 强制）/ 豁免。
 * <p>「接口即权限资源」：启动日志即为权限码清单，杜绝「注解已加、目录漏登」，供覆盖率审计与后续按码授权。
 */
@Component
public class PermissionScanner {

	private static final Logger log = LoggerFactory.getLogger(PermissionScanner.class);

	private final RequestMappingHandlerMapping handlerMapping;

	public PermissionScanner(
			@org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
			RequestMappingHandlerMapping handlerMapping) {
		this.handlerMapping = handlerMapping;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void scan() {
		Set<String> codes = new TreeSet<>();
		int explicitWrites = 0;
		int derivedWrites = 0;
		int exemptWrites = 0;
		for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
			RequestMappingInfo info = entry.getKey();
			HandlerMethod hm = entry.getValue();
			SaCheckPermission perm = annotation(hm, SaCheckPermission.class);
			if (perm != null) {
				for (String v : perm.value()) {
					codes.add(v);
				}
			}
			for (String path : paths(info)) {
				if (!path.startsWith("/system/") || !isWrite(info)) {
					continue;
				}
				if (perm != null || annotation(hm, SaCheckRole.class) != null) {
					explicitWrites++;
				} else if (annotation(hm, SaIgnore.class) != null || PermissionModules.isExempt(path)
					|| PermissionModules.deriveCode(path) == null) {
					exemptWrites++;
				} else {
					derivedWrites++;
				}
			}
		}
		log.info("权限资源登记：显式权限码 {} 个 {}", codes.size(), codes);
		log.info("/system 写接口权限覆盖：显式带码 {} / 兜底派生强制 {} / 豁免 {}（受控写接口 100% 有码或兜底拒绝）",
			explicitWrites, derivedWrites, exemptWrites);
	}

	private <A extends java.lang.annotation.Annotation> A annotation(HandlerMethod hm, Class<A> type) {
		A a = hm.getMethodAnnotation(type);
		return a != null ? a : hm.getBeanType().getAnnotation(type);
	}

	private boolean isWrite(RequestMappingInfo info) {
		var methods = info.getMethodsCondition().getMethods();
		return methods.stream().anyMatch(m -> {
			String n = m.name();
			return "POST".equals(n) || "PUT".equals(n) || "DELETE".equals(n);
		});
	}

	@SuppressWarnings("deprecation")
	private Set<String> paths(RequestMappingInfo info) {
		PathPatternsRequestCondition pp = info.getPathPatternsCondition();
		if (pp != null) {
			Set<String> s = new java.util.HashSet<>();
			pp.getPatterns().forEach(p -> s.add(p.getPatternString()));
			return s;
		}
		PatternsRequestCondition pc = info.getPatternsCondition();
		return pc != null ? pc.getPatterns() : Set.of();
	}
}
