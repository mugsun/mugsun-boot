package com.mugsun.boot.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiChannelBind;
import com.mugsun.boot.ai.mapper.AiChannelBindMapper;
import com.mugsun.boot.ai.support.AiOutboundProperties;
import com.mugsun.boot.ai.support.OutboundWebClientFactory;
import com.mugsun.boot.common.i18n.EnglishRequest;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 渠道发送：企微、钉钉、飞书各按自己的文本消息格式发，成败看平台回复里的结果码；地址只以打码后的样子给人看。
 * 这里的机器人接口是本机起的替身：消息格式和成功回复按各平台公开文档写；key 不对时的回复，照 2026-11-20 用一个不存在的 key
 * 向三家真接口问到的样子写（都是 HTTP 200，错误在回复内容里；里面的 IP 换成了文档专用的保留地址）。
 */
class AiChannelSendTest {

	private static final String GOOD = "good-7f3a91c2d4e5";
	private static final String BAD = "bad-00000000ffff";
	private static final String ECHO = "echo-5d2c88aa01ff";

	private final ObjectMapper om = new ObjectMapper();
	private final List<String> paths = new CopyOnWriteArrayList<>();
	private final List<String> bodies = new CopyOnWriteArrayList<>();
	private HttpServer server;
	private String base;
	private AiChannelBindMapper mapper;
	private AiChannelBizService service;

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/cgi-bin/webhook/send", ex -> {
			JsonNode body = capture(ex);
			if (!("key=" + GOOD).equals(ex.getRequestURI().getQuery())) {
				reply(ex, 200, "{\"errcode\":93000,\"errmsg\":\"invalid webhook url, hint: [1795166163496741624646818], "
					+ "from ip: 203.0.113.9, more info at https://open.work.weixin.qq.com/devtool/query?e=93000\"}");
			} else if (!"text".equals(body.path("msgtype").asText()) || body.path("text").path("content").asText().isEmpty()) {
				reply(ex, 200, "{\"errcode\":40008,\"errmsg\":\"invalid message type\"}");
			} else {
				reply(ex, 200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
			}
		});
		server.createContext("/robot/send", ex -> {
			JsonNode body = capture(ex);
			String query = String.valueOf(ex.getRequestURI().getQuery());
			if (query.equals("access_token=" + ECHO)) {
				// 替身故意把请求里的参数原样说回来，用来看说明里的 key 会不会被盖住；真平台问到的回复里不带 key
				reply(ex, 200, "{\"errcode\":310000,\"errmsg\":\"keywords not in content,\\n more: /robot/send?" + query + "\"}");
			} else if (query.equals("access_token=str-" + GOOD)) {
				reply(ex, 200, "{\"errcode\":\"0\",\"errmsg\":\"ok\"}");
			} else if (!query.equals("access_token=" + GOOD)) {
				reply(ex, 200, "{\"errcode\":300005,\"errmsg\":\"token is not exist\"}");
			} else if (!"text".equals(body.path("msgtype").asText()) || body.path("text").path("content").asText().isEmpty()) {
				reply(ex, 200, "{\"errcode\":40035,\"errmsg\":\"缺少参数 json\"}");
			} else {
				reply(ex, 200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
			}
		});
		server.createContext("/open-apis/bot/v2/hook/", ex -> {
			JsonNode body = capture(ex);
			String token = ex.getRequestURI().getPath().substring("/open-apis/bot/v2/hook/".length());
			if (token.equals("old-" + GOOD)) {
				reply(ex, 200, "{\"Extra\":null,\"StatusCode\":0,\"StatusMessage\":\"success\"}");
			} else if (token.equals("strict-" + GOOD)) {
				reply(ex, 400, "{\"code\":9499,\"msg\":\"Bad Request\",\"data\":{}}");
			} else if (!token.equals(GOOD)) {
				reply(ex, 200, "{\"code\":19001,\"data\":{},\"msg\":\"param invalid: incoming webhook access token invalid\"}");
			} else if (!"text".equals(body.path("msg_type").asText()) || body.path("content").path("text").asText().isEmpty()) {
				reply(ex, 200, "{\"code\":9499,\"msg\":\"Bad Request\",\"data\":{}}");
			} else {
				reply(ex, 200, "{\"StatusCode\":0,\"StatusMessage\":\"success\",\"code\":0,\"data\":{},\"msg\":\"success\"}");
			}
		});
		server.createContext("/hook", ex -> {
			capture(ex);
			reply(ex, 200, "received");
		});
		server.createContext("/down", ex -> {
			capture(ex);
			reply(ex, 500, "<html>Internal Server Error</html>");
		});
		server.createContext("/page", ex -> {
			capture(ex);
			reply(ex, 200, "<html>welcome</html>");
		});
		server.createContext("/huge", ex -> {
			capture(ex);
			reply(ex, 200, "x".repeat(300_000));
		});
		server.createContext("/moved", ex -> {
			capture(ex);
			ex.getResponseHeaders().add("Location", base + "/hook");
			ex.sendResponseHeaders(302, -1);
			ex.close();
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();

		AiOutboundProperties outbound = new AiOutboundProperties();
		outbound.setAllowHosts(List.of("127.0.0.1"));
		mapper = mock(AiChannelBindMapper.class);
		service = new AiChannelBizService(mock(AiModuleService.class), mapper, outbound,
			new OutboundWebClientFactory(WebClient.builder(), outbound));
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private JsonNode capture(HttpExchange ex) throws IOException {
		String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		paths.add(ex.getRequestURI().getPath());
		bodies.add(body);
		try {
			return om.readTree(body);
		} catch (Exception e) {
			return om.createObjectNode();
		}
	}

	private static void reply(HttpExchange ex, int status, String text) throws IOException {
		byte[] data = text.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", text.startsWith("{") ? "application/json" : "text/html; charset=utf-8");
		ex.sendResponseHeaders(status, data.length);
		ex.getResponseBody().write(data);
		ex.close();
	}

	private AiChannelBind stored(String code, String address) {
		AiChannelBind bind = new AiChannelBind();
		bind.setId(7L);
		bind.setAlias("发布通知群");
		bind.setChannelCode(code);
		bind.setParamsSample(address);
		bind.setStatus(1);
		when(mapper.selectOneById(7L)).thenReturn(bind);
		return bind;
	}

	private Map<String, Object> send(String code, String address, String content, Map<String, Object> vars) {
		stored(code, address);
		return service.send(7L, content, vars);
	}

	private JsonNode lastBody() throws Exception {
		return om.readTree(bodies.get(bodies.size() - 1));
	}

	@Test
	void wecomAndDingtalkGetMsgtypeTextAndZeroErrcodeMeansDelivered() throws Exception {
		JsonNode expected = om.readTree("{\"msgtype\":\"text\",\"text\":{\"content\":\"发布完成\"}}");

		Map<String, Object> wecom = send("wecom", base + "/cgi-bin/webhook/send?key=" + GOOD, "发布完成",
			Map.of("idCard", "不该发出去"));
		assertEquals("已发出，企业微信已收下这条消息。请到群里确认", wecom.get("message"));
		assertEquals(true, wecom.get("ok"));
		assertEquals(expected, lastBody());

		Map<String, Object> ding = send("dingtalk", base + "/robot/send?access_token=" + GOOD, "发布完成", Map.of());
		assertEquals("已发出，钉钉已收下这条消息。请到群里确认", ding.get("message"));
		assertEquals(true, ding.get("ok"));
		assertEquals(expected, lastBody());
		assertEquals(true, send("dingtalk", base + "/robot/send?access_token=str-" + GOOD, "发布完成", Map.of()).get("ok"),
			"钉钉文档的返回示例里 errcode 写成了字符串 \"0\"，也认");
	}

	@Test
	void feishuGetsMsgTypeAndContentText() throws Exception {
		Map<String, Object> sent = send("feishu", base + "/open-apis/bot/v2/hook/" + GOOD, "发布完成", Map.of());

		assertEquals("已发出，飞书已收下这条消息。请到群里确认", sent.get("message"));
		assertEquals(true, sent.get("ok"));
		assertEquals(om.readTree("{\"msg_type\":\"text\",\"content\":{\"text\":\"发布完成\"}}"), lastBody());
		assertEquals(true, send("feishu", base + "/open-apis/bot/v2/hook/old-" + GOOD, "发布完成", Map.of()).get("ok"),
			"只有 StatusCode 的回复也认");
	}

	@Test
	void theResultFollowsTheReadersLanguageIncludingThePlatformName() {
		try (EnglishRequest english = EnglishRequest.open()) {
			Map<String, Object> sent = send("wecom", base + "/cgi-bin/webhook/send?key=" + GOOD, "发布完成", Map.of());
			assertEquals("Sent. WeCom accepted this message. Please check it in the group.", sent.get("message"));

			Map<String, Object> refused = send("dingtalk", base + "/robot/send?access_token=" + BAD, "发布完成", Map.of());
			assertEquals("DingTalk did not accept this message: token is not exist (error code 300005)",
				refused.get("message"));
		}
	}

	@Test
	void aWrongKeyComesBackAsHttp200WithAnErrorCodeAndIsAFailureInThePlatformsWords() {
		Map<String, Object> refused = send("wecom", base + "/cgi-bin/webhook/send?key=" + BAD, "发布完成", Map.of());

		assertEquals(false, refused.get("ok"));
		assertEquals("企业微信没有收下这条消息：invalid webhook url, hint: [1795166163496741624646818], from ip: 203.0.113.9, "
			+ "more info at https://open.work.weixin.qq.com/devtool/query?e=93000（错误码 93000）", refused.get("message"));
		assertEquals("93000", refused.get("errorCode"));
		assertEquals(200, refused.get("httpStatus"));
		assertEquals(true, refused.get("httpSent"));
		assertEquals("", refused.get("httpResp"));

		Map<String, Object> ding = send("dingtalk", base + "/robot/send?access_token=" + BAD, "发布完成", Map.of());
		assertEquals(false, ding.get("ok"));
		assertEquals("钉钉没有收下这条消息：token is not exist（错误码 300005）", ding.get("message"));

		Map<String, Object> feishu = send("feishu", base + "/open-apis/bot/v2/hook/" + BAD, "发布完成", Map.of());
		assertEquals(false, feishu.get("ok"));
		assertEquals("飞书没有收下这条消息：param invalid: incoming webhook access token invalid（错误码 19001）",
			feishu.get("message"));
		assertEquals(200, feishu.get("httpStatus"));
	}

	@Test
	void aKeyEchoedInThePlatformsWordsIsCoveredUp() {
		Map<String, Object> refused = send("dingtalk", base + "/robot/send?access_token=" + ECHO, "发布完成", Map.of());

		assertEquals("钉钉没有收下这条消息：keywords not in content, more: /robot/send?****（错误码 310000）", refused.get("message"));
	}

	@Test
	void aRefusalWithANon2xxStatusStillShowsThePlatformsReason() {
		Map<String, Object> refused = send("feishu", base + "/open-apis/bot/v2/hook/strict-" + GOOD, "发布完成", Map.of());

		assertEquals(false, refused.get("ok"));
		assertEquals("飞书没有收下这条消息：Bad Request（错误码 9499）", refused.get("message"));
		assertEquals(400, refused.get("httpStatus"));
	}

	@Test
	void aReplyThatIsNotTheRobotsFormatIsNotCountedAsDelivered() {
		Map<String, Object> page = send("dingtalk", base + "/page", "发布完成", Map.of());
		assertEquals(false, page.get("ok"));
		assertEquals("对方回复的不是钉钉群机器人的格式，没法确认消息有没有送达。请检查地址是不是钉钉群机器人的 Webhook 地址",
			page.get("message"));

		Map<String, Object> down = send("wecom", base + "/down", "发布完成", Map.of());
		assertEquals(false, down.get("ok"));
		assertEquals("对方返回了 HTTP 500，消息没有送达。请检查地址是不是企业微信群机器人的 Webhook 地址", down.get("message"));

		Map<String, Object> huge = send("wecom", base + "/huge", "发布完成", Map.of());
		assertEquals("对方回复的不是企业微信群机器人的格式，没法确认消息有没有送达。请检查地址是不是企业微信群机器人的 Webhook 地址",
			huge.get("message"), "回复大到读不下来，也不说成没有发出去");
		assertEquals(200, huge.get("httpStatus"));
	}

	@Test
	void webhookKeepsItsOwnThreeFieldsAndIsJudgedByHttpStatus() throws Exception {
		Map<String, Object> ok = send("webhook", base + "/hook", "发布完成", Map.of("orderNo", "A-1"));
		assertEquals(true, ok.get("ok"));
		assertEquals("已发出。请到 Webhook 那边确认有没有收到", ok.get("message"));
		assertEquals(om.readTree("{\"channel\":\"webhook\",\"content\":\"发布完成\",\"vars\":{\"orderNo\":\"A-1\"}}"), lastBody());

		Map<String, Object> down = send("webhook", base + "/down", "发布完成", Map.of());
		assertEquals(false, down.get("ok"));
		assertEquals("对方返回了 HTTP 500，消息没有送达。请检查 Webhook 地址和接收方的服务", down.get("message"));

		Map<String, Object> moved = send("webhook", base + "/moved", "发布完成", Map.of());
		assertEquals(false, moved.get("ok"));
		assertEquals("对方返回了跳转（HTTP 302），这里不会跟着跳，消息没有送达。请把地址换成跳转后的最终地址", moved.get("message"));
		assertEquals(List.of("/hook", "/down", "/moved"), paths, "跳转的目标没有被请求");
	}

	@Test
	void robotsAreNotSentAnEmptyMessage() {
		Map<String, Object> empty = send("feishu", base + "/open-apis/bot/v2/hook/" + GOOD, "  ", Map.of());

		assertEquals(false, empty.get("ok"));
		assertEquals("消息内容是空的，没有发送。飞书群机器人不接收空消息", empty.get("message"));
		assertEquals(false, empty.get("httpSent"));
		assertTrue(paths.isEmpty());
	}

	@Test
	void disabledOrAddresslessChannelSendsNothing() {
		stored("wecom", base + "/cgi-bin/webhook/send?key=" + GOOD).setStatus(0);
		assertEquals("这个渠道已停用，没有发送。请先启用", service.send(7L, "发布完成", Map.of()).get("message"));

		Map<String, Object> blank = send("wecom", null, "发布完成", Map.of());
		assertEquals(false, blank.get("ok"));
		assertEquals("这个渠道还没有填写 Webhook 地址，没有发送。请先编辑这个渠道，填上能接收消息的地址", blank.get("message"));
		assertTrue(paths.isEmpty());
	}

	@Test
	void aConnectionFailureIsReportedWithoutTheAddress() throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}

		Map<String, Object> failed = send("wecom", "http://127.0.0.1:" + closedPort + "/cgi-bin/webhook/send?key=" + GOOD,
			"发布完成", Map.of());

		assertEquals(false, failed.get("ok"));
		assertEquals("这次没有发出去。请检查 Webhook 地址能不能从服务器访问，再试一次", failed.get("message"));
		assertEquals(false, failed.get("httpSent"));
	}

	@Test
	void addressesAreShownWithOnlyTheHostAndTheLastFourCharacters() {
		assertEquals("https://qyapi.weixin.qq.com/****5aaa", AiChannelBizService.maskAddress(
			"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=693a91f6-7b1e-4bc4-97a0-0ec2c1f05aaa"));
		assertEquals("https://open.feishu.cn/****96ab", AiChannelBizService.maskAddress(
			"https://open.feishu.cn/open-apis/bot/v2/hook/c41d96ab-0000-4bc4-97a0-0ec2c41d96ab"));
		assertEquals("http://192.168.56.101:18093/****", AiChannelBizService.maskAddress("http://192.168.56.101:18093/hook?k=1234"),
			"短地址的末 4 位差不多就是整个凭据，不显示");
		assertEquals("https://****@example.com/****", AiChannelBizService.maskAddress("https://bot:pw@example.com/x"));
		assertEquals("https://example.com/", AiChannelBizService.maskAddress("https://example.com/"));
		assertEquals("****", AiChannelBizService.maskAddress("{\"webhook\":\"https://example.com/x?key=abc\"}"));
		assertNull(AiChannelBizService.maskAddress(null));
	}

	@Test
	void theListAndTheSaveResultOnlyCarryMaskedAddresses() {
		String full = base + "/cgi-bin/webhook/send?key=" + GOOD;
		String shown = base + "/****d4e5";
		Page<AiChannelBind> rows = new Page<>(1, 20);
		rows.setRecords(List.of(stored("wecom", full)));
		when(mapper.paginate(anyLong(), anyLong(), any(QueryWrapper.class))).thenReturn(rows);

		assertEquals(shown, service.page(1, 20, null).getRecords().get(0).getParamsSample());

		AtomicReference<String> written = new AtomicReference<>();
		doAnswer(inv -> {
			written.set(((AiChannelBind) inv.getArgument(0)).getParamsSample());
			return 1;
		}).when(mapper).insert(any(AiChannelBind.class));
		AiChannelBind created = new AiChannelBind();
		created.setAlias("新群");
		created.setChannelCode("wecom");
		created.setParamsSample(full);

		assertEquals(shown, service.submit(created).getParamsSample());
		assertEquals(full, written.get(), "库里存的还是完整地址");
	}

	@Test
	void editingWithAnEmptyAddressKeepsTheStoredOne() {
		String full = base + "/cgi-bin/webhook/send?key=" + GOOD;
		stored("wecom", full).setTemplateCode("旧应用");
		AtomicReference<AiChannelBind> written = new AtomicReference<>();
		doAnswer(inv -> {
			AiChannelBind row = inv.getArgument(0);
			AiChannelBind copy = new AiChannelBind();
			copy.setParamsSample(row.getParamsSample());
			copy.setTemplateCode(row.getTemplateCode());
			copy.setChannelCode(row.getChannelCode());
			written.set(copy);
			return 1;
		}).when(mapper).update(any(AiChannelBind.class));
		AiChannelBind edit = new AiChannelBind();
		edit.setId(7L);
		edit.setAlias("发布通知群");
		edit.setChannelCode("dingtalk");
		edit.setParamsSample("");
		edit.setTemplateCode("");

		AiChannelBind saved = service.submit(edit);

		assertEquals(full, written.get().getParamsSample());
		assertEquals("dingtalk", written.get().getChannelCode());
		assertEquals("", written.get().getTemplateCode(), "清空绑定应用要写空串，update 不写 null");
		assertEquals(base + "/****d4e5", saved.getParamsSample());
	}

	@Test
	void aMaskedAddressIsRefusedInsteadOfBeingSavedAsTheAddress() {
		stored("wecom", base + "/cgi-bin/webhook/send?key=" + GOOD);
		AiChannelBind edit = new AiChannelBind();
		edit.setId(7L);
		edit.setAlias("发布通知群");
		edit.setChannelCode("wecom");
		edit.setParamsSample(base + "/****d4e5");

		ServiceException refused = assertThrows(ServiceException.class, () -> service.submit(edit));

		assertEquals("这是打码后的地址，不能直接保存。要换地址请填完整的新地址；不改就把这一栏留空", refused.getMessage());
		verify(mapper, never()).update(any(AiChannelBind.class));
	}

	@Test
	void platformWordsAreCutToOneShortLine() {
		String url = "https://oapi.dingtalk.com/robot/send?access_token=0123456789abcdef0123";
		assertEquals("sign not match, more: ****", AiChannelBizService.scrub("sign not match,\r\n more: " + url, url));
		assertEquals("token 0123 ****", AiChannelBizService.scrub("token 0123 0123456789abcdef0123", url));
		assertFalse(AiChannelBizService.scrub("x".repeat(500), url).length() > 201);
		assertEquals("robot send refused", AiChannelBizService.scrub("robot send refused", url), "普通词不盖");
	}
}
