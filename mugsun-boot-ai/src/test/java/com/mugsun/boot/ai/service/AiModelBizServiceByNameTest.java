package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.mapper.AiModelMapper;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.ai.support.AiOutboundProperties;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.CPI;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 按名称取已激活的模型：先按模型名称，再按模型标识；找不到时报错里带着要找的名称。 */
class AiModelBizServiceByNameTest {

	private final AiModuleService moduleService = Mockito.mock(AiModuleService.class);
	private final AiModelMapper mapper = Mockito.mock(AiModelMapper.class);
	private final AiModelBizService service = new AiModelBizService(moduleService, mapper,
		Mockito.mock(AiLlmClient.class), new AiOutboundProperties());

	private static AiModel model(String name) {
		AiModel m = new AiModel();
		m.setModelName(name);
		return m;
	}

	/** 每次查询带的条件值，按先后 */
	private List<Object[]> queries() {
		ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
		Mockito.verify(mapper, Mockito.atLeast(0)).selectOneByQuery(captor.capture());
		return captor.getAllValues().stream().map(CPI::getValueArray).toList();
	}

	@Test
	@DisplayName("先按模型名称找：找到就用，不再按标识找；名称两头的空格不算")
	void foundByName() {
		AiModel fast = model("快的那个");
		Mockito.when(mapper.selectOneByQuery(Mockito.any(QueryWrapper.class))).thenReturn(fast);

		assertThat(service.requireActiveByName("chat", " 快的那个 ")).isSameAs(fast);

		List<Object[]> queries = queries();
		assertThat(queries).hasSize(1);
		assertThat(queries.get(0)).startsWith("chat", AiConstants.FLAG_YES, "快的那个");
		Mockito.verify(moduleService).requireEnabled();
	}

	@Test
	@DisplayName("名称没找到再按模型标识找，类型和「已激活」两个条件不变")
	void fallsBackToModelCode() {
		AiModel byCode = model("通义向量");
		Mockito.when(mapper.selectOneByQuery(Mockito.any(QueryWrapper.class))).thenReturn(null, byCode);

		assertThat(service.requireActiveByName("embedding", "text-embedding-v3")).isSameAs(byCode);

		List<Object[]> queries = queries();
		assertThat(queries).hasSize(2);
		assertThat(queries.get(0)).startsWith("embedding", AiConstants.FLAG_YES, "text-embedding-v3");
		assertThat(queries.get(1)).startsWith("embedding", AiConstants.FLAG_YES, "text-embedding-v3");
	}

	@Test
	@DisplayName("两种都没找到：报错里带着要找的名称")
	void notFoundNamesTheModel() {
		assertThatThrownBy(() -> service.requireActiveByName("chat", "不存在的"))
			.isInstanceOf(ServiceException.class).hasMessageContaining("不存在的");
	}

	@Test
	@DisplayName("名称是空的：直接报错，不查库")
	void blankNameIsRejectedWithoutQuery() {
		assertThatThrownBy(() -> service.requireActiveByName("chat", "  ")).isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> service.requireActiveByName("chat", null)).isInstanceOf(ServiceException.class);
		Mockito.verifyNoInteractions(mapper);
	}

	@Test
	@DisplayName("AI 模块关着：照模块开关的报错，不查库")
	void moduleSwitchIsChecked() {
		Mockito.doThrow(new ServiceException("AI 模块未开启")).when(moduleService).requireEnabled();

		assertThatThrownBy(() -> service.requireActiveByName("chat", "快的那个")).hasMessage("AI 模块未开启");
		Mockito.verifyNoInteractions(mapper);
	}
}
