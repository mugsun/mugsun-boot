package com.mugsun.boot.ai.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 知识库分段：对齐开源常见策略（定长 / 换行 / 符号 / Markdown 标题 / 段落）。
 * 分段规则自己实现，没有用 Spring AI 的 TokenTextSplitter：行为可测、无外部模型。
 * 每一段都不超过分段长度：按符号、换行、段落、标题切出来的块如果超长，再按定长切开。
 */
public final class AiTextSplitter {

	private static final Pattern MD_HEADING = Pattern.compile("(?m)^#{1,6}\\s+.+$");

	private AiTextSplitter() {
	}

	/** 段数超过上限：切到上限就停，不会先把所有分段都放进内存 */
	public static final class TooManyPiecesException extends RuntimeException {

		private final int limit;

		TooManyPiecesException(int limit) {
			super("more than " + limit + " pieces");
			this.limit = limit;
		}

		public int limit() {
			return limit;
		}
	}

	public static List<String> split(String text, int length, int overlap, String type, String symbol) {
		return split(text, length, overlap, type, symbol, Integer.MAX_VALUE);
	}

	/**
	 * @param maxPieces 最多切出多少段，超过时抛 {@link TooManyPiecesException}；小于等于 0 表示不限
	 */
	public static List<String> split(String text, int length, int overlap, String type, String symbol, int maxPieces) {
		Pieces out = new Pieces(maxPieces <= 0 ? Integer.MAX_VALUE : maxPieces);
		if (text == null || text.isBlank()) {
			return out.list;
		}
		String raw = normalizeNewlines(text).trim();
		String mode = type == null || type.isBlank() ? "length" : type.trim().toLowerCase(Locale.ROOT);
		int ov = Math.max(0, overlap);
		switch (mode) {
			case "symbol" -> byDelimiter(raw, symbol == null || symbol.isBlank() ? "\n\n" : normalizeNewlines(symbol),
				Math.max(32, length), ov, out);
			case "newline", "line" -> byDelimiter(raw, "\n", Math.max(32, length), ov, out);
			case "paragraph", "para" -> byDelimiter(raw, "\n\n", Math.max(32, length), ov, out);
			case "markdown", "md" -> byMarkdown(raw, Math.max(64, length), ov, out);
			default -> byLength(raw, Math.max(32, length), ov, out);
		}
		return out.list;
	}

	private static String normalizeNewlines(String s) {
		return s.replace("\r\n", "\n").replace('\r', '\n');
	}

	/** 逐个找分隔符，边找边出段：段数到上限就停，不用一次 split 出全部 */
	private static void byDelimiter(String text, String delimiter, int length, int overlap, Pieces out) {
		int from = 0;
		while (from <= text.length()) {
			int at = text.indexOf(delimiter, from);
			addBounded(at < 0 ? text.substring(from) : text.substring(from, at), length, overlap, out);
			if (at < 0) {
				break;
			}
			from = at + delimiter.length();
		}
		if (out.list.isEmpty()) {
			addBounded(text, length, overlap, out);
		}
	}

	/** 按 Markdown 标题切块，过长块再定长二次切 */
	private static void byMarkdown(String text, int length, int overlap, Pieces out) {
		StringBuilder cur = new StringBuilder();
		int from = 0;
		while (from <= text.length()) {
			int at = text.indexOf('\n', from);
			String line = at < 0 ? text.substring(from) : text.substring(from, at);
			if (MD_HEADING.matcher(line).matches() && cur.length() > 0) {
				addBounded(cur.toString(), length, overlap, out);
				cur.setLength(0);
			}
			if (cur.length() > 0) {
				cur.append('\n');
			}
			cur.append(line);
			if (at < 0) {
				break;
			}
			from = at + 1;
		}
		if (cur.length() > 0) {
			addBounded(cur.toString(), length, overlap, out);
		}
		if (out.list.isEmpty()) {
			byLength(text, length, overlap, out);
		}
	}

	private static void addBounded(String block, int length, int overlap, Pieces out) {
		String piece = block.trim();
		if (piece.isEmpty()) {
			return;
		}
		if (piece.length() <= length) {
			out.add(piece);
		} else {
			byLength(piece, length, overlap, out);
		}
	}

	private static void byLength(String text, int length, int overlap, Pieces out) {
		int step = Math.max(1, length - overlap);
		for (int i = 0; i < text.length(); i += step) {
			int end = Math.min(text.length(), i + length);
			String piece = text.substring(i, end).trim();
			if (!piece.isEmpty()) {
				out.add(piece);
			}
			if (end >= text.length()) {
				break;
			}
		}
	}

	private static final class Pieces {

		private final List<String> list = new ArrayList<>();
		private final int max;

		Pieces(int max) {
			this.max = max;
		}

		void add(String piece) {
			if (list.size() >= max) {
				throw new TooManyPiecesException(max);
			}
			list.add(piece);
		}
	}
}
