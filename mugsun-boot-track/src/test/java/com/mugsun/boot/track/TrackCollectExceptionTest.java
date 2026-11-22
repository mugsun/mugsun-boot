package com.mugsun.boot.track;

import com.mugsun.boot.common.i18n.EnglishRequest;
import com.mugsun.boot.common.i18n.StoredSentence;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 拒收的那句话：上报方当场看到的、日志里的、管理员过后在接入自检里看到的，各按各的语言 */
class TrackCollectExceptionTest {

	private static final String TOO_LARGE = "这段回放太大。单块内容最长 {0} 个字，请缩小后再上传";

	@Test
	void plainSentenceIsKeptAsItIs() {
		TrackCollectException e = new TrackCollectException(400, "应用不存在");

		assertThat(e.getStatus()).isEqualTo(400);
		assertThat(e.getReason()).isNull();
		assertThat(e.getMessage()).isEqualTo("应用不存在");
		assertThat(e.messageForCaller()).isEqualTo("应用不存在");
		assertThat(e.sample()).isEqualTo("应用不存在");
	}

	@Test
	void reasonGoesWithThePlainSentence() {
		TrackCollectException e = new TrackCollectException(400, "请求体须是一个 JSON 对象。数组或一段文字不行，请改完再发",
			TrackRejectReason.BAD_PAYLOAD);

		assertThat(e.getReason()).isEqualTo(TrackRejectReason.BAD_PAYLOAD);
		assertThat(e.getMessage()).startsWith("请求体须是一个 JSON 对象");
		assertThat(e.sample()).isEqualTo(e.getMessage());
	}

	@Test
	void valuesAreFilledIn() {
		TrackCollectException e = new TrackCollectException(413, TOO_LARGE, 2048);

		assertThat(e.getStatus()).isEqualTo(413);
		assertThat(e.getMessage()).isEqualTo("这段回放太大。单块内容最长 2048 个字，请缩小后再上传");
		assertThat(e.messageForCaller()).isEqualTo(e.getMessage());
		assertThat(StoredSentence.render(e.sample())).isEqualTo(e.getMessage());
	}

	/** 访客的浏览器是英文、管理员用中文：当场返回英文，日志和管理员过后看到的是中文 */
	@Test
	void callerAndAdministratorEachReadTheirOwnLanguage() {
		TrackCollectException withValue;
		TrackCollectException plain;
		try (EnglishRequest english = EnglishRequest.open()) {
			withValue = new TrackCollectException(413, TOO_LARGE, 2048);
			plain = new TrackCollectException(400, "应用不存在");

			assertThat(withValue.messageForCaller()).isEqualTo(
				"This replay chunk is too large. One chunk can be at most 2048 characters. Please make it smaller and upload again.");
			assertThat(plain.messageForCaller()).isEqualTo("App not found");
			assertThat(withValue.getMessage()).as("日志里的不跟访客的语言走").isEqualTo("这段回放太大。单块内容最长 2048 个字，请缩小后再上传");
			assertThat(StoredSentence.render(withValue.sample())).as("管理员也用英文时").isEqualTo(withValue.messageForCaller());
		}

		assertThat(StoredSentence.render(withValue.sample())).isEqualTo("这段回放太大。单块内容最长 2048 个字，请缩小后再上传");
		assertThat(StoredSentence.render(plain.sample())).isEqualTo("应用不存在");
	}
}
