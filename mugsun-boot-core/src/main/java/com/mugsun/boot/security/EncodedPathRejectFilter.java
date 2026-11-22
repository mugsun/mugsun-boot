package com.mugsun.boot.security;

import com.mugsun.boot.common.web.DirectResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 拒绝路径里百分号编码的非保留字符（字母、数字、{@code - . _ ~}），返回 400。
 * <p>RFC 3986 规定这类字符不应编码，正常客户端（含 encodeURIComponent）不会这样发；Spring 路由却按解码后的路径匹配，
 * {@code /system/%74enant/list} 照样进租户列表。任何按原始 URI 做前缀判断的守卫都会被这种写法绕过，
 * 这里在最前面统一拦掉。保留字符和非 ASCII 的编码（{@code %2F}、中文文件名等）有正当用途，不在此处理。
 */
@Component
@Order(MultipartAuthFilter.ORDER + 1)
public class EncodedPathRejectFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		if (hasEncodedUnreserved(request.getRequestURI())) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			response.setContentType("application/json;charset=UTF-8");
			response.getWriter().write("{\"code\":400,\"success\":false,\"msg\":\""
				+ DirectResponse.jsonMsg(request, "请求路径含非法编码") + "\",\"data\":null}");
			return;
		}
		chain.doFilter(request, response);
	}

	static boolean hasEncodedUnreserved(String uri) {
		if (uri == null) {
			return false;
		}
		for (int i = uri.indexOf('%'); i >= 0 && i + 2 < uri.length(); i = uri.indexOf('%', i + 1)) {
			int hi = Character.digit(uri.charAt(i + 1), 16);
			int lo = Character.digit(uri.charAt(i + 2), 16);
			if (hi >= 0 && lo >= 0 && isUnreserved((char) (hi * 16 + lo))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isUnreserved(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
			|| c == '-' || c == '.' || c == '_' || c == '~';
	}
}
