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
 * 分块传输（无 Content-Length）在读取时限长，超出即中断解析。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
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
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType("application/json;charset=UTF-8");
			response.getWriter().write("{\"code\":" + CaptchaCodes.FAILED + ",\"success\":false,\"data\":null,\"msg\":\""
				+ CaptchaCodes.MSG_FAILED + "\"}");
			return;
		}
		chain.doFilter(new Bounded(request), response);
	}

	static final class Bounded extends HttpServletRequestWrapper {

		private ServletInputStream stream;

		Bounded(HttpServletRequest request) {
			super(request);
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			if (stream == null) {
				stream = new LimitedStream(super.getInputStream());
			}
			return stream;
		}

		@Override
		public BufferedReader getReader() throws IOException {
			return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}
	}

	static final class LimitedStream extends ServletInputStream {

		private final ServletInputStream in;
		private long read;

		LimitedStream(ServletInputStream in) {
			this.in = in;
		}

		@Override
		public int read() throws IOException {
			int b = in.read();
			if (b >= 0) {
				count(1);
			}
			return b;
		}

		@Override
		public int read(byte[] buf, int off, int len) throws IOException {
			int n = in.read(buf, off, len);
			if (n > 0) {
				count(n);
			}
			return n;
		}

		private void count(int n) throws IOException {
			read += n;
			if (read > MAX_BODY_BYTES) {
				throw new IOException("captcha request body too large");
			}
		}

		@Override
		public boolean isFinished() {
			return in.isFinished();
		}

		@Override
		public boolean isReady() {
			return in.isReady();
		}

		@Override
		public void setReadListener(ReadListener listener) {
			in.setReadListener(listener);
		}
	}
}
