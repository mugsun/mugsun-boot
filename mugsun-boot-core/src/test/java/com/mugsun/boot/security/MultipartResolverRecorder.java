package com.mugsun.boot.security;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartResolver;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用：把名为 multipartResolver 的 Bean 包成计数器，DispatcherServlet 初始化时拿到的就是它。
 * <p>lite 模式配置类（不加 @Configuration/@TestConfiguration，见 TestComponentScanGuardTest），通过 @Import 显式引入。
 * 包装只委托、不改行为，对其他测试无副作用。
 */
public class MultipartResolverRecorder {

	/** 到达 DispatcherServlet.checkMultipart 且判定为 multipart 的次数 */
	public static final AtomicInteger REACHED = new AtomicInteger();
	/** 真正解析 multipart（getParts）的次数 */
	public static final AtomicInteger PARSED = new AtomicInteger();
	/** 解析完成那一刻临时目录里 upload_* 的文件数累加 */
	public static final AtomicInteger TMP_FILES_SEEN = new AtomicInteger();
	public static void reset() {
		REACHED.set(0);
		PARSED.set(0);
		TMP_FILES_SEEN.set(0);
	}

	/** Tomcat 在 location 为空时把 multipart 临时文件写到上下文临时目录，文件名 upload_*.tmp */
	public static int countTmp(ServletContext servletContext) {
		File dir = (File) servletContext.getAttribute(ServletContext.TEMPDIR);
		String[] names = dir == null ? null : dir.list((d, n) -> n.startsWith("upload_"));
		return names == null ? 0 : names.length;
	}

	@Bean
	static BeanPostProcessor multipartResolverRecorderPostProcessor() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if ("multipartResolver".equals(beanName) && bean instanceof MultipartResolver delegate
					&& !(bean instanceof Counting)) {
					return new Counting(delegate);
				}
				return bean;
			}
		};
	}

	/** 计数包装 */
	public static final class Counting implements MultipartResolver {
		private final MultipartResolver delegate;

		Counting(MultipartResolver delegate) {
			this.delegate = delegate;
		}

		@Override
		public boolean isMultipart(HttpServletRequest request) {
			boolean m = delegate.isMultipart(request);
			if (m) {
				REACHED.incrementAndGet();
			}
			return m;
		}

		@Override
		public MultipartHttpServletRequest resolveMultipart(HttpServletRequest request) {
			PARSED.incrementAndGet();
			MultipartHttpServletRequest r = delegate.resolveMultipart(request);
			// 从当前请求取 ServletContext，不静态持有
			TMP_FILES_SEEN.addAndGet(countTmp(request.getServletContext()));
			return r;
		}

		@Override
		public void cleanupMultipart(MultipartHttpServletRequest request) {
			delegate.cleanupMultipart(request);
		}
	}
}
