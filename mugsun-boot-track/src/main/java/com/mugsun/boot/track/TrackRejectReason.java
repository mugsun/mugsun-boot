package com.mugsun.boot.track;

/**
 * 接入自检拒收/异常原因码（每个码带严重度与修复建议，管理端「接入自检」直接展示）。
 * <p>severity：fail=数据没进来；warn=数据进来了但被修正/部分丢弃，值得排查。
 * 注：服务端不做来源域名/签名校验（appKey 非机密，防护靠存在性校验 + 限流 + 背压），故无对应原因码。
 */
public enum TrackRejectReason {

	APP_DISABLED("fail", "这个应用的采集已关闭，服务端拒收全部上报。",
		"到「接入管理」打开这个应用的采集开关；如果是有意停用，请从页面里移除 SDK 初始化，避免白白发请求。"),
	BAD_PAYLOAD("fail", "请求体为空、不是 JSON 对象，或 gzip 压缩内容损坏。",
		"不要手工拼上报请求，使用官方 SDK；如果经过网关/代理，确认它没有改写请求体或去掉 Content-Encoding。"),
	PAYLOAD_TOO_LARGE("fail", "单次上报的请求体超过服务端上限（413）。",
		"调小 SDK 的 batchSize；检查是否把大对象（整份响应、DOM、base64 图片）塞进了事件属性。"),
	SCHEMA_ERROR("fail", "事件缺少必填字段（event_id / distinct_id / session_id / ts）或字段超长，或事件列表格式不对。",
		"升级到最新 SDK；自定义上报请按协议填齐字段，ID 类字段不要超过 64 位。"),
	BAD_EVENT_NAME("fail", "事件名不合法：$ 开头的是保留名，自定义事件名只能字母开头、字母数字下划线、最长 64 位。",
		"把自定义事件改名，例如 order_paid；不要自己使用 $ 前缀。"),
	EVENT_DISABLED("warn", "事件在「事件定义」里被停用，服务端静默丢弃。",
		"如果仍需要这个事件，到「事件定义」重新启用；否则从代码里删掉这次 track 调用。"),
	TS_ABSURD("fail", "事件时间离谱（早于 2020 年或比服务器时间晚 7 天以上），被丢弃。",
		"确认传的是毫秒时间戳而不是秒；不要自行覆盖 ts，交给 SDK 生成。"),
	CLOCK_SKEWED("warn", "客户端时钟和服务器相差超过 24 小时，事件已收下，但发生时间被改成接收时间。",
		"多为用户设备时间不准，少量可忽略；比例高时检查是否有自定义 ts 或离线补发积压过久。"),
	RATE_LIMITED("fail", "同一 IP 对这个应用的上报超过每分钟上限（429）。",
		"检查是否有循环/重复上报（如在渲染函数里 track）；公司出口 NAT 共用 IP 时，请管理员调高 track.collect.rate-limit 参数。"),
	BATCH_TRUNCATED("warn", "一次上报的事件条数超过上限，多出的被截掉。",
		"调小 SDK 的 batchSize，或升级 SDK（新版自动分批）。"),
	QUEUE_FULL("warn", "服务端写入队列已满，部分事件被丢弃（服务端背压）。",
		"这是服务端容量问题：联系运维检查埋点库写入和消费延迟，必要时扩容。"),
	IDENTIFY_REJECTED("warn", "identify 被拒：没有带登录令牌，或令牌用户与传入的 userId 不一致，未建立身份映射。",
		"在用户登录后再调用 identify，并通过 headers 选项带上当前登录令牌；userId 用登录接口返回的用户 ID。"),
	TENANT_MISMATCH("warn", "登录令牌所属租户与应用所属租户不一致，事件按令牌租户归属。",
		"确认每个租户使用自己的应用标识；不要在多个租户之间共用同一个 appKey。"),
	REPLAY_DISABLED("warn", "SDK 在上传会话回放，但这个应用没有打开会话回放。",
		"需要回放就到「接入管理」打开会话回放；不需要就别加载回放插件，省流量。"),
	REPLAY_TOO_LARGE("warn", "回放块或单个会话回放超过体积上限，后续内容不再接收。",
		"用 maskTextSelector / blockSelector 屏蔽大块动态区域，或调低回放采样率。"),
	API_BODY_DISABLED("warn", "SDK 在上传接口响应体，但这个应用没有打开响应体采集。",
		"需要的话到「接入管理」打开响应体采集；否则在 SDK 关闭 apiMonitor 的 body 采集。"),
	API_BODY_TOO_LARGE("warn", "接口响应体超过采集上限，被拒收。",
		"把大接口加入忽略列表，或调低 apiBodyMaxBytes；不要采集下载/导出类接口。"),
	SERVER_BUSY("warn", "服务端暂时繁忙（503），SDK 会自动重试。",
		"偶发可忽略；持续出现请联系运维检查对象存储和队列。");

	private final String severity;
	private final String description;
	private final String suggestion;

	TrackRejectReason(String severity, String description, String suggestion) {
		this.severity = severity;
		this.description = description;
		this.suggestion = suggestion;
	}

	public String severity() { return severity; }
	public String description() { return description; }
	public String suggestion() { return suggestion; }

	/** 未显式指定原因时按 HTTP 状态兜底（collect 通道） */
	public static TrackRejectReason byStatus(int status) {
		return switch (status) {
			case 413 -> PAYLOAD_TOO_LARGE;
			case 429 -> RATE_LIMITED;
			case 503 -> SERVER_BUSY;
			case 400 -> SCHEMA_ERROR;
			default -> null;
		};
	}

	/** 事件级丢弃原因（TrackIngestMetrics reason 字串）→ 原因码；无对应返回 null */
	public static TrackRejectReason byDropReason(String reason) {
		return switch (reason) {
			case "invalid_event" -> SCHEMA_ERROR;
			case "bad_name" -> BAD_EVENT_NAME;
			case "event_disabled" -> EVENT_DISABLED;
			case "ts_absurd" -> TS_ABSURD;
			case "batch_truncated" -> BATCH_TRUNCATED;
			case "queue_full" -> QUEUE_FULL;
			case "identify_no_token", "identify_user_mismatch" -> IDENTIFY_REJECTED;
			case "token_tenant_mismatch" -> TENANT_MISMATCH;
			case "clock_skewed" -> CLOCK_SKEWED;
			default -> null;
		};
	}
}
