package com.mugsun.boot.ai.entity;

import com.mugsun.boot.common.crypto.Sm4TypeHandler;
import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.annotation.Column;

@Table("ai_mcp_tool")
public class AiMcpTool extends BaseEntity {

	private String tenantId;
	private String name;
	private String description;
	private String category;
	private String transport;
	private String sseUrl;
	private String headersJson;
	private String command;
	private String envJson;
	private String toolsJson;
	@Column(typeHandler = Sm4TypeHandler.class)
	private String apiKey;
	private String roleWhitelist;
	private Integer lockFlag;
	private Integer defaultFlag;
	private Integer status;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getCategory() {
		return category;
	}

	public void setCategory(String category) {
		this.category = category;
	}

	public String getTransport() {
		return transport;
	}

	public void setTransport(String transport) {
		this.transport = transport;
	}

	public String getSseUrl() {
		return sseUrl;
	}

	public void setSseUrl(String sseUrl) {
		this.sseUrl = sseUrl;
	}

	public String getHeadersJson() {
		return headersJson;
	}

	public void setHeadersJson(String headersJson) {
		this.headersJson = headersJson;
	}

	public String getCommand() {
		return command;
	}

	public void setCommand(String command) {
		this.command = command;
	}

	public String getEnvJson() {
		return envJson;
	}

	public void setEnvJson(String envJson) {
		this.envJson = envJson;
	}

	public String getToolsJson() {
		return toolsJson;
	}

	public void setToolsJson(String toolsJson) {
		this.toolsJson = toolsJson;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getRoleWhitelist() {
		return roleWhitelist;
	}

	public void setRoleWhitelist(String roleWhitelist) {
		this.roleWhitelist = roleWhitelist;
	}

	public Integer getLockFlag() {
		return lockFlag;
	}

	public void setLockFlag(Integer lockFlag) {
		this.lockFlag = lockFlag;
	}

	public Integer getDefaultFlag() {
		return defaultFlag;
	}

	public void setDefaultFlag(Integer defaultFlag) {
		this.defaultFlag = defaultFlag;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	/** 页面用 SSE / HTTP / STDIO，库里存小写 transport。 */
	public String getProtocolType() {
		if (transport == null || transport.isBlank()) {
			return null;
		}
		return switch (transport.trim().toLowerCase()) {
			case "sse" -> "SSE";
			case "http", "streamable" -> "HTTP";
			case "stdio" -> "STDIO";
			default -> transport;
		};
	}

	public void setProtocolType(String protocolType) {
		if (protocolType == null || protocolType.isBlank()) {
			return;
		}
		this.transport = switch (protocolType.trim().toUpperCase()) {
			case "SSE" -> "sse";
			case "HTTP", "STREAMABLE HTTP" -> "http";
			case "STDIO" -> "stdio";
			default -> protocolType.trim().toLowerCase();
		};
	}
}
