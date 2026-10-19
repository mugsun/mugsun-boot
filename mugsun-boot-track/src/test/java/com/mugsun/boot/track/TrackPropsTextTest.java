package com.mugsun.boot.track;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 与 SDK 同口径：截断不拆代理对、按字节截断、堆栈帧 URL 去 query */
class TrackPropsTextTest {

	private static boolean hasLoneSurrogate(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isHighSurrogate(c) && (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1)))) {
				return true;
			}
			if (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)))) {
				return true;
			}
		}
		return false;
	}

	@Test
	void truncateKeepsSurrogatePairs() {
		String s = "a" + "😀".repeat(3);
		assertThat(TrackPropsText.truncate(s, 2)).isEqualTo("a");
		assertThat(TrackPropsText.truncate(s, 3)).isEqualTo("a😀");
		assertThat(hasLoneSurrogate(TrackPropsText.truncate(s, 4))).isFalse();
	}

	@Test
	void truncateUtf8ByCodePoint() {
		assertThat(TrackPropsText.truncateUtf8("中文😀", 7)).isEqualTo("中文");
		assertThat(TrackPropsText.truncateUtf8("中文😀", 10)).isEqualTo("中文😀");
		assertThat(hasLoneSurrogate(TrackPropsText.truncateUtf8("😀😀", 5))).isFalse();
	}

	@Test
	void stripsQueryFromStackFrameUrls() {
		String stack = "TypeError: boom\n"
			+ "    at a (https://cdn.x.com/app.js?token=SECRET&v=1:10:5)\n"
			+ "    at https://cdn.x.com/b.js#frag:3:7\n"
			+ "f@https://x.com/a.js?sig=1:2:3\n"
			+ "    at d (https://cdn.x.com/plain.js:4:4)";
		String out = TrackPropsText.stripStackUrlQuery(stack);
		assertThat(out).doesNotContain("SECRET").doesNotContain("sig=")
			.contains("(https://cdn.x.com/app.js:10:5)")
			.contains("at https://cdn.x.com/b.js:3:7")
			.contains("f@https://x.com/a.js:2:3")
			.contains("(https://cdn.x.com/plain.js:4:4)");
	}
}
