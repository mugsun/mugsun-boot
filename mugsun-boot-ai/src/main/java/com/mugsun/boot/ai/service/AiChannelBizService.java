package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiChannelBind;
import com.mugsun.boot.ai.mapper.AiChannelBindMapper;
import com.mugsun.boot.ai.support.AiOutboundProperties;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiChannelBizService {

	private static final Set<String> CODES = Set.of("wecom", "dingtalk", "feishu", "webhook");
	private static final Pattern URL = Pattern.compile("https?://\\S+");
	private static final Pattern WHOLE_URL = Pattern.compile("^https?://[^\\s<>\"']{1,2000}$");

	private final AiModuleService moduleService;
	private final AiChannelBindMapper mapper;
	private final AiOutboundProperties outbound;

	public AiChannelBizService(AiModuleService moduleService, AiChannelBindMapper mapper,
							   AiOutboundProperties outbound) {
		this.moduleService = moduleService;
		this.mapper = mapper;
		this.outbound = outbound;
	}

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
		return mapper.paginate(pageNum, pageSize, query);
	}

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
		String sample = body.getParamsSample() == null ? "" : body.getParamsSample().trim();
		if (!sample.isEmpty() && !WHOLE_URL.matcher(sample).matches()) {
			throw new ServiceException("Webhook 需要以 http:// 或 https:// 开头，并且不要带空格。留空表示先不调试");
		}
		if (!sample.isEmpty()) {
			outbound.guard(sample);
		}
		if (body.getStatus() != null && body.getStatus() != 0 && body.getStatus() != 1) {
			throw new ServiceException("状态请选择启用或停用");
		}
		requireUnique(alias, body.getId());
		int status = body.getStatus() == null ? AiConstants.STATUS_ENABLE : body.getStatus();
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			body.setAlias(alias);
			body.setChannelCode(code);
			body.setTemplateCode(template.isEmpty() ? null : template);
			body.setParamsSample(sample.isEmpty() ? null : sample);
			body.setStatus(status);
			mapper.insert(body);
			return body;
		}
		AiChannelBind db = require(body.getId());
		db.setAlias(alias);
		db.setChannelCode(code);
		db.setTemplateCode(template.isEmpty() ? null : template);
		db.setParamsSample(sample.isEmpty() ? null : sample);
		db.setStatus(status);
		mapper.update(db);
		return db;
	}

	public List<AiChannelBind> remove(List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的渠道");
		}
		List<AiChannelBind> rows = new java.util.ArrayList<>();
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

	/** 渠道发送：停用或没填地址时不向外发；失败只返回下一步，不带连接原文。 */
	public Map<String, Object> send(Long id, String content, Map<String, Object> vars) {
		moduleService.requireEnabled();
		AiChannelBind bind = require(id);
		if (bind.getStatus() != null && bind.getStatus() == 0) {
			return result(false, "这个渠道已停用。请先启用后再调试", bind, content);
		}
		String url = extractUrl(bind.getParamsSample());
		if (url == null) {
			return result(false, "还没有填写 Webhook。请先编辑这个渠道，填上能接收消息的地址", bind, content);
		}
		try {
			url = outbound.guard(url);
		} catch (ServiceException e) {
			return result(false, "Webhook 地址不允许访问内网或元数据地址。请换成公网地址，或让管理员加入出站白名单", bind, content);
		}
		try {
			WebClient.create()
				.post().uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of(
					"channel", bind.getChannelCode(),
					"content", content == null ? "" : content,
					"vars", vars == null ? Map.of() : vars))
				.retrieve().bodyToMono(String.class)
				.block(Duration.ofSeconds(8));
			return result(true, "已发出。请到 Webhook 那边确认有没有收到", bind, content);
		} catch (Exception ignored) {
			return result(false, "这次没有发出去。请检查 Webhook 地址能不能从本机访问，再试一次", bind, content);
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
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("ok", ok);
		data.put("sent", ok);
		data.put("message", message);
		data.put("channelCode", bind.getChannelCode());
		data.put("alias", bind.getAlias() == null ? "" : bind.getAlias());
		data.put("content", content == null ? "" : content);
		data.put("httpSent", ok);
		data.put("httpResp", "");
		return data;
	}
}
