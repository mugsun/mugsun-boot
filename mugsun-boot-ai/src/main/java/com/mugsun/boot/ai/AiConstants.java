package com.mugsun.boot.ai;

/**
 * AI 模块常量：开关键、权限码、消息文案。
 */
public final class AiConstants {

	public static final String PARAM_MODULE_ENABLED = "ai.module.enabled";

	public static final String MSG_DISABLED = "AI 模块未启用";
	public static final String MSG_MODEL_MISSING = "模型不存在";
	public static final String MSG_MODEL_INACTIVE = "模型未通过探测激活，不可用";
	public static final String MSG_BUILTIN_LOCKED = "内置配置不可删除";
	public static final String MSG_VECTOR_MISSING = "向量库不存在";
	public static final String MSG_DS_MISSING = "数据源不存在";
	public static final String MSG_PROMPT_MISSING = "提示词不存在";
	public static final String MSG_SESSION_MISSING = "会话不存在";
	public static final String MSG_SECRET_MISSING = "密钥不存在";
	public static final String MSG_APP_MISSING = "应用不存在";
	public static final String MSG_KB_MISSING = "知识库不存在";
	public static final String MSG_DATASET_MISSING = "问数智能体不存在";
	public static final String MSG_DASHBOARD_MISSING = "仪表盘不存在";
	public static final String MSG_MCP_MISSING = "MCP 工具不存在";
	public static final String MSG_CHANNEL_MISSING = "渠道绑定不存在";
	public static final String MSG_SQL_UNSAFE = "仅允许只读 SELECT 查询";
	public static final String MSG_SECRET_ONCE = "明文密钥仅创建时返回一次";
	public static final String MSG_OPENAPI_AUTH = "无效的 API Key";
	public static final String MSG_QUOTA_EXCEEDED = "已超出配额";

	public static final int STATUS_ENABLE = 1;
	public static final int STATUS_DISABLE = 0;
	public static final int FLAG_YES = 1;
	public static final int FLAG_NO = 0;

	public static final String MODEL_TYPE_CHAT = "chat";
	public static final String MODEL_TYPE_EMBEDDING = "embedding";

	public static final String SOURCE_ASSISTANT = "assistant";
	public static final String SOURCE_APP = "app";
	public static final String SOURCE_DATASET = "dataset";
	public static final String SOURCE_GENERATOR = "generator";
	public static final String SOURCE_OPENAPI = "openapi";

	public static final String ROLE_USER = "user";
	public static final String ROLE_ASSISTANT = "assistant";
	public static final String ROLE_SYSTEM = "system";
	public static final String ROLE_TOOL = "tool";

	public static final String MSG_STATUS_STREAMING = "streaming";
	public static final String MSG_STATUS_DONE = "done";
	public static final String MSG_STATUS_CANCELED = "canceled";
	public static final String MSG_STATUS_FAILED = "failed";

	public static final String SSE_CHUNK = "chunk";
	public static final String SSE_THINK = "think";
	public static final String SSE_USAGE = "usage";
	public static final String SSE_DONE = "done";
	public static final String SSE_ERROR = "error";
	public static final String SSE_REFS = "refs";

	public static final String SECRET_PREFIX = "sk-";

	// ---- permissions ----
	public static final String PERM_ASSISTANT_LIST = "ai:assistant:list";
	public static final String PERM_ASSISTANT_CHAT = "ai:assistant:chat";
	public static final String PERM_ASSISTANT_REMOVE = "ai:assistant:remove";
	public static final String PERM_ASSISTANT_EXPORT = "ai:assistant:export";

	public static final String PERM_APP_LIST = "ai:app:list";
	public static final String PERM_APP_SAVE = "ai:app:save";
	public static final String PERM_APP_REMOVE = "ai:app:remove";
	public static final String PERM_APP_COPY = "ai:app:copy";
	public static final String PERM_APP_IMPORT = "ai:app:import";
	public static final String PERM_APP_EXPORT = "ai:app:export";
	public static final String PERM_APP_RUN = "ai:app:run";
	public static final String PERM_APP_PUBLISH = "ai:app:publish";
	public static final String PERM_APP_DESIGN = "ai:app:design";

	public static final String PERM_KB_LIST = "ai:kb:list";
	public static final String PERM_KB_SAVE = "ai:kb:save";
	public static final String PERM_KB_REMOVE = "ai:kb:remove";
	public static final String PERM_KB_COPY = "ai:kb:copy";
	public static final String PERM_KB_TEST = "ai:kb:test";
	public static final String PERM_KB_DETAIL = "ai:kb:detail";
	public static final String PERM_KB_ASSET_UPLOAD = "ai:kb-asset:upload";
	public static final String PERM_KB_ASSET_REMOVE = "ai:kb-asset:remove";
	public static final String PERM_KB_SEG_EDIT = "ai:kb-seg:edit";
	public static final String PERM_KB_SEG_VECTOR = "ai:kb-seg:vector";
	public static final String PERM_KB_HIT_TEST = "ai:kb:hit-test";

	public static final String PERM_DATASET_LIST = "ai:dataset:list";
	public static final String PERM_DATASET_SAVE = "ai:dataset:save";
	public static final String PERM_DATASET_REMOVE = "ai:dataset:remove";
	public static final String PERM_DATASET_COPY = "ai:dataset:copy";
	public static final String PERM_DATASET_EXPORT = "ai:dataset:export";
	public static final String PERM_DATASET_CONFIG = "ai:dataset:config";
	public static final String PERM_DATASET_RUN = "ai:dataset:run";
	public static final String PERM_DATASET_ANALYZE = "ai:dataset:analyze";
	public static final String PERM_DATASET_PREDICT = "ai:dataset:predict";

	public static final String PERM_DASHBOARD_DESIGN = "ai:dashboard:design";

	public static final String PERM_MODEL_LIST = "ai:model:list";
	public static final String PERM_MODEL_SAVE = "ai:model:save";
	public static final String PERM_MODEL_REMOVE = "ai:model:remove";
	public static final String PERM_MODEL_DEFAULT = "ai:model:default";
	public static final String PERM_MODEL_TEST = "ai:model:test";

	public static final String PERM_PROMPT_LIST = "ai:prompt:list";
	public static final String PERM_PROMPT_SAVE = "ai:prompt:save";
	public static final String PERM_PROMPT_REMOVE = "ai:prompt:remove";
	public static final String PERM_PROMPT_OPTIMIZE = "ai:prompt:optimize";
	public static final String PERM_PROMPT_TRY = "ai:prompt:try";
	public static final String PERM_PROMPT_VERSION = "ai:prompt:version";

	public static final String PERM_MCP_LIST = "ai:mcp:list";
	public static final String PERM_MCP_SAVE = "ai:mcp:save";
	public static final String PERM_MCP_REMOVE = "ai:mcp:remove";
	public static final String PERM_MCP_PARSE = "ai:mcp:parse";
	public static final String PERM_MCP_DEBUG = "ai:mcp:debug";
	public static final String PERM_MCP_LOCK = "ai:mcp:lock";
	public static final String PERM_MCP_DEFAULT = "ai:mcp:default";
	public static final String PERM_MCP_SERVER = "ai:mcp:server";

	public static final String PERM_VECTOR_LIST = "ai:vector:list";
	public static final String PERM_VECTOR_SAVE = "ai:vector:save";
	public static final String PERM_VECTOR_REMOVE = "ai:vector:remove";
	public static final String PERM_VECTOR_TEST = "ai:vector:test";

	public static final String PERM_DATASOURCE_LIST = "ai:datasource:list";
	public static final String PERM_DATASOURCE_SAVE = "ai:datasource:save";
	public static final String PERM_DATASOURCE_REMOVE = "ai:datasource:remove";
	public static final String PERM_DATASOURCE_TEST = "ai:datasource:test";

	public static final String PERM_CHANNEL_LIST = "ai:channel:list";
	public static final String PERM_CHANNEL_SAVE = "ai:channel:save";
	public static final String PERM_CHANNEL_REMOVE = "ai:channel:remove";
	public static final String PERM_CHANNEL_DEBUG = "ai:channel:debug";

	public static final String PERM_SECRET_LIST = "ai:secret:list";
	public static final String PERM_SECRET_SAVE = "ai:secret:save";
	public static final String PERM_SECRET_REMOVE = "ai:secret:remove";
	public static final String PERM_SECRET_STATUS = "ai:secret:status";

	public static final String PERM_CONVERSATION_LIST = "ai:conversation:list";
	public static final String PERM_CONVERSATION_DETAIL = "ai:conversation:detail";
	public static final String PERM_CONVERSATION_EXPORT = "ai:conversation:export";

	public static final String PERM_BILLING_LIST = "ai:billing:list";
	public static final String PERM_BILLING_EXPORT = "ai:billing:export";

	public static final String PERM_QUOTA_LIST = "ai:quota:list";
	public static final String PERM_QUOTA_SAVE = "ai:quota:save";

	public static final String PERM_GEN_MINDMAP = "ai:gen-mindmap:use";
	public static final String PERM_GEN_POSTER = "ai:gen-poster:use";
	public static final String PERM_GEN_ARTICLE = "ai:gen-article:use";
	public static final String PERM_GEN_PRODUCT = "ai:gen-product:use";
	public static final String PERM_GEN_MARKETING = "ai:gen-marketing:use";
	public static final String PERM_GEN_SVG = "ai:gen-svg:use";
	public static final String PERM_GEN_LAYOUT = "ai:gen-layout:use";

	private AiConstants() {
	}
}
