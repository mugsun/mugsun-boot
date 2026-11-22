package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.support.AiDocumentTextExtractor;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.common.i18n.EnglishRequest;
import com.mugsun.boot.common.i18n.StoredSentence;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.io.IOException;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 不经过统一返回体的两类话也跟着人的语言走：流式回答失败时推给页面的那句（在别的线程上说），
 * 资料处理失败时存进库里、过后才给人看的原因（写的人和看的人语言可能不一样）。
 */
class AiReaderLanguageTest {

	private static final String NO_ANSWER = "模型没有回应。请检查模型地址是否可访问，然后再发送";
	private static final String NO_ANSWER_EN =
		"The model did not respond. Please check whether the model URL is accessible, then send again.";

	@Test
	void aFailedAnswerIsToldInTheAskersLanguageEvenOnAnotherThread() {
		ServiceException callFailed = new AiLlmClient.CallFailedException("模型调用失败: 401 Unauthorized");
		ServiceException business = new ServiceException("请选择对话模型。向量模型不能用来聊天");

		try (EnglishRequest english = EnglishRequest.open()) {
			// 回调跑在别的线程上，那里的语言是默认的中文；提问人的语言是开始回答时记下来传进去的
			LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);

			assertEquals(NO_ANSWER_EN, AiChatBizService.streamFailure(callFailed, Locale.ENGLISH),
				"对方返回的原始报错不给提问的人看");
			assertEquals(NO_ANSWER_EN, AiChatBizService.streamFailure(new IllegalStateException(callFailed), Locale.ENGLISH));
			assertEquals(NO_ANSWER_EN, AiChatBizService.streamFailure(new IOException("Connection reset"), Locale.ENGLISH));
			assertEquals("Please select a chat model. Embedding models cannot be used for chat.",
				AiChatBizService.streamFailure(new IllegalStateException(business), Locale.ENGLISH),
				"自己写的业务提示照给，翻成提问人的语言");

			assertEquals(NO_ANSWER, AiChatBizService.streamFailure(callFailed, Locale.SIMPLIFIED_CHINESE));
			assertEquals(business.getMessage(), AiChatBizService.streamFailure(business, Locale.SIMPLIFIED_CHINESE));
		}
	}

	@Test
	void aStoredFailReasonIsReadInTheReadersLanguage() {
		String[] reason = new String[1];
		new AiDocumentTextExtractor().extractOrNull(new byte[0], "empty.pdf", "application/pdf", reason);
		String tooLarge = StoredSentence.pack("文件超过 {0}MB。请压缩或拆分后再上传", 20);

		assertEquals("文件是空的。请换一份有正文的资料再上传", StoredSentence.render(reason[0]));
		assertEquals("文件超过 20MB。请压缩或拆分后再上传", StoredSentence.render(tooLarge));
		try (EnglishRequest english = EnglishRequest.open()) {
			assertEquals("The file is empty. Please upload a file that has content.", StoredSentence.render(reason[0]));
			assertEquals("The file is larger than 20MB. Please compress or split it and upload again.",
				StoredSentence.render(tooLarge));
		}
	}
}
