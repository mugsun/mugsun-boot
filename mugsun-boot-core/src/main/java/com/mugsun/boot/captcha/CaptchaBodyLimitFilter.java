package com.mugsun.boot.captcha;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 验证码校验接口请求体上限：声明了 Content-Length 的超限请求直接拒绝；
 * 分块传输（无 Content-Length）先限长读入，超出同样回标准 R JSON（HTTP 200 + 40011）。
 */
@Component
// 让出 HIGHEST_PRECEDENCE 给 MultipartAuthFilter：本过滤器会读请求体，必须排在 multipart 闸门之后
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CaptchaBodyLimitFilter extends OncePerRequestFilter {

	/** 400 个轨迹点约 20KB */
	static final int MAX_BODY_BYTES = 32 * 1024;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return uri == null || !(uri.endsWith("/auth/captcha/verify") || uri.endsWith("/app/captcha/check"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		if (request.getContentLengthLong() > MAX_BODY_BYTES) {
			reject(response);
			return;
		}
		// 先按上限读入（含分块传输），超限统一回标准 R JSON（HTTP 200 + 40011），不进入业务解析
		byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			reject(response);
			return;
		}
		chain.doFilter(new Cached(request, body), response);
	}

	private static void reject(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("application/json;charset=UTF-8");
		response.setHeader("Connection", "close");
		response.getWriter().write("{\"code\":" + CaptchaCodes.FAILED + ",\"success\":false,\"data\":null,\"msg\":\""
			+ CaptchaCodes.MSG_FAILED + "\"}");
		response.flushBuffer();
	}

	/** 已读入的请求体回放 */
	static final class Cached extends HttpServletRequestWrapper {

		private final byte[] body;

		Cached(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(body);
			return new ServletInputStream() {
				@Override
				public int read() {
					return in.read();
				}

				@Override
				public int read(byte[] b, int off, int len) {
					return in.read(b, off, len);
				}

				@Override
				public boolean isFinished() {
					return in.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					throw new UnsupportedOperationException();
				}
			};
		}

		@Override
		public BufferedReader getReader() {
			return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}

		@Override
		public int getContentLength() {
			return body.length;
		}

		@Override
		public long getContentLengthLong() {
			return body.length;
		}
	}
}
