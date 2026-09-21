package com.mugsun.boot.auth;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.session.SaTerminalInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.cache.RedisScanSupport;
import com.mugsun.boot.common.constant.MonitorConstants;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.websocket.WsMessageSender;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在线会话管理：枚举当前所有在线终端（多端登录各占一行），支持强制下线
 * （按 token 踢单端 / 按账号踢全部端）。会话落 Redis 后重启不失效，列表在重启后仍反映真实在线状态。
 */
@RestController
@RequestMapping("/system/online")
@SaCheckLogin
public class OnlineController {

	private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final Logger log = LoggerFactory.getLogger(OnlineController.class);

	/** 强制下线帧携带的提示语 */
	private static final String FORCE_OFFLINE_REASON = "您的账号已被管理员强制下线";

	/** 在线会话查询权限码：列表按终端展开（仅 tokenMask 展示，明文令牌永不下发），持码管理员可见，独立于在线表单域前缀 */
	private static final String PERM_LIST = "sys:session:list";

	/** 强制下线权限码：显式声明替代兜底派生码，与查询同域管理 */
	private static final String PERM_KICKOUT = "sys:session:kickout";

	private final SysUserMapper userMapper;
	private final WsMessageSender wsMessageSender;
	private final StringRedisTemplate redisTemplate;

	@Value("${sa-token.token-name:Authorization}")
	private String tokenName;

	public OnlineController(SysUserMapper userMapper, WsMessageSender wsMessageSender,
		StringRedisTemplate redisTemplate) {
		this.userMapper = userMapper;
		this.wsMessageSender = wsMessageSender;
		this.redisTemplate = redisTemplate;
	}

	/**
	 * 在线会话列表：遍历所有账号会话，展开每个终端为一行。
	 * 安全红线：tokenValue 永不明文下发（明文即会话凭据，泄露=会话冒用），踢单端由前端回传 loginId+deviceType+tokenMask 服务端解析。
	 * <p>用户已删/不存在时仍列出终端，并标 {@code stale=true}（账号/昵称不再用裸「-」误导运维）。
	 */
	@GetMapping("/list")
	@SaCheckPermission(PERM_LIST)
	public R<List<Map<String, Object>>> list() {
		List<SaSession> sessions = loadSessions();
		Map<Long, SysUser> users = loadUsers(sessions);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (SaSession session : sessions) {
			Object loginId = session.getLoginId();
			Long uid = parseLoginId(loginId);
			SysUser user = uid == null ? null : users.get(uid);
			boolean stale = user == null;
			for (SaTerminalInfo terminal : session.terminalListCopy()) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("loginId", loginId == null ? null : loginId.toString());
				row.put("stale", stale);
				if (stale) {
					// 失效会话：展示 loginId 便于定位，昵称标明已失效（e2e/删户残留常见）
					row.put("username", loginId == null ? "-" : loginId.toString());
					row.put("nickname", "账号已失效");
				} else {
					row.put("username", user.getUsername());
					row.put("nickname", user.getNickname() == null ? user.getUsername() : user.getNickname());
				}
				row.put("tokenMask", mask(terminal.getTokenValue()));
				row.put("deviceType", terminal.getDeviceType());
				// 登录时经 SaLoginParameter.terminalExtra 落终端的 IP/UA（G90 补展示，机制不动）
				Object ip = terminal.getExtra(MonitorConstants.TERMINAL_EXTRA_IP);
				Object ua = terminal.getExtra(MonitorConstants.TERMINAL_EXTRA_UA);
				row.put("ip", ip == null ? "-" : ip.toString());
				row.put("userAgent", ua == null ? "-" : ua.toString());
				row.put("loginTime", format(terminal.getCreateTime()));
				rows.add(row);
			}
		}
		return R.data(rows);
	}

	/**
	 * 清理失效会话：用户已删或不存在于库中的 Redis 会话整账号踢出并断开推送。
	 * 返回清理的终端行数（便于前端提示）。
	 */
	@PostMapping("/cleanup-stale")
	@SaCheckPermission(PERM_KICKOUT)
	public R<Integer> cleanupStale() {
		List<SaSession> sessions = loadSessions();
		Map<Long, SysUser> users = loadUsers(sessions);
		int terminals = 0;
		for (SaSession session : sessions) {
			Object loginId = session.getLoginId();
			Long uid = parseLoginId(loginId);
			if (uid != null && users.containsKey(uid)) {
				continue;
			}
			if (loginId == null) {
				continue;
			}
			terminals += session.terminalListCopy().size();
			StpUtil.kickout(loginId);
			closePushSessions(loginId.toString());
		}
		return R.data(terminals);
	}

	/** 强制下线：传 loginId+deviceType(+tokenMask) 踢单端（服务端按会话终端解析真实 token，前端从不持有明文）；
	 *  仅传 loginId 踢该账号全部端；踢人后同步断开实时推送连接 */
	@PostMapping("/kickout")
	@SaCheckPermission(PERM_KICKOUT)
	public R<Void> kickout(@RequestBody Map<String, String> body) {
		String loginId = body.get("loginId");
		String deviceType = body.get("deviceType");
		String tokenMask = body.get("tokenMask");
		if (loginId == null || loginId.isBlank()) {
			throw new ServiceException("缺少 loginId");
		}
		if (deviceType == null || deviceType.isBlank()) {
			StpUtil.kickout(loginId);
			closePushSessions(loginId);
			return R.success("已强制下线");
		}
		// 踢单端：在该账号会话终端中按 deviceType(+tokenMask) 定位，服务端取真实 tokenValue 踢出
		SaSession session = StpUtil.getSessionByLoginId(loginId, false);
		if (session == null) {
			return R.success("已强制下线");
		}
		for (SaTerminalInfo terminal : session.terminalListCopy()) {
			boolean deviceMatch = deviceType.equals(String.valueOf(terminal.getDeviceType()));
			boolean maskMatch = tokenMask == null || tokenMask.isBlank() || mask(terminal.getTokenValue()).equals(tokenMask);
			if (deviceMatch && maskMatch) {
				StpUtil.kickoutByTokenValue(terminal.getTokenValue());
				wsMessageSender.closeUser(null, terminal.getTokenValue(), FORCE_OFFLINE_REASON);
			}
		}
		return R.success("已强制下线");
	}

	/** 断开该账号全部端的推送连接；loginId 非数字（非常规账号）或断开失败时跳过，不影响踢人主流程 */
	private void closePushSessions(String loginId) {
		try {
			wsMessageSender.closeUser(Long.valueOf(loginId), null, FORCE_OFFLINE_REASON);
		} catch (Exception e) {
			log.warn("断开推送连接失败 loginId={}", loginId, e);
		}
	}

	private List<SaSession> loadSessions() {
		List<SaSession> sessions = new ArrayList<>();
		String pattern = tokenName + ":" + StpUtil.getLoginType() + ":session:*";
		for (String sid : RedisScanSupport.scan(redisTemplate, pattern)) {
			SaSession session = StpUtil.getSessionBySessionId(sid);
			if (session != null) {
				sessions.add(session);
			}
		}
		return sessions;
	}

	/** 批量解析会话用户（忽略租户 + 一次 IN 查询），避免按终端 N+1 */
	private Map<Long, SysUser> loadUsers(List<SaSession> sessions) {
		Set<Long> ids = new HashSet<>();
		for (SaSession session : sessions) {
			Long id = parseLoginId(session.getLoginId());
			if (id != null) {
				ids.add(id);
			}
		}
		if (ids.isEmpty()) {
			return Map.of();
		}
		List<SysUser> list = TenantContext.ignore(() ->
			userMapper.selectListByQuery(QueryWrapper.create().in("id", ids)));
		Map<Long, SysUser> map = new HashMap<>(list.size() * 2);
		for (SysUser u : list) {
			map.put(u.getId(), u);
		}
		return map;
	}

	private Long parseLoginId(Object loginId) {
		if (loginId == null) {
			return null;
		}
		try {
			return Long.valueOf(loginId.toString());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private String mask(String token) {
		if (token == null || token.length() <= 8) {
			return token;
		}
		return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
	}

	private String format(long epochMilli) {
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault()).format(FMT);
	}
}
