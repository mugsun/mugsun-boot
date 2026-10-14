package com.mugsun.boot.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 只读演示模式的资源闸门（Order -140，在 {@link DemoReadOnlyFilter} 之后，只处理已放行的请求）：
 * <ul>
 *   <li>导出/下载/SSE 等重请求全局并发上限（{@code mugsun.demo.heavy-concurrency}，默认 2），超出返回 429（安全复审 F4）；</li>
 *   <li>埋点库容量超过硬上限（{@link DemoCapacityGate}）时采集返回 503（安全复审 F3）。
 *       按 IP+app_key 的采集配额沿用埋点模块已有的分钟窗限流（TrackIngestService），这里不重复实现。</li>
 * </ul>
 */
@Component
@Order(-140)
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "true")
public class DemoLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(DemoLimitFilter.class);

	private final ObjectMapper objectMapper;
	private final DemoCapacityGate capacityGate;
	private final Semaphore heavy;
	private final int heavyMax;

	public DemoLimitFilter(ObjectMapper objectMapper, DemoCapacityGate capacityGate,
						   @Value("${mugsun.demo.heavy-concurrency:2}") int heavyConcurrency) {
		this.objectMapper = objectMapper;
		this.capacityGate = capacityGate;
		this.heavyMax = Math.max(1, heavyConcurrency);
		this.heavy = new Semaphore(heavyMax);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String path = DemoReadOnlyPolicy.normalize(DemoReadOnlyFilter.pathWithinApp(request));
		if (path != null && DemoReadOnlyPolicy.TRACK_COLLECT_ALLOWLIST.contains(path)) {
			if (capacityGate.trackFull()) {
				write(response, 503, "演示环境埋点库已满，暂停采集");
				return;
			}
			chain.doFilter(request, response);
			return;
		}
		if (path == null || !isHeavy(path, request)) {
			chain.doFilter(request, response);
			return;
		}
		if (!heavy.tryAcquire()) {
			write(response, 429, "演示环境繁忙：导出类请求同时只能进行 " + heavyLimit() + " 个，请稍后再试");
			return;
		}
		AtomicBoolean released = new AtomicBoolean(false);
		Runnable release = () -> {
			if (released.compareAndSet(false, true)) {
				heavy.release();
			}
		};
		try {
			chain.doFilter(request, response);
		} finally {
			if (request.isAsyncStarted()) {
				// SSE 等异步响应：连接结束时才归还名额
				request.getAsyncContext().addListener(new AsyncListener() {
					@Override public void onComplete(AsyncEvent e) { release.run(); }
					@Override public void onTimeout(AsyncEvent e) { release.run(); }
					@Override public void onError(AsyncEvent e) { release.run(); }
					@Override public void onStartAsync(AsyncEvent e) { }
				});
			} else {
				release.run();
			}
		}
	}

	int heavyLimit() {
		return heavyMax;
	}

	/** 导出、下载、SSE 流 */
	static boolean isHeavy(String path, HttpServletRequest request) {
		String lower = path.toLowerCase();
		if (lower.endsWith("/export") || lower.contains("/export/") || lower.contains("/download")
			|| lower.endsWith("/stream") || lower.contains("/sse")) {
			return true;
		}
		String accept = request.getHeader("Accept");
		return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
	}


	private void write(HttpServletResponse response, int status, String msg) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", status);
		body.put("success", false);
		body.put("msg", msg);
		body.put("data", null);
		body.put("errorCode", status == 503 ? "DEMO_CAPACITY" : "DEMO_BUSY");
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(objectMapper.writeValueAsString(body));
	}
}
