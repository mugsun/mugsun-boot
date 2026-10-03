package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiChannelBind;
import com.mugsun.boot.ai.mapper.AiChannelBindMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AiChannelBizService {

	private final AiModuleService moduleService;
	private final AiChannelBindMapper mapper;

	public AiChannelBizService(AiModuleService moduleService, AiChannelBindMapper mapper) {
		this.moduleService = moduleService;
		this.mapper = mapper;
	}

	public Page<AiChannelBind> page(long pageNum, long pageSize) {
		moduleService.requireEnabled();
		return mapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false));
	}

	public AiChannelBind submit(AiChannelBind body) {
		moduleService.requireEnabled();
		if (body.getChannelCode() == null || body.getChannelCode().isBlank()) {
			throw new ServiceException("请选择渠道");
		}
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getStatus() == null) {
				body.setStatus(AiConstants.STATUS_ENABLE);
			}
			mapper.insert(body);
		} else {
			body.sanitizeForUpdate();
			mapper.update(body);
		}
		return body;
	}

	public void remove(List<Long> ids) {
		moduleService.requireEnabled();
		for (Long id : ids) {
			require(id);
			mapper.deleteById(id);
		}
	}

	public Map<String, Object> debug(Long id) {
		return send(id, "debug ping", Map.of());
	}

	/** 渠道发送：记流水；若绑定了 webhook 参数样例则尝试 HTTP POST */
	public Map<String, Object> send(Long id, String content, Map<String, Object> vars) {
		moduleService.requireEnabled();
		AiChannelBind bind = require(id);
		String sample = bind.getParamsSample();
		boolean httpSent = false;
		String httpResp = "";
		if (sample != null && sample.contains("http")) {
			try {
				String url = sample.replaceAll("(?s).*?(https?://\\S+).*", "$1").trim();
				if (url.startsWith("http")) {
					httpResp = org.springframework.web.reactive.function.client.WebClient.create()
						.post().uri(url)
						.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
						.bodyValue(Map.of(
							"channel", bind.getChannelCode(),
							"content", content == null ? "" : content,
							"vars", vars == null ? Map.of() : vars))
						.retrieve().bodyToMono(String.class)
						.block(java.time.Duration.ofSeconds(15));
					httpSent = true;
				}
			} catch (Exception e) {
				httpResp = e.getMessage() == null ? "send failed" : e.getMessage();
			}
		}
		boolean attempted = sample != null && sample.contains("http");
		String message = httpSent ? "调试成功" : (attempted
			? (httpResp == null || httpResp.isBlank() ? "调试未发出" : httpResp)
			: "未配置 Webhook");
		return Map.of(
			"ok", httpSent,
			"sent", httpSent,
			"message", message,
			"channelCode", bind.getChannelCode(),
			"alias", bind.getAlias() == null ? "" : bind.getAlias(),
			"content", content == null ? "" : content,
			"httpSent", httpSent,
			"httpResp", httpResp == null ? "" : httpResp);
	}

	private AiChannelBind require(Long id) {
		AiChannelBind b = mapper.selectOneById(id);
		if (b == null) {
			throw new ServiceException(AiConstants.MSG_CHANNEL_MISSING);
		}
		return b;
	}
}
