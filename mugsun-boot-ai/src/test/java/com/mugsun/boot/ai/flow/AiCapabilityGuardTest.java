package com.mugsun.boot.ai.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AiCapabilityGuardTest {

	private final ObjectMapper om = new ObjectMapper();

	@Test
	void allowsSafeTypes() throws Exception {
		String dsl = "{\"nodes\":["
			+ "{\"id\":\"s\",\"type\":\"start\"},"
			+ "{\"id\":\"t\",\"type\":\"text\",\"data\":{\"type\":\"text\"}},"
			+ "{\"id\":\"u\",\"type\":\"updateVar\"},"
			+ "{\"id\":\"e\",\"type\":\"end\"}]}";
		assertTrue(AiCapabilityGuard.deniedTypes(om, dsl).isEmpty());
	}

	@Test
	void deniesDbHttpMcpCodeAndSynonyms() throws Exception {
		String dsl = "{\"nodes\":["
			+ "{\"id\":\"a\",\"type\":\"db\"},"
			+ "{\"id\":\"b\",\"type\":\"HTTP\"},"
			+ "{\"id\":\"c\",\"data\":{\"type\":\"mcp\"}},"
			+ "{\"id\":\"d\",\"type\":\"javascript\"},"
			+ "{\"id\":\"e\",\"type\":\"sql\"}"
			+ "]}";
		Set<String> denied = AiCapabilityGuard.deniedTypes(om, dsl);
		assertTrue(denied.contains("db"));
		assertTrue(denied.contains("http"));
		assertTrue(denied.contains("mcp"));
		assertTrue(denied.contains("javascript"));
		assertTrue(denied.contains("sql"));
	}

	@Test
	void deniesNoticeAndSynonyms() throws Exception {
		String dsl = "{\"nodes\":["
			+ "{\"id\":\"n1\",\"type\":\"notice\"},"
			+ "{\"id\":\"n2\",\"type\":\"NOTIFY\"},"
			+ "{\"id\":\"n3\",\"data\":{\"type\":\"notification\"}},"
			+ "{\"id\":\"n4\",\"type\":\"channel\"},"
			+ "{\"id\":\"n5\",\"type\":\"webhook\"}"
			+ "]}";
		Set<String> denied = AiCapabilityGuard.deniedTypes(om, dsl);
		assertTrue(denied.contains("notice"), denied.toString());
		assertTrue(denied.contains("notify"), denied.toString());
		assertTrue(denied.contains("notification"), denied.toString());
		assertTrue(denied.contains("channel"), denied.toString());
		assertTrue(denied.contains("webhook"), denied.toString());
	}
}
