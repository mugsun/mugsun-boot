package com.mugsun.boot.ai.support;

import com.mugsun.core.tool.exception.ServiceException;

import java.util.Locale;
import java.util.Set;

/**
 * 一份资料的分段设置：分段方式、分段长度、重叠、分隔符。
 * 上传走 {@link #strict}，取值范围和上传窗口里的输入框一致，超出就拒绝；
 * 重新分段用的是库里存的旧值，走 {@link #lenient} 收进范围，不让早先上传的资料从此无法重新分段。
 */
public record AiSegmentOptions(String type, int length, int overlap, String symbol) {

	public static final int MIN_LENGTH = 100;
	public static final int MAX_LENGTH = 4000;
	public static final int DEFAULT_LENGTH = 500;
	public static final int MAX_OVERLAP = 500;
	public static final int DEFAULT_OVERLAP = 50;
	/** 分隔符列是 VARCHAR(16) */
	public static final int MAX_SYMBOL_LENGTH = 16;
	/** 手工改一段内容时的字数上限，和最大分段长度相同 */
	public static final int MAX_SEGMENT_CHARS = MAX_LENGTH;

	private static final String DEFAULT_TYPE = "length";
	private static final Set<String> TYPES =
		Set.of("length", "symbol", "newline", "line", "paragraph", "para", "markdown", "md");

	/** 重叠最多是分段长度的一半，这样每段至少往前走半段，段数不超过「正文字数 ÷ 分段长度」的两倍 */
	public static int maxOverlapFor(int length) {
		return Math.min(MAX_OVERLAP, length / 2);
	}

	public static AiSegmentOptions strict(String type, Integer length, Integer overlap, String symbol) {
		String mode = normalizeType(type);
		if (!TYPES.contains(mode)) {
			throw new ServiceException("分段方式请在 Markdown 标题、段落、定长、按行、按分隔符里选一种");
		}
		int len = length == null ? DEFAULT_LENGTH : length;
		if (len < MIN_LENGTH || len > MAX_LENGTH) {
			throw new ServiceException("分段长度请填 " + MIN_LENGTH + " 到 " + MAX_LENGTH + " 之间的整数");
		}
		int maxOverlap = maxOverlapFor(len);
		int ov = overlap == null ? Math.min(DEFAULT_OVERLAP, maxOverlap) : overlap;
		if (ov < 0 || ov > maxOverlap) {
			throw new ServiceException("分段长度是 " + len + " 时，重叠请填 0 到 " + maxOverlap
				+ " 之间的整数（最多是分段长度的一半）");
		}
		String sym = symbol == null || symbol.isEmpty() ? null : symbol;
		if (sym != null && sym.length() > MAX_SYMBOL_LENGTH) {
			throw new ServiceException("分隔符请控制在 " + MAX_SYMBOL_LENGTH + " 个字符以内");
		}
		return new AiSegmentOptions(mode, len, ov, sym);
	}

	public static AiSegmentOptions lenient(String type, Integer length, Integer overlap, String symbol) {
		String mode = normalizeType(type);
		if (!TYPES.contains(mode)) {
			mode = DEFAULT_TYPE;
		}
		int len = length == null || length <= 0 ? DEFAULT_LENGTH : Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, length));
		int ov = overlap == null ? DEFAULT_OVERLAP : Math.max(0, overlap);
		ov = Math.min(ov, maxOverlapFor(len));
		String sym = symbol == null || symbol.isEmpty() ? null : symbol;
		if (sym != null && sym.length() > MAX_SYMBOL_LENGTH) {
			sym = sym.substring(0, MAX_SYMBOL_LENGTH);
		}
		return new AiSegmentOptions(mode, len, ov, sym);
	}

	private static String normalizeType(String type) {
		return type == null || type.isBlank() ? DEFAULT_TYPE : type.trim().toLowerCase(Locale.ROOT);
	}
}
