package com.mugsun.boot.track;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 来源域名：referrer_domain 优先，否则从 referrer URL 取主机名 */
class TrackReferrerTest {

	@Test
	void derivesHostFromReferrerUrl() {
		assertThat(TrackReferrer.domainOf(null, "https://www.Google.com/search?q=x")).isEqualTo("www.google.com");
		assertThat(TrackReferrer.domainOf("", "http://news.example.cn:8080/a/b#c")).isEqualTo("news.example.cn");
		assertThat(TrackReferrer.domainOf(null, "https://example.com./")).isEqualTo("example.com");
	}

	@Test
	void explicitReferrerDomainWins() {
		assertThat(TrackReferrer.domainOf("partner.io", "https://other.com/")).isEqualTo("partner.io");
	}

	@Test
	void emptyOrNonHttpReferrerGivesNull() {
		assertThat(TrackReferrer.domainOf(null, null)).isNull();
		assertThat(TrackReferrer.domainOf(null, "")).isNull();
		assertThat(TrackReferrer.domainOf(null, "android-app://com.x/")).isNull();
		assertThat(TrackReferrer.domainOf(null, "not a url")).isNull();
		assertThat(TrackReferrer.domainOf(null, "/relative/path")).isNull();
	}
}
