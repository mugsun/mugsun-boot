package com.mugsun.boot.security;

import com.mugsun.boot.common.web.DirectResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.List;

/**
 * 请求体缓存过滤器：把请求体读进内存（上限 2MB，超出回 413），让访问日志、开放接口验签和 @RequestBody
 * 能各读一遍同一份内容。多部件（文件上传）和下面列出的路径不缓存。
 * <p>这里不改写请求内容：参数和请求体里的文字原样交给业务代码，富文本字段由 {@link RichTextSanitizer}
 * 在保存前净化（BOOT-117）。
 */
@Component
@Order(-100)
public class RequestBodyCacheFilter extends OncePerRequestFilter {

	/** 不缓存请求体的路径前缀（文件上传/下载、静态资源、监控） */
	private static final List<String> EXCLUDES = List.of(
		"/system/oss", "/system/file", "/actuator", "/warm-flow", "/v3/api-docs", "/doc.html", "/swagger");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String contentType = request.getContentType();
		boolean multipart = contentType != null && contentType.toLowerCase().startsWith("multipart/");
		if (multipart || isExcluded(UrlPathHelper.defaultInstance.getLookupPathForRequest(request))) {
			chain.doFilter(request, response);
			return;
		}
		try {
			chain.doFilter(new CachedBodyRequestWrapper(request), response);
		} catch (CachedBodyRequestWrapper.BodyTooLargeException e) {
			// 超限 body 直接 413：无界缓存会被未认证大 body 打成内存 DoS
			response.setStatus(413);
			response.setContentType("application/json;charset=UTF-8");
			response.getWriter().write("{\"code\":413,\"success\":false,\"msg\":\""
				+ DirectResponse.jsonMsg(request, "请求体过大") + "\",\"data\":null}");
		}
	}

	private boolean isExcluded(String uri) {
		if (uri == null) {
			return false;
		}
		for (String prefix : EXCLUDES) {
			if (uri.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}
}
