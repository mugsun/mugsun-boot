-- Mugsun AI 可选模块（达梦简化版）
-- 跳过 vector 扩展；类型尽量兼容；菜单写法对齐 PG


-- ========== AI 模块业务表 ==========

CREATE TABLE ai_model (
	id              BIGINT PRIMARY KEY,
	tenant_id       VARCHAR(12),
	model_name      VARCHAR(64) NOT NULL,
	model_type      VARCHAR(16) NOT NULL,
	provider        VARCHAR(32),
	icon            VARCHAR(255),
	base_url        VARCHAR(255),
	model_code      VARCHAR(64),
	api_key         VARCHAR(512),
	secret_key      VARCHAR(512),
	dimensions      INT,
	price_input     NUMERIC(10,6),
	price_output    NUMERIC(10,6),
	params_json     TEXT,
	vision_flag     INT NOT NULL DEFAULT 0,
	activate_flag   INT NOT NULL DEFAULT 0,
	default_flag    INT NOT NULL DEFAULT 0,
	builtin_flag    INT NOT NULL DEFAULT 0,
	remark          VARCHAR(255),
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_ai_model_tenant ON ai_model (tenant_id);

CREATE TABLE ai_prompt (
	id              BIGINT PRIMARY KEY,
	tenant_id       VARCHAR(12),
	name            VARCHAR(64) NOT NULL,
	category        VARCHAR(32),
	scene           VARCHAR(64),
	content         TEXT,
	variables_json  TEXT,
	version         INT NOT NULL DEFAULT 1,
	status          INT NOT NULL DEFAULT 1,
	remark          VARCHAR(255),
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_ai_prompt_tenant ON ai_prompt (tenant_id);

CREATE TABLE ai_mcp_tool (
	id              BIGINT PRIMARY KEY,
	tenant_id       VARCHAR(12),
	name            VARCHAR(64) NOT NULL,
	description     VARCHAR(255),
	category        VARCHAR(32),
	transport       VARCHAR(16),
	sse_url         VARCHAR(512),
	headers_json    TEXT,
	command         TEXT,
	env_json        TEXT,
	tools_json      TEXT,
	api_key         VARCHAR(512),
	role_whitelist  VARCHAR(512),
	lock_flag       INT NOT NULL DEFAULT 0,
	default_flag    INT NOT NULL DEFAULT 0,
	status          INT NOT NULL DEFAULT 1,
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);

CREATE TABLE ai_vector_store (
	id              BIGINT PRIMARY KEY,
	tenant_id       VARCHAR(12),
	name            VARCHAR(64) NOT NULL,
	store_type      VARCHAR(16) NOT NULL,
	host            VARCHAR(128),
	port            INT,
	database_name   VARCHAR(64),
	table_name      VARCHAR(64),
	collection      VARCHAR(64),
	db_index        INT,
	username        VARCHAR(64),
	password        VARCHAR(512),
	dimensions      INT,
	metric          VARCHAR(16),
	index_type      VARCHAR(16),
	builtin_flag    INT NOT NULL DEFAULT 0,
	remark          VARCHAR(255),
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);

CREATE TABLE ai_datasource (
	id                      BIGINT PRIMARY KEY,
	tenant_id               VARCHAR(12),
	name                    VARCHAR(64) NOT NULL,
	db_type                 VARCHAR(16) NOT NULL,
	driver_class            VARCHAR(128),
	jdbc_url                VARCHAR(512),
	username                VARCHAR(64),
	password                VARCHAR(512),
	read_only_flag          INT NOT NULL DEFAULT 1,
	table_whitelist         TEXT,
	pool_initial_size       INT DEFAULT 5,
	pool_max_active         INT DEFAULT 20,
	pool_max_wait           INT DEFAULT 60000,
	pool_validation_query   VARCHAR(64) DEFAULT 'SELECT 1',
	remark                  VARCHAR(255),
	create_time             TIMESTAMP,
	update_time             TIMESTAMP,
	is_deleted              INT NOT NULL DEFAULT 0
);

CREATE TABLE ai_channel_bind (
	id              BIGINT PRIMARY KEY,
	tenant_id       VARCHAR(12),
	channel_code    VARCHAR(32) NOT NULL,
	template_code   VARCHAR(64),
	alias           VARCHAR(64),
	params_sample   TEXT,
	status          INT NOT NULL DEFAULT 1,
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);

CREATE TABLE ai_app (
	id                  BIGINT PRIMARY KEY,
	tenant_id           VARCHAR(12),
	icon                VARCHAR(255),
	name                VARCHAR(64) NOT NULL,
	description         VARCHAR(200),
	app_type            VARCHAR(16) NOT NULL,
	dsl                 TEXT,
	model_id            BIGINT,
	params_json         TEXT,
	system_prompt       TEXT,
	opening_remark      TEXT,
	preset_questions    TEXT,
	share_token         VARCHAR(64),
	share_status        INT DEFAULT 0,
	status              INT NOT NULL DEFAULT 1,
	version             INT NOT NULL DEFAULT 1,
	create_time         TIMESTAMP,
	update_time         TIMESTAMP,
	is_deleted          INT NOT NULL DEFAULT 0
);

CREATE TABLE ai_app_run (
	id              BIGINT PRIMARY KEY,
	app_id          BIGINT NOT NULL,
	tenant_id       VARCHAR(12),
	user_id         BIGINT,
	trace_id        VARCHAR(64),
	input_json      TEXT,
	output_json     TEXT,
	status          VARCHAR(16),
	error_code      VARCHAR(32),
	error_msg       VARCHAR(512),
	duration_ms     BIGINT,
	total_tokens    INT,
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_ai_app_run_app ON ai_app_run (app_id);

CREATE TABLE ai_app_node_run (
	id                  BIGINT PRIMARY KEY,
	run_id              BIGINT NOT NULL,
	node_id             VARCHAR(64),
	node_type           VARCHAR(24),
	node_name           VARCHAR(64),
	input_json          TEXT,
	output_json         TEXT,
	status              VARCHAR(16),
	error_msg           VARCHAR(512),
	duration_ms         BIGINT,
	prompt_tokens       INT,
	completion_tokens   INT,
	retry_count         INT DEFAULT 0,
	create_time         TIMESTAMP,
	update_time         TIMESTAMP,
	is_deleted          INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_ai_app_node_run_run ON ai_app_node_run (run_id);

CREATE TABLE ai_session (
	id              BIGINT PRIMARY KEY,
	tenant_id       VARCHAR(12),
	user_id         BIGINT,
	title           VARCHAR(128),
	source          VARCHAR(16),
	biz_id          BIGINT,
	model_id        BIGINT,
	message_count   INT DEFAULT 0,
	total_tokens    INT DEFAULT 0,
	last_time       TIMESTAMP,
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_ai_session_user ON ai_session (tenant_id, user_id);

CREATE TABLE ai_message (
	id                  BIGINT PRIMARY KEY,
	session_id          BIGINT NOT NULL,
	tenant_id           VARCHAR(12),
	user_id             BIGINT,
	role                VARCHAR(16) NOT NULL,
	content             TEXT,
	think_content       TEXT,
	attachments_json    TEXT,
	model_id            BIGINT,
	prompt_tokens       INT,
	completion_tokens   INT,
	total_tokens        INT,
	amount              NUMERIC(12,6),
	ip                  VARCHAR(64),
	request_id          VARCHAR(64),
	rag_refs_json       TEXT,
	status              VARCHAR(16),
	create_time         TIMESTAMP,
	update_time         TIMESTAMP,
	is_deleted          INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_ai_message_session ON ai_message (session_id);

CREATE TABLE ai_knowledge (
	id                  BIGINT PRIMARY KEY,
	tenant_id           VARCHAR(12),
	icon                VARCHAR(255),
	name                VARCHAR(64) NOT NULL,
	description         VARCHAR(200),
	vector_store_id     BIGINT,
	embedding_model_id  BIGINT,
	dimensions          INT,
	retrieval_mode      VARCHAR(16) DEFAULT 'hybrid',
	top_k               INT DEFAULT 6,
	min_score           NUMERIC(3,2) DEFAULT 0.70,
	rerank_flag         INT DEFAULT 0,
	rerank_model_id     BIGINT,
	status              INT NOT NULL DEFAULT 1,
	create_time         TIMESTAMP,
	update_time         TIMESTAMP,
	is_deleted          INT NOT NULL DEFAULT 0
);

CREATE TABLE ai_knowledge_asset (
	id                  BIGINT PRIMARY KEY,
	knowledge_id        BIGINT NOT NULL,
	tenant_id           VARCHAR(12),
	file_name           VARCHAR(255),
	file_url            VARCHAR(512),
	file_size           BIGINT,
	file_type           VARCHAR(16),
	segment_type        VARCHAR(24),
	segment_length      INT,
	segment_overlap     INT,
	segment_symbol      VARCHAR(16),
	keep_format_flag    INT DEFAULT 0,
	extract_meta_flag   INT DEFAULT 0,
	clean_rule          VARCHAR(32) DEFAULT 'strip_control',
	segment_count       INT DEFAULT 0,
	vector_status       VARCHAR(16) DEFAULT 'pending',
	progress            INT DEFAULT 0,
	fail_reason         VARCHAR(512),
	vector_strategy     VARCHAR(16),
	vector_priority     VARCHAR(8),
	batch_size          INT DEFAULT 32,
	concurrency         INT DEFAULT 4,
	create_time         TIMESTAMP,
	update_time         TIMESTAMP,
	is_deleted          INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_ai_kb_asset_kb ON ai_knowledge_asset (knowledge_id);

CREATE TABLE ai_knowledge_segment (
	id              BIGINT PRIMARY KEY,
	knowledge_id    BIGINT NOT NULL,
	asset_id        BIGINT,
	tenant_id       VARCHAR(12),
	seq             INT,
	content         TEXT,
	aux_content     TEXT,
	meta_json       TEXT,
	token_count     INT,
	embedding_id    VARCHAR(64),
	vector_status   VARCHAR(16),
	enabled         INT NOT NULL DEFAULT 1,
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_ai_kb_seg_kb ON ai_knowledge_segment (knowledge_id);

CREATE TABLE ai_dataset (
	id              BIGINT PRIMARY KEY,
	tenant_id       VARCHAR(12),
	icon            VARCHAR(255),
	name            VARCHAR(64) NOT NULL,
	description     VARCHAR(200),
	dataset_type    VARCHAR(16),
	model_id        BIGINT,
	enabled         INT NOT NULL DEFAULT 1,
	max_rows        INT DEFAULT 1000,
	timeout_ms      INT DEFAULT 30000,
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);

CREATE TABLE ai_dataset_table (
	id              BIGINT PRIMARY KEY,
	dataset_id      BIGINT NOT NULL,
	datasource_id   BIGINT,
	table_name      VARCHAR(64) NOT NULL,
	table_alias     VARCHAR(64),
	table_comment   VARCHAR(255),
	fields_json     TEXT,
	relations_json  TEXT,
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_ai_dataset_table_ds ON ai_dataset_table (dataset_id);

CREATE TABLE ai_terminology (
	id              BIGINT PRIMARY KEY,
	tenant_id       VARCHAR(12),
	name            VARCHAR(64) NOT NULL,
	term_type       VARCHAR(16),
	content         TEXT,
	synonyms        VARCHAR(512),
	remark          VARCHAR(255),
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);

CREATE TABLE ai_dataset_term (
	id              BIGINT PRIMARY KEY,
	dataset_id      BIGINT NOT NULL,
	terminology_id  BIGINT NOT NULL,
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);

CREATE TABLE ai_dashboard (
	id              BIGINT PRIMARY KEY,
	dataset_id      BIGINT,
	tenant_id       VARCHAR(12),
	name            VARCHAR(64) NOT NULL,
	dashboard_type  VARCHAR(16),
	description     VARCHAR(200),
	dsl             TEXT,
	layout_mode     VARCHAR(8) DEFAULT 'auto',
	enabled         INT NOT NULL DEFAULT 1,
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);

CREATE TABLE ai_secret (
	id              BIGINT PRIMARY KEY,
	tenant_id       VARCHAR(12),
	secret_key      VARCHAR(64) NOT NULL,
	secret_prefix   VARCHAR(12),
	scope           VARCHAR(16),
	scope_id        BIGINT,
	description     VARCHAR(128) NOT NULL,
	expire_time     TIMESTAMP,
	rate_limit      INT,
	allow_ips       VARCHAR(512),
	last_used_time  TIMESTAMP,
	used_count      BIGINT DEFAULT 0,
	status          INT NOT NULL DEFAULT 1,
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_ai_secret_prefix ON ai_secret (secret_prefix);

CREATE TABLE ai_bill (
	id                  BIGINT PRIMARY KEY,
	tenant_id           VARCHAR(12),
	message_id          BIGINT,
	session_id          BIGINT,
	user_id             BIGINT,
	model_id            BIGINT,
	model_name          VARCHAR(64),
	prompt_tokens       INT,
	completion_tokens   INT,
	total_tokens        INT,
	amount              NUMERIC(12,6),
	ip                  VARCHAR(64),
	biz_type            VARCHAR(16),
	call_time           TIMESTAMP,
	create_time         TIMESTAMP,
	update_time         TIMESTAMP,
	is_deleted          INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_ai_bill_tenant_time ON ai_bill (tenant_id, call_time);

CREATE TABLE ai_quota (
	id              BIGINT PRIMARY KEY,
	tenant_id       VARCHAR(12),
	period          VARCHAR(8) NOT NULL,
	token_limit     BIGINT,
	amount_limit    NUMERIC(12,2),
	warn_ratio      INT DEFAULT 80,
	over_action     VARCHAR(16) DEFAULT 'reject',
	used_tokens     BIGINT DEFAULT 0,
	used_amount     NUMERIC(12,2) DEFAULT 0,
	reset_time      TIMESTAMP,
	status          INT NOT NULL DEFAULT 1,
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);


INSERT INTO ai_vector_store (id, tenant_id, name, store_type, host, port, database_name, table_name,
	dimensions, metric, index_type, builtin_flag, remark, create_time, update_time, is_deleted)
SELECT 1095000000000000901, '000000', '内置向量库', 'pgvector', 'localhost', 5432, 'mugsun', 'vector_store',
	1536, 'COSINE', 'HNSW', 1, '系统内置', SYSDATE, SYSDATE, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM ai_vector_store WHERE builtin_flag = 1 AND is_deleted = 0);


-- ========== AI 菜单种子（号段 1095…）==========

-- 一级目录
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, sort, icon, is_hide, create_time, is_deleted)
SELECT 1095000000000000001, 0, 'AI', '/ai', '/index/index', 'M', 7, 'ri:robot-2-line', 0, SYSDATE, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/ai' AND is_deleted = 0);

-- 二级目录
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, sort, icon, is_hide, create_time, is_deleted)
SELECT v.id, 1095000000000000001, v.n, v.p, '/index/index', 'M', v.s, v.i, 0, SYSDATE, 0
FROM (VALUES
	(1095000000000000002, 'AI 应用', '/ai/app-group', 1, 'ri:apps-2-line'),
	(1095000000000000003, 'AI 工具', '/ai/tool-group', 2, 'ri:tools-line'),
	(1095000000000000004, 'AI 运维', '/ai/ops-group', 3, 'ri:settings-3-line'),
	(1095000000000000005, '智能体集合', '/ai/gen-group', 4, 'ri:magic-line')
) AS v(id, n, p, s, i)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = v.p AND x.is_deleted = 0);

-- 应用章页面 C
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_hide, create_time, is_deleted)
SELECT v.id, 1095000000000000002, v.n, v.p, v.c, 'C', v.perm, v.s, v.i, v.h, SYSDATE, 0
FROM (VALUES
	(1095000000000000010, '机器人助手', '/ai/assistant', '/ai/assistant', 'ai:assistant:list', 1, 'ri:chat-ai-line', 0),
	(1095000000000000011, '机器人应用', '/ai/app', '/ai/app', 'ai:app:list', 2, 'ri:robot-line', 0),
	(1095000000000000012, '应用编排设计器', '/ai/app/design/:id', '/ai/app/design', 'ai:app:design', 3, 'ri:flow-chart', 1),
	(1095000000000000013, '知识库中心', '/ai/knowledge', '/ai/knowledge', 'ai:kb:list', 4, 'ri:book-2-line', 0),
	(1095000000000000014, '知识库详情', '/ai/knowledge/detail/:id', '/ai/knowledge/detail', 'ai:kb:detail', 5, 'ri:file-list-3-line', 1),
	(1095000000000000015, '智能体问数', '/ai/dataset', '/ai/dataset', 'ai:dataset:list', 6, 'ri:database-2-line', 0),
	(1095000000000000016, '问数配置', '/ai/dataset/config/:id', '/ai/dataset/config', 'ai:dataset:config', 7, 'ri:settings-line', 1),
	(1095000000000000017, '问数对话', '/ai/dataset/run/:id', '/ai/dataset/run', 'ai:dataset:run', 8, 'ri:chat-1-line', 1),
	(1095000000000000018, '仪表盘设计器', '/ai/dashboard/design/:id', '/ai/dashboard/design', 'ai:dashboard:design', 9, 'ri:dashboard-line', 1)
) AS v(id, n, p, c, perm, s, i, h)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = v.p AND x.is_deleted = 0);

-- 工具章页面 C
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_hide, create_time, is_deleted)
SELECT v.id, 1095000000000000003, v.n, v.p, v.c, 'C', v.perm, v.s, v.i, 0, SYSDATE, 0
FROM (VALUES
	(1095000000000000020, '大模型配置', '/ai/model', '/ai/model', 'ai:model:list', 1, 'ri:cpu-line'),
	(1095000000000000021, '提示词配置', '/ai/prompt', '/ai/prompt', 'ai:prompt:list', 2, 'ri:quill-pen-line'),
	(1095000000000000022, 'MCP 工具箱', '/ai/mcp', '/ai/mcp', 'ai:mcp:list', 3, 'ri:plug-line'),
	(1095000000000000023, '向量库配置', '/ai/vector', '/ai/vector', 'ai:vector:list', 4, 'ri:shape-line'),
	(1095000000000000024, '数据库配置', '/ai/datasource', '/ai/datasource', 'ai:datasource:list', 5, 'ri:database-line'),
	(1095000000000000025, '消息渠道绑定', '/ai/channel', '/ai/channel', 'ai:channel:list', 6, 'ri:mail-send-line')
) AS v(id, n, p, c, perm, s, i)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = v.p AND x.is_deleted = 0);

-- 运维章页面 C
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_hide, create_time, is_deleted)
SELECT v.id, 1095000000000000004, v.n, v.p, v.c, 'C', v.perm, v.s, v.i, 0, SYSDATE, 0
FROM (VALUES
	(1095000000000000030, '超级密钥', '/ai/secret', '/ai/secret', 'ai:secret:list', 1, 'ri:key-2-line'),
	(1095000000000000031, '对话记录', '/ai/conversation', '/ai/conversation', 'ai:conversation:list', 2, 'ri:chat-history-line'),
	(1095000000000000032, '账单记录', '/ai/billing', '/ai/billing', 'ai:billing:list', 3, 'ri:bill-line'),
	(1095000000000000033, '配额与预警', '/ai/quota', '/ai/quota', 'ai:quota:list', 4, 'ri:alarm-warning-line')
) AS v(id, n, p, c, perm, s, i)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = v.p AND x.is_deleted = 0);

-- 生成器章页面 C
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_hide, create_time, is_deleted)
SELECT v.id, 1095000000000000005, v.n, v.p, v.c, 'C', v.perm, v.s, v.i, 0, SYSDATE, 0
FROM (VALUES
	(1095000000000000040, '脑图生成器', '/ai/gen/mindmap', '/ai/gen/mindmap', 'ai:gen-mindmap:use', 1, 'ri:mind-map'),
	(1095000000000000041, '海报生成器', '/ai/gen/poster', '/ai/gen/poster', 'ai:gen-poster:use', 2, 'ri:image-line'),
	(1095000000000000042, '文章生成器', '/ai/gen/article', '/ai/gen/article', 'ai:gen-article:use', 3, 'ri:article-line'),
	(1095000000000000043, '产品描述生成器', '/ai/gen/product', '/ai/gen/product', 'ai:gen-product:use', 4, 'ri:shopping-bag-line'),
	(1095000000000000044, '营销文案生成器', '/ai/gen/marketing', '/ai/gen/marketing', 'ai:gen-marketing:use', 5, 'ri:megaphone-line'),
	(1095000000000000045, 'SVG 插图生成器', '/ai/gen/svg', '/ai/gen/svg', 'ai:gen-svg:use', 6, 'ri:shape-2-line'),
	(1095000000000000046, '自动排版生成器', '/ai/gen/layout', '/ai/gen/layout', 'ai:gen-layout:use', 7, 'ri:layout-line')
) AS v(id, n, p, c, perm, s, i)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = v.p AND x.is_deleted = 0);

-- 按钮 F（应用）
INSERT INTO sys_menu (id, parent_id, menu_name, permission, menu_type, sort, create_time, is_deleted)
SELECT v.id, v.pid, v.n, v.perm, 'F', v.s, SYSDATE, 0
FROM (VALUES
	(1095000000000000100, 1095000000000000010, '对话', 'ai:assistant:chat', 1),
	(1095000000000000101, 1095000000000000010, '删除会话', 'ai:assistant:remove', 2),
	(1095000000000000102, 1095000000000000010, '导出会话', 'ai:assistant:export', 3),
	(1095000000000000110, 1095000000000000011, '保存应用', 'ai:app:save', 1),
	(1095000000000000111, 1095000000000000011, '删除应用', 'ai:app:remove', 2),
	(1095000000000000112, 1095000000000000011, '复制应用', 'ai:app:copy', 3),
	(1095000000000000113, 1095000000000000011, '导入应用', 'ai:app:import', 4),
	(1095000000000000114, 1095000000000000011, '导出应用', 'ai:app:export', 5),
	(1095000000000000115, 1095000000000000011, '运行应用', 'ai:app:run', 6),
	(1095000000000000116, 1095000000000000011, '发布应用', 'ai:app:publish', 7),
	(1095000000000000120, 1095000000000000013, '保存知识库', 'ai:kb:save', 1),
	(1095000000000000121, 1095000000000000013, '删除知识库', 'ai:kb:remove', 2),
	(1095000000000000122, 1095000000000000013, '复制知识库', 'ai:kb:copy', 3),
	(1095000000000000123, 1095000000000000013, '测试检索', 'ai:kb:test', 4),
	(1095000000000000130, 1095000000000000014, '上传资料', 'ai:kb-asset:upload', 1),
	(1095000000000000131, 1095000000000000014, '删除资料', 'ai:kb-asset:remove', 2),
	(1095000000000000132, 1095000000000000014, '编辑分段', 'ai:kb-seg:edit', 3),
	(1095000000000000133, 1095000000000000014, '向量化', 'ai:kb-seg:vector', 4),
	(1095000000000000134, 1095000000000000014, '命中测试', 'ai:kb:hit-test', 5),
	(1095000000000000140, 1095000000000000015, '保存问数', 'ai:dataset:save', 1),
	(1095000000000000141, 1095000000000000015, '删除问数', 'ai:dataset:remove', 2),
	(1095000000000000142, 1095000000000000015, '复制问数', 'ai:dataset:copy', 3),
	(1095000000000000143, 1095000000000000015, '导出问数', 'ai:dataset:export', 4),
	(1095000000000000150, 1095000000000000017, '分析', 'ai:dataset:analyze', 1),
	(1095000000000000151, 1095000000000000017, '预测', 'ai:dataset:predict', 2)
) AS v(id, pid, n, perm, s)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.permission = v.perm AND x.is_deleted = 0);

-- 按钮 F（工具）
INSERT INTO sys_menu (id, parent_id, menu_name, permission, menu_type, sort, create_time, is_deleted)
SELECT v.id, v.pid, v.n, v.perm, 'F', v.s, SYSDATE, 0
FROM (VALUES
	(1095000000000000200, 1095000000000000020, '保存模型', 'ai:model:save', 1),
	(1095000000000000201, 1095000000000000020, '删除模型', 'ai:model:remove', 2),
	(1095000000000000202, 1095000000000000020, '设为默认', 'ai:model:default', 3),
	(1095000000000000203, 1095000000000000020, '探测激活', 'ai:model:test', 4),
	(1095000000000000210, 1095000000000000021, '保存提示词', 'ai:prompt:save', 1),
	(1095000000000000211, 1095000000000000021, '删除提示词', 'ai:prompt:remove', 2),
	(1095000000000000212, 1095000000000000021, '优化提示词', 'ai:prompt:optimize', 3),
	(1095000000000000213, 1095000000000000021, '试运行', 'ai:prompt:try', 4),
	(1095000000000000214, 1095000000000000021, '版本', 'ai:prompt:version', 5),
	(1095000000000000220, 1095000000000000022, '保存 MCP', 'ai:mcp:save', 1),
	(1095000000000000221, 1095000000000000022, '删除 MCP', 'ai:mcp:remove', 2),
	(1095000000000000222, 1095000000000000022, '解析工具', 'ai:mcp:parse', 3),
	(1095000000000000223, 1095000000000000022, '调试', 'ai:mcp:debug', 4),
	(1095000000000000224, 1095000000000000022, '锁定', 'ai:mcp:lock', 5),
	(1095000000000000225, 1095000000000000022, '设为默认', 'ai:mcp:default', 6),
	(1095000000000000226, 1095000000000000022, '对外暴露', 'ai:mcp:server', 7),
	(1095000000000000230, 1095000000000000023, '保存向量库', 'ai:vector:save', 1),
	(1095000000000000231, 1095000000000000023, '删除向量库', 'ai:vector:remove', 2),
	(1095000000000000232, 1095000000000000023, '测试连接', 'ai:vector:test', 3),
	(1095000000000000240, 1095000000000000024, '保存数据源', 'ai:datasource:save', 1),
	(1095000000000000241, 1095000000000000024, '删除数据源', 'ai:datasource:remove', 2),
	(1095000000000000242, 1095000000000000024, '测试连接', 'ai:datasource:test', 3),
	(1095000000000000250, 1095000000000000025, '保存渠道', 'ai:channel:save', 1),
	(1095000000000000251, 1095000000000000025, '删除渠道', 'ai:channel:remove', 2),
	(1095000000000000252, 1095000000000000025, '调试发送', 'ai:channel:debug', 3)
) AS v(id, pid, n, perm, s)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.permission = v.perm AND x.is_deleted = 0);

-- 按钮 F（运维）
INSERT INTO sys_menu (id, parent_id, menu_name, permission, menu_type, sort, create_time, is_deleted)
SELECT v.id, v.pid, v.n, v.perm, 'F', v.s, SYSDATE, 0
FROM (VALUES
	(1095000000000000300, 1095000000000000030, '保存密钥', 'ai:secret:save', 1),
	(1095000000000000301, 1095000000000000030, '删除密钥', 'ai:secret:remove', 2),
	(1095000000000000302, 1095000000000000030, '启停密钥', 'ai:secret:status', 3),
	(1095000000000000310, 1095000000000000031, '对话详情', 'ai:conversation:detail', 1),
	(1095000000000000311, 1095000000000000031, '导出对话', 'ai:conversation:export', 2),
	(1095000000000000320, 1095000000000000032, '导出账单', 'ai:billing:export', 1),
	(1095000000000000330, 1095000000000000033, '保存配额', 'ai:quota:save', 1)
) AS v(id, pid, n, perm, s)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.permission = v.perm AND x.is_deleted = 0);

