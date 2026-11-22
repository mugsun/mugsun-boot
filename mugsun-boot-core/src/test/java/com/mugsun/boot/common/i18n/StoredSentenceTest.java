package com.mugsun.boot.common.i18n;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 存起来过后给别人看的句子：存的是原文和值，谁看按谁的语言拼 */
class StoredSentenceTest {

	private static final String DELETED = "已删除 {0} 个客户端。列表里不会再出现";

	@Test
	void sentenceWithoutValuesIsStoredAsItIs() {
		String stored = StoredSentence.pack("验证码错误。请核对后再填");

		assertThat(stored).isEqualTo("验证码错误。请核对后再填");
		assertThat(StoredSentence.render(stored)).isEqualTo("验证码错误。请核对后再填");
	}

	@Test
	void valuesAreFilledInWhenItIsRead() {
		String stored = StoredSentence.pack(DELETED, 3);

		assertThat(stored).startsWith(DELETED).isNotEqualTo(DELETED);
		assertThat(StoredSentence.render(stored)).isEqualTo("已删除 3 个客户端。列表里不会再出现");
	}

	/** 写的人用中文、看的人用英文：看到的是英文，数量读出来还是数字，所以单复数也对 */
	@Test
	void readerGetsTheirOwnLanguage() {
		String one = StoredSentence.pack(DELETED, 1);
		String plain = StoredSentence.pack("验证码错误。请核对后再填");

		try (EnglishRequest english = EnglishRequest.open()) {
			assertThat(StoredSentence.render(one)).isEqualTo("Deleted 1 client. It will no longer appear in the list.");
			assertThat(StoredSentence.render(StoredSentence.pack(DELETED, 12345)))
				.isEqualTo("Deleted 12345 clients. They will no longer appear in the list.");
			assertThat(StoredSentence.render(plain)).isEqualTo("Incorrect verification code. Please check it and try again.");
		}

		assertThat(StoredSentence.render(one)).isEqualTo("已删除 1 个客户端。列表里不会再出现");
	}

	@Test
	void textValuesStayText() {
		String stored = StoredSentence.pack("已删除「{0}」，版本 {1}", "007", "1.0");

		assertThat(StoredSentence.render(stored)).isEqualTo("已删除「007」，版本 1.0");
	}

	@Test
	void nothingStaysNothing() {
		assertThat(StoredSentence.pack(null)).isNull();
		assertThat(StoredSentence.render(null)).isNull();
		assertThat(StoredSentence.render("")).isEmpty();
	}

	/** 存的地方有长度上限：存得下原样存；存不下的退回成一句拼好的话，不能把值截掉一半、读出来变成另一个数 */
	@Test
	void recordTooLongToStoreBecomesAPlainSentence() {
		String packed = StoredSentence.pack("最长 {0} 位", 100000);

		assertThat(StoredSentence.fit(packed, 200)).isEqualTo(packed);
		assertThat(StoredSentence.fit(packed, packed.length() - 1)).isEqualTo("最长 100000 位");
		assertThat(StoredSentence.fit(packed, 6)).isEqualTo("最长 10…");
		assertThat(StoredSentence.fit("没有变量的一句话", 5)).isEqualTo("没有变量…");
		assertThat(StoredSentence.fit(null, 5)).isNull();

		// 存下来是给以后所有人看的，不跟着这一次请求的语言走
		try (EnglishRequest english = EnglishRequest.open()) {
			assertThat(StoredSentence.fit(packed, packed.length() - 1)).isEqualTo("最长 100000 位");
		}
	}

	/** 存的时候被截断、占位只剩一半：照存下来的原文给出去，不能让整页跟着出错 */
	@Test
	void brokenRecordDoesNotBreakThePage() {
		String broken = StoredSentence.pack("最长 {0} 位", 64).replace("{0} 位", "{0");

		assertThat(StoredSentence.render(broken)).isEqualTo("最长 {0");
	}
}
