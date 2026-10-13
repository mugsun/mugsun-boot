package com.mugsun.boot.system.file;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 本地存储直出访问控制：x-file-storage enable-access 注册的静态资源映射本身不鉴权，
 * 私有附件只要知道 url 就能匿名下载。此过滤器拦在资源映射之前——
 * 公开前缀（头像等）放行；其余对象必须带有效短时签名，否则一律 404（不区分存在与否）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class LocalFileAccessFilter extends OncePerRequestFilter {

	private final LocalFileAccessProperties properties;
	private final LocalFileUrlSigner signer;

	public LocalFileAccessFilter(LocalFileAccessProperties properties, LocalFileUrlSigner signer) {
		this.properties = properties;
		this.signer = signer;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String ctx = request.getContextPath();
		String path = ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
		String prefix = properties.getUrlPrefix();
		return !path.startsWith(prefix) && !path.equals(prefix.substring(0, prefix.length() - 1));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String relPath = relativePath(request);
		if (relPath == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		if (isPublic(relPath) || signer.verify(relPath, request.getParameter(LocalFileUrlSigner.PARAM_EXPIRE),
			request.getParameter(LocalFileUrlSigner.PARAM_SIGN))) {
			chain.doFilter(request, response);
			return;
		}
		response.sendError(HttpServletResponse.SC_NOT_FOUND);
	}

	/** 取 urlPrefix 后的相对路径；含编码、穿越、路径参数、反斜杠等可疑形态直接返回 null（拒绝） */
	String relativePath(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String ctx = request.getContextPath();
		if (ctx != null && !ctx.isEmpty()) {
			uri = uri.substring(ctx.length());
		}
		String prefix = properties.getUrlPrefix();
		if (!uri.startsWith(prefix)) {
			return null;
		}
		String rel = uri.substring(prefix.length());
		if (rel.isEmpty() || rel.contains("%") || rel.contains("..") || rel.contains(";") || rel.contains("\\")
			|| rel.contains("//") || rel.startsWith("/")) {
			return null;
		}
		return rel;
	}

	private boolean isPublic(String relPath) {
		for (String p : properties.getPublicPrefixes()) {
			if (p != null && !p.isEmpty() && relPath.startsWith(p)) {
				return true;
			}
		}
		return false;
	}
}
