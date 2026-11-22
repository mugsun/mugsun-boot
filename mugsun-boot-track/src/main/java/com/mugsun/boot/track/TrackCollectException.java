package com.mugsun.boot.track;

import com.mugsun.boot.common.i18n.StoredSentence;
import com.mugsun.core.i18n.message.I18n;

import java.util.Locale;

/**
 * collect 端点拒收信号：携带 HTTP 状态码（400 协议/应用非法，413 超限，429 限流），
 * 由控制器映射为同状态码的 R 信封响应。
 * <p>拒收的这句话有两个读者，语言可能不一样：上报方（访客的浏览器）当场收到，管理员过后在「接入自检」里看到。
 * 所以这里留的是原文和要填的值，谁看就按谁的语言拼：{@link #messageForCaller()} 给上报方，
 * {@link #sample()} 存进自检记录。{@link #getMessage()} 按系统默认语言拼好，写日志用。
 */
public class TrackCollectException extends RuntimeException {

	private static final Object[] NO_ARGS = new Object[0];

	private final int status;
	/** 接入自检原因码（可空：控制器按状态码兜底） */
	private final TrackRejectReason reason;
	/** 原文；带变量的句子用 {0}、{1} 占位 */
	private final String pattern;
	private final Object[] args;

	public TrackCollectException(int status, String message) {
		this(status, null, message, NO_ARGS);
	}

	public TrackCollectException(int status, String message, TrackRejectReason reason) {
		this(status, reason, message, NO_ARGS);
	}

	/** 带变量的句子：原文里用 {0}、{1} 占位，值跟在后面（数量传数字，不要先转成字符串） */
	public TrackCollectException(int status, String pattern, Object... args) {
		this(status, null, pattern, args);
	}

	private TrackCollectException(int status, TrackRejectReason reason, String pattern, Object[] args) {
		super(args.length == 0 ? pattern : I18n.get((Locale) null, pattern, args));
		this.status = status;
		this.reason = reason;
		this.pattern = pattern;
		this.args = args;
	}

	public TrackRejectReason getReason() {
		return reason;
	}

	public int getStatus() {
		return status;
	}

	/** 返回给上报方的那句话，按这次请求的语言 */
	public String messageForCaller() {
		return args.length == 0 ? I18n.translate(pattern) : I18n.get(pattern, args);
	}

	/** 存进接入自检的样例：管理员看的时候用 {@link StoredSentence#render} 按他的语言拼出来 */
	public String sample() {
		return StoredSentence.pack(pattern, args);
	}
}
