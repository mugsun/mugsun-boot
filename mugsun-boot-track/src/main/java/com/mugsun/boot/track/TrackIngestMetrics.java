package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.TrackConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 埋点摄入自监控指标（Micrometer，接 G90 actuator/metrics 体系）：
 * received / dropped / ratelimited / duplicated / clock_skewed / identity_rejected + 落库 lag。
 * <p>dropped 与 identity_rejected 带 reason 标签细分原因（见 {@link TrackConstants} 指标名注释）。
 */
@Component
public class TrackIngestMetrics {

	private final MeterRegistry registry;

	public TrackIngestMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	/** 摄入接收（通过全部校验、已入消费队列）事件数 */
	public void received(long n) {
		registry.counter(TrackConstants.METRIC_RECEIVED).increment(n);
	}

	/** 丢弃事件数（reason：batch_truncated / invalid_event / bad_name / ts_absurd / queue_full / retry_exhausted / persist_failed） */
	public void dropped(String reason, long n) {
		registry.counter(TrackConstants.METRIC_DROPPED, Tags.of("reason", reason)).increment(n);
	}

	/** 限流拒收批次数（IP+appKey 分钟窗） */
	public void ratelimited() {
		registry.counter(TrackConstants.METRIC_RATELIMITED).increment();
	}

	/** Redis 幂等命中丢弃事件数 */
	public void duplicated(long n) {
		registry.counter(TrackConstants.METRIC_DUPLICATED).increment(n);
	}

	/** 发生校时修正事件数 */
	public void clockSkewed(long n) {
		registry.counter(TrackConstants.METRIC_CLOCK_SKEWED).increment(n);
	}

	/** 身份裁定拒绝/越界数（reason：identify_no_token / identify_user_mismatch / token_tenant_mismatch） */
	public void identityRejected(String reason) {
		registry.counter(TrackConstants.METRIC_IDENTITY_REJECTED, Tags.of("reason", reason)).increment();
	}

	/** 回放块接收数（通过全部校验、已入回放消费队列；G100） */
	public void replayReceived(long n) {
		registry.counter(TrackConstants.METRIC_REPLAY_RECEIVED).increment(n);
	}

	/** 回放块幂等命中丢弃数（同 session+seq 重发；G100） */
	public void replayDuplicated(long n) {
		registry.counter(TrackConstants.METRIC_REPLAY_DUPLICATED).increment(n);
	}

	/** 响应体接收数（通过全部校验、已落对象存储；G102） */
	public void apiBodyReceived(long n) {
		registry.counter(TrackConstants.METRIC_API_BODY_RECEIVED).increment(n);
	}

	/** 响应体幂等命中丢弃数（同 event_id 重发；G102） */
	public void apiBodyDuplicated(long n) {
		registry.counter(TrackConstants.METRIC_API_BODY_DUPLICATED).increment(n);
	}

	/** 落库延迟：received_at 与落库时刻差（队列积压时此指标抬头，实时流不受影响） */
	public void lag(Duration lag) {
		registry.timer(TrackConstants.METRIC_LAG).record(lag);
	}
}
