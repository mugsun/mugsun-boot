package com.mugsun.boot.ai.support;

import com.mugsun.boot.ai.service.AiMcpBizService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiRetrievalSupportTest {

	@Test
	void lengthSplitWithOverlap() {
		String text = "a".repeat(80);
		List<String> parts = AiTextSplitter.split(text, 40, 8, "length", null);
		assertTrue(parts.size() >= 2);
		assertTrue(parts.get(0).length() <= 40);
	}

	@Test
	void markdownSplitsOnHeading() {
		String md = "# Title\nintro\n## Sec\nbody here";
		List<String> parts = AiTextSplitter.split(md, 200, 0, "markdown", null);
		assertEquals(2, parts.size());
		assertTrue(parts.get(0).startsWith("# Title"));
		assertTrue(parts.get(1).startsWith("## Sec"));
	}

	@Test
	void paragraphMode() {
		List<String> parts = AiTextSplitter.split("a\n\nb\n\nc", 100, 0, "paragraph", null);
		assertEquals(3, parts.size());
	}

	@Test
	void fusePrefersConsensus() {
		List<Map<String, Object>> v = List.of(
			Map.of("segmentId", 1L, "score", 0.9, "source", "vector", "content", "a"),
			Map.of("segmentId", 2L, "score", 0.8, "source", "vector", "content", "b"));
		List<Map<String, Object>> k = List.of(
			Map.of("segmentId", 2L, "score", 0.7, "source", "keyword", "content", "b"),
			Map.of("segmentId", 3L, "score", 0.6, "source", "keyword", "content", "c"));
		List<Map<String, Object>> fused = AiRrfFusion.fuse(3, v, k);
		assertEquals(3, fused.size());
		assertEquals(2L, ((Number) fused.get(0).get("segmentId")).longValue());
		assertTrue(fused.get(0).get("source").toString().contains("vector"));
	}

	@Test
	void lexicalBoostRaisesKeywordOverlap() {
		List<Map<String, Object>> hits = List.of(
			Map.of("segmentId", 1L, "score", 0.5, "source", "vector", "content", "unrelated text"),
			Map.of("segmentId", 2L, "score", 0.4, "source", "vector", "content", "mugsun knowledge base"));
		List<Map<String, Object>> out = AiRrfFusion.lexicalBoost(hits, "mugsun knowledge", 2);
		assertEquals(2L, ((Number) out.get(0).get("segmentId")).longValue());
	}

	@Test
	void resolveCallUrl() {
		assertEquals("http://x/call", AiMcpBizService.resolveCallUrl("http://x/sse"));
		assertEquals("http://x/api/call", AiMcpBizService.resolveCallUrl("http://x/api/call"));
		assertEquals("http://x/v1/call", AiMcpBizService.resolveCallUrl("http://x/v1/"));
	}
}
