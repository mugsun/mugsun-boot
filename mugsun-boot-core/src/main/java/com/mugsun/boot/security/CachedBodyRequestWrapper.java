package com.mugsun.boot.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 请求包装：缓存请求体使其可重复读（@RequestBody、访问日志、开放接口签名校验共用同一份 body）。
 * 参数和请求体的内容不改写。
 */
public class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

	/** body 缓存上限：超出即拒绝（413 由 RequestBodyCacheFilter 映射），防未认证大 body 内存 DoS */
	private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

	/** body 超限信号（RequestBodyCacheFilter 捕获转 413） */
	public static final class BodyTooLargeException extends RuntimeException {
		BodyTooLargeException(String message) {
			super(message);
		}
	}

	private final byte[] body;

	public CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
		super(request);
		// 表单请求(application/x-www-form-urlencoded)不缓存 body：否则提前消费流会导致容器解析不到表单参数。
		// 仅缓存 JSON/原始 body，供 @RequestBody、访问日志与开放接口签名共用重复读。
		String ct = request.getContentType();
		boolean form = ct != null && ct.toLowerCase().contains("application/x-www-form-urlencoded");
		if (form) {
			this.body = null;
			return;
		}
		if (request.getContentLengthLong() > MAX_BODY_BYTES) {
			throw new BodyTooLargeException("请求体超过 2MB 上限");
		}
		java.io.InputStream in = request.getInputStream();
		java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		int total = 0;
		int n;
		while ((n = in.read(chunk)) != -1) {
			total += n;
			if (total > MAX_BODY_BYTES) {
				throw new BodyTooLargeException("请求体超过 2MB 上限");
			}
			buf.write(chunk, 0, n);
		}
		this.body = buf.toByteArray();
	}

	/** 原始请求体字节（供签名哈希和访问日志；表单请求返回 null，改由 super 读取） */
	public byte[] cachedBody() {
		return body;
	}

	@Override
	public ServletInputStream getInputStream() throws IOException {
		if (body == null) {
			return super.getInputStream();
		}
		ByteArrayInputStream bais = new ByteArrayInputStream(body);
		return new ServletInputStream() {
			@Override
			public int read() {
				return bais.read();
			}

			@Override
			public boolean isFinished() {
				return bais.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener listener) {
			}
		};
	}

	@Override
	public BufferedReader getReader() throws IOException {
		return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
	}
}
