package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.RoleConstants;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.message.MessageService;
import com.mugsun.boot.system.entity.SysRole;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.entity.SysUserRole;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.track.entity.TrackApp;
import com.mybatisflex.core.query.QueryWrapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 埋点错误告警引擎（G101）：消费侧 $error 落库成功后逐条评估，命中规则即给应用所属租户的管理员发站内信。
 * <p><b>规则 A 新指纹</b>：{@code SETNX alert-new:{app}:{fp}}（TTL 7 天）首次成功 = 该指纹周期内首现 → 告警；
 * <b>规则 B 频次阈值</b>：{@code INCR alert-freq:{app}:{fp}}（首置 10 分钟窗 EXPIRE）达 alert_threshold
 * 且窗级抑制键 SETNX 成功（本窗未告过）→ 告警。告警状态全在 Redis，不落表。
 * <p><b>非阻塞静默纪律</b>：消费器只调 {@link #evaluateQuietly}——评估/发送任何异常只记日志，
 * 绝不回传触发批次重试（告警是附加动作，绝不能改变事件落库的 at-most-once 语义）。
 * 注意：落库失败重试的批次会重估规则 B 计数（同接收窗 ON CONFLICT 去重而 INCR 不去重），
 * 极端瞬断下同窗计数可能略偏高——告警宁多报不漏报，可接受。
 * <p><b>数据源纪律</b>：本类<b>严禁</b>类级 @TrackDS——告警配置经 {@link TrackAppService}（自带 @TrackDS
 * 逐方法路由）读埋点库；收件人查询（sys_role/sys_user_role/sys_user）与站内信落库（sys_message）都在业务库，
 * 保持默认 primary 路由才正确。消费线程无会话上下文，收件人查询显式 {@link TenantContext#execute} 按事件
 * 自带 tenant_id 建作用域（租户行级插件随之拼条件），不继承、不越界。
 */
@Service
public class TrackAlertService {

	private static final Logger log = LoggerFactory.getLogger(TrackAlertService.class);

	/** 告警时间格式（UTC 墙钟，与库内时间口径一致） */
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
		.withZone(ZoneOffset.UTC);
	/** 指标 reason：新指纹首告 */
	private static final String REASON_NEW_FINGERPRINT = "new-fingerprint";
	/** 指标 reason：频次阈值越线 */
	private static final String REASON_THRESHOLD = "threshold";

	private final TrackAppService appService;
	private final StringRedisTemplate redis;
	private final MessageService messageService;
	private final SysRoleMapper roleMapper;
	private final SysUserRoleMapper userRoleMapper;
	private final SysUserMapper userMapper;
	private final MeterRegistry registry;

	public TrackAlertService(TrackAppService appService, StringRedisTemplate redis, MessageService messageService,
							 SysRoleMapper roleMapper, SysUserRoleMapper userRoleMapper, SysUserMapper userMapper,
							 MeterRegistry registry) {
		this.appService = appService;
		this.redis = redis;
		this.messageService = messageService;
		this.roleMapper = roleMapper;
		this.userRoleMapper = userRoleMapper;
		this.userMapper = userMapper;
		this.registry = registry;
	}

	/** 消费侧唯一织入点：非阻塞静默——任何异常只记日志，绝不影响落库主流程与批次重试语义 */
	public void evaluateQuietly(TrackIngestEvent event) {
		try {
			evaluate(event);
		} catch (Exception e) {
			log.warn("错误告警评估失败（静默不阻塞消费）app={} fp={}：{}",
				event.getAppKey(), event.getErrorFingerprint(), e.getMessage());
		}
	}

	/** 规则评估：app 未开告警 / 无指纹直接短路；规则 A/B 独立判定（同条事件可同时命中，极端阈值=1 时会各发一条） */
	private void evaluate(TrackIngestEvent event) {
		String fingerprint = event.getErrorFingerprint();
		if (fingerprint == null || fingerprint.isBlank()) {
			return;
		}
		Optional<TrackApp> found = appService.findByAppKey(event.getAppKey());
		if (found.isEmpty()) {
			return;
		}
		TrackApp app = found.get();
		if (app.getAlertEnabled() == null || app.getAlertEnabled() != 1) {
			return;
		}
		int threshold = app.getAlertThreshold() == null || app.getAlertThreshold() < 1
			? TrackConstants.DEFAULT_ALERT_THRESHOLD : app.getAlertThreshold();
		String scope = event.getAppKey() + ":" + fingerprint;

		// 规则 A：新指纹首告（SETNX 7 天去重——同指纹周期内只首告一次）
		Boolean isNew = redis.opsForValue().setIfAbsent(TrackConstants.ALERT_NEW_KEY_PREFIX + scope, "1",
			Duration.ofSeconds(TrackConstants.ALERT_NEW_TTL_SECONDS));
		if (Boolean.TRUE.equals(isNew)) {
			fire(app, event, REASON_NEW_FINGERPRINT, 1, threshold);
		}

		// 规则 B：10 分钟窗频次阈值（INCR + 首置 EXPIRE；越线且本窗未告过才告，抑制键 TTL 对齐窗剩余）
		String freqKey = TrackConstants.ALERT_FREQ_KEY_PREFIX + scope;
		Long count = redis.opsForValue().increment(freqKey);
		if (count == null) {
			return;
		}
		if (count == 1L) {
			redis.expire(freqKey, Duration.ofSeconds(TrackConstants.ALERT_FREQ_WINDOW_SECONDS));
		}
		if (count >= threshold) {
			long remain = redis.getExpire(freqKey);
			Boolean firstInWindow = redis.opsForValue().setIfAbsent(TrackConstants.ALERT_FREQ_SENT_KEY_PREFIX + scope, "1",
				Duration.ofSeconds(remain > 0 ? remain : TrackConstants.ALERT_FREQ_WINDOW_SECONDS));
			if (Boolean.TRUE.equals(firstInWindow)) {
				fire(app, event, REASON_THRESHOLD, count, threshold);
			}
		}
	}

	/** 发告警站内信：收件人 = 应用所属租户的管理员角色用户（启用状态）；发送计数指标（reason 标签） */
	private void fire(TrackApp app, TrackIngestEvent event, String reason, long count, int threshold) {
		String tenantId = event.getTenantId();
		if (tenantId == null || tenantId.isBlank()) {
			log.warn("错误告警跳过：事件无 tenantId app={} fp={}", event.getAppKey(), event.getErrorFingerprint());
			return;
		}
		List<Long> adminIds = TenantContext.execute(tenantId, this::tenantAdminIds);
		if (adminIds.isEmpty()) {
			log.warn("错误告警无收件人（租户 {} 无启用状态的管理员角色用户）app={} fp={}",
				tenantId, event.getAppKey(), event.getErrorFingerprint());
			return;
		}
		// type 传 null 由 MessageService 落默认 system（与统一调度站内信渠道同口径）
		messageService.sendSystem(TrackConstants.ALERT_MESSAGE_TITLE,
			buildContent(app, event, reason, count, threshold), null, adminIds);
		registry.counter(TrackConstants.METRIC_ALERT_SENT, Tags.of("reason", reason)).increment();
	}

	/**
	 * 当前租户（{@link TenantContext#execute} 作用域内）的管理员角色用户 id：
	 * sys_role 按内置角色编码 {@link RoleConstants#ADMIN} 定角色 → sys_user_role 取绑定 → sys_user 过滤启用。
	 * 租户行级插件对 sys_role/sys_user 自动拼 tenant_id 条件（sys_user_role 无租户列，靠角色行已隔离）。
	 */
	private List<Long> tenantAdminIds() {
		List<SysRole> roles = roleMapper.selectListByQuery(QueryWrapper.create().eq("role_code", RoleConstants.ADMIN));
		if (roles.isEmpty()) {
			return List.of();
		}
		List<Long> roleIds = roles.stream().map(SysRole::getId).toList();
		List<Long> userIds = userRoleMapper.selectListByQuery(QueryWrapper.create().in("role_id", roleIds))
			.stream().map(SysUserRole::getUserId).distinct().toList();
		if (userIds.isEmpty()) {
			return List.of();
		}
		return userMapper.selectListByQuery(QueryWrapper.create().in("id", userIds).eq("status", 1))
			.stream().map(SysUser::getId).toList();
	}

	/** 告警信内容（HTML 片段，与站内信模板同渲染口径；动态值一律转义防存储型 XSS） */
	private String buildContent(TrackApp app, TrackIngestEvent event, String reason, long count, int threshold) {
		String rule = REASON_NEW_FINGERPRINT.equals(reason)
			? "新错误指纹首次出现"
			: "10 分钟内同指纹错误 " + count + " 次（阈值 " + threshold + "）";
		String time = TIME_FORMAT.format(Instant.ofEpochMilli(event.getTsMs()));
		return "<p>应用：" + escape(app.getAppName()) + "（" + escape(app.getAppKey()) + "）</p>"
			+ "<p>事件：" + TrackConstants.EVENT_ERROR + "</p>"
			+ "<p>错误指纹：" + escape(event.getErrorFingerprint()) + "</p>"
			+ "<p>触发规则：" + rule + "</p>"
			+ "<p>本次计数：" + count + "</p>"
			+ "<p>页面：" + escape(event.getUrlPath()) + "</p>"
			+ "<p>时间：" + time + "（UTC）</p>"
			+ "<p>查看：<a href=\"" + TrackConstants.ALERT_ERROR_LINK + "\">" + TrackConstants.ALERT_ERROR_LINK + "</a></p>";
	}

	/** HTML 转义（&lt;&gt;&amp; 三字符足防标签注入；空值兜底占位） */
	private static String escape(String s) {
		if (s == null || s.isBlank()) {
			return "-";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
