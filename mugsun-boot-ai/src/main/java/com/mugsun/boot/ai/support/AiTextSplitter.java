package com.mugsun.boot.ai.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 知识库分段：对齐开源常见策略（定长 / 换行 / 符号 / Markdown 标题 / 段落）。
 * 不引入 Spring AI TokenTextSplitter 依赖链，行为可测、无外部模型。
 */
public final class AiTextSplitter {

	private static final Pattern MD_HEADING = Pattern.compile("(?m)^#{1,6}\\s+.+$");

	private AiTextSplitter() {
	}

	public static List<String> split(String text, int length, int overlap, String type, String symbol) {
		List<String> out = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return out;
		}
		String raw = text.replace("\r\n", "\n").replace('\r', '\n').trim();
		String mode = type == null || type.isBlank() ? "length" : type.trim().toLowerCase(Locale.ROOT);
		return switch (mode) {
			case "symbol" -> byDelimiter(raw, symbol == null || symbol.isBlank() ? "\n\n" : symbol);
			case "newline", "line" -> byDelimiter(raw, "\n");
			case "paragraph", "para" -> byDelimiter(raw, "\n\n");
			case "markdown", "md" -> byMarkdown(raw, Math.max(64, length), Math.max(0, overlap));
			default -> byLength(raw, Math.max(32, length), Math.max(0, overlap));
		};
	}

	private static List<String> byDelimiter(String text, String delimiter) {
		List<String> out = new ArrayList<>();
		String[] parts = text.split(Pattern.quote(delimiter));
		for (String part : parts) {
			if (part != null && !part.isBlank()) {
				out.add(part.trim());
			}
		}
		return out.isEmpty() ? List.of(text) : out;
	}

	/** 按 Markdown 标题切块，过长块再定长二次切 */
	private static List<String> byMarkdown(String text, int length, int overlap) {
		List<String> sections = new ArrayList<>();
		String[] lines = text.split("\n", -1);
		StringBuilder cur = new StringBuilder();
		for (String line : lines) {
			if (MD_HEADING.matcher(line).matches() && cur.length() > 0) {
				sections.add(cur.toString().trim());
				cur.setLength(0);
			}
			if (cur.length() > 0) {
				cur.append('\n');
			}
			cur.append(line);
		}
		if (cur.length() > 0) {
			sections.add(cur.toString().trim());
		}
		List<String> out = new ArrayList<>();
		for (String sec : sections) {
			if (sec.length() <= length) {
				if (!sec.isBlank()) {
					out.add(sec);
				}
			} else {
				out.addAll(byLength(sec, length, overlap));
			}
		}
		return out.isEmpty() ? byLength(text, length, overlap) : out;
	}

	private static List<String> byLength(String text, int length, int overlap) {
		List<String> out = new ArrayList<>();
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
		return out;
	}
}
