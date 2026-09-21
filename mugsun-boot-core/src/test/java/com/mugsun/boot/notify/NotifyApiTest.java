package com.mugsun.boot.notify;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.mugsun.boot.AbstractIntegrationTest;
import com.mugsun.boot.common.constant.NotifyConstants;
import com.mugsun.boot.message.entity.SysMessage;
import com.mugsun.boot.message.entity.SysMessageUser;
import com.mugsun.boot.message.mapper.SysMessageMapper;
import com.mugsun.boot.message.mapper.SysMessageUserMapper;
import com.mugsun.boot.notify.api.NotifyReceiver;
import com.mugsun.boot.notify.api.NotifySendApi;
import com.mugsun.boot.notify.entity.SysNotifyChannel;
import com.mugsun.boot.notify.entity.SysNotifyRecord;
import com.mugsun.boot.notify.entity.SysNotifyTemplate;
import com.mugsun.boot.notify.mapper.SysNotifyChannelMapper;
import com.mugsun.boot.notify.mapper.SysNotifyRecordMapper;
import com.mugsun.boot.notify.mapper.SysNotifyTemplateMapper;
import com.mugsun.boot.system.entity.SysParam;
import com.mugsun.boot.system.mapper.SysParamMapper;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.net.ServerSocket;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G88 多渠道消息统一调度集成测试：渲染 fail-fast、三渠道 fan-out（GreenMail 假 SMTP 真实收发）、
 * 失败重试转 DEAD、流水管理端查询。短信渠道保持占位凭证（无启用 sys_sms）必失败，验证 FAILURE 留痕。
 * <p>重试扫描由测试直接调用 {@link NotifyRetryJob#scanAndRetry()} 触发（不长时间 sleep 等调度）；
 * 定时 tick 被节流参数放大到 1 小时，避免与手动扫描并发干扰断言。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotifyApiTest extends AbstractIntegrationTest {

	private static final String FANOUT_USERNAME = "it-notify-fanout";
	private static final String FANOUT_NICKNAME = "通知集成测试";
	private static final String FANOUT_EMAIL = "notify-fanout@test.local";
	private static final String FANOUT_PHONE = "13800009999";

	@Autowired
	private SysNotifyTemplateMapper templateMapper;
	@Autowired
	private SysNotifyChannelMapper channelMapper;
	@Autowired
	private SysNotifyRecordMapper recordMapper;
	@Autowired
	private SysMessageMapper messageMapper;
	@Autowired
	private SysMessageUserMapper messageUserMapper;
	@Autowired
	private SysParamMapper paramMapper;
	@Autowired
	private ParamService paramService;
	@Autowired
	private NotifyRetryJob retryJob;
	@Autowired
	private NotifyTemplateRenderer renderer;
	@Autowired
	private NotifySendApi notifySendApi;

	private GreenMail greenMail;
	private int smtpPort;
	private String adminToken;

	@BeforeAll
	void setup() throws Exception {
		// 空闲端口起 GreenMail 假 SMTP（jakarta 兼容）
		try (ServerSocket socket = new ServerSocket(0)) {
			smtpPort = socket.getLocalPort();
		}
		greenMail = new GreenMail(new ServerSetup(smtpPort, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
		greenMail.start();

		// 定时重试 tick 节流放大到 1 小时：测试期间只允许手动 scanAndRetry，保证断言确定性
		updateParam(NotifyConstants.PARAM_RETRY_SCAN_INTERVAL, "3600000");
		adminToken = loginAdmin();
	}

	@AfterAll
	void tearDown() throws FolderException {
		// 还原全局配置：模板默认仅站内信、邮件渠道停用、重试参数回种子值
		SysNotifyTemplate welcome = welcomeTemplate();
		welcome.setChannels(NotifyConstants.CHANNEL_IN_APP);
		templateMapper.update(welcome);
		SysNotifyChannel mail = mailChannel();
		mail.setStatus(0);
		channelMapper.update(mail);
		updateParam(NotifyConstants.PARAM_RETRY_SCAN_INTERVAL, "60000");
		updateParam(NotifyConstants.PARAM_RETRY_MAX_TIMES, "3");
		if (greenMail != null) {
			greenMail.stop();
		}
	}

	// ============ ① 渲染：缺参 fail-fast / 参数齐全正确 ============

	@Test
	@Order(1)
	void renderFailFastAndSuccess() {
		// 缺参被拒（渲染器层）
		assertThatThrownBy(() -> renderer.render("你好 ${name}", Map.of()))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("name");
		// 参数齐全渲染正确
		assertThat(renderer.render("你好 ${name}，工号 ${code}", Map.of("name", "张三", "code", "A1")))
			.isEqualTo("你好 张三，工号 A1");
		// 保存期抽参
		assertThat(renderer.extractParams("欢迎 ${name}", "内容含 ${code} 和 ${name}"))
			.containsExactly("name", "code");
		// 缺参被拒（门面层：welcome 模板 required_params=name，落流水前即抛，无副作用）
		assertThatThrownBy(() -> notifySendApi.send(NotifyConstants.TEMPLATE_WELCOME,
			List.of(NotifyReceiver.of(1L, "x", null, null)), Map.of()))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("name");
	}

	// ============ ② fan-out：一次业务事件三渠道触达，回执留痕 ============

	@Test
	@Order(2)
	void fanOutToThreeChannels() throws Exception {
		// 配置：welcome 模板三渠道；邮件渠道指向 GreenMail；短信渠道启用但 sys_sms 无启用配置（必失败）
		SysNotifyTemplate welcome = welcomeTemplate();
		welcome.setChannels("in_app,mail,sms");
		templateMapper.update(welcome);
		SysNotifyChannel mail = mailChannel();
		mail.setStatus(1);
		mail.setConfig("{\"host\":\"127.0.0.1\",\"port\":" + smtpPort + ",\"from\":\"noreply@mugsun.com\"}");
		channelMapper.update(mail);

		// 触发业务事件：管理端新建用户（afterCommit 触发 NotifyService.send("welcome", ...)）
		Map<String, Object> user = new HashMap<>();
		user.put("username", FANOUT_USERNAME);
		user.put("nickname", FANOUT_NICKNAME);
		user.put("email", FANOUT_EMAIL);
		user.put("phone", FANOUT_PHONE);
		user.put("password", "123456");
		user.put("status", 1);
		JsonNode created = readBody(post("/system/user/submit", user, adminToken));
		assertThat(created.path("code").asInt()).as("建用户：" + created.path("msg").asText()).isEqualTo(200);
		long userId = findUserId(FANOUT_USERNAME);

		// 等待异步 fan-out 完成（短间隔轮询，非长时间 sleep）
		await(() -> recordsOf(userId).stream().noneMatch(r -> NotifyConstants.STATUS_INIT.equals(r.getStatus())),
			"fan-out 流水全部离开 INIT 态");
		Map<String, SysNotifyRecord> byChannel = new HashMap<>();
		recordsOf(userId).forEach(r -> byChannel.put(r.getChannel(), r));
		assertThat(byChannel.keySet()).containsExactlyInAnyOrder(
			NotifyConstants.CHANNEL_IN_APP, NotifyConstants.CHANNEL_MAIL, NotifyConstants.CHANNEL_SMS);

		// 站内信：真实落库 + SUCCESS
		assertThat(byChannel.get(NotifyConstants.CHANNEL_IN_APP).getStatus())
			.isEqualTo(NotifyConstants.STATUS_SUCCESS);
		List<SysMessageUser> inbox = messageUserMapper.selectListByQuery(
			QueryWrapper.create().eq("user_id", userId));
		assertThat(inbox).isNotEmpty();
		SysMessage message = messageMapper.selectOneById(inbox.get(0).getMessageId());
		assertThat(message.getTitle()).contains(FANOUT_NICKNAME);

		// 邮件：GreenMail 真实收到 + SUCCESS
		assertThat(byChannel.get(NotifyConstants.CHANNEL_MAIL).getStatus())
			.isEqualTo(NotifyConstants.STATUS_SUCCESS);
		assertThat(greenMail.waitForIncomingEmail(15000, 1)).as("GreenMail 收到欢迎邮件").isTrue();
		MimeMessage received = greenMail.getReceivedMessages()[0];
		assertThat(received.getSubject()).contains(FANOUT_NICKNAME);
		assertThat(received.getAllRecipients()[0].toString()).isEqualTo(FANOUT_EMAIL);

		// 短信：占位凭证必失败 → FAILURE 留痕含错误信息
		SysNotifyRecord sms = byChannel.get(NotifyConstants.CHANNEL_SMS);
		assertThat(sms.getStatus()).isEqualTo(NotifyConstants.STATUS_FAILURE);
		assertThat(sms.getErrorMsg()).isNotBlank();
		assertThat(sms.getNextRetryTime()).isNotNull();
	}

	// ============ ③ 失败重试：次数递增，达上限转 DEAD ============

	@Test
	@Order(3)
	void retryEscalatesToDead() {
		// 造一条到期 FAILURE 流水（短信渠道，无启用 sys_sms 配置，重试必再败）
		SysNotifyRecord record = new SysNotifyRecord();
		record.setBatchId(IdUtil.getSnowflakeNextId());
		record.setTemplateCode(NotifyConstants.TEMPLATE_WELCOME);
		record.setChannel(NotifyConstants.CHANNEL_SMS);
		record.setReceiverContact("13800001111");
		record.setSubject("重试测试");
		record.setContent("重试测试内容");
		record.setStatus(NotifyConstants.STATUS_FAILURE);
		record.setErrorMsg("初始失败");
		record.setRetryCount(0);
		record.setNextRetryTime(LocalDateTime.now().minusMinutes(1));
		TenantContext.execute(PLATFORM_TENANT, () -> recordMapper.insertSelective(record));
		Long recordId = record.getId();

		// 手动触发扫描：重试次数递增、仍为 FAILURE（默认上限 3）
		retryJob.scanAndRetry();
		SysNotifyRecord afterFirst = TenantContext.ignore(() -> recordMapper.selectOneById(recordId));
		assertThat(afterFirst.getRetryCount()).isEqualTo(1);
		assertThat(afterFirst.getStatus()).isEqualTo(NotifyConstants.STATUS_FAILURE);
		assertThat(afterFirst.getNextRetryTime()).isAfter(LocalDateTime.now());

		// 上限调为 2 且再次到期 → 第二次重试失败即达上限（retryCount=2 ≥ 2）转 DEAD
		updateParam(NotifyConstants.PARAM_RETRY_MAX_TIMES, "2");
		SysNotifyRecord again = new SysNotifyRecord();
		again.setId(recordId);
		again.setStatus(NotifyConstants.STATUS_FAILURE);
		again.setNextRetryTime(LocalDateTime.now().minusMinutes(1));
		TenantContext.execute(PLATFORM_TENANT, () -> recordMapper.update(again));
		retryJob.scanAndRetry();
		SysNotifyRecord dead = TenantContext.ignore(() -> recordMapper.selectOneById(recordId));
		assertThat(dead.getStatus()).isEqualTo(NotifyConstants.STATUS_DEAD);
		assertThat(dead.getErrorMsg()).contains("重试达上限");
	}

	// ============ ④ 流水管理端查询：权限码生效、无 token 401 ============

	@Test
	@Order(4)
	void recordPageApiGuarded() {
		ResponseEntity<String> noToken = get("/system/notify/record/page?pageNum=1&pageSize=10", null);
		assertThat(noToken.getStatusCode().value()).isEqualTo(401);

		JsonNode page = readBody(get(
			"/system/notify/record/page?pageNum=1&pageSize=10&templateCode=welcome", adminToken));
		assertThat(page.path("code").asInt()).isEqualTo(200);
		assertThat(page.path("data").path("records").size()).isGreaterThan(0);
		assertThat(page.path("data").path("records").get(0).path("status").asText()).isNotBlank();
	}

	// ============ 内部工具 ============

	private SysNotifyTemplate welcomeTemplate() {
		return templateMapper.selectOneByQuery(
			QueryWrapper.create().eq("code", NotifyConstants.TEMPLATE_WELCOME));
	}

	private SysNotifyChannel mailChannel() {
		return channelMapper.selectOneByQuery(
			QueryWrapper.create().eq("channel", NotifyConstants.CHANNEL_MAIL));
	}

	/** 该接收人的本次 fan-out 流水（跨租户读，测试在平台租户下造数） */
	private List<SysNotifyRecord> recordsOf(long userId) {
		return TenantContext.ignore(() -> recordMapper.selectListByQuery(QueryWrapper.create()
			.eq("receiver_id", userId).eq("template_code", NotifyConstants.TEMPLATE_WELCOME)));
	}

	private void updateParam(String key, String value) {
		SysParam param = paramMapper.selectOneByQuery(QueryWrapper.create().eq("param_key", key));
		param.setParamValue(value);
		paramMapper.update(param);
		// JetCache 本地缓存按键失效，保证调度/重试立即读到新值
		paramService.evict(key);
	}

	private long findUserId(String username) {
		JsonNode page = readBody(get("/system/user/page?pageNum=1&pageSize=100", adminToken));
		for (JsonNode record : page.path("data").path("records")) {
			if (username.equals(record.path("username").asText())) {
				return record.path("id").asLong();
			}
		}
		throw new IllegalStateException("分页中未找到用户 " + username);
	}

	/** 短间隔轮询至条件成立（上限 15s），避免长时间 sleep */
	private void await(Supplier<Boolean> condition, String description) {
		long deadline = System.currentTimeMillis() + 15000;
		while (System.currentTimeMillis() < deadline) {
			if (Boolean.TRUE.equals(condition.get())) {
				return;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		assertThat(condition.get()).as(description).isTrue();
	}
}
