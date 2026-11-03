package com.mugsun.boot.ai.service;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiMessage;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.entity.AiSession;
import com.mugsun.boot.ai.mapper.AiBillMapper;
import com.mugsun.boot.ai.mapper.AiMessageMapper;
import com.mugsun.boot.ai.mapper.AiSessionMapper;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #71 regenerate：不重复写提问、仅最后一条助手、本人会话、SSE 与 stream 同形。
 */
class AiChatRegenerateTest {

	private static final long UID = 42L;
	private static final long SID = 100L;
	private static final long USER_MSG = 10L;
	private static final long ASST_MSG = 11L;
	private static final long OLD_ASST = 9L;
	private static final long MODEL_ID = 7L;

	private AiModuleService moduleService;
	private AiSessionMapper sessionMapper;
	private AiMessageMapper messageMapper;
	private AiBillMapper billMapper;
	private AiModelBizService modelBizService;
	private AiKnowledgeBizService knowledgeBizService;
	private AiLlmClient llmClient;
	private AiChatBizService service;
	private MockedStatic<StpUtil> stp;

	@BeforeEach
	void setUp() {
		moduleService = mock(AiModuleService.class);
		sessionMapper = mock(AiSessionMapper.class);
		messageMapper = mock(AiMessageMapper.class);
		billMapper = mock(AiBillMapper.class);
		modelBizService = mock(AiModelBizService.class);
		knowledgeBizService = mock(AiKnowledgeBizService.class);
		llmClient = mock(AiLlmClient.class);
		service = new AiChatBizService(moduleService, sessionMapper, messageMapper, billMapper,
			modelBizService, knowledgeBizService, llmClient, new ObjectMapper());
		stp = mockStatic(StpUtil.class);
		stp.when(StpUtil::isLogin).thenReturn(true);
		stp.when(StpUtil::getLoginIdAsLong).thenReturn(UID);
	}

	@AfterEach
	void tearDown() {
		stp.close();
	}

	@Test
	void rejectsWhenNotLastAssistant() {
		stubOwnSession();
		AiMessage target = assistant(OLD_ASST, AiConstants.MSG_STATUS_DONE, "旧回复");
		when(messageMapper.selectOneById(OLD_ASST)).thenReturn(target);
		when(messageMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(assistant(ASST_MSG, AiConstants.MSG_STATUS_DONE, "最新"));

		assertThatThrownBy(() -> service.regenerate(SID, OLD_ASST, null, null, null))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("最后一条");
		verify(messageMapper, never()).insert(any());
		verify(messageMapper, never()).update(any());
	}

	@Test
	void rejectsForeignSession() {
		AiSession foreign = session(999L);
		when(sessionMapper.selectOneById(SID)).thenReturn(foreign);

		assertThatThrownBy(() -> service.regenerate(SID, ASST_MSG, null, null, null))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("不存在");
		verify(messageMapper, never()).selectOneById(anyLong());
	}

	@Test
	void rejectsMessageFromOtherSession() {
		stubOwnSession();
		AiMessage other = assistant(ASST_MSG, AiConstants.MSG_STATUS_DONE, "x");
		other.setSessionId(SID + 1);
		when(messageMapper.selectOneById(ASST_MSG)).thenReturn(other);

		assertThatThrownBy(() -> service.regenerate(SID, ASST_MSG, null, null, null))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("不存在");
	}

	@Test
	void rejectsUserRoleMessage() {
		stubOwnSession();
		AiMessage user = new AiMessage();
		user.setId(ASST_MSG);
		user.setSessionId(SID);
		user.setRole(AiConstants.ROLE_USER);
		user.setContent("提问");
		when(messageMapper.selectOneById(ASST_MSG)).thenReturn(user);

		assertThatThrownBy(() -> service.regenerate(SID, ASST_MSG, null, null, null))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("不存在");
	}

	@Test
	void doesNotInsertUserAndOverwritesAssistantInPlace() throws Exception {
		stubOwnSession();
		stubActiveChatModel();
		AiMessage asst = assistant(ASST_MSG, AiConstants.MSG_STATUS_FAILED, "失败内容");
		AiMessage user = userMessage(USER_MSG, "原提问");
		when(messageMapper.selectOneById(ASST_MSG)).thenReturn(asst);
		// last assistant, then preceding user, then buildHistory list
		when(messageMapper.selectOneByQuery(any(QueryWrapper.class)))
			.thenReturn(asst, user);
		when(messageMapper.selectListByQuery(any(QueryWrapper.class)))
			.thenReturn(List.of(user, resetStreaming(asst)));

		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<List<Map<String, String>>> historyRef = new AtomicReference<>();
		doAnswer(inv -> {
			@SuppressWarnings("unchecked")
			List<Map<String, String>> history = inv.getArgument(1);
			historyRef.set(history);
			Consumer<String> onData = inv.getArgument(3);
			Runnable onComplete = inv.getArgument(5);
			when(llmClient.extractDeltaContent(any())).thenReturn("新", (String) null);
			when(llmClient.extractDeltaThink(any())).thenReturn(null);
			when(llmClient.extractUsage(any())).thenReturn(new int[]{1, 2, 3});
			onData.accept("{\"choices\":[{\"delta\":{\"content\":\"新\"}}]}");
			onComplete.run();
			done.countDown();
			return null;
		}).when(llmClient).stream(any(), any(), anyInt(), any(), any(), any());

		SseEmitter emitter = service.regenerate(SID, ASST_MSG, null, "req-regen-01", null);
		assertThat(emitter).isNotNull();
		assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

		verify(messageMapper, never()).insert(any());
		ArgumentCaptor<AiMessage> updated = ArgumentCaptor.forClass(AiMessage.class);
		verify(messageMapper, atLeastOnce()).update(updated.capture());
		// 同一 AiMessage 引用会被原地改写，Captor 只能看终态
		AiMessage lastUpdate = updated.getAllValues().get(updated.getAllValues().size() - 1);
		assertThat(lastUpdate.getId()).isEqualTo(ASST_MSG);
		assertThat(lastUpdate.getRequestId()).isEqualTo("req-regen-01");
		assertThat(lastUpdate.getStatus()).isEqualTo(AiConstants.MSG_STATUS_DONE);
		assertThat(lastUpdate.getContent()).isEqualTo("新");
		assertThat(updated.getAllValues().size()).isGreaterThanOrEqualTo(2);

		assertThat(historyRef.get()).isNotNull();
		assertThat(historyRef.get().stream().filter(m -> "user".equals(m.get("role"))).count()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void sseDoneCarriesSameShapeAsStream() throws Exception {
		stubOwnSession();
		stubActiveChatModel();
		AiMessage asst = assistant(ASST_MSG, AiConstants.MSG_STATUS_DONE, "旧");
		AiMessage user = userMessage(USER_MSG, "问");
		when(messageMapper.selectOneById(ASST_MSG)).thenReturn(asst);
		when(messageMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(asst, user);
		when(messageMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(user));

		CountDownLatch done = new CountDownLatch(1);
		doAnswer(inv -> {
			Consumer<String> onData = inv.getArgument(3);
			Runnable onComplete = inv.getArgument(5);
			when(llmClient.extractDeltaContent(any())).thenReturn("ok");
			when(llmClient.extractDeltaThink(any())).thenReturn("");
			when(llmClient.extractUsage(any())).thenReturn(new int[]{0, 1, 1});
			onData.accept("data");
			onComplete.run();
			done.countDown();
			return null;
		}).when(llmClient).stream(any(), any(), anyInt(), any(), any(), any());

		SseEmitter emitter = service.regenerate(SID, ASST_MSG, MODEL_ID, "reqabcd12", null);
		assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(emitter).isNotNull();
		verify(llmClient).stream(any(), any(), eq(2048), any(), any(), any());
		verify(billMapper).insert(any());
	}

	private void stubOwnSession() {
		when(sessionMapper.selectOneById(SID)).thenReturn(session(UID));
	}

	private void stubActiveChatModel() {
		AiModel model = new AiModel();
		model.setId(MODEL_ID);
		model.setModelName("demo");
		model.setModelType(AiConstants.MODEL_TYPE_CHAT);
		model.setActivateFlag(AiConstants.FLAG_YES);
		when(modelBizService.requireRaw(MODEL_ID)).thenReturn(model);
		when(modelBizService.requireDefaultChat()).thenReturn(model);
	}

	private static AiSession session(long userId) {
		AiSession s = new AiSession();
		s.setId(SID);
		s.setUserId(userId);
		s.setTenantId("000000");
		s.setModelId(MODEL_ID);
		s.setMessageCount(4);
		s.setTotalTokens(10);
		s.setSource(AiConstants.SOURCE_ASSISTANT);
		return s;
	}

	private static AiMessage assistant(long id, String status, String content) {
		AiMessage m = new AiMessage();
		m.setId(id);
		m.setSessionId(SID);
		m.setUserId(UID);
		m.setRole(AiConstants.ROLE_ASSISTANT);
		m.setStatus(status);
		m.setContent(content);
		m.setModelId(MODEL_ID);
		m.setRequestId("old-req-01");
		m.setTenantId("000000");
		return m;
	}

	private static AiMessage userMessage(long id, String content) {
		AiMessage m = new AiMessage();
		m.setId(id);
		m.setSessionId(SID);
		m.setUserId(UID);
		m.setRole(AiConstants.ROLE_USER);
		m.setStatus(AiConstants.MSG_STATUS_DONE);
		m.setContent(content);
		m.setTenantId("000000");
		return m;
	}

	private static AiMessage resetStreaming(AiMessage src) {
		AiMessage m = assistant(src.getId(), AiConstants.MSG_STATUS_STREAMING, "");
		m.setRequestId("req-regen-01");
		return m;
	}
}
