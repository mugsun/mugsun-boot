package com.mugsun.boot.common.i18n;

import com.mugsun.core.i18n.source.MessagePatterns;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 英文里带数量的句子按数量分单复数（不需要容器：用运行时填值的那套办法，填翻译文件里的句子） */
class CountWordingTest {

	private final Map<String, String> english = AbstractI18nBundleTest.read("mugsun-boot_en.properties"::equals);

	@Test
	void oneIsSingularAndTheRestArePlural() {
		String key = "已删除 {0} 个客户端。列表里不会再出现";
		assertThat(fill(key, 1)).isEqualTo("Deleted 1 client. It will no longer appear in the list.");
		assertThat(fill(key, 3)).isEqualTo("Deleted 3 clients. They will no longer appear in the list.");
		assertThat(fill(key, 0)).isEqualTo("Deleted 0 clients. They will no longer appear in the list.");
	}

	@Test
	void theNumberItselfIsShownAsItIs() {
		assertThat(fill("审计链完整，共验签 {0} 条记录", 12345))
			.isEqualTo("The audit chain is intact. 12345 records were verified.");
	}

	@Test
	void eachCountPicksItsOwnWording() {
		assertThat(fill("已保存 {0} 个菜单，有 {1} 个已删除已跳过", 5, 1))
			.isEqualTo("Saved 5 menus. 1 deleted menu was skipped.");
	}

	private String fill(String key, Object... values) {
		return MessagePatterns.compile(english.get(key), Locale.ENGLISH).format(values);
	}
}
