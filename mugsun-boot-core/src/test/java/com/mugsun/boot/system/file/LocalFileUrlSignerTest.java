package com.mugsun.boot.system.file;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
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

	@Test
	void signUrlDerivesPathFromUrlItself() {
		LocalFileAccessProperties p = props(SECRET);
		LocalFileUrlSigner signer = new LocalFileUrlSigner(p);
		String url = signer.signUrl("http://127.0.0.1:8080/file/mugsun/private/a.pdf");
		String q = url.substring(url.indexOf('?') + 1);
		assertThat(signer.verify("mugsun/private/a.pdf", param(q, "e"), param(q, "s"))).isTrue();
		String behindContext = signer.signUrl("https://example.com/api/file/mugsun/private/b.pdf");
		String q2 = behindContext.substring(behindContext.indexOf('?') + 1);
		assertThat(signer.verify("mugsun/private/b.pdf", param(q2, "e"), param(q2, "s"))).isTrue();
		assertThatThrownBy(() -> signer.signUrl("https://example.com/other/x.pdf"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void prodWithLocalAccessRequiresSecret() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("prod");
		env.setProperty("dromara.x-file-storage.local-plus[0].enable-access", "true");
		assertThatThrownBy(() -> new LocalFileUrlSigner(props(""), env)).isInstanceOf(IllegalStateException.class);
		// prod 关闭直出（默认）或已配密钥：正常启动
		MockEnvironment off = new MockEnvironment();
		off.setActiveProfiles("prod");
		off.setProperty("dromara.x-file-storage.local-plus[0].enable-access", "false");
		new LocalFileUrlSigner(props(""), off);
		new LocalFileUrlSigner(props(SECRET), env);
		// 非 prod 留空：随机密钥
		MockEnvironment dev = new MockEnvironment();
		dev.setProperty("dromara.x-file-storage.local-plus[0].enable-access", "true");
		new LocalFileUrlSigner(props(""), dev);
	}

	@Test
	void servedFilesGetNosniffAndActiveTypesForcedToDownload() throws Exception {
		LocalFileAccessProperties p = props(SECRET);
		LocalFileUrlSigner signer = new LocalFileUrlSigner(p);
		LocalFileAccessFilter filter = new LocalFileAccessFilter(p, signer);
		String q = signer.signQuery("mugsun/private/x.SVG");
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/file/mugsun/private/x.SVG");
		req.setParameter("e", param(q, "e"));
		req.setParameter("s", param(q, "s"));
		MockHttpServletResponse resp = new MockHttpServletResponse();
		filter.doFilter(req, resp, new MockFilterChain());
		assertThat(resp.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(resp.getHeader("Content-Disposition")).isEqualTo("attachment");
		assertThat(resp.getHeader("Content-Security-Policy")).isEqualTo("sandbox");

		MockHttpServletResponse png = new MockHttpServletResponse();
		filter.doFilter(new MockHttpServletRequest("GET", "/file/mugsun/public/avatar/a.png"), png, new MockFilterChain());
		assertThat(png.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(png.getHeader("Content-Disposition")).isNull();
		assertThat(LocalFileAccessFilter.isActive("mugsun/public/a.html")).isTrue();
		assertThat(LocalFileAccessFilter.isActive("mugsun/public.html/a")).isFalse();
	}
}
