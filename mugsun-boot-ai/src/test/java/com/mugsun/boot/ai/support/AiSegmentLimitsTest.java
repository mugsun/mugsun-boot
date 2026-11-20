package com.mugsun.boot.ai.support;

import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BOOT-089：分段设置的取值范围，和切段时的段数、段长上限。 */
class AiSegmentLimitsTest {

	private static String rejected(String type, Integer length, Integer overlap, String symbol) {
		return assertThrows(ServiceException.class, () -> AiSegmentOptions.strict(type, length, overlap, symbol))
			.getMessage();
	}

	@Test
	@DisplayName("不填时用默认值：定长、长度 500、重叠 50")
	void defaults() {
		AiSegmentOptions o = AiSegmentOptions.strict(null, null, null, null);
		assertEquals("length", o.type());
		assertEquals(500, o.length());
		assertEquals(50, o.overlap());
		assertNull(o.symbol());
		assertEquals("paragraph", AiSegmentOptions.strict(" Paragraph ", 800, 80, "").type());
	}

	@Test
	@DisplayName("分段长度只接受 100 到 4000")
	void lengthRange() {
		assertEquals(100, AiSegmentOptions.strict("length", 100, 0, null).length());
		assertEquals(4000, AiSegmentOptions.strict("length", 4000, 0, null).length());
		for (int bad : new int[] {-1, 0, 1, 32, 99, 4001, Integer.MAX_VALUE}) {
			assertTrue(rejected("length", bad, 0, null).contains("100 到 4000"), "长度 " + bad);
		}
	}

	@Test
	@DisplayName("重叠最多是分段长度的一半，且不超过 500")
	void overlapRange() {
		assertEquals(50, AiSegmentOptions.strict("length", 100, 50, null).overlap());
		assertEquals(500, AiSegmentOptions.strict("length", 4000, 500, null).overlap());
		assertTrue(rejected("length", 100, 51, null).contains("0 到 50"));
		assertTrue(rejected("length", 100, 500, null).contains("0 到 50"));
		assertTrue(rejected("length", 500, 500, null).contains("0 到 250"));
		assertTrue(rejected("length", 4000, 501, null).contains("0 到 500"));
		assertTrue(rejected("length", 500, -1, null).contains("0 到 250"));
	}

	@Test
	@DisplayName("分段方式只接受白名单，分隔符最多 16 个字符")
	void typeAndSymbol() {
		for (String ok : List.of("length", "symbol", "newline", "line", "paragraph", "para", "markdown", "md", "MD")) {
			AiSegmentOptions.strict(ok, 500, 50, null);
		}
		assertTrue(rejected("regex", 500, 50, null).contains("分段方式"));
		assertEquals("-".repeat(16), AiSegmentOptions.strict("symbol", 500, 50, "-".repeat(16)).symbol());
		assertTrue(rejected("symbol", 500, 50, "-".repeat(17)).contains("16"));
	}

	@Test
	@DisplayName("重新分段：库里的旧值收进范围，不拒绝")
	void lenientClampsStoredValues() {
		AiSegmentOptions o = AiSegmentOptions.lenient("regex", 32, 500, "=".repeat(40));
		assertEquals("length", o.type());
		assertEquals(100, o.length());
		assertEquals(50, o.overlap());
		assertEquals(16, o.symbol().length());
		assertEquals(4000, AiSegmentOptions.lenient("md", 90000, -3, null).length());
		assertEquals(0, AiSegmentOptions.lenient("md", 90000, -3, null).overlap());
		assertEquals(500, AiSegmentOptions.lenient(null, 0, null, null).length());
	}

	@Test
	@DisplayName("段数到上限就停：刚好等于上限可以，多一段就抛错")
	void stopsAtPieceLimit() {
		String lines = "一行内容\n".repeat(100);
		assertEquals(100, AiTextSplitter.split(lines, 500, 50, "newline", null, 100).size());
		AiTextSplitter.TooManyPiecesException e = assertThrows(AiTextSplitter.TooManyPiecesException.class,
			() -> AiTextSplitter.split(lines, 500, 50, "newline", null, 99));
		assertEquals(99, e.limit());
		// 定长、段落、按符号、Markdown 标题都受同一个上限
		String text = "字".repeat(10_000);
		assertThrows(AiTextSplitter.TooManyPiecesException.class, () -> AiTextSplitter.split(text, 100, 50, "length", null, 50));
		assertThrows(AiTextSplitter.TooManyPiecesException.class, () -> AiTextSplitter.split(text, 100, 0, "paragraph", null, 50));
		assertThrows(AiTextSplitter.TooManyPiecesException.class,
			() -> AiTextSplitter.split("段;".repeat(200), 100, 0, "symbol", ";", 50));
		assertThrows(AiTextSplitter.TooManyPiecesException.class,
			() -> AiTextSplitter.split("# 标题\n正文\n".repeat(200), 100, 0, "markdown", null, 50));
		assertEquals(100, AiTextSplitter.split(text, 100, 0, "length", null, 0).size(), "上限填 0 表示不限");
	}

	@Test
	@DisplayName("按段落、按行、按符号切出的超长块再按分段长度切开，每段都不超过分段长度")
	void oversizeBlocksAreCutAgain() {
		String text = "短段落。\n\n" + "长".repeat(950) + "\n\n结尾段落。";
		for (String type : List.of("paragraph", "newline", "symbol")) {
			List<String> parts = AiTextSplitter.split(text, 300, 0, type, "\n\n");
			assertEquals("短段落。", parts.get(0), type);
			assertEquals("结尾段落。", parts.get(parts.size() - 1), type);
			assertEquals(6, parts.size(), type + "：950 字的段落按 300 切成 4 段");
			assertTrue(parts.stream().allMatch(p -> p.length() <= 300), type);
		}
	}

	@Test
	@DisplayName("按符号切：空块丢掉，正文里只有分隔符时返回整段正文")
	void delimiterEdges() {
		assertEquals(List.of("a", "b", "c"), AiTextSplitter.split("a\n\nb\n\n\n\nc\n\n", 100, 0, "paragraph", null));
		assertEquals(List.of("a", "b"), AiTextSplitter.split("a\r\n---\r\nb", 100, 0, "symbol", "\r\n---\r\n"));
		assertEquals(List.of("---"), AiTextSplitter.split("---", 100, 0, "symbol", "-"));
		assertTrue(AiTextSplitter.split("  \n ", 100, 0, "paragraph", null).isEmpty());
	}
}
