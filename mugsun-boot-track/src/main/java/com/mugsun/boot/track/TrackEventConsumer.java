package com.mugsun.boot.track;

import cn.hutool.http.useragent.UserAgent;
import cn.hutool.http.useragent.UserAgentUtil;
import com.mugsun.boot.auth.IpRegionService;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 埋点事件异步消费器：内存有界队列 → 批量聚合落库。
 * <p><b>执行器纪律（严禁继承请求租户上下文）</b>：独立 {@code newVirtualThreadPerTaskExecutor}，
 * 刻意<em>不走</em> AsyncConfig 的 @Async 执行器、<em>不挂</em> TenantTaskDecorator——消费批跨租户混合，
 * 若继承发起请求的租户 ThreadLocal，租户行级上下文会污染其他租户事件的落库归属；
 * 每行 tenant_id 以事件自带值显式写，落库全程 {@link TenantContext#ignore} + {@link TrackDS} 语义包裹。
 * <p><b>批量</b>：200 条 / 500ms 双触发；批内同 session 先聚合再 upsert（避免单条 ON CONFLICT 自冲突）。
 * <p><b>背压</b>：队列上限 {@value TrackConstants#CONSUME_QUEUE_CAPACITY}，offer 失败 = 丢新 + dropped 计数
 * （宁可丢事件不拖垮 DB）。<b>失败重试</b>：DB 瞬断批次重回队列尾，指数退避（1s 基数翻倍、30s 封顶），
 * 超 {@value TrackConstants#CONSUME_MAX_RETRY} 次丢弃 + 计数；进程重启丢失上限 = 一个批量窗口（at-most-once，
 * 幂等键防重放）。
 */
@Component
public class TrackEventConsumer {

	private static final Logger log = LoggerFactory.getLogger(TrackEventConsumer.class);

	private final LinkedBlockingQueue<TrackIngestEvent> queue =
		new LinkedBlockingQueue<>(TrackConstants.CONSUME_QUEUE_CAPACITY);
	/** 独立虚拟线程执行器：不挂 TenantTaskDecorator（见类 javadoc 执行器纪律） */
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private volatile boolean running = true;

	private final TrackEventStore store;
	private final IpRegionService ipRegionService;
	private final TrackIngestMetrics metrics;
	private final TrackAlertService alertService;

	public TrackEventConsumer(TrackEventStore store, IpRegionService ipRegionService, TrackIngestMetrics metrics,
							  TrackAlertService alertService) {
		this.store = store;
		this.ipRegionService = ipRegionService;
		this.metrics = metrics;
		this.alertService = alertService;
	}

	/** 启动消费循环（2 个虚拟线程，小并发档；不占业务数据源连接池——落库走 track 池） */
	@PostConstruct
	void start() {
		for (int i = 0; i < TrackConstants.CONSUME_THREAD_COUNT; i++) {
			executor.submit(this::consumeLoop);
		}
	}

	/** 摄入侧入队（满即 false=丢新，由调用方计数） */
	public boolean offer(TrackIngestEvent event) {
		return queue.offer(event);
	}

	/** 消费循环：poll 首条（500ms 空转 tick）→ 窗口内凑批 → flush；中断即退出 */
	private void consumeLoop() {
		List<TrackIngestEvent> batch = new ArrayList<>(TrackConstants.CONSUME_BATCH_SIZE);
		while (running) {
			try {
				TrackIngestEvent first = queue.poll(TrackConstants.CONSUME_BATCH_WINDOW_MS, TimeUnit.MILLISECONDS);
				if (first == null) {
					continue;
				}
				batch.add(first);
				long deadline = System.currentTimeMillis() + TrackConstants.CONSUME_BATCH_WINDOW_MS;
				while (batch.size() < TrackConstants.CONSUME_BATCH_SIZE) {
					long wait = deadline - System.currentTimeMillis();
					if (wait <= 0) {
						break;
					}
					TrackIngestEvent next = queue.poll(wait, TimeUnit.MILLISECONDS);
					if (next == null) {
						break;
					}
					batch.add(next);
				}
				flush(batch);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				// flush 已自处理重试；此处为兜底防御（如聚合/NPE），整批重回队列尾
				log.error("埋点消费批处理异常，批次重回队列：{}", e.getMessage(), e);
				requeue(batch);
			} finally {
				batch.clear();
			}
		}
	}

	/** 落库一批：富化（UA/IP 属地）→ 事件批量插 → 会话聚合 upsert → identity/event_def upsert → lag 指标
	 *  → $error 告警评估（G101，仅落库成功后；evaluateQuietly 非阻塞静默，任何异常不触发批次重试） */
	private void flush(List<TrackIngestEvent> batch) {
		if (batch.isEmpty()) {
			return;
		}
		try {
			enrich(batch);
			// 跨租户混合批写：显式 ignore 租户上下文（JdbcTemplate 原生 SQL 本不过租户插件，双保险）；
			// 数据源路由由 TrackEventStore 类级 @TrackDS 切面压栈
			TenantContext.ignore(() -> {
				store.insertEvents(batch);
				store.upsertSessions(aggregateSessions(batch));
				store.upsertIdentities(bindingsOf(batch));
				store.upsertEventDefs(eventDefsOf(batch));
			});
			long now = System.currentTimeMillis();
			for (TrackIngestEvent e : batch) {
				metrics.lag(Duration.ofMillis(Math.max(0, now - e.getReceivedAtMs())));
			}
			// G101 错误告警：$error 逐条评估（新指纹/频次阈值 → 站内信）；静默语义由 evaluateQuietly 保证
			for (TrackIngestEvent e : batch) {
				if (TrackConstants.EVENT_ERROR.equals(e.getEventName())) {
					alertService.evaluateQuietly(e);
				}
			}
		} catch (Exception e) {
			log.warn("埋点落库失败（批次重回队列尾重试）：{}", e.getMessage());
			requeue(batch);
		}
	}

	/** 批次重回队列尾：逐条 attempts+1，指数退避后经独立虚拟线程 re-offer；超上限丢弃 + 计数 */
	private void requeue(List<TrackIngestEvent> batch) {
		for (TrackIngestEvent event : batch) {
			int attempts = event.getAttempts() + 1;
			event.setAttempts(attempts);
			if (attempts > TrackConstants.CONSUME_MAX_RETRY) {
				metrics.dropped("retry_exhausted", 1);
				continue;
			}
			long backoff = Math.min(TrackConstants.CONSUME_RETRY_BACKOFF_BASE_MS << (attempts - 1),
				TrackConstants.CONSUME_RETRY_BACKOFF_MAX_MS);
			try {
				executor.submit(() -> {
					try {
						Thread.sleep(backoff);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
					if (!queue.offer(event)) {
						metrics.dropped("queue_full", 1);
					}
				});
			} catch (java.util.concurrent.RejectedExecutionException e) {
				// 停机窗口：执行器已关，按 at-most-once 语义丢弃 + 计数
				metrics.dropped("persist_failed", 1);
			}
		}
	}

	/** 富化：UA 解析 browser/os/device（仅 web 平台，props 自报 device 优先）+ IP 属地；批内同 UA/IP 去重解析 */
	private void enrich(List<TrackIngestEvent> batch) {
		Map<String, UserAgent> uaCache = new HashMap<>();
		Map<String, String> regionCache = new HashMap<>();
		for (TrackIngestEvent e : batch) {
			String ua = e.getUserAgent();
			if (ua != null && !ua.isBlank()
				&& (e.getPlatform() == null || TrackConstants.PLATFORM_WEB.equalsIgnoreCase(e.getPlatform()))) {
				UserAgent agent = uaCache.computeIfAbsent(ua, u -> {
					try {
						return UserAgentUtil.parse(u);
					} catch (Exception ex) {
						return null;
					}
				});
				if (agent != null) {
					e.setBrowser(truncate(agent.getBrowser() == null ? null : agent.getBrowser().getName()));
					e.setOs(truncate(agent.getOs() == null ? null : agent.getOs().getName()));
					if (e.getDevice() == null) {
						e.setDevice(deviceOf(agent, ua));
					}
				}
			}
			if (e.getIp() != null && !e.getIp().isBlank()) {
				e.setIpRegion(regionCache.computeIfAbsent(e.getIp(), ipRegionService::resolve));
			}
		}
	}

	private String truncate(String s) {
		if (s == null) {
			return null;
		}
		return s.length() > TrackConstants.BROWSER_OS_MAX_LEN ? s.substring(0, TrackConstants.BROWSER_OS_MAX_LEN) : s;
	}

	/** 设备类型兜底（props 未自报时）：iPad/Tablet 关键字判平板，hutool isMobile 判手机，其余桌面 */
	private static String deviceOf(UserAgent agent, String ua) {
		String platformName = agent.getPlatform() == null ? "" : agent.getPlatform().getName();
		if ("iPad".equalsIgnoreCase(platformName) || ua.contains("Tablet")) {
			return TrackConstants.DEVICE_TABLET;
		}
		return agent.isMobile() ? TrackConstants.DEVICE_MOBILE : TrackConstants.DEVICE_DESKTOP;
	}

	/** 批内同 session 聚合（appKey+sessionId 分组；极值/计数/置位一口径，与 TrackEventStore upsert 语义咬合） */
	private List<TrackEventStore.SessionAggregate> aggregateSessions(List<TrackIngestEvent> batch) {
		Map<String, List<TrackIngestEvent>> bySession = new LinkedHashMap<>();
		for (TrackIngestEvent e : batch) {
			bySession.computeIfAbsent(e.getAppKey() + "" + e.getSessionId(), k -> new ArrayList<>()).add(e);
		}
		List<TrackEventStore.SessionAggregate> out = new ArrayList<>(bySession.size());
		for (List<TrackIngestEvent> group : bySession.values()) {
			TrackIngestEvent earliest = null;
			TrackIngestEvent latest = null;
			int pageviews = 0;
			int hasError = 0;
			int settled = 0;
			Long userId = null;
			for (TrackIngestEvent e : group) {
				if (earliest == null || e.getTsMs() < earliest.getTsMs()) {
					earliest = e;
				}
				// 出口取"最晚"事件：同刻后到者胜（同一毫秒多事件时以批内靠后者为出口）
				if (latest == null || e.getTsMs() >= latest.getTsMs()) {
					latest = e;
				}
				if (TrackConstants.EVENT_PAGEVIEW.equals(e.getEventName())) {
					pageviews++;
				}
				if (TrackConstants.EVENT_ERROR.equals(e.getEventName())) {
					hasError = 1;
				}
				if (TrackConstants.EVENT_SESSION_END.equals(e.getEventName())) {
					settled = 1;
				}
				if (userId == null && e.getUserId() != null) {
					userId = e.getUserId();
				}
			}
			TrackIngestEvent first = group.get(0);
			long duration = latest.getTsMs() - earliest.getTsMs();
			out.add(new TrackEventStore.SessionAggregate(
				first.getSessionId(), first.getAppKey(), first.getTenantId(), first.getDistinctId(), userId,
				localTs(earliest.getTsMs()), localTs(latest.getTsMs()),
				(int) Math.min(duration, Integer.MAX_VALUE), pageviews, group.size(),
				earliest.getUrlPath(), latest.getUrlPath(),
				earliest.getReferrerDomain(), earliest.getUtmSource(), earliest.getBrowser(), earliest.getOs(),
				earliest.getDevice(), earliest.getIpRegion(), hasError, settled));
		}
		return out;
	}

	/** 批内 $identify 待绑定（identifyUserId 非空 = 已过 token 一致性核对）；同 distinct_id 批内去重保先出现者 */
	private List<TrackEventStore.IdentityBinding> bindingsOf(List<TrackIngestEvent> batch) {
		Map<String, TrackEventStore.IdentityBinding> bindings = new LinkedHashMap<>();
		for (TrackIngestEvent e : batch) {
			if (e.getIdentifyUserId() == null) {
				continue;
			}
			bindings.putIfAbsent(e.getAppKey() + "" + e.getDistinctId(),
				new TrackEventStore.IdentityBinding(e.getAppKey(), e.getDistinctId(), e.getIdentifyUserId(), e.getTenantId()));
		}
		return new ArrayList<>(bindings.values());
	}

	/** 批内事件名去重（自动注册 first/last_seen） */
	private List<TrackEventStore.EventDefSeen> eventDefsOf(List<TrackIngestEvent> batch) {
		Map<String, TrackEventStore.EventDefSeen> defs = new LinkedHashMap<>();
		for (TrackIngestEvent e : batch) {
			defs.putIfAbsent(e.getAppKey() + "" + e.getEventName(),
				new TrackEventStore.EventDefSeen(e.getAppKey(), e.getEventName(), e.getTenantId()));
		}
		return new ArrayList<>(defs.values());
	}

	/** epoch 毫秒 → UTC 墙钟（TIMESTAMP 列绑定规约，见 TrackEventStore javadoc） */
	private static LocalDateTime localTs(long epochMs) {
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
	}

	/** 停机：退出循环 → 等 in-flight → 残余队列尽力 flush 一次（at-most-once 语义内的最后努力） */
	@PreDestroy
	void stop() {
		running = false;
		executor.shutdown();
		try {
			if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			executor.shutdownNow();
		}
		List<TrackIngestEvent> rest = new ArrayList<>();
		queue.drainTo(rest);
		if (!rest.isEmpty()) {
			try {
				flush(rest);
			} catch (Exception e) {
				log.warn("停机残余批次落库失败，按 at-most-once 语义丢弃 {} 条：{}", rest.size(), e.getMessage());
			}
		}
	}
}
