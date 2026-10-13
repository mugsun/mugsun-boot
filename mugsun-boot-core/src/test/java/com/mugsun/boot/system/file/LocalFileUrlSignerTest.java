package com.mugsun.boot.system.file;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileUrlSignerTest {

	private static final String SECRET = "0123456789abcdef0123456789abcdef";

	private LocalFileAccessProperties props(String secret) {
		LocalFileAccessProperties p = new LocalFileAccessProperties();
		p.setSignSecret(secret);
		p.setExpireSeconds(60);
		return p;
	}

	private static String param(String query, String name) {
		for (String kv : query.split("&")) {
			if (kv.startsWith(name + "=")) {
				return kv.substring(name.length() + 1);
			}
		}
		return null;
	}

	@Test
	void signedQueryVerifiesAndBindsPath() {
		LocalFileUrlSigner signer = new LocalFileUrlSigner(props(SECRET));
		String q = signer.signQuery("mugsun/private/a.pdf");
		assertThat(signer.verify("mugsun/private/a.pdf", param(q, "e"), param(q, "s"))).isTrue();
		assertThat(signer.verify("mugsun/private/b.pdf", param(q, "e"), param(q, "s"))).isFalse();
		assertThat(signer.verify("mugsun/private/a.pdf", param(q, "e") + "0", param(q, "s"))).isFalse();
		assertThat(signer.verify("mugsun/private/a.pdf", param(q, "e"), param(q, "s") + "x")).isFalse();
		assertThat(signer.verify("mugsun/private/a.pdf", "abc", param(q, "s"))).isFalse();
		assertThat(signer.verify("mugsun/private/a.pdf", null, null)).isFalse();
	}

	@Test
	void expiredLinkRejected() {
		LocalFileUrlSigner signer = new LocalFileUrlSigner(props(SECRET));
		Instant now = Instant.parse("2026-10-13T00:00:00Z");
		signer.setClock(Clock.fixed(now, ZoneOffset.UTC));
		String q = signer.signQuery("mugsun/private/a.pdf");
		signer.setClock(Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC));
		assertThat(signer.verify("mugsun/private/a.pdf", param(q, "e"), param(q, "s"))).isFalse();
	}

	@Test
	void differentSecretRejected() {
		String q = new LocalFileUrlSigner(props(SECRET)).signQuery("p");
		LocalFileUrlSigner other = new LocalFileUrlSigner(props(SECRET.replace('0', 'z')));
		assertThat(other.verify("p", param(q, "e"), param(q, "s"))).isFalse();
	}

	@Test
	void shortSecretFailsFast() {
		assertThatThrownBy(() -> new LocalFileUrlSigner(props("short"))).isInstanceOf(IllegalStateException.class);
	}

	private int status(LocalFileAccessFilter filter, String uri, String query) throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
		if (query != null) {
			req.setQueryString(query);
			req.setParameter("e", param(query, "e"));
			req.setParameter("s", param(query, "s"));
		}
		MockHttpServletResponse resp = new MockHttpServletResponse();
		FilterChain chain = new MockFilterChain();
		filter.doFilter(req, resp, chain);
		return resp.getStatus();
	}

	@Test
	void filterAllowsPublicAndSignedOnly() throws Exception {
		LocalFileAccessProperties p = props(SECRET);
		LocalFileUrlSigner signer = new LocalFileUrlSigner(p);
		LocalFileAccessFilter filter = new LocalFileAccessFilter(p, signer);
		assertThat(status(filter, "/file/mugsun/public/avatar/x.png", null)).isEqualTo(200);
		assertThat(status(filter, "/file/mugsun/private/x.pdf", null)).isEqualTo(404);
		assertThat(status(filter, "/file/mugsun/private/x.pdf", signer.signQuery("mugsun/private/x.pdf"))).isEqualTo(200);
		// 签名不能挪用到别的对象
		assertThat(status(filter, "/file/mugsun/private/y.pdf", signer.signQuery("mugsun/private/x.pdf"))).isEqualTo(404);
		// 内部对象（埋点回放等）不在公开前缀，匿名拒绝
		assertThat(status(filter, "/file/mugsun/track/replay/1.json", null)).isEqualTo(404);
		// 借公开前缀穿越、编码、路径参数、双斜杠一律拒绝
		assertThat(status(filter, "/file/mugsun/public/../private/x.pdf", null)).isEqualTo(404);
		assertThat(status(filter, "/file/mugsun/public/%2e%2e/private/x.pdf", null)).isEqualTo(404);
		assertThat(status(filter, "/file/mugsun/public;x/../private/x.pdf", null)).isEqualTo(404);
		assertThat(status(filter, "/file//mugsun/private/x.pdf", null)).isEqualTo(404);
		assertThat(status(filter, "/file/", null)).isEqualTo(404);
		// 非 /file 路径不受影响
		assertThat(status(filter, "/system/file/page", null)).isEqualTo(200);
	}
}
