package com.mugsun.boot.common.i18n;

import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 几句话连成一段（不需要容器，这时按中文连） */
class SentencesTest {

	@Test
	void joinsWithTheChineseSemicolonByDefault() {
		assertThat(Sentences.join(List.of("甲", "乙", "丙"))).isEqualTo("甲；乙；丙");
	}

	@Test
	void oneSentenceStaysAsItIs() {
		assertThat(Sentences.join(List.of("只有一句"))).isEqualTo("只有一句");
	}

	@Test
	void nothingToJoinGivesNull() {
		assertThat(Sentences.join(List.of())).isNull();
	}

	/** 英文的连法写在翻译文件里：每句自带句号，中间只空一格 */
	@Test
	void englishJoinsWithASpace() {
		String pattern = AbstractI18nBundleTest.read("mugsun-boot_en.properties"::equals).get("{0}；{1}");
		assertThat(pattern).isNotNull();
		assertThat(new MessageFormat(pattern).format(new Object[] { "First.", "Second." })).isEqualTo("First. Second.");
	}
}
