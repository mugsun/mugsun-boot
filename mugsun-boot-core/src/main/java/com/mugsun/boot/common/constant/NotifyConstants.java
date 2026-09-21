package com.mugsun.boot.common.constant;

/**
 * 多渠道消息统一调度（G88）常量。
 */
public interface NotifyConstants {

	/** 渠道编码：站内信 */
	String CHANNEL_IN_APP = "in_app";
	/** 渠道编码：邮件 */
	String CHANNEL_MAIL = "mail";
	/** 渠道编码：短信 */
	String CHANNEL_SMS = "sms";

	/** 流水状态：初始（已落库待投递） */
	String STATUS_INIT = "INIT";
	/** 流水状态：忽略（渠道停用/未配置，或接收人缺联系方式；终态） */
	String STATUS_IGNORE = "IGNORE";
	/** 流水状态：发送成功（终态） */
	String STATUS_SUCCESS = "SUCCESS";
	/** 流水状态：发送失败（待重试） */
	String STATUS_FAILURE = "FAILURE";
	/** 流水状态：死信（重试达上限；终态） */
	String STATUS_DEAD = "DEAD";

	/** sys_param 键：重试扫描间隔（毫秒） */
	String PARAM_RETRY_SCAN_INTERVAL = "notify.retry.scan-interval-ms";
	/** sys_param 键：单条流水最大重试次数 */
	String PARAM_RETRY_MAX_TIMES = "notify.retry.max-times";
	/** sys_param 键：线性退避基数（毫秒） */
	String PARAM_RETRY_BACKOFF = "notify.retry.backoff-ms";
	/** 兜底默认：扫描间隔 60s */
	long DEFAULT_RETRY_SCAN_INTERVAL_MS = 60000L;
	/** 兜底默认：最大重试 3 次 */
	int DEFAULT_RETRY_MAX_TIMES = 3;
	/** 兜底默认：退避基数 5 分钟 */
	long DEFAULT_RETRY_BACKOFF_MS = 300000L;
	/** 调度 tick（固定 15s 触发一次，实际扫描按 notify.retry.scan-interval-ms 节流） */
	long RETRY_SCHEDULE_TICK_MS = 15000L;

	/** 重试扫描分布式锁键（Redis SETNX，集群仅一节点执行扫描） */
	String RETRY_LOCK_KEY = "mugsun:notify:retry-lock";
	/** 锁持有上限（秒），防持锁节点宕机死锁 */
	long RETRY_LOCK_SECONDS = 300L;
	/** 单次扫描最大处理条数，防积压拖垮单轮 */
	int RETRY_SCAN_BATCH_SIZE = 100;

	/** 内容摘要截断长度 */
	int CONTENT_SUMMARY_LEN = 500;
	/** 错误信息截断长度 */
	int ERROR_MSG_LEN = 500;

	/** 邮件渠道 SMTP 连接/读取超时（毫秒），防占位配置长阻塞 */
	int MAIL_TIMEOUT_MS = 3000;

	/** 内置模板编码：新用户欢迎通知 */
	String TEMPLATE_WELCOME = "welcome";
	/** 渠道分隔符（模板 channels / required_params 列） */
	String SPLIT = ",";
}
