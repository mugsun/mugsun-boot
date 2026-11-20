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
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * BOOT-086：发给模型的历史取最近的消息，最后一条一定是本次提问。
 * 消息表用内存列表代替，查询按 SQL 里的条件、排序和条数取数，所以取数方向写反时测试会失败。
 */
class AiChatHistoryWindowTest {

	private static final long UID = 42L;
	private static final long SID = 100L;
	private static final long MODEL_ID = 7L;

	private final List<AiMessage> table = new ArrayList<>();
	private AiMessageMapper messageMapper;
	private AiLlmClient llmClient;
	private AiChatBizService service;
	private MockedStatic<StpUtil> stp;

	@BeforeEach
	void setUp() {
		AiSessionMapper sessionMapper = mock(AiSessionMapper.class);
		AiModelBizService modelBizService = mock(AiModelBizService.class);
		messageMapper = mock(AiMessageMapper.class);
		llmClient = mock(AiLlmClient.class);
		service = new AiChatBizService(mock(AiModuleService.class), sessionMapper, messageMapper, mock(AiBillMapper.class),
			modelBizService, mock(AiKnowledgeBizService.class), llmClient, new ObjectMapper());
		stp = mockStatic(StpUtil.class);
		stp.when(StpUtil::isLogin).thenReturn(true);
		stp.when(StpUtil::getLoginIdAsLong).thenReturn(UID);

		AiSession session = new AiSession();
		session.setId(SID);
		session.setUserId(UID);
		session.setTenantId("000000");
		session.setModelId(MODEL_ID);
		session.setMessageCount(0);
		session.setTotalTokens(0);
		session.setSource(AiConstants.SOURCE_ASSISTANT);
		when(sessionMapper.selectOneById(SID)).thenReturn(session);

		AiModel model = new AiModel();
		model.setId(MODEL_ID);
		model.setModelName("demo");
		model.setModelType(AiConstants.MODEL_TYPE_CHAT);
		model.setActivateFlag(AiConstants.FLAG_YES);
		when(modelBizService.requireRaw(MODEL_ID)).thenReturn(model);
		when(modelBizService.requireDefaultChat()).thenReturn(model);

		when(messageMapper.insert(any(AiMessage.class))).thenAnswer(inv -> {
			AiMessage m = inv.getArgument(0);
			m.setId(table.size() + 1L);
			table.add(m);
			return 1;
		});
		when(messageMapper.selectOneById(anyLong())).thenAnswer(inv -> {
			long id = inv.getArgument(0);
			return table.stream().filter(m -> m.getId() == id).findFirst().orElse(null);
		});
		when(messageMapper.selectListByQuery(any(QueryWrapper.class))).thenAnswer(inv -> select(inv.getArgument(0)));
		when(messageMapper.selectOneByQuery(any(QueryWrapper.class))).thenAnswer(inv -> {
			List<AiMessage> rows = select(inv.getArgument(0));
			return rows.isEmpty() ? null : rows.get(0);
		});
	}

	@AfterEach
	void tearDown() {
		stp.close();
	}

	@Test
	void streamSendsTheNewestMessagesAndEndsWithTheCurrentQuestion() throws Exception {
		seedRounds(25);

		List<Map<String, String>> history = sentHistory(() -> service.stream(SID, null, "第 26 问", "req-hist-0001", null));

		// 表里 50 条旧消息 + 本次提问：取最近 40 条，开头那条孤立的回答丢掉，剩 39 条
		assertThat(history).hasSize(AiChatBizService.HISTORY_LIMIT - 1);
		assertThat(history.get(history.size() - 1)).containsEntry("role", "user").containsEntry("content", "第 26 问");
		assertThat(history.get(0)).containsEntry("role", "user").containsEntry("content", "问 7");
		assertThat(contents(history)).contains("答 25").doesNotContain("问 1", "答 6");
	}

	@Test
	void regenerateEndsWithTheQuestionBeingAnswered() throws Exception {
		seedRounds(25);
		add(AiConstants.ROLE_USER, AiConstants.MSG_STATUS_DONE, "第 26 问");
		AiMessage failed = add(AiConstants.ROLE_ASSISTANT, AiConstants.MSG_STATUS_FAILED, "");

		List<Map<String, String>> history = sentHistory(() -> service.regenerate(SID, failed.getId(), null, "req-hist-0002", null));

		assertThat(history).hasSize(AiChatBizService.HISTORY_LIMIT - 1);
		assertThat(history.get(history.size() - 1)).containsEntry("role", "user").containsEntry("content", "第 26 问");
		assertThat(history.get(0)).containsEntry("role", "user").containsEntry("content", "问 7");
	}

	@Test
	void shortSessionKeepsEveryMessageInOrder() throws Exception {
		seedRounds(2);

		List<Map<String, String>> history = sentHistory(() -> service.stream(SID, null, "第 3 问", "req-hist-0003", null));

		assertThat(contents(history)).containsExactly("问 1", "答 1", "问 2", "答 2", "第 3 问");
	}

	@Test
	void failedAndCanceledRepliesAreLeftOut() throws Exception {
		add(AiConstants.ROLE_USER, AiConstants.MSG_STATUS_DONE, "问 1");
		add(AiConstants.ROLE_ASSISTANT, AiConstants.MSG_STATUS_FAILED, "失败的回答");
		add(AiConstants.ROLE_USER, AiConstants.MSG_STATUS_DONE, "问 2");
		add(AiConstants.ROLE_ASSISTANT, AiConstants.MSG_STATUS_CANCELED, "停掉的回答");

		List<Map<String, String>> history = sentHistory(() -> service.stream(SID, null, "问 3", "req-hist-0004", null));

		assertThat(contents(history)).containsExactly("问 1", "问 2", "问 3");
	}

	/** 调用一次对话，返回交给模型的消息列表。 */
	private List<Map<String, String>> sentHistory(Callable<SseEmitter> call) throws Exception {
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<List<Map<String, String>>> sent = new AtomicReference<>();
		when(llmClient.extractDeltaContent(any())).thenReturn("好");
		when(llmClient.extractDeltaThink(any())).thenReturn(null);
		when(llmClient.extractUsage(any())).thenReturn(new int[]{1, 1, 2});
		doAnswer(inv -> {
			List<Map<String, String>> history = inv.getArgument(1);
			sent.set(List.copyOf(history));
			Consumer<String> onData = inv.getArgument(3);
			Runnable onComplete = inv.getArgument(5);
			onData.accept("{}");
			onComplete.run();
			done.countDown();
			return null;
		}).when(llmClient).stream(any(), any(), anyInt(), any(), any(), any());

		assertThat(call.call()).isNotNull();
		assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
		return sent.get();
	}

	private static List<String> contents(List<Map<String, String>> history) {
		return history.stream().map(m -> m.get("content")).toList();
	}

	/** 往表里放 rounds 轮已完成的问答：问 1、答 1、问 2、答 2 …… */
	private void seedRounds(int rounds) {
		for (int i = 1; i <= rounds; i++) {
			add(AiConstants.ROLE_USER, AiConstants.MSG_STATUS_DONE, "问 " + i);
			add(AiConstants.ROLE_ASSISTANT, AiConstants.MSG_STATUS_DONE, "答 " + i);
		}
	}

	private AiMessage add(String role, String status, String content) {
		AiMessage m = new AiMessage();
		m.setId(table.size() + 1L);
		m.setSessionId(SID);
		m.setUserId(UID);
		m.setTenantId("000000");
		m.setRole(role);
		m.setStatus(status);
		m.setContent(content);
		m.setModelId(MODEL_ID);
		table.add(m);
		return m;
	}

	/** 按查询里的 id 上限、角色、状态、排序方向和条数，从内存列表里取数。 */
	private List<AiMessage> select(QueryWrapper query) {
		String sql = query.toSQL().replace("`", "").replace("\"", "");
		String before = first(sql, "\\bid\\s*<\\s*'?(\\d+)");
		String role = first(sql, "\\brole\\s*=\\s*'(\\w+)'");
		String statuses = first(sql, "(?i)\\bstatus\\s+IN\\s*\\(([^)]*)\\)");
		String limit = first(sql, "(?i)\\bLIMIT\\s+(\\d+)");
		List<AiMessage> rows = new ArrayList<>();
		for (AiMessage m : table) {
			if (before != null && m.getId() >= Long.parseLong(before)) {
				continue;
			}
			if (role != null && !role.equals(m.getRole())) {
				continue;
			}
			if (statuses != null && !statuses.contains("'" + m.getStatus() + "'")) {
				continue;
			}
			rows.add(m);
		}
		rows.sort(Comparator.comparing(AiMessage::getId));
		if (Pattern.compile("(?i)ORDER BY\\s+id\\s+DESC").matcher(sql).find()) {
			Collections.reverse(rows);
		}
		if (limit != null && rows.size() > Integer.parseInt(limit)) {
			return new ArrayList<>(rows.subList(0, Integer.parseInt(limit)));
		}
		return rows;
	}

	private static String first(String text, String regex) {
		Matcher m = Pattern.compile(regex).matcher(text);
		return m.find() ? m.group(1) : null;
	}
}
