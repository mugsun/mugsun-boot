package com.mugsun.boot.security.csp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CspReportSanitizerTest {

	private final ObjectMapper om = new ObjectMapper();

	private List<Map<String, Object>> parse(String json) {
		return CspReportSanitizer.sanitize(om, json.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void legacyReportStripsQueryEverywhere() {
		List<Map<String, Object>> r = parse("""
			{"csp-report":{"document-uri":"https://demo.example.com/system/user?token=abc#frag",
			"blocked-uri":"wss://demo.example.com/api/ws/message?token=SECRET-TOKEN",
			"violated-directive":"connect-src 'self'","effective-directive":"connect-src",
			"source-file":"https://demo.example.com/assets/index.js?v=1","line-number":12,
			"script-sample":"alert(document.cookie)","disposition":"enforce"}}""");
		assertThat(r).hasSize(1);
		Map<String, Object> m = r.get(0);
		assertThat(m.get("document")).isEqualTo("https://demo.example.com/system/user");
		assertThat(m.get("blocked")).isEqualTo("wss://demo.example.com/api/ws/message");
		assertThat(m.get("source")).isEqualTo("https://demo.example.com/assets/index.js");
		assertThat(m.get("directive")).isEqualTo("connect-src");
		assertThat(m.get("line")).isEqualTo(12);
		assertThat(m.toString()).doesNotContain("SECRET-TOKEN", "token=", "cookie", "frag");
	}

	@Test
	void reportingApiArrayAndKeywords() {
		List<Map<String, Object>> r = parse("""
			[{"type":"csp-violation","url":"https://demo.example.com/a?x=1",
			  "body":{"documentURL":"https://demo.example.com/a?x=1","blockedURL":"inline",
			  "effectiveDirective":"script-src-elem","disposition":"report"}},
			 {"type":"deprecation","body":{}},
			 {"type":"csp-violation","body":{"documentURL":"https://user:pw@demo.example.com:8443/b?t=2",
			  "blockedURL":"data:image/png;base64,AAAA"}}]""");
		assertThat(r).hasSize(2);
		assertThat(r.get(0).get("document")).isEqualTo("https://demo.example.com/a");
		assertThat(r.get(0).get("blocked")).isEqualTo("inline");
		assertThat(r.get(1).get("document")).isEqualTo("https://demo.example.com:8443/b");
		assertThat(r.get(1).get("blocked")).isEqualTo("data");
	}

	@Test
	void garbageAndOversizedFieldsAreSafe() {
		assertThat(parse("not json")).isEmpty();
		assertThat(parse("{}")).isEmpty();
		String longPath = "https://demo.example.com/" + "a".repeat(1000) + "?token=x";
		Map<String, Object> m = parse("{\"csp-report\":{\"document-uri\":\"" + longPath + "\"}}").get(0);
		assertThat((String) m.get("document")).hasSize(CspReportSanitizer.MAX_FIELD).doesNotContain("token");
		assertThat(CspReportSanitizer.stripUrl("/relative/path?token=1#x")).isEqualTo("/relative/path");
	}
}
