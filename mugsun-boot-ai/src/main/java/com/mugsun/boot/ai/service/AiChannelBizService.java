package com.mugsun.boot.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiChannelBind;
import com.mugsun.boot.ai.mapper.AiChannelBindMapper;
import com.mugsun.boot.ai.support.AiOutboundProperties;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiChannelBizService {

	private static final Logger log = LoggerFactory.getLogger(AiChannelBizService.class);
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Set<String> CODES = Set.of("wecom", "dingtalk", "feishu", "webhook");
	/** 群机器人类的渠道：消息体按各平台的文本消息格式组装，成败看平台回复里的结果码 */
	private static final Map<String, String> ROBOTS = Map.of("wecom", "企业微信", "dingtalk", "钉钉", "feishu", "飞书");
	private static final Pattern URL = Pattern.compile("https?://\\S+");
	private static final Pattern WHOLE_URL = Pattern.compile("^https?://[^\\s<>\"']{1,2000}$");
	private static final Pattern ORIGIN = Pattern.compile("^(https?://)([^/?#\\s]+)(.*)$", Pattern.DOTALL);
	/** 打码后的地址用它盖住中间部分；提交上来的地址带着它，说明把打码值当成了地址 */
	static final String MASK = "****";
	/** 路径和参数加起来不到这么长时，末 4 位也不显示：短地址的末 4 位差不多就是整个凭据 */
	private static final int TAIL_MIN_LENGTH = 24;
	private static final int REPLY_TEXT_LIMIT = 200;
	private static final Duration SEND_TIMEOUT = Duration.ofSeconds(8);

	private final AiModuleService moduleService;
	private final AiChannelBindMapper mapper;
	private final AiOutboundProperties outbound;
	private final com.mugsun.boot.ai.support.OutboundWebClientFactory outboundWebClients;

	public AiChannelBizService(AiModuleService moduleService, AiChannelBindMapper mapper,
							   AiOutboundProperties outbound, com.mugsun.boot.ai.support.OutboundWebClientFactory outboundWebClients) {
		this.outboundWebClients = outboundWebClients;
		this.moduleService = moduleService;
		this.mapper = mapper;
		this.outbound = outbound;
	}

	/** 列表里的地址是打码后的：地址里带着机器人的 key，拿到就能往群里发消息。 */
	public Page<AiChannelBind> page(long pageNum, long pageSize, String alias) {
		moduleService.requireEnabled();
		if (pageNum < 1) {
			throw new ServiceException("页码请从 1 开始");
		}
		if (pageSize < 1 || pageSize > 100) {
			throw new ServiceException("每页条数请在 1 到 100 之间");
		}
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		if (alias != null && !alias.isBlank()) {
			String keyword = alias.trim();
			if (keyword.length() > 64) {
				keyword = keyword.substring(0, 64);
			}
			query.like("alias", keyword);
		}
		Page<AiChannelBind> page = mapper.paginate(pageNum, pageSize, query);
		if (page != null && page.getRecords() != null) {
			page.getRecords().forEach(AiChannelBizService::masked);
		}
		return page;
	}

	/** 保存渠道。编辑时地址留空表示不修改；返回的地址和列表一样是打码后的。 */
	public AiChannelBind submit(AiChannelBind body) {
		moduleService.requireEnabled();
		if (body == null) {
			throw new ServiceException("请填写渠道名称。用来在列表里区分");
		}
		String alias = body.getAlias() == null ? "" : body.getAlias().trim();
		if (alias.isEmpty()) {
			throw new ServiceException("请填写渠道名称。用来在列表里区分");
		}
		if (alias.length() > 64) {
			throw new ServiceException("渠道名称请控制在 64 个字以内");
		}
		String code = body.getChannelCode() == null ? "" : body.getChannelCode().trim();
		if (!CODES.contains(code)) {
			throw new ServiceException("渠道请选择企微、钉钉、飞书或 Webhook");
		}
		String template = body.getTemplateCode() == null ? "" : body.getTemplateCode().trim();
		if (template.length() > 64) {
			throw new ServiceException("绑定应用请控制在 64 个字以内");
		}
		boolean create = body.getId() == null;
		String sample = body.getParamsSample() == null ? "" : body.getParamsSample().trim();
		if (sample.contains(MASK)) {
			throw new ServiceException("这是打码后的地址，不能直接保存。要换地址请填完整的新地址"
				+ (create ? "" : "；不改就把这一栏留空"));
		}
		if (!sample.isEmpty() && !WHOLE_URL.matcher(sample).matches()) {
			throw new ServiceException("Webhook 需要以 http:// 或 https:// 开头，并且不要带空格。"
				+ (create ? "留空表示先不调试" : "留空表示不修改原来的地址"));
		}
		if (!sample.isEmpty()) {
			outbound.guard(sample);
		}
		if (body.getStatus() != null && body.getStatus() != 0 && body.getStatus() != 1) {
			throw new ServiceException("状态请选择启用或停用");
		}
		requireUnique(alias, body.getId());
		int status = body.getStatus() == null ? AiConstants.STATUS_ENABLE : body.getStatus();
		if (create) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			body.setAlias(alias);
			body.setChannelCode(code);
			body.setTemplateCode(template.isEmpty() ? null : template);
			body.setParamsSample(sample.isEmpty() ? null : sample);
			body.setStatus(status);
			mapper.insert(body);
			return masked(body);
		}
		AiChannelBind db = require(body.getId());
		db.setAlias(alias);
		db.setChannelCode(code);
		// update 不写空值字段：绑定应用要清空就得写空串，写 null 会留着原来的值
		db.setTemplateCode(template);
		if (!sample.isEmpty()) {
			db.setParamsSample(sample);
		}
		db.setStatus(status);
		mapper.update(db);
		return masked(db);
	}

	public List<AiChannelBind> remove(List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的渠道");
		}
		List<AiChannelBind> rows = new ArrayList<>();
		for (Long id : ids) {
			rows.add(require(id));
		}
		for (AiChannelBind row : rows) {
			mapper.deleteById(row.getId());
		}
		return rows;
	}

	public Map<String, Object> debug(Long id) {
		return send(id, "debug ping", Map.of());
	}

	/**
	 * 渠道发送。企微、钉钉、飞书按各自群机器人的文本消息格式发，并看平台回复里的结果码；Webhook 发
	 * {channel, content, vars}，看 HTTP 状态。停用、没填地址、机器人消息为空时不向外发。失败说明里不带地址和连接原文。
	 */
	public Map<String, Object> send(Long id, String content, Map<String, Object> vars) {
		moduleService.requireEnabled();
		AiChannelBind bind = require(id);
		String text = content == null ? "" : content;
		if (bind.getStatus() != null && bind.getStatus() == 0) {
			return result(false, "这个渠道已停用，没有发送。请先启用", bind, text);
		}
		String url = extractUrl(bind.getParamsSample());
		if (url == null) {
			return result(false, "这个渠道还没有填写 Webhook 地址，没有发送。请先编辑这个渠道，填上能接收消息的地址", bind, text);
		}
		String code = bind.getChannelCode() == null ? "" : bind.getChannelCode();
		String platform = ROBOTS.get(code);
		if (platform != null && text.isBlank()) {
			return result(false, "消息内容是空的，没有发送。" + platform + "群机器人不接收空消息", bind, text);
		}
		try {
			url = outbound.guard(url);
		} catch (ServiceException e) {
			return result(false, "Webhook 地址不允许访问内网或元数据地址。请换成公网地址，或让管理员加入出站白名单", bind, text);
		}
		Reply reply;
		try {
			boolean readBody = platform != null;
			reply = outboundWebClients.create()
				.post().uri(URI.create(url))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(payload(code, text, vars))
				.exchangeToMono(resp -> {
					int status = resp.statusCode().value();
					// 回复读不下来（比如大得离谱）时按空回复算：状态码已经拿到了，不能说成「没有发出去」
					return readBody
						? resp.bodyToMono(String.class).onErrorReturn("").defaultIfEmpty("").map(body -> new Reply(status, body))
						: resp.releaseBody().thenReturn(new Reply(status, ""));
				})
				.block(SEND_TIMEOUT);
		} catch (Exception e) {
			log.warn("[ai-channel] send failed: channel={} type={} host={} cause={}",
				bind.getId(), code, hostOf(url), e.getClass().getSimpleName());
			reply = null;
		}
		if (reply == null) {
			return result(false, "这次没有发出去。请检查 Webhook 地址能不能从服务器访问，再试一次", bind, text);
		}
		return platform == null
			? webhookResult(bind, text, reply)
			: robotResult(bind, text, platform, url, reply);
	}

	/** 发给对方的消息体：企微、钉钉是 msgtype + text.content，飞书是 msg_type + content.text，Webhook 沿用自己的三个字段。 */
	private static Map<String, Object> payload(String code, String text, Map<String, Object> vars) {
		return switch (code) {
			case "wecom", "dingtalk" -> Map.of("msgtype", "text", "text", Map.of("content", text));
			case "feishu" -> Map.of("msg_type", "text", "content", Map.of("text", text));
			default -> Map.of("channel", code, "content", text, "vars", vars == null ? Map.of() : vars);
		};
	}

	private static Map<String, Object> webhookResult(AiChannelBind bind, String text, Reply reply) {
		int status = reply.status();
		if (status >= 200 && status < 300) {
			return result(true, "已发出。请到 Webhook 那边确认有没有收到", bind, text, status, null);
		}
		String message = status >= 300 && status < 400
			? "对方返回了跳转（HTTP " + status + "），这里不会跟着跳，消息没有送达。请把地址换成跳转后的最终地址"
			: "对方返回了 HTTP " + status + "，消息没有送达。请检查 Webhook 地址和接收方的服务";
		return result(false, message, bind, text, status, null);
	}

	private static Map<String, Object> robotResult(AiChannelBind bind, String text, String platform, String url, Reply reply) {
		int status = reply.status();
		boolean accepted = status >= 200 && status < 300;
		Verdict verdict = readVerdict(bind.getChannelCode(), reply.body());
		if (verdict.code() != null && verdict.code() != 0) {
			String said = scrub(verdict.message(), url);
			return result(false, platform + "没有收下这条消息" + (said.isEmpty() ? "" : "：" + said)
				+ "（错误码 " + verdict.code() + "）", bind, text, status, String.valueOf(verdict.code()));
		}
		if (verdict.code() != null && accepted) {
			return result(true, "已发出，" + platform + "已收下这条消息。请到群里确认", bind, text, status, null);
		}
		String hint = "请检查地址是不是" + platform + "群机器人的 Webhook 地址";
		return result(false, accepted
			? "对方回复的不是" + platform + "群机器人的格式，没法确认消息有没有送达。" + hint
			: "对方返回了 HTTP " + status + "，消息没有送达。" + hint, bind, text, status, null);
	}

	/** 企微、钉钉的结果在 errcode / errmsg，飞书在 code / msg（老版只有 StatusCode / StatusMessage）。 */
	private static Verdict readVerdict(String channelCode, String body) {
		JsonNode root;
		try {
			root = JSON.readTree(body == null ? "" : body);
		} catch (Exception e) {
			return new Verdict(null, "");
		}
		if (root == null || !root.isObject()) {
			return new Verdict(null, "");
		}
		if ("feishu".equals(channelCode)) {
			boolean current = root.has("code");
			return new Verdict(asCode(root.get(current ? "code" : "StatusCode")),
				root.path(current ? "msg" : "StatusMessage").asText(""));
		}
		return new Verdict(asCode(root.get("errcode")), root.path("errmsg").asText(""));
	}

	private static Long asCode(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isIntegralNumber()) {
			return node.asLong();
		}
		String text = node.asText("").trim();
		return text.matches("-?\\d{1,18}") ? Long.valueOf(text) : null;
	}

	/** 平台的说明给人看之前：压成一行，盖掉里面出现的地址和 key，截到 200 字。 */
	static String scrub(String message, String url) {
		if (message == null) {
			return "";
		}
		String text = message.replaceAll("\\s+", " ").trim();
		for (String secret : secretsOf(url)) {
			text = text.replace(secret, MASK);
		}
		return text.length() > REPLY_TEXT_LIMIT ? text.substring(0, REPLY_TEXT_LIMIT) + "…" : text;
	}

	/** 地址里可能是凭据的部分：整条地址、整段参数，以及带数字的参数值和路径最后一段（8 个字符以上，免得把普通词也盖掉）。 */
	private static List<String> secretsOf(String url) {
		List<String> parts = new ArrayList<>();
		Matcher matcher = url == null ? null : ORIGIN.matcher(url);
		if (matcher == null || !matcher.matches()) {
			return parts;
		}
		parts.add(url);
		String rest = matcher.group(3);
		int mark = rest.indexOf('?');
		String path = mark < 0 ? rest : rest.substring(0, mark);
		String query = mark < 0 ? "" : rest.substring(mark + 1);
		if (query.length() >= 8) {
			parts.add(query);
		}
		List<String> tokens = new ArrayList<>();
		for (String pair : query.split("&")) {
			tokens.add(pair.substring(pair.indexOf('=') + 1));
		}
		tokens.add(path.substring(path.lastIndexOf('/') + 1));
		for (String token : tokens) {
			if (token.length() >= 8 && token.chars().anyMatch(Character::isDigit)) {
				parts.add(token);
			}
		}
		parts.sort(Comparator.comparingInt(String::length).reversed());
		return parts;
	}

	/** 给人看的地址：只留协议、主机和末 4 位，中间用 **** 盖住；看不出是地址的内容整个盖住。 */
	static String maskAddress(String sample) {
		if (sample == null || sample.isBlank()) {
			return sample;
		}
		Matcher matcher = ORIGIN.matcher(sample.trim());
		if (!matcher.matches()) {
			return MASK;
		}
		String authority = matcher.group(2);
		int at = authority.lastIndexOf('@');
		String host = at < 0 ? authority : MASK + "@" + authority.substring(at + 1);
		String rest = matcher.group(3);
		if (rest.isEmpty() || "/".equals(rest)) {
			return matcher.group(1) + host + rest;
		}
		String tail = rest.length() >= TAIL_MIN_LENGTH ? rest.substring(rest.length() - 4) : "";
		return matcher.group(1) + host + "/" + MASK + tail;
	}

	private static AiChannelBind masked(AiChannelBind row) {
		row.setParamsSample(maskAddress(row.getParamsSample()));
		return row;
	}

	private static String hostOf(String url) {
		try {
			String host = URI.create(url).getHost();
			return host == null ? "" : host;
		} catch (Exception e) {
			return "";
		}
	}

	private void requireUnique(String alias, Long id) {
		QueryWrapper query = QueryWrapper.create().eq("alias", alias);
		if (id != null) {
			query.ne("id", id);
		}
		if (mapper.selectCountByQuery(query) > 0) {
			throw new ServiceException("已经有叫「" + alias + "」的渠道。请换一个名称，或打开原来那个");
		}
	}

	private AiChannelBind require(Long id) {
		AiChannelBind row = mapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException(AiConstants.MSG_CHANNEL_MISSING);
		}
		return row;
	}

	private static String extractUrl(String sample) {
		if (sample == null || sample.isBlank()) {
			return null;
		}
		String text = sample.trim();
		if (WHOLE_URL.matcher(text).matches()) {
			return text;
		}
		Matcher matcher = URL.matcher(text);
		if (!matcher.find()) {
			return null;
		}
		String url = matcher.group().trim();
		return url.startsWith("http") ? url : null;
	}

	private static Map<String, Object> result(boolean ok, String message, AiChannelBind bind, String content) {
		return result(ok, message, bind, content, 0, null);
	}

	/** httpStatus 为 0 表示没有收到对方的回复（没发，或者发了没连上）；errorCode 是平台回复里的错误码。 */
	private static Map<String, Object> result(boolean ok, String message, AiChannelBind bind, String content,
											  int httpStatus, String errorCode) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("ok", ok);
		data.put("sent", ok);
		data.put("message", message);
		data.put("channelCode", bind.getChannelCode());
		data.put("alias", bind.getAlias() == null ? "" : bind.getAlias());
		data.put("content", content == null ? "" : content);
		data.put("httpSent", httpStatus > 0);
		data.put("httpStatus", httpStatus);
		data.put("errorCode", errorCode == null ? "" : errorCode);
		data.put("httpResp", "");
		return data;
	}

	private record Reply(int status, String body) {
	}

	/** 平台回复里的结果码和说明；回复不是这个平台的格式时 code 为 null */
	private record Verdict(Long code, String message) {
	}
}
