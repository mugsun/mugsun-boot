package com.mugsun.boot.ai.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiBill;
import com.mugsun.boot.ai.entity.AiMessage;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.entity.AiSession;
import com.mugsun.boot.ai.mapper.AiBillMapper;
import com.mugsun.boot.ai.mapper.AiMessageMapper;
import com.mugsun.boot.ai.mapper.AiSessionMapper;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AiChatBizService {

	private final AiModuleService moduleService;
	private final AiSessionMapper sessionMapper;
	private final AiMessageMapper messageMapper;
	private final AiBillMapper billMapper;
	private final AiModelBizService modelBizService;
	private final AiKnowledgeBizService knowledgeBizService;
	private final AiLlmClient llmClient;
	private final ObjectMapper objectMapper;

	private final ConcurrentHashMap<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

	public AiChatBizService(AiModuleService moduleService, AiSessionMapper sessionMapper,
							AiMessageMapper messageMapper, AiBillMapper billMapper,
							AiModelBizService modelBizService, AiKnowledgeBizService knowledgeBizService,
							AiLlmClient llmClient, ObjectMapper objectMapper) {
		this.moduleService = moduleService;
		this.sessionMapper = sessionMapper;
		this.messageMapper = messageMapper;
		this.billMapper = billMapper;
		this.modelBizService = modelBizService;
		this.knowledgeBizService = knowledgeBizService;
		this.llmClient = llmClient;
		this.objectMapper = objectMapper;
	}

	private static final java.util.Set<String> SOURCES = java.util.Set.of(
		AiConstants.SOURCE_ASSISTANT, AiConstants.SOURCE_APP, AiConstants.SOURCE_DATASET,
		AiConstants.SOURCE_GENERATOR, AiConstants.SOURCE_OPENAPI);
	private static final String SOURCE_HINT = "来源请选择助手、应用、问数、生成或开放接口";

	public Page<AiSession> sessionPage(long pageNum, long pageSize, String source) {
		moduleService.requireEnabled();
		if (pageNum < 1) {
			throw new ServiceException("页码请从 1 开始");
		}
		if (pageSize < 1 || pageSize > 100) {
			throw new ServiceException("每页条数请在 1 到 100 之间");
		}
		QueryWrapper q = QueryWrapper.create()
			.eq("user_id", StpUtil.getLoginIdAsLong())
			.orderBy("last_time", false);
		if (source != null && !source.isBlank()) {
			String value = source.trim();
			if (!SOURCES.contains(value)) {
				throw new ServiceException(SOURCE_HINT);
			}
			q.eq("source", value);
		}
		return sessionMapper.paginate(pageNum, pageSize, q);
	}

	public AiSession createSession(String title, String source, Long bizId, Long modelId) {
		moduleService.requireEnabled();
		String name = title == null ? "" : title.trim();
		if (name.isEmpty()) {
			name = "新对话";
		}
		if (name.length() > 128) {
			throw new ServiceException("会话标题请控制在 128 个字以内");
		}
		String from = source == null || source.isBlank() ? AiConstants.SOURCE_ASSISTANT : source.trim();
		if (!SOURCES.contains(from)) {
			throw new ServiceException(SOURCE_HINT);
		}
		if (modelId != null) {
			requireChatModel(modelId);
		}
		AiSession s = new AiSession();
		s.sanitizeForInsert();
		s.setTenantId(TenantContext.current());
		s.setUserId(StpUtil.getLoginIdAsLong());
		s.setTitle(name);
		s.setSource(from);
		s.setBizId(bizId);
		s.setModelId(modelId);
		s.setMessageCount(0);
		s.setTotalTokens(0);
		s.setLastTime(LocalDateTime.now());
		sessionMapper.insert(s);
		return s;
	}

	public AiSession updateSession(Long id, String title, Long modelId, boolean modelPresent) {
		moduleService.requireEnabled();
		if (id == null) {
			throw new ServiceException("请先在列表里选择要修改的会话");
		}
		AiSession db = requireSession(id);
		if (title != null) {
			String name = title.trim();
			if (name.isEmpty()) {
				throw new ServiceException("请填写会话标题。用来在列表里区分");
			}
			if (name.length() > 128) {
				throw new ServiceException("会话标题请控制在 128 个字以内");
			}
			db.setTitle(name);
		}
		if (modelPresent) {
			if (modelId != null) {
				requireChatModel(modelId);
			}
			db.setModelId(modelId);
		}
		db.sanitizeForUpdate();
		sessionMapper.update(db);
		return db;
	}

	public AiSession removeSession(Long id) {
		moduleService.requireEnabled();
		if (id == null) {
			throw new ServiceException("请先选择要删除的会话");
		}
		AiSession row = requireSession(id);
		messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", id));
		sessionMapper.deleteById(id);
		return row;
	}

	public void clearMessages(Long sessionId) {
		moduleService.requireEnabled();
		if (sessionId == null) {
			throw new ServiceException("请先选择要清空的会话");
		}
		requireSession(sessionId);
		messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", sessionId));
		AiSession s = sessionMapper.selectOneById(sessionId);
		if (s != null) {
			s.setMessageCount(0);
			s.setTotalTokens(0);
			s.sanitizeForUpdate();
			sessionMapper.update(s);
		}
	}

	public Map<String, Object> exportSession(Long sessionId) {
		moduleService.requireEnabled();
		AiSession s = requireSession(sessionId);
		List<AiMessage> msgs = messages(sessionId);
		List<Map<String, Object>> lines = new ArrayList<>();
		for (AiMessage m : msgs) {
			lines.add(Map.of(
				"role", m.getRole() == null ? "" : m.getRole(),
				"content", m.getContent() == null ? "" : m.getContent(),
				"status", m.getStatus() == null ? "" : m.getStatus()));
		}
		return Map.of("session", s, "messages", lines);
	}

	public List<AiMessage> messages(Long sessionId) {
		moduleService.requireEnabled();
		requireSession(sessionId);
		return messageMapper.selectListByQuery(
			QueryWrapper.create().eq("session_id", sessionId).orderBy("id", true));
	}

	public SseEmitter stream(Long sessionId, Long modelId, String content, String requestId) {
		return stream(sessionId, modelId, content, requestId, null);
	}

	public SseEmitter stream(Long sessionId, Long modelId, String content, String requestId, Long knowledgeId) {
		moduleService.requireEnabled();
		if (content == null || content.isBlank()) {
			throw new ServiceException("请填写要发送的内容");
		}
		String text = content.trim();
		if (text.length() > 8000) {
			throw new ServiceException("内容请控制在 8000 字以内。可以分成几条再发送");
		}
		AiSession session = requireSession(sessionId);
		AiModel model = modelId != null ? modelBizService.requireRaw(modelId) : (
			session.getModelId() != null ? modelBizService.requireRaw(session.getModelId())
				: modelBizService.requireDefaultChat());
		if (!Integer.valueOf(AiConstants.FLAG_YES).equals(model.getActivateFlag())) {
			throw new ServiceException("「" + model.getModelName() + "」还没激活。请先在大模型配置里探测通过，再用来发送");
		}
		if (!AiConstants.MODEL_TYPE_CHAT.equals(model.getModelType())) {
			throw new ServiceException("请选择对话模型。向量模型不能用来聊天");
		}
		String rid = requestId == null || requestId.isBlank() ? IdUtil.fastSimpleUUID() : requestId.trim();
		if (!rid.matches("[A-Za-z0-9_-]{8,64}")) {
			throw new ServiceException("请求编号不对。请重新发送");
		}
		AtomicBoolean stop = new AtomicBoolean(false);
		stopFlags.put(rid, stop);

		// user message
		AiMessage userMsg = new AiMessage();
		userMsg.sanitizeForInsert();
		userMsg.setSessionId(sessionId);
		userMsg.setTenantId(TenantContext.current());
		userMsg.setUserId(StpUtil.getLoginIdAsLong());
		userMsg.setRole(AiConstants.ROLE_USER);
		userMsg.setContent(text);
		userMsg.setStatus(AiConstants.MSG_STATUS_DONE);
		userMsg.setRequestId(rid);
		messageMapper.insert(userMsg);

		AiMessage asst = new AiMessage();
		asst.sanitizeForInsert();
		asst.setSessionId(sessionId);
		asst.setTenantId(TenantContext.current());
		asst.setUserId(StpUtil.getLoginIdAsLong());
		asst.setRole(AiConstants.ROLE_ASSISTANT);
		asst.setModelId(model.getId());
		asst.setStatus(AiConstants.MSG_STATUS_STREAMING);
		asst.setRequestId(rid);
		asst.setContent("");
		asst.setThinkContent("");
		messageMapper.insert(asst);

		SseEmitter emitter = new SseEmitter(300_000L);
		StringBuilder contentBuf = new StringBuilder();
		StringBuilder thinkBuf = new StringBuilder();
		int[] usageHolder = new int[]{0, 0, 0};

		List<Map<String, String>> history = buildHistory(sessionId);
		List<Map<String, Object>> refs = List.of();
		if (knowledgeId != null) {
			try {
				refs = knowledgeBizService.hitTest(knowledgeId, text, 0);
				if (!refs.isEmpty()) {
					StringBuilder ctx = new StringBuilder("以下是相关知识库片段，请优先依据片段回答；若片段不足请明确说明。\n");
					int i = 1;
					for (Map<String, Object> r : refs) {
						Object c = r.get("content");
						if (c == null || c.toString().isBlank()) {
							continue;
						}
						ctx.append("[").append(i++).append("] ").append(c).append("\n\n");
					}
					history = new ArrayList<>(history);
					history.add(0, Map.of("role", "system", "content", ctx.toString()));
				}
			} catch (Exception e) {
				refs = List.of();
			}
		}
		final List<Map<String, Object>> finalRefs = refs;
		final List<Map<String, String>> finalHistory = history;

		// 异步/Reactor 线程无 Sa-Token 会话，须显式透传租户，否则落库 fail-closed
		String tenantId = session.getTenantId() != null ? session.getTenantId() : TenantContext.current();
		if (tenantId == null || tenantId.isBlank()) {
			tenantId = "000000";
		}
		final String tid = tenantId;
		final Long kbIdForSse = knowledgeId;
		AtomicBoolean failed = new AtomicBoolean(false);
		Thread.startVirtualThread(() -> TenantContext.execute(tid, () -> {
			try {
				if (!finalRefs.isEmpty()) {
					List<Map<String, Object>> slim = new ArrayList<>();
					for (Map<String, Object> r : finalRefs) {
						Map<String, Object> m = new java.util.HashMap<>();
						m.put("segmentId", r.get("segmentId"));
						m.put("score", r.get("score"));
						m.put("source", r.get("source"));
						Object c = r.get("content");
						String preview = c == null ? "" : c.toString();
						if (preview.length() > 240) {
							preview = preview.substring(0, 240) + "…";
						}
						m.put("content", preview);
						slim.add(m);
					}
					Map<String, Object> refsPayload = new java.util.HashMap<>();
					refsPayload.put("refs", slim);
					refsPayload.put("requestId", rid);
					if (kbIdForSse != null) {
						refsPayload.put("knowledgeId", kbIdForSse);
					}
					send(emitter, AiConstants.SSE_REFS, refsPayload);
				}
				llmClient.stream(model, finalHistory, 2048, data -> TenantContext.execute(tid, () -> {
					if (stop.get()) {
						return;
					}
					String think = llmClient.extractDeltaThink(data);
					if (think != null && !think.isEmpty()) {
						thinkBuf.append(think);
						send(emitter, AiConstants.SSE_THINK, Map.of("content", think, "requestId", rid));
					}
					String delta = llmClient.extractDeltaContent(data);
					if (delta != null && !delta.isEmpty()) {
						contentBuf.append(delta);
						send(emitter, AiConstants.SSE_CHUNK, Map.of("content", delta, "requestId", rid));
					}
					int[] u = llmClient.extractUsage(data);
					if (u != null) {
						usageHolder[0] = u[0];
						usageHolder[1] = u[1];
						usageHolder[2] = u[2];
					}
				}), err -> TenantContext.execute(tid, () -> failStream(failed, asst, contentBuf, thinkBuf, emitter, rid, stop, err)),
					() -> TenantContext.execute(tid, () -> {
					if (failed.get()) {
						return;
					}
					boolean canceled = stop.get();
					asst.setContent(contentBuf.toString());
					asst.setThinkContent(thinkBuf.toString());
					asst.setPromptTokens(usageHolder[0]);
					asst.setCompletionTokens(usageHolder[1]);
					asst.setTotalTokens(usageHolder[2] > 0 ? usageHolder[2] : usageHolder[0] + usageHolder[1]);
					asst.setStatus(canceled ? AiConstants.MSG_STATUS_CANCELED : AiConstants.MSG_STATUS_DONE);
					BigDecimal amount = calcAmount(model, asst.getPromptTokens(), asst.getCompletionTokens());
					asst.setAmount(amount);
					asst.sanitizeForUpdate();
					messageMapper.update(asst);
					saveBill(session, asst, model, amount);
					touchSession(session, asst.getTotalTokens());
					send(emitter, AiConstants.SSE_USAGE, Map.of(
						"promptTokens", asst.getPromptTokens() == null ? 0 : asst.getPromptTokens(),
						"completionTokens", asst.getCompletionTokens() == null ? 0 : asst.getCompletionTokens(),
						"totalTokens", asst.getTotalTokens() == null ? 0 : asst.getTotalTokens(),
						"amount", amount));
					send(emitter, AiConstants.SSE_DONE, Map.of("requestId", rid, "messageId", asst.getId()));
					emitter.complete();
					stopFlags.remove(rid);
				}));
			} catch (Exception e) {
				failStream(failed, asst, contentBuf, thinkBuf, emitter, rid, stop, e);
			}
		}));
		return emitter;
	}

	public String stop(String requestId) {
		moduleService.requireEnabled();
		if (requestId == null || requestId.isBlank()) {
			throw new ServiceException("请先发送问题。生成开始后才能停止");
		}
		String rid = requestId.trim();
		if (!rid.matches("[A-Za-z0-9_-]{8,64}")) {
			throw new ServiceException("请求编号不对。请重新发送，生成过程中再点停止");
		}
		AtomicBoolean flag = stopFlags.get(rid);
		if (flag != null) {
			flag.set(true);
			return "已停止。这条回答不会再继续生成";
		}
		AiMessage msg = findAssistantByRequest(rid);
		if (msg == null) {
			throw new ServiceException("没有正在生成的回答。请先发送问题，生成过程中再点停止");
		}
		requireSession(msg.getSessionId());
		if (AiConstants.MSG_STATUS_STREAMING.equals(msg.getStatus())) {
			msg.setStatus(AiConstants.MSG_STATUS_CANCELED);
			msg.sanitizeForUpdate();
			messageMapper.update(msg);
			return "已停止。这条回答不会再继续生成";
		}
		throw new ServiceException("这次生成已经结束。刷新会话后可以看到结果");
	}

	/**
	 * 客户端上报已收 requestId：回写 ack 状态；若流仍在跑则返回 streaming，否则回查落库消息。
	 */
	public Map<String, Object> receiveAck(String requestId) {
		moduleService.requireEnabled();
		String rid = requireRequestId(requestId, "请先发送问题。生成开始后才能确认收到",
			"请求编号不对。请重新发送后再确认");
		boolean streaming = stopFlags.containsKey(rid);
		AiMessage msg = findAssistantByRequest(rid);
		if (msg == null && !streaming) {
			throw new ServiceException("没有这条生成记录。请重新发送后再确认");
		}
		if (msg != null) {
			requireSession(msg.getSessionId());
		}
		Map<String, Object> out = new java.util.HashMap<>();
		out.put("requestId", rid);
		out.put("acked", true);
		out.put("streaming", streaming);
		if (msg != null) {
			out.put("messageId", msg.getId());
			out.put("status", msg.getStatus());
			out.put("sessionId", msg.getSessionId());
			out.put("contentLength", msg.getContent() == null ? 0 : msg.getContent().length());
		} else {
			out.put("status", AiConstants.MSG_STATUS_STREAMING);
		}
		return out;
	}

	/**
	 * 断线续传：按 requestId 重放已落库助手消息（整段 content 一次 chunk + done）。
	 * 仍在流式中时返回当前缓冲不可用提示。
	 */
	public SseEmitter replay(String requestId) {
		try {
			moduleService.requireEnabled();
		} catch (ServiceException e) {
			return errorEmitter(e.getMessage());
		}
		if (requestId == null || requestId.isBlank()) {
			return errorEmitter("请先发送问题。生成开始后才能续传");
		}
		String rid = requestId.trim();
		if (!rid.matches("[A-Za-z0-9_-]{8,64}")) {
			return errorEmitter("请求编号不对。请重新发送后再续传");
		}
		if (stopFlags.containsKey(rid)) {
			return errorEmitter("这条回答还在生成。请等它结束，或点停止后再续传");
		}
		AiMessage msg = findAssistantByRequest(rid);
		if (msg == null) {
			return errorEmitter("没有可续传的回答。请重新发送");
		}
		try {
			requireSession(msg.getSessionId());
		} catch (ServiceException e) {
			return errorEmitter(e.getMessage());
		}
		String content = msg.getContent() == null ? "" : msg.getContent();
		String think = msg.getThinkContent() == null ? "" : msg.getThinkContent();
		SseEmitter emitter = new SseEmitter(60_000L);
		if (!think.isEmpty()) {
			send(emitter, AiConstants.SSE_THINK, Map.of("content", think, "requestId", rid, "replay", true));
		}
		if (!content.isEmpty()) {
			send(emitter, AiConstants.SSE_CHUNK, Map.of("content", content, "requestId", rid, "replay", true));
		}
		String done = content.isEmpty() && think.isEmpty()
			? "这段回答还没有内容。请重新发送"
			: "已取回这段回答。内容已经补在这条消息上";
		send(emitter, AiConstants.SSE_DONE, Map.of(
			"requestId", rid,
			"messageId", msg.getId(),
			"replay", true,
			"message", done,
			"status", msg.getStatus() == null ? "" : msg.getStatus()));
		emitter.complete();
		return emitter;
	}

	private SseEmitter errorEmitter(String message) {
		SseEmitter emitter = new SseEmitter(60_000L);
		send(emitter, AiConstants.SSE_ERROR, Map.of("message", message));
		emitter.complete();
		return emitter;
	}

	private static String requireRequestId(String requestId, String empty, String bad) {
		if (requestId == null || requestId.isBlank()) {
			throw new ServiceException(empty);
		}
		String rid = requestId.trim();
		if (!rid.matches("[A-Za-z0-9_-]{8,64}")) {
			throw new ServiceException(bad);
		}
		return rid;
	}

	private AiMessage findAssistantByRequest(String requestId) {
		return messageMapper.selectOneByQuery(QueryWrapper.create()
			.eq("request_id", requestId)
			.eq("role", AiConstants.ROLE_ASSISTANT)
			.orderBy("id", false)
			.limit(1));
	}

	public Page<AiSession> conversationPage(long pageNum, long pageSize, String keyword) {
		moduleService.requireEnabled();
		QueryWrapper query = QueryWrapper.create().orderBy("last_time", false);
		if (keyword != null && !keyword.isBlank()) {
			query.like("title", keyword.trim());
		}
		return sessionMapper.paginate(pageNum, pageSize, query);
	}

	public Page<AiBill> billPage(long pageNum, long pageSize) {
		moduleService.requireEnabled();
		return billMapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("call_time", false));
	}

	public List<Map<String, Object>> billTrend(int days) {
		moduleService.requireEnabled();
		// 简化：最近账单按日聚合在内存
		LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, Math.min(days, 90)));
		List<AiBill> bills = billMapper.selectListByQuery(
			QueryWrapper.create().ge("call_time", since).orderBy("call_time", true));
		java.util.LinkedHashMap<String, long[]> agg = new java.util.LinkedHashMap<>();
		for (AiBill b : bills) {
			String day = b.getCallTime() == null ? "unknown" : b.getCallTime().toLocalDate().toString();
			long[] a = agg.computeIfAbsent(day, k -> new long[]{0, 0});
			a[0] += b.getTotalTokens() == null ? 0 : b.getTotalTokens();
			a[1] += 1;
		}
		List<Map<String, Object>> out = new ArrayList<>();
		agg.forEach((d, a) -> out.add(Map.of("day", d, "tokens", a[0], "calls", a[1])));
		return out;
	}

	private List<Map<String, String>> buildHistory(Long sessionId) {
		List<AiMessage> msgs = messageMapper.selectListByQuery(
			QueryWrapper.create().eq("session_id", sessionId)
				.in("status", List.of(AiConstants.MSG_STATUS_DONE, AiConstants.MSG_STATUS_STREAMING))
				.orderBy("id", true).limit(40));
		List<Map<String, String>> history = new ArrayList<>();
		for (AiMessage m : msgs) {
			if (AiConstants.MSG_STATUS_STREAMING.equals(m.getStatus()) && (m.getContent() == null || m.getContent().isEmpty())) {
				continue;
			}
			history.add(Map.of("role", m.getRole(), "content", m.getContent() == null ? "" : m.getContent()));
		}
		return history;
	}

	private void touchSession(AiSession session, Integer tokens) {
		session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 2);
		session.setTotalTokens((session.getTotalTokens() == null ? 0 : session.getTotalTokens())
			+ (tokens == null ? 0 : tokens));
		session.setLastTime(LocalDateTime.now());
		session.sanitizeForUpdate();
		sessionMapper.update(session);
	}

	private void saveBill(AiSession session, AiMessage msg, AiModel model, BigDecimal amount) {
		AiBill bill = new AiBill();
		bill.sanitizeForInsert();
		bill.setTenantId(session.getTenantId());
		bill.setMessageId(msg.getId());
		bill.setSessionId(session.getId());
		bill.setUserId(session.getUserId());
		bill.setModelId(model.getId());
		bill.setModelName(model.getModelName());
		bill.setPromptTokens(msg.getPromptTokens());
		bill.setCompletionTokens(msg.getCompletionTokens());
		bill.setTotalTokens(msg.getTotalTokens());
		bill.setAmount(amount);
		bill.setBizType(session.getSource());
		bill.setCallTime(LocalDateTime.now());
		billMapper.insert(bill);
	}

	private BigDecimal calcAmount(AiModel model, Integer prompt, Integer completion) {
		BigDecimal pi = model.getPriceInput() == null ? BigDecimal.ZERO : model.getPriceInput();
		BigDecimal po = model.getPriceOutput() == null ? BigDecimal.ZERO : model.getPriceOutput();
		BigDecimal p = BigDecimal.valueOf(prompt == null ? 0 : prompt).multiply(pi)
			.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
		BigDecimal c = BigDecimal.valueOf(completion == null ? 0 : completion).multiply(po)
			.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
		return p.add(c);
	}

	private void failStream(AtomicBoolean failed, AiMessage asst, StringBuilder contentBuf, StringBuilder thinkBuf,
							SseEmitter emitter, String rid, AtomicBoolean stop, Throwable err) {
		if (!failed.compareAndSet(false, true)) {
			return;
		}
		asst.setStatus(stop.get() ? AiConstants.MSG_STATUS_CANCELED : AiConstants.MSG_STATUS_FAILED);
		String failure = streamFailure(err);
		asst.setContent(contentBuf.length() == 0 ? failure : contentBuf.toString());
		asst.setThinkContent(thinkBuf.toString());
		asst.sanitizeForUpdate();
		messageMapper.update(asst);
		send(emitter, AiConstants.SSE_ERROR, Map.of("message", streamFailure(err), "requestId", rid));
		emitter.complete();
		stopFlags.remove(rid);
	}

	private static String streamFailure(Throwable err) {
		Throwable cur = err;
		while (cur != null) {
			if (cur instanceof ServiceException) {
				String message = cur.getMessage();
				if (message != null && !message.isBlank() && !message.contains("Exception")
					&& !message.startsWith("模型调用失败")) {
					return message;
				}
			}
			cur = cur.getCause();
		}
		return "模型没有回应。请检查模型地址是否可访问，然后再发送";
	}

	private void send(SseEmitter emitter, String event, Object data) {
		try {
			emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
		} catch (IOException ignored) {
		}
	}

	private void requireChatModel(Long modelId) {
		AiModel model = modelBizService.requireRaw(modelId);
		if (!AiConstants.MODEL_TYPE_CHAT.equals(model.getModelType())) {
			throw new ServiceException("请选择对话模型。向量模型不能用来聊天");
		}
	}

	private AiSession requireSession(Long id) {
		AiSession s = sessionMapper.selectOneById(id);
		if (s == null) {
			throw new ServiceException(AiConstants.MSG_SESSION_MISSING);
		}
		// 同租户内仍须本人会话，防猜 id 串读/续聊（平台超管豁免）
		if (!TenantContext.isPlatformSuperAdmin()) {
			long uid = StpUtil.getLoginIdAsLong();
			if (s.getUserId() == null || !s.getUserId().equals(uid)) {
				throw new ServiceException(AiConstants.MSG_SESSION_MISSING);
			}
		}
		return s;
	}
}
