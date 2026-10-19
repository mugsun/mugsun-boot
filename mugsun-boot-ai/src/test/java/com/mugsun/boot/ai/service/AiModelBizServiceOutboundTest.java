package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.mapper.AiModelMapper;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.ai.support.AiOutboundProperties;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 模型保存时 baseUrl 先过出站校验：内网 / 元数据 / 非常用端口被拒；平台白名单主机放行。 */
class AiModelBizServiceOutboundTest {

	private AiModelBizService service(List<String> allow) {
		AiOutboundProperties props = new AiOutboundProperties();
		props.setAllowHosts(allow);
		return new AiModelBizService(Mockito.mock(AiModuleService.class), Mockito.mock(AiModelMapper.class),
			Mockito.mock(AiLlmClient.class), props);
	}

	private static AiModel model(String baseUrl) {
		AiModel m = new AiModel();
		m.setModelName("测试模型");
		m.setModelType("chat");
		m.setProvider("ollama");
		m.setModelCode("qwen2");
		m.setBaseUrl(baseUrl);
		return m;
	}

	private static boolean rejectedAsInternal(AiModelBizService s, String url) {
		try {
			s.submit(model(url));
			return false;
		} catch (ServiceException e) {
			return e.getMessage() != null && e.getMessage().contains("出站白名单");
		} catch (RuntimeException other) {
			return false;
		}
	}

	@Test
	void internalAddressesRejectedOnSave() {
		AiModelBizService s = service(List.of());
		assertTrue(rejectedAsInternal(s, "http://127.0.0.1:11434/v1"));
		assertTrue(rejectedAsInternal(s, "http://localhost:11434/v1"));
		assertTrue(rejectedAsInternal(s, "http://169.254.169.254/latest/meta-data/"));
		assertTrue(rejectedAsInternal(s, "http://100.100.100.200/latest"));
		assertTrue(rejectedAsInternal(s, "http://10.0.0.8/v1"));
		assertTrue(rejectedAsInternal(s, "http://[::ffff:127.0.0.1]/v1"));
		assertTrue(rejectedAsInternal(s, "http://8.8.8.8:6379/"));
	}

	@Test
	void platformAllowlistPermitsInternalModel() {
		AiModelBizService s = service(List.of("localhost"));
		assertFalse(rejectedAsInternal(s, "http://localhost:11434/v1"));
	}
}
