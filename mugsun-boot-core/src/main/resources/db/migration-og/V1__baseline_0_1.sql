-- Mugsun 主库 Flyway 基线（PostgreSQL / 金仓兼容）
-- 0.1.0 基线：合并原 V1–V79 为单脚本；全新安装只跑本文件。
-- 后续版本演进（如 0.1.1）再追加 V2__*.sql / V3__*.sql，勿再拆回历史增量。
-- 生成方式：scripts/squash_flyway_baseline.py（本仓库一次性收口）。

-- ========== 原 V1__auth.sql ==========
CREATE TABLE sys_user (
	id          BIGINT       PRIMARY KEY,
	username    VARCHAR(64)  NOT NULL UNIQUE,
	password    VARCHAR(100) NOT NULL,
	nickname    VARCHAR(64),
	status      SMALLINT     NOT NULL DEFAULT 1,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

COMMENT ON TABLE sys_user IS '系统用户';
COMMENT ON COLUMN sys_user.status IS '状态：1 启用 / 0 停用';
COMMENT ON COLUMN sys_user.is_deleted IS '逻辑删除：0 正常 / 1 删除';

-- ========== 原 V2__org.sql ==========
CREATE TABLE sys_dept (
	id          BIGINT      PRIMARY KEY,
	parent_id   BIGINT      NOT NULL DEFAULT 0,
	dept_name   VARCHAR(64) NOT NULL,
	sort        INT         NOT NULL DEFAULT 0,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT         NOT NULL DEFAULT 0
);

CREATE TABLE sys_post (
	id          BIGINT      PRIMARY KEY,
	post_code   VARCHAR(64) NOT NULL,
	post_name   VARCHAR(64) NOT NULL,
	sort        INT         NOT NULL DEFAULT 0,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT         NOT NULL DEFAULT 0
);

ALTER TABLE sys_user ADD COLUMN dept_id BIGINT;
ALTER TABLE sys_user ADD COLUMN post_id BIGINT;

COMMENT ON TABLE sys_dept IS '部门';
COMMENT ON TABLE sys_post IS '岗位';

-- ========== 原 V3__rbac.sql ==========
CREATE TABLE sys_role (
	id          BIGINT      PRIMARY KEY,
	role_name   VARCHAR(64) NOT NULL,
	role_code   VARCHAR(64) NOT NULL,
	sort        INT         NOT NULL DEFAULT 0,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT         NOT NULL DEFAULT 0
);

CREATE TABLE sys_menu (
	id          BIGINT       PRIMARY KEY,
	parent_id   BIGINT       NOT NULL DEFAULT 0,
	menu_name   VARCHAR(64)  NOT NULL,
	path        VARCHAR(128),
	component   VARCHAR(128),
	menu_type   VARCHAR(8),
	permission  VARCHAR(128),
	sort        INT          NOT NULL DEFAULT 0,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

CREATE TABLE sys_role_menu (
	id          BIGINT PRIMARY KEY,
	role_id     BIGINT NOT NULL,
	menu_id     BIGINT NOT NULL,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT    NOT NULL DEFAULT 0
);

CREATE TABLE sys_user_role (
	id          BIGINT PRIMARY KEY,
	user_id     BIGINT NOT NULL,
	role_id     BIGINT NOT NULL,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT    NOT NULL DEFAULT 0
);

COMMENT ON TABLE sys_role IS '角色';
COMMENT ON TABLE sys_menu IS '菜单/按钮权限';
COMMENT ON COLUMN sys_menu.menu_type IS 'M 菜单 / B 按钮';
COMMENT ON COLUMN sys_menu.permission IS '权限标识码';

-- ========== 原 V4__data_scope.sql ==========
ALTER TABLE sys_role ADD COLUMN data_scope INT NOT NULL DEFAULT 1;

COMMENT ON COLUMN sys_role.data_scope IS '数据范围：1 全部 / 2 本部门 / 3 仅本人';

-- ========== 原 V5__dict_param.sql ==========
CREATE TABLE sys_dict (
	id          BIGINT       PRIMARY KEY,
	parent_id   BIGINT       NOT NULL DEFAULT 0,
	code        VARCHAR(64)  NOT NULL,
	dict_key    VARCHAR(64)  NOT NULL,
	dict_value  VARCHAR(128) NOT NULL,
	sort        INT          NOT NULL DEFAULT 0,
	remark      VARCHAR(255),
	is_sealed   INT          NOT NULL DEFAULT 0,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

CREATE TABLE sys_dict_biz (
	id          BIGINT       PRIMARY KEY,
	tenant_id   VARCHAR(12),
	parent_id   BIGINT       NOT NULL DEFAULT 0,
	code        VARCHAR(64)  NOT NULL,
	dict_key    VARCHAR(64)  NOT NULL,
	dict_value  VARCHAR(128) NOT NULL,
	sort        INT          NOT NULL DEFAULT 0,
	remark      VARCHAR(255),
	is_sealed   INT          NOT NULL DEFAULT 0,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

CREATE TABLE sys_param (
	id          BIGINT       PRIMARY KEY,
	param_name  VARCHAR(128) NOT NULL,
	param_key   VARCHAR(128) NOT NULL,
	param_value VARCHAR(255) NOT NULL,
	remark      VARCHAR(255),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

COMMENT ON TABLE sys_dict IS '系统字典';
COMMENT ON TABLE sys_dict_biz IS '业务字典（租户隔离）';
COMMENT ON TABLE sys_param IS '系统参数';
COMMENT ON COLUMN sys_dict.code IS '字典编码（分类标识，如 sex）';
COMMENT ON COLUMN sys_dict.dict_key IS '字典键';
COMMENT ON COLUMN sys_dict.dict_value IS '字典值/名称';
COMMENT ON COLUMN sys_dict.is_sealed IS '是否封存 0 否 1 是';
COMMENT ON COLUMN sys_dict_biz.tenant_id IS '租户编号（多租户隔离）';

-- ========== 原 V6__tenant.sql ==========
CREATE TABLE sys_tenant (
	id            BIGINT      PRIMARY KEY,
	tenant_code   VARCHAR(12) NOT NULL,
	tenant_name   VARCHAR(64) NOT NULL,
	contact_user  VARCHAR(32),
	contact_phone VARCHAR(20),
	expire_time   TIMESTAMP,
	create_time   TIMESTAMP,
	update_time   TIMESTAMP,
	is_deleted    INT         NOT NULL DEFAULT 0
);

ALTER TABLE sys_user ADD COLUMN tenant_id VARCHAR(12);
ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS sys_user_username_key;
CREATE UNIQUE INDEX uk_user_tenant_username ON sys_user (tenant_id, username);
ALTER TABLE sys_dept ADD COLUMN tenant_id VARCHAR(12);
ALTER TABLE sys_post ADD COLUMN tenant_id VARCHAR(12);
ALTER TABLE sys_role ADD COLUMN tenant_id VARCHAR(12);

COMMENT ON TABLE sys_tenant IS '租户';
COMMENT ON COLUMN sys_tenant.tenant_code IS '租户编号（业务表 tenant_id 隔离列取值来源）';
COMMENT ON COLUMN sys_user.tenant_id IS '租户编号（字段隔离）';

-- ========== 原 V7__attach_sms.sql ==========
CREATE TABLE sys_attach (
	id           BIGINT       PRIMARY KEY,
	tenant_id    VARCHAR(12),
	name         VARCHAR(255),
	url          VARCHAR(512),
	path         VARCHAR(255),
	filename     VARCHAR(255),
	ext          VARCHAR(32),
	content_type VARCHAR(128),
	size         BIGINT,
	platform     VARCHAR(32),
	create_time  TIMESTAMP,
	update_time  TIMESTAMP,
	is_deleted   INT          NOT NULL DEFAULT 0
);

CREATE TABLE sys_sms_code (
	id          BIGINT      PRIMARY KEY,
	phone       VARCHAR(20) NOT NULL,
	code        VARCHAR(8)  NOT NULL,
	expire_time TIMESTAMP   NOT NULL,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT         NOT NULL DEFAULT 0
);

COMMENT ON TABLE sys_attach IS '附件登记';
COMMENT ON TABLE sys_sms_code IS '短信验证码';
COMMENT ON COLUMN sys_attach.name IS '原始文件名';
COMMENT ON COLUMN sys_attach.filename IS '存储文件名';

-- ========== 原 V8__notice.sql ==========
CREATE TABLE sys_notice (
	id           BIGINT       PRIMARY KEY,
	tenant_id    VARCHAR(12),
	title        VARCHAR(255) NOT NULL,
	content      TEXT,
	category     VARCHAR(16),
	is_top       INT          NOT NULL DEFAULT 0,
	release_time TIMESTAMP,
	create_time  TIMESTAMP,
	update_time  TIMESTAMP,
	is_deleted   INT          NOT NULL DEFAULT 0
);

COMMENT ON TABLE sys_notice IS '通知公告';
COMMENT ON COLUMN sys_notice.category IS 'notice 通知 / announcement 公告';
COMMENT ON COLUMN sys_notice.is_top IS '是否置顶 0 否 1 是';

-- ========== 原 V9__log_audit.sql ==========
CREATE TABLE sys_oper_log (
	id             BIGINT       PRIMARY KEY,
	title          VARCHAR(128),
	method         VARCHAR(255),
	request_method VARCHAR(16),
	request_uri    VARCHAR(255),
	operator       VARCHAR(64),
	ip             VARCHAR(64),
	params         TEXT,
	duration       BIGINT,
	status         INT,
	error_msg      TEXT,
	create_time    TIMESTAMP,
	update_time    TIMESTAMP,
	is_deleted     INT          NOT NULL DEFAULT 0
);

CREATE TABLE sys_data_audit (
	id          BIGINT      PRIMARY KEY,
	biz_table   VARCHAR(64),
	biz_id      VARCHAR(64),
	before_data TEXT,
	after_data  TEXT,
	operator    VARCHAR(64),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT         NOT NULL DEFAULT 0
);

COMMENT ON TABLE sys_oper_log IS '操作日志';
COMMENT ON TABLE sys_data_audit IS '数据变更审计（前后镜像）';
COMMENT ON COLUMN sys_oper_log.duration IS '耗时（毫秒）';
COMMENT ON COLUMN sys_oper_log.status IS '状态 1 成功 0 失败';

-- ========== 原 V10__warm_flow.sql ==========
CREATE TABLE flow_definition (
	id              int8         NOT NULL,
	flow_code       varchar(40)  NOT NULL,
	flow_name       varchar(100) NOT NULL,
	model_value     varchar(40)  NOT NULL DEFAULT 'CLASSICS',
	category        varchar(100),
	version         varchar(20)  NOT NULL,
	is_publish      int2         NOT NULL DEFAULT 0,
	form_custom     bpchar(1)    DEFAULT 'N',
	form_path       varchar(100),
	activity_status int2         NOT NULL DEFAULT 1,
	listener_type   varchar(100),
	listener_path   varchar(400),
	ext             varchar(500),
	create_time     timestamp,
	create_by       varchar(64)  DEFAULT '',
	update_time     timestamp,
	update_by       varchar(64)  DEFAULT '',
	del_flag        bpchar(1)    DEFAULT '0',
	tenant_id       varchar(40),
	CONSTRAINT flow_definition_pkey PRIMARY KEY (id)
);

CREATE TABLE flow_node (
	id              int8         NOT NULL,
	node_type       int2         NOT NULL,
	definition_id   int8         NOT NULL,
	node_code       varchar(100) NOT NULL,
	node_name       varchar(100),
	permission_flag varchar(200),
	node_ratio      varchar(200),
	coordinate      varchar(100),
	any_node_skip   varchar(100),
	listener_type   varchar(100),
	listener_path   varchar(400),
	form_custom     bpchar(1)    DEFAULT 'N',
	form_path       varchar(100),
	version         varchar(20)  NOT NULL,
	create_time     timestamp,
	create_by       varchar(64)  DEFAULT '',
	update_time     timestamp,
	update_by       varchar(64)  DEFAULT '',
	ext             text,
	del_flag        bpchar(1)    DEFAULT '0',
	tenant_id       varchar(40),
	CONSTRAINT flow_node_pkey PRIMARY KEY (id)
);

CREATE TABLE flow_skip (
	id             int8         NOT NULL,
	definition_id  int8         NOT NULL,
	now_node_code  varchar(100) NOT NULL,
	now_node_type  int2,
	next_node_code varchar(100) NOT NULL,
	next_node_type int2,
	skip_name      varchar(100),
	skip_type      varchar(40),
	skip_condition varchar(200),
	coordinate     varchar(100),
	create_time    timestamp,
	create_by      varchar(64)  DEFAULT '',
	update_time    timestamp,
	update_by      varchar(64)  DEFAULT '',
	del_flag       bpchar(1)    DEFAULT '0',
	tenant_id      varchar(40),
	CONSTRAINT flow_skip_pkey PRIMARY KEY (id)
);

CREATE TABLE flow_instance (
	id              int8         NOT NULL,
	definition_id   int8         NOT NULL,
	business_id     varchar(40)  NOT NULL,
	node_type       int2         NOT NULL,
	node_code       varchar(40)  NOT NULL,
	node_name       varchar(100),
	variable        text,
	flow_status     varchar(20)  NOT NULL,
	activity_status int2         NOT NULL DEFAULT 1,
	def_json        text,
	create_time     timestamp,
	create_by       varchar(64)  DEFAULT '',
	update_time     timestamp,
	update_by       varchar(64)  DEFAULT '',
	ext             varchar(500),
	del_flag        bpchar(1)    DEFAULT '0',
	tenant_id       varchar(40),
	CONSTRAINT flow_instance_pkey PRIMARY KEY (id)
);

CREATE TABLE flow_task (
	id            int8         NOT NULL,
	definition_id int8         NOT NULL,
	instance_id   int8         NOT NULL,
	node_code     varchar(100) NOT NULL,
	node_name     varchar(100),
	node_type     int2         NOT NULL,
	flow_status   varchar(20)  NOT NULL,
	form_custom   bpchar(1)    DEFAULT 'N',
	form_path     varchar(100),
	create_time   timestamp,
	create_by     varchar(64)  DEFAULT '',
	update_time   timestamp,
	update_by     varchar(64)  DEFAULT '',
	del_flag      bpchar(1)    DEFAULT '0',
	tenant_id     varchar(40),
	CONSTRAINT flow_task_pkey PRIMARY KEY (id)
);

CREATE TABLE flow_his_task (
	id               int8         NOT NULL,
	definition_id    int8         NOT NULL,
	instance_id      int8         NOT NULL,
	task_id          int8         NOT NULL,
	node_code        varchar(100),
	node_name        varchar(100),
	node_type        int2,
	target_node_code varchar(200),
	target_node_name varchar(200),
	approver         varchar(40),
	cooperate_type   int2         NOT NULL DEFAULT 0,
	collaborator     varchar(500),
	skip_type        varchar(10),
	flow_status      varchar(20)  NOT NULL,
	form_custom      bpchar(1)    DEFAULT 'N',
	form_path        varchar(100),
	ext              text,
	message          varchar(500),
	variable         text,
	create_time      timestamp,
	update_time      timestamp,
	del_flag         bpchar(1)    DEFAULT '0',
	tenant_id        varchar(40),
	CONSTRAINT flow_his_task_pkey PRIMARY KEY (id)
);

CREATE TABLE flow_user (
	id           int8        NOT NULL,
	type         bpchar(1)   NOT NULL,
	processed_by varchar(80),
	associated   int8        NOT NULL,
	create_time  timestamp,
	create_by    varchar(64) DEFAULT '',
	update_time  timestamp,
	update_by    varchar(64) DEFAULT '',
	del_flag     bpchar(1)   DEFAULT '0',
	tenant_id    varchar(40),
	CONSTRAINT flow_user_pk PRIMARY KEY (id)
);
CREATE INDEX user_processed_type ON flow_user USING btree (processed_by, type);
CREATE INDEX user_associated_idx ON flow_user USING btree (associated);

COMMENT ON TABLE flow_definition IS '流程定义';
COMMENT ON TABLE flow_node IS '流程节点';
COMMENT ON TABLE flow_skip IS '节点跳转';
COMMENT ON TABLE flow_instance IS '流程实例';
COMMENT ON TABLE flow_task IS '待办任务';
COMMENT ON TABLE flow_his_task IS '历史任务';
COMMENT ON TABLE flow_user IS '流程用户';

-- ========== 原 V11__gen_demo.sql ==========
CREATE TABLE gen_product (
	id           BIGINT        PRIMARY KEY,
	product_name VARCHAR(128)  NOT NULL,
	price        NUMERIC(10,2),
	stock        INT           DEFAULT 0,
	create_time  TIMESTAMP,
	update_time  TIMESTAMP,
	is_deleted   INT           NOT NULL DEFAULT 0
);

COMMENT ON TABLE gen_product IS '代码生成演示表-商品';

-- ========== 原 V12__report.sql ==========
CREATE TABLE sys_report (
	id          BIGINT       PRIMARY KEY,
	report_name VARCHAR(128) NOT NULL,
	report_key  VARCHAR(64)  NOT NULL,
	chart_type  VARCHAR(16),
	remark      VARCHAR(255),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

COMMENT ON TABLE sys_report IS '报表定义';
COMMENT ON COLUMN sys_report.report_key IS '内置数据集标识（user_status/dept_user 等）';
COMMENT ON COLUMN sys_report.chart_type IS '展示类型 table/bar/pie/line';

-- ========== 原 V13__oss_sms.sql ==========
CREATE TABLE sys_oss (
	id           BIGINT       PRIMARY KEY,
	tenant_id    VARCHAR(12),
	name         VARCHAR(64)  NOT NULL,
	oss_code     VARCHAR(32)  NOT NULL,
	category     VARCHAR(32)  NOT NULL DEFAULT 'local',
	endpoint     VARCHAR(255),
	access_key   VARCHAR(255),
	secret_key   VARCHAR(255),
	bucket_name  VARCHAR(128),
	domain       VARCHAR(255),
	storage_path VARCHAR(255),
	status       INT          NOT NULL DEFAULT 0,
	remark       VARCHAR(255),
	create_time  TIMESTAMP,
	update_time  TIMESTAMP,
	is_deleted   INT          NOT NULL DEFAULT 0
);

CREATE TABLE sys_sms (
	id          BIGINT       PRIMARY KEY,
	tenant_id   VARCHAR(12),
	name        VARCHAR(64)  NOT NULL,
	sms_code    VARCHAR(32)  NOT NULL,
	category    VARCHAR(32)  NOT NULL DEFAULT 'alibaba',
	access_key  VARCHAR(255),
	secret_key  VARCHAR(255),
	signature   VARCHAR(64),
	template_id VARCHAR(64),
	status      INT          NOT NULL DEFAULT 0,
	remark      VARCHAR(255),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

COMMENT ON TABLE sys_oss IS '对象存储配置';
COMMENT ON TABLE sys_sms IS '短信平台配置';
COMMENT ON COLUMN sys_oss.oss_code IS '存储平台唯一标识';
COMMENT ON COLUMN sys_oss.category IS '存储类型：local/minio/aliyun 等';
COMMENT ON COLUMN sys_oss.storage_path IS '本地存储绝对路径（local 类型用）';
COMMENT ON COLUMN sys_oss.status IS '状态：1启用 0禁用（同租户仅一个启用）';
COMMENT ON COLUMN sys_sms.sms_code IS '短信配置唯一标识';
COMMENT ON COLUMN sys_sms.category IS '供应商：alibaba/tencent 等';
COMMENT ON COLUMN sys_sms.status IS '状态：1启用 0禁用（同租户仅一个启用）';

-- ========== 原 V14__auth_security.sql ==========
CREATE TABLE sys_login_log (
	id          BIGINT       PRIMARY KEY,
	tenant_id   VARCHAR(12),
	username    VARCHAR(64)  NOT NULL,
	ip          VARCHAR(64),
	status      INT          NOT NULL DEFAULT 1,
	msg         VARCHAR(255),
	login_time  TIMESTAMP,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

CREATE TABLE sys_api_key (
	id          BIGINT       PRIMARY KEY,
	tenant_id   VARCHAR(12),
	name        VARCHAR(64)  NOT NULL,
	access_key  VARCHAR(64)  NOT NULL,
	secret_key  VARCHAR(128) NOT NULL,
	scope       VARCHAR(255),
	status      INT          NOT NULL DEFAULT 1,
	remark      VARCHAR(255),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

COMMENT ON TABLE sys_login_log IS '登录日志';
COMMENT ON TABLE sys_api_key IS 'API 密钥';
COMMENT ON COLUMN sys_login_log.status IS '状态：1成功 0失败';
COMMENT ON COLUMN sys_api_key.access_key IS '访问标识 AK';
COMMENT ON COLUMN sys_api_key.secret_key IS '密钥 SK';
COMMENT ON COLUMN sys_api_key.scope IS '作用域（逗号分隔的授权范围）';
COMMENT ON COLUMN sys_api_key.status IS '状态：1启用 0停用';

-- ========== 原 V15__region.sql ==========
CREATE TABLE sys_region (
	id          BIGINT       PRIMARY KEY,
	code        VARCHAR(20)  NOT NULL,
	parent_code VARCHAR(20)  NOT NULL DEFAULT '0',
	name        VARCHAR(64)  NOT NULL,
	level       INT          NOT NULL DEFAULT 1,
	sort        INT          NOT NULL DEFAULT 0,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

COMMENT ON TABLE sys_region IS '行政区划';
COMMENT ON COLUMN sys_region.code IS '区划编码';
COMMENT ON COLUMN sys_region.parent_code IS '父级编码，0 为顶级';
COMMENT ON COLUMN sys_region.level IS '层级：1省 2市 3区县';

-- 种子数据：省 / 市 / 区县 三级，用于懒加载树演示
INSERT INTO sys_region(id, code, parent_code, name, level, sort, is_deleted) VALUES
	(1500000000000000001, '110000', '0',      '北京市',   1, 1, 0),
	(1500000000000000002, '110100', '110000', '市辖区',   2, 1, 0),
	(1500000000000000003, '110101', '110100', '东城区',   3, 1, 0),
	(1500000000000000004, '110102', '110100', '西城区',   3, 2, 0),
	(1500000000000000010, '440000', '0',      '广东省',   1, 2, 0),
	(1500000000000000011, '440100', '440000', '广州市',   2, 1, 0),
	(1500000000000000012, '440103', '440100', '荔湾区',   3, 1, 0),
	(1500000000000000013, '440104', '440100', '越秀区',   3, 2, 0),
	(1500000000000000014, '440300', '440000', '深圳市',   2, 2, 0),
	(1500000000000000015, '440303', '440300', '罗湖区',   3, 1, 0),
	(1500000000000000020, '330000', '0',      '浙江省',   1, 3, 0),
	(1500000000000000021, '330100', '330000', '杭州市',   2, 1, 0),
	(1500000000000000022, '330102', '330100', '上城区',   3, 1, 0);

-- ========== 原 V16__user_sensitive.sql ==========
-- G36 数据脱敏 + 字段加密：sys_user 增敏感字段
-- phone 明文存储、展示脱敏（@ColumnMask）；id_card 存 SM4 密文（TypeHandler），查询自动解密
ALTER TABLE sys_user ADD COLUMN phone   VARCHAR(32);
ALTER TABLE sys_user ADD COLUMN id_card VARCHAR(255);
COMMENT ON COLUMN sys_user.phone   IS '手机号（展示脱敏）';
COMMENT ON COLUMN sys_user.id_card IS '身份证号（SM4 加密存储）';

-- ========== 原 V17__password_security.sql ==========
-- G38 等保密码与登录安全：历史密码表 + 策略参数（存 sys_param，可后台即时改）
CREATE TABLE sys_password_log (
	id          BIGINT       PRIMARY KEY,
	user_id     BIGINT       NOT NULL,
	password    VARCHAR(100) NOT NULL,
	create_time TIMESTAMP
);
CREATE INDEX idx_pwd_log_user ON sys_password_log (user_id, create_time DESC);
COMMENT ON TABLE sys_password_log IS '历史密码（防重复使用）';

-- 密码/登录安全策略参数（后台参数管理可改，即时生效）
INSERT INTO sys_param (id, param_name, param_key, param_value, remark, create_time, is_deleted) VALUES
 (900001, '密码最小长度',       'security.password.min-length',    '8',  '密码最小位数',                   NOW(), 0),
 (900002, '密码复杂度校验',     'security.password.complexity',    'true','开启后要求大写/小写/数字/特殊字符至少3类', NOW(), 0),
 (900003, '历史密码防重个数',   'security.password.history-count', '3',  '新密码不可与最近N次重复',        NOW(), 0),
 (900004, '密码有效期(天)',     'security.password.expire-days',   '0',  '0=永不过期，>0 到期强制改密',    NOW(), 0),
 (900005, '登录失败锁定阈值',   'security.login.fail-max',         '5',  '连续失败达此次数锁定',           NOW(), 0),
 (900006, '登录锁定时长(分钟)', 'security.login.lock-minutes',     '10', '锁定持续时长',                   NOW(), 0);

-- ========== 原 V18__mail_two_factor.sql ==========
-- G39 双因子登录 + 邮件模板
CREATE TABLE sys_mail_template (
	id          BIGINT       PRIMARY KEY,
	code        VARCHAR(64)  NOT NULL,
	name        VARCHAR(128) NOT NULL,
	subject     VARCHAR(255) NOT NULL,
	content     TEXT         NOT NULL,
	status      INT          NOT NULL DEFAULT 1,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);
COMMENT ON TABLE sys_mail_template IS '邮件模板（${key} 占位）';

-- 内置登录双因子验证码邮件模板
INSERT INTO sys_mail_template (id, code, name, subject, content, status, create_time, is_deleted) VALUES
 (910001, 'login_2fa', '登录双因子验证码', '【Mugsun】登录验证码', '您的登录验证码是 ${code}，5 分钟内有效，请勿泄露。', 1, NOW(), 0);

-- 双因子登录策略参数（默认关闭，不影响现有登录）
INSERT INTO sys_param (id, param_name, param_key, param_value, remark, create_time, is_deleted) VALUES
 (900007, '双因子登录开关',   'security.login.two-factor',         'false', '开启后登录需二次验证码',        NOW(), 0),
 (900008, '双因子验证渠道',   'security.login.two-factor-channel', 'email', 'email=邮箱 / sms=短信',          NOW(), 0);

-- ========== 原 V19__watermark_file_access.sql ==========
-- G41 全局水印 + 文件公私目录
-- 水印策略开关（后台可改，即时生效）
INSERT INTO sys_param (id, param_name, param_key, param_value, remark, create_time, is_deleted) VALUES
 (900009, '全局水印开关', 'security.watermark.enabled', 'false', '开启后前端满屏水印(用户名+日期)', NOW(), 0);

-- 附件公私访问：public 直取 / private 需授权下载
ALTER TABLE sys_attach ADD COLUMN access VARCHAR(16) NOT NULL DEFAULT 'private';
COMMENT ON COLUMN sys_attach.access IS '访问级别：public 公开直取 / private 私有授权下载';

-- ========== 原 V20__audit_change_content.sql ==========
-- G42 数据变更记录·字段级 diff：审计表增字段级变更内容
ALTER TABLE sys_data_audit ADD COLUMN change_content TEXT;
COMMENT ON COLUMN sys_data_audit.change_content IS '字段级变更内容 JSON：[{label,old,new}]';

-- ========== 原 V21__user_status_dict.sql ==========
-- 用户状态字典：供数据变更审计将 status 值翻译为中文标签
INSERT INTO sys_dict (id, parent_id, code, dict_key, dict_value, sort, is_sealed, create_time, is_deleted) VALUES
(1050000000000000001, 0,                   'user_status', 'user_status', '用户状态', 0, 1, now(), 0),
(1050000000000000002, 1050000000000000001, 'user_status', '1',           '正常',     1, 0, now(), 0),
(1050000000000000003, 1050000000000000001, 'user_status', '0',           '停用',     2, 0, now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V22__serial_number.sql ==========
-- 单号生成器：规则定义表 + 生成记录表
CREATE TABLE sys_serial_number (
	id                BIGINT       PRIMARY KEY,
	code              VARCHAR(64)  NOT NULL,
	business_name     VARCHAR(64)  NOT NULL,
	format            VARCHAR(64)  NOT NULL,
	rule_type         VARCHAR(16)  NOT NULL,
	init_number       BIGINT       NOT NULL DEFAULT 0,
	step_random_range INT          NOT NULL DEFAULT 1,
	last_number       BIGINT,
	last_time         TIMESTAMP,
	remark            VARCHAR(255),
	create_time       TIMESTAMP,
	update_time       TIMESTAMP,
	is_deleted        INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_serial_code ON sys_serial_number (code);

COMMENT ON TABLE sys_serial_number IS '单号生成规则';
COMMENT ON COLUMN sys_serial_number.format IS '格式：[yyyy]年 [mm]月 [dd]日 [n..n]数字位';
COMMENT ON COLUMN sys_serial_number.rule_type IS '周期：none/year/month/day';
COMMENT ON COLUMN sys_serial_number.step_random_range IS '步长随机上限，1 为固定步长 1';

CREATE TABLE sys_serial_number_record (
	id          BIGINT    PRIMARY KEY,
	serial_code VARCHAR(64) NOT NULL,
	record_date DATE      NOT NULL,
	last_number BIGINT    NOT NULL DEFAULT 0,
	last_time   TIMESTAMP NOT NULL,
	gen_count   BIGINT    NOT NULL DEFAULT 0,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_serial_record ON sys_serial_number_record (serial_code, record_date);

COMMENT ON TABLE sys_serial_number_record IS '单号生成记录（每业务每天一条）';

INSERT INTO sys_serial_number (id, code, business_name, format, rule_type, init_number, step_random_range, remark, create_time) VALUES
(1060000000000000001, 'ORDER',    '订单编号', 'DK[yyyy][mm][dd]NO[nnnnn]', 'day',  1000, 1, '按日重置', now()),
(1060000000000000002, 'CONTRACT', '合同编号', 'HT[yyyy][nnnnnn]',          'year', 0,    1, '按年重置', now());

-- ========== 原 V23__table_column.sql ==========
-- 表格自定义列持久化：每用户 + 每表格一条列配置（顺序/显隐/列宽 JSON）
CREATE TABLE sys_table_column (
	id          BIGINT       PRIMARY KEY,
	user_id     BIGINT       NOT NULL,
	table_key   VARCHAR(128) NOT NULL,
	config_json TEXT,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_table_column_user ON sys_table_column (user_id, table_key);

COMMENT ON TABLE sys_table_column IS '表格自定义列配置（每用户每表一条）';
COMMENT ON COLUMN sys_table_column.table_key IS '表格标识：前端页面唯一 key';
COMMENT ON COLUMN sys_table_column.config_json IS '列配置 JSON：[{key,visible,width}] 顺序即数组序';

-- ========== 原 V24__help_doc.sql ==========
-- 在线帮助文档：目录树 + 文档(富文本+浏览量) + 页面绑定(按路由关联)
CREATE TABLE help_catalog (
	id          BIGINT       PRIMARY KEY,
	parent_id   BIGINT       NOT NULL DEFAULT 0,
	name        VARCHAR(128) NOT NULL,
	sort        INT          NOT NULL DEFAULT 0,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);
COMMENT ON TABLE help_catalog IS '帮助文档目录（树形，parent_id=0 为根）';

CREATE TABLE help_doc (
	id          BIGINT        PRIMARY KEY,
	catalog_id  BIGINT        NOT NULL,
	title       VARCHAR(255)  NOT NULL,
	content     TEXT,
	view_count  BIGINT        NOT NULL DEFAULT 0,
	sort        INT           NOT NULL DEFAULT 0,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT           NOT NULL DEFAULT 0
);
CREATE INDEX idx_help_doc_catalog ON help_doc (catalog_id);
COMMENT ON TABLE help_doc IS '帮助文档（富文本内容，view_count 累计浏览量）';

CREATE TABLE help_page_binding (
	id          BIGINT        PRIMARY KEY,
	route_path  VARCHAR(255)  NOT NULL,
	doc_id      BIGINT        NOT NULL,
	sort        INT           NOT NULL DEFAULT 0,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT           NOT NULL DEFAULT 0
);
CREATE INDEX idx_help_binding_route ON help_page_binding (route_path);
CREATE UNIQUE INDEX uk_help_binding ON help_page_binding (route_path, doc_id);
COMMENT ON TABLE help_page_binding IS '帮助文档与前端路由的绑定（一页可绑多文档）';

-- 示例种子：系统管理帮助 → 如何新增用户，绑定 /system/user
INSERT INTO help_catalog (id, parent_id, name, sort, create_time) VALUES
(1070000000000000001, 0, '系统管理帮助', 1, now());
INSERT INTO help_doc (id, catalog_id, title, content, view_count, sort, create_time) VALUES
(1070000000000000101, 1070000000000000001, '如何新增用户',
 '<h3>新增用户步骤</h3><p>1. 进入「用户管理」页面；</p><p>2. 点击左上角<strong>新增用户</strong>按钮；</p><p>3. 填写用户名、昵称、手机号后保存即可。</p>', 0, 1, now());
INSERT INTO help_page_binding (id, route_path, doc_id, sort, create_time) VALUES
(1070000000000000201, '/system/user', 1070000000000000101, 1, now());

-- ========== 原 V25__feedback_changelog.sql ==========
-- 意见反馈（含附件引用）+ 版本更新记录（类型分类富文本）
CREATE TABLE sys_feedback (
	id          BIGINT       PRIMARY KEY,
	content     TEXT         NOT NULL,
	contact     VARCHAR(64),
	attach_id   BIGINT,
	attach_name VARCHAR(255),
	attach_url  VARCHAR(512),
	user_id     BIGINT,
	status      INT          NOT NULL DEFAULT 0,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);
COMMENT ON TABLE sys_feedback IS '意见反馈';
COMMENT ON COLUMN sys_feedback.status IS '处理状态：0未处理/1已处理';

CREATE TABLE sys_changelog (
	id           BIGINT       PRIMARY KEY,
	version      VARCHAR(32)  NOT NULL,
	type         VARCHAR(16)  NOT NULL,
	title        VARCHAR(255) NOT NULL,
	content      TEXT,
	publish_time TIMESTAMP,
	sort         INT          NOT NULL DEFAULT 0,
	create_time  TIMESTAMP,
	update_time  TIMESTAMP,
	is_deleted   INT          NOT NULL DEFAULT 0
);
COMMENT ON TABLE sys_changelog IS '版本更新记录';
COMMENT ON COLUMN sys_changelog.type IS '类型：feature新增/optimize优化/fix修复';

-- 更新日志种子（首页卡即时展示）
INSERT INTO sys_changelog (id, version, type, title, content, publish_time, sort, create_time) VALUES
(1080000000000000001, 'v1.2.0', 'feature', '新增在线帮助文档与全局帮助抽屉', '<p>支持按页面绑定帮助文档，右侧抽屉随当前页展示。</p>', now(), 3, now()),
(1080000000000000002, 'v1.1.0', 'optimize', '表格支持自定义列并持久化', '<p>列顺序、显隐、宽度可保存，刷新与重登后保持。</p>', now(), 2, now()),
(1080000000000000003, 'v1.0.0', 'fix', '修复若干已知问题并发布首个稳定版', '<p>修复登录与权限相关问题，平台首个稳定版本。</p>', now(), 1, now());

-- ========== 原 V26__message.sql ==========
-- 消息中心/站内信：消息主体 + 收件人未读态 + 占位模板
CREATE TABLE sys_message (
	id          BIGINT       PRIMARY KEY,
	title       VARCHAR(255) NOT NULL,
	content     TEXT,
	type        VARCHAR(16)  NOT NULL DEFAULT 'system',
	sender_id   BIGINT,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);
COMMENT ON TABLE sys_message IS '站内信消息主体';
COMMENT ON COLUMN sys_message.type IS '类型：system系统/notice通知/todo待办';

CREATE TABLE sys_message_user (
	id          BIGINT    PRIMARY KEY,
	message_id  BIGINT    NOT NULL,
	user_id     BIGINT    NOT NULL,
	is_read     INT       NOT NULL DEFAULT 0,
	read_time   TIMESTAMP,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_message_user_unread ON sys_message_user (user_id, is_read);
CREATE UNIQUE INDEX uk_message_user ON sys_message_user (message_id, user_id);
COMMENT ON TABLE sys_message_user IS '站内信收件人（每收件人一条读未读状态）';

CREATE TABLE sys_message_template (
	id          BIGINT       PRIMARY KEY,
	code        VARCHAR(64)  NOT NULL,
	title       VARCHAR(255) NOT NULL,
	content     TEXT,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_message_template_code ON sys_message_template (code);
COMMENT ON TABLE sys_message_template IS '站内信模板（title/content 含 ${key} 占位）';

-- 模板种子（${} 占位）
INSERT INTO sys_message_template (id, code, title, content, create_time) VALUES
(1090000000000000001, 'welcome', '欢迎 ${name}',
 '<p>你好 ${name}，欢迎加入 Mugsun 平台！你的角色是 ${role}，如有疑问可查看右侧帮助文档。</p>', now());

-- ========== 原 V27__dict_color_gen_config.sql ==========
-- G48 补漏：字典项标签颜色 + 代码生成配置持久化
ALTER TABLE sys_dict ADD COLUMN color VARCHAR(32);
ALTER TABLE sys_dict_biz ADD COLUMN color VARCHAR(32);
COMMENT ON COLUMN sys_dict.color IS '标签颜色（十六进制，前端着色）';

CREATE TABLE gen_config (
	id           BIGINT       PRIMARY KEY,
	table_name   VARCHAR(128) NOT NULL,
	base_package VARCHAR(255),
	table_prefix VARCHAR(64),
	create_time  TIMESTAMP,
	update_time  TIMESTAMP,
	is_deleted   INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_gen_config_table ON gen_config (table_name);
COMMENT ON TABLE gen_config IS '代码生成表配置（按表名持久化）';

-- ========== 原 V28__notice_enhance.sql ==========
-- G49 通知公告增强：可见范围 + 阅读记录/UV
ALTER TABLE sys_notice ADD COLUMN all_visible INT NOT NULL DEFAULT 1;
ALTER TABLE sys_notice ADD COLUMN view_uv     INT NOT NULL DEFAULT 0;
ALTER TABLE sys_notice ADD COLUMN view_pv     INT NOT NULL DEFAULT 0;
COMMENT ON COLUMN sys_notice.all_visible IS '1全部可见/0按范围';
COMMENT ON COLUMN sys_notice.view_uv IS '浏览用户数(去重)';
COMMENT ON COLUMN sys_notice.view_pv IS '浏览次数(累计)';

-- 可见范围（主体+关联，对齐 sys_message_user 风格）
CREATE TABLE sys_notice_scope (
	id          BIGINT PRIMARY KEY,
	notice_id   BIGINT NOT NULL,
	scope_type  INT    NOT NULL,
	scope_id    BIGINT NOT NULL,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT    NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_notice_scope ON sys_notice_scope (notice_id, scope_type, scope_id);
CREATE INDEX idx_notice_scope_lookup ON sys_notice_scope (scope_type, scope_id);
COMMENT ON TABLE sys_notice_scope IS '通知可见范围（scope_type 1员工/2部门）';

-- 阅读记录（每用户一行，read_count 累加）
CREATE TABLE sys_notice_read (
	id          BIGINT PRIMARY KEY,
	notice_id   BIGINT NOT NULL,
	user_id     BIGINT NOT NULL,
	read_count  INT    NOT NULL DEFAULT 1,
	first_time  TIMESTAMP,
	last_time   TIMESTAMP,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT    NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_notice_read ON sys_notice_read (notice_id, user_id);
COMMENT ON TABLE sys_notice_read IS '通知阅读记录（每用户一行，read_count 累加）';

-- ========== 原 V29__workbench_shortcut.sql ==========
-- G50 首页工作台：每用户快捷入口持久化
CREATE TABLE sys_workbench_shortcut (
	id          BIGINT PRIMARY KEY,
	user_id     BIGINT NOT NULL,
	config_json TEXT,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT    NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_workbench_shortcut_user ON sys_workbench_shortcut (user_id);
COMMENT ON TABLE sys_workbench_shortcut IS '工作台快捷入口（每用户一行，config_json 存 [{name,path}]）';

-- ========== 原 V30__role_dept.sql ==========
-- G52 数据权限注解化：角色-自定义部门关联（data_scope=5 时生效）
CREATE TABLE sys_role_dept (
	id          BIGINT PRIMARY KEY,
	role_id     BIGINT NOT NULL,
	dept_id     BIGINT NOT NULL,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT    NOT NULL DEFAULT 0
);
CREATE INDEX idx_role_dept_role ON sys_role_dept (role_id);
COMMENT ON TABLE sys_role_dept IS '角色自定义数据部门（角色 data_scope=5 时的可见部门集合）';

-- ========== 原 V31__user_oauth.sql ==========
-- G53 社交登录：用户第三方账号绑定（微信/支付宝/QQ 等，回调后按 source+open_id 绑定/登录）
CREATE TABLE sys_user_oauth (
	id          BIGINT PRIMARY KEY,
	user_id     BIGINT NOT NULL,
	source      VARCHAR(32) NOT NULL,
	open_id     VARCHAR(128) NOT NULL,
	union_id    VARCHAR(128),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_user_oauth_source_openid ON sys_user_oauth (source, open_id);
COMMENT ON TABLE sys_user_oauth IS '用户第三方账号绑定（社交登录 source+open_id 唯一）';

-- ========== 原 V32__oauth_client.sql ==========
CREATE TABLE sys_oauth_client (
	id                    BIGINT       PRIMARY KEY,
	tenant_id             VARCHAR(12),
	name                  VARCHAR(64)  NOT NULL,
	client_id             VARCHAR(64)  NOT NULL,
	client_secret         VARCHAR(128) NOT NULL,
	grant_types           VARCHAR(128) NOT NULL DEFAULT 'client_credentials',
	scopes                VARCHAR(255),
	redirect_uri          VARCHAR(255),
	access_token_validity INT          NOT NULL DEFAULT 7200,
	status                INT          NOT NULL DEFAULT 1,
	remark                VARCHAR(255),
	create_time           TIMESTAMP,
	update_time           TIMESTAMP,
	is_deleted            INT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_oauth_client_id ON sys_oauth_client (client_id);

CREATE TABLE sys_oauth_log (
	id          BIGINT       PRIMARY KEY,
	tenant_id   VARCHAR(12),
	client_id   VARCHAR(64)  NOT NULL,
	api_path    VARCHAR(255),
	scope       VARCHAR(128),
	status      INT          NOT NULL DEFAULT 1,
	ip          VARCHAR(64),
	msg         VARCHAR(255),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_oauth_log_client ON sys_oauth_log (client_id);

COMMENT ON TABLE sys_oauth_client IS 'OAuth2 客户端（开放平台）';
COMMENT ON COLUMN sys_oauth_client.client_id IS '客户端标识';
COMMENT ON COLUMN sys_oauth_client.client_secret IS '客户端密钥';
COMMENT ON COLUMN sys_oauth_client.grant_types IS '授权类型（逗号分隔：client_credentials,authorization_code）';
COMMENT ON COLUMN sys_oauth_client.scopes IS '可授权的接口范围（逗号分隔）';
COMMENT ON COLUMN sys_oauth_client.redirect_uri IS '授权码回调地址';
COMMENT ON COLUMN sys_oauth_client.access_token_validity IS '令牌有效期（秒）';
COMMENT ON COLUMN sys_oauth_client.status IS '状态：1启用 0停用';
COMMENT ON TABLE sys_oauth_log IS '开放接口调用日志';
COMMENT ON COLUMN sys_oauth_log.status IS '状态：1放行 0拒绝';

-- ========== 原 V33__tenant_package.sql ==========
CREATE TABLE sys_tenant_package (
	id          BIGINT       PRIMARY KEY,
	name        VARCHAR(64)  NOT NULL,
	menu_keys   TEXT,
	status      INT          NOT NULL DEFAULT 1,
	remark      VARCHAR(255),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

ALTER TABLE sys_tenant ADD COLUMN package_id BIGINT;

COMMENT ON TABLE sys_tenant_package IS '租户套餐（限定可用功能菜单）';
COMMENT ON COLUMN sys_tenant_package.menu_keys IS '套餐内可用菜单标识（前端路由 name，逗号分隔）';
COMMENT ON COLUMN sys_tenant_package.status IS '状态：1启用 0停用';
COMMENT ON COLUMN sys_tenant.package_id IS '所属套餐（NULL 表示不限功能）';

-- ========== 原 V34__tenant_datasource.sql ==========
CREATE TABLE sys_tenant_datasource (
	id          BIGINT       PRIMARY KEY,
	tenant_code VARCHAR(12)  NOT NULL,
	ds_url      VARCHAR(255) NOT NULL,
	ds_username VARCHAR(64)  NOT NULL,
	ds_password VARCHAR(128) NOT NULL,
	status      INT          NOT NULL DEFAULT 1,
	remark      VARCHAR(255),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_tenant_ds_code ON sys_tenant_datasource (tenant_code);

CREATE TABLE biz_customer (
	id          BIGINT       PRIMARY KEY,
	tenant_id   VARCHAR(12),
	name        VARCHAR(64)  NOT NULL,
	phone       VARCHAR(32),
	remark      VARCHAR(255),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

COMMENT ON TABLE sys_tenant_datasource IS '租户独立数据源配置（动态多源隔离）';
COMMENT ON COLUMN sys_tenant_datasource.tenant_code IS '租户编号';
COMMENT ON COLUMN sys_tenant_datasource.ds_url IS '独立库 JDBC URL';
COMMENT ON COLUMN sys_tenant_datasource.status IS '状态：1启用 0停用';
COMMENT ON TABLE biz_customer IS '演示业务实体（客户）——按租户独立数据源路由';

-- ========== 原 V35__form.sql ==========
CREATE TABLE sys_form (
	id          BIGINT       PRIMARY KEY,
	name        VARCHAR(64)  NOT NULL,
	form_key    VARCHAR(64)  NOT NULL,
	form_schema TEXT,
	form_option TEXT,
	status      INT          NOT NULL DEFAULT 1,
	remark      VARCHAR(255),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_form_key ON sys_form (form_key);

CREATE TABLE sys_form_data (
	id          BIGINT       PRIMARY KEY,
	form_key    VARCHAR(64)  NOT NULL,
	form_data   TEXT,
	submitter   BIGINT,
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_form_data_key ON sys_form_data (form_key);

COMMENT ON TABLE sys_form IS '低代码表单定义（form-create schema）';
COMMENT ON COLUMN sys_form.form_schema IS '表单规则 JSON（form-create rule）';
COMMENT ON COLUMN sys_form.form_option IS '表单配置 JSON（form-create option）';
COMMENT ON TABLE sys_form_data IS '低代码表单填报数据';
COMMENT ON COLUMN sys_form_data.form_data IS '填报数据 JSON';

-- ========== 原 V36__report_charts.sql ==========
ALTER TABLE sys_report ADD COLUMN charts TEXT;

COMMENT ON COLUMN sys_report.charts IS '多图表仪表盘配置 JSON：[{dataset,chartType,title}]';

-- ========== 原 V37__attach_base_path.sql ==========
ALTER TABLE sys_attach ADD COLUMN base_path VARCHAR(255);

COMMENT ON COLUMN sys_attach.base_path IS '存储平台基础路径（授权流式下载定位文件用）';

-- ========== 原 V38__dict_status_color.sql ==========
-- G59 字典运行时体系：为状态类字典项补标签颜色 + 新增登录结果字典
-- 用户状态标签着色（正常绿 / 停用红），供前端 ArtDictTag 纯字典驱动着色
UPDATE sys_dict SET color = '#67C23A' WHERE code = 'user_status' AND dict_key = '1';
UPDATE sys_dict SET color = '#F56C6C' WHERE code = 'user_status' AND dict_key = '0';

-- 登录结果字典（1 成功 / 0 失败），供登录日志页 ArtStatusTag 使用
INSERT INTO sys_dict (id, parent_id, code, dict_key, dict_value, sort, is_sealed, color, create_time, is_deleted) VALUES
(1050000000000000010, 0,                   'login_result', 'login_result', '登录结果', 0, 1, NULL,      now(), 0),
(1050000000000000011, 1050000000000000010, 'login_result', '1',            '成功',     1, 0, '#67C23A', now(), 0),
(1050000000000000012, 1050000000000000010, 'login_result', '0',            '失败',     2, 0, '#F56C6C', now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V39__user_button_perms.sql ==========
-- G61 权限双通道：用户管理下按钮权限（menu_type=F）+ 授予 datatest 角色部分按钮，供非超管按钮门控实测
-- 按钮权限挂在「用户管理」菜单（id=102821679786000104）下，StpInterface 由角色→菜单派生 permission 进 buttons
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted) VALUES
(1061000000000000001, 102821679786000104, '新增用户', 'F', 'sys:user:add',    1, now(), 0),
(1061000000000000002, 102821679786000104, '编辑用户', 'F', 'sys:user:edit',   2, now(), 0),
(1061000000000000003, 102821679786000104, '删除用户', 'F', 'sys:user:remove', 3, now(), 0),
(1061000000000000004, 102821679786000104, '用户授权', 'F', 'sys:user:grant',  4, now(), 0),
(1061000000000000005, 102821679786000104, '重置密码', 'F', 'sys:user:reset',  5, now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- datatest 角色（id=102852046756000121）仅授予「新增用户」，演示非超管按真实权限码显隐按钮
INSERT INTO sys_role_menu (id, role_id, menu_id, create_time, is_deleted) VALUES
(1061000000000000101, 102852046756000121, 1061000000000000001, now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V40__client_policy_login_log.sql ==========
-- G67 登录客户端差异化策略 + 登录日志增强
-- 登录客户端：平台级配置（无 tenant_id，规避 Flex 租户隔离，登录前无租户上下文即可加载）
CREATE TABLE sys_client (
	id              BIGINT PRIMARY KEY,
	client_id       VARCHAR(64) NOT NULL,
	client_name     VARCHAR(64) NOT NULL,
	captcha_enabled INT NOT NULL DEFAULT 1,       -- 图形验证码开关（1 开 / 0 关）
	max_online      INT NOT NULL DEFAULT 0,        -- 单账号最大在线终端数（0 = 不限）
	token_timeout   INT NOT NULL DEFAULT 2592000,  -- 令牌有效期（秒）
	status          INT NOT NULL DEFAULT 1,
	remark          VARCHAR(255),
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_sys_client_id ON sys_client (client_id);
COMMENT ON TABLE sys_client IS '登录客户端差异化策略（验证码开关/并发在线数/令牌有效期，一 client 一套）';

-- 内置客户端：web 管理后台（验证码开、不限在线、30 天）
INSERT INTO sys_client (id, client_id, client_name, captcha_enabled, max_online, token_timeout, status, create_time, is_deleted)
VALUES (6700000000000001, 'web', '管理后台', 1, 0, 2592000, 1, now(), 0);

-- 登录日志增强：User-Agent / 设备（tenant_id 列已存在，实体直接映射记录）
ALTER TABLE sys_login_log ADD COLUMN user_agent VARCHAR(512);
ALTER TABLE sys_login_log ADD COLUMN device     VARCHAR(32);

-- ========== 原 V41__oper_log_tenant.sql ==========
-- G68 操作日志租户化：@Async 落库经 TenantTaskDecorator 透传租户，验证异步不丢隔离
ALTER TABLE sys_oper_log ADD COLUMN tenant_id VARCHAR(12);
-- 存量日志回填默认租户，避免租户过滤后管理端视图为空
UPDATE sys_oper_log SET tenant_id = '000000' WHERE tenant_id IS NULL;

-- ========== 原 V42__tenant_lifecycle.sql ==========
-- G70 租户生命周期：停用开关 + 账号数上限（expire_time 已由 V6 提供）
ALTER TABLE sys_tenant ADD COLUMN status        INT NOT NULL DEFAULT 1;
ALTER TABLE sys_tenant ADD COLUMN account_count INT NOT NULL DEFAULT -1;

COMMENT ON COLUMN sys_tenant.status IS '状态：1正常 0停用（停用后该租户禁止登录与访问）';
COMMENT ON COLUMN sys_tenant.account_count IS '账号数上限（-1 不限制）';

-- ========== 原 V43__tenant_datasource_isolation.sql ==========
-- G71 数据源隔离系统化：隔离策略 + schema 模式 + 密码密文列宽
ALTER TABLE sys_tenant_datasource ADD COLUMN isolation_type SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE sys_tenant_datasource ADD COLUMN schema_name    VARCHAR(64);
ALTER TABLE sys_tenant_datasource ALTER COLUMN ds_password TYPE VARCHAR(255);

COMMENT ON COLUMN sys_tenant_datasource.isolation_type IS '隔离策略：1独立库(DATASOURCE) 2独立schema(同库search_path)';
COMMENT ON COLUMN sys_tenant_datasource.schema_name IS 'schema 隔离模式的目标 schema（PostgreSQL search_path）';
COMMENT ON COLUMN sys_tenant_datasource.ds_password IS '独立库密码（SM4 密文存储）';

-- ========== 原 V44__role_custom_sql.sql ==========
-- G73 数据权限：角色自定义 SQL 规则（data_scope=6 时按此 SQL 片段过滤）
ALTER TABLE sys_role ADD COLUMN custom_sql VARCHAR(512);
COMMENT ON COLUMN sys_role.custom_sql IS '自定义数据权限 SQL 片段（data_scope=6 生效，平台管理员配置）';

-- ========== 原 V45__user_field_perms.sql ==========
-- G74 列级/字段级权限：用户管理下敏感字段的查看/明文按钮权限（menu_type=F），供角色按字段授权
-- 挂在「用户管理」菜单（id=102821679786000104）下，StpInterface 由角色→菜单派生 permission 进 buttons，
-- RoleAwareMaskProcessor 按这些权限码裁决 明文/脱敏/不可见。
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted) VALUES
(1074000000000000001, 102821679786000104, '查看手机号', 'F', 'sys:user:phone',        10, now(), 0),
(1074000000000000002, 102821679786000104, '手机号明文', 'F', 'sys:user:phone:plain',  11, now(), 0),
(1074000000000000003, 102821679786000104, '查看身份证', 'F', 'sys:user:idcard',       12, now(), 0),
(1074000000000000004, 102821679786000104, '身份证明文', 'F', 'sys:user:idcard:plain', 13, now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- datatest 角色（id=102852046756000121）仅授予「查看手机号（脱敏）」：演示非超管按字段权限
-- 手机号见脱敏、身份证不可见（无 idcard 权），与超管全明文形成对照
INSERT INTO sys_role_menu (id, role_id, menu_id, create_time, is_deleted) VALUES
(1074000000000000101, 102852046756000121, 1074000000000000001, now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V46__gen_metadata.sql ==========
-- G75 代码生成器企业级重写：双表元数据（gen_table 表级 + gen_column 字段级在线配置）
-- 替代原单表 gen_config（仅 3 字段），支撑字段级控件/字典/查询项配置与表结构增量同步保留已编辑配置。
CREATE TABLE gen_table (
	id              BIGINT       PRIMARY KEY,
	table_name      VARCHAR(128) NOT NULL,
	table_comment   VARCHAR(255),
	entity_name     VARCHAR(128),
	module_name     VARCHAR(64),
	business_name   VARCHAR(64),
	function_name   VARCHAR(64),
	function_author VARCHAR(64),
	base_package    VARCHAR(128),
	table_prefix    VARCHAR(64),
	gen_type        VARCHAR(8)   NOT NULL DEFAULT 'zip',
	parent_menu_id  BIGINT,
	options         VARCHAR(512),
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_gen_table_name ON gen_table (table_name);

CREATE TABLE gen_column (
	id             BIGINT       PRIMARY KEY,
	table_id       BIGINT       NOT NULL,
	column_name    VARCHAR(128) NOT NULL,
	column_comment VARCHAR(255),
	column_type    VARCHAR(64),
	java_type      VARCHAR(64),
	java_field     VARCHAR(128),
	is_pk          INT          NOT NULL DEFAULT 0,
	is_increment   INT          NOT NULL DEFAULT 0,
	is_required    INT          NOT NULL DEFAULT 0,
	is_insert      INT          NOT NULL DEFAULT 1,
	is_edit        INT          NOT NULL DEFAULT 1,
	is_list        INT          NOT NULL DEFAULT 1,
	is_query       INT          NOT NULL DEFAULT 0,
	query_type     VARCHAR(16)  NOT NULL DEFAULT 'EQ',
	html_type      VARCHAR(32)  NOT NULL DEFAULT 'input',
	dict_type      VARCHAR(64),
	sort           INT          NOT NULL DEFAULT 0,
	create_time    TIMESTAMP,
	update_time    TIMESTAMP,
	is_deleted     INT          NOT NULL DEFAULT 0
);
CREATE INDEX idx_gen_column_table ON gen_column (table_id);

-- 代码生成菜单权限码（配 GenController 写端点 @SaCheckPermission，挂系统管理下；admin 通配天然放行）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted) VALUES
(1075000000000000001, 102821679785000176, '代码生成', 'C', 'sys:gen:list',   90, now(), 0),
(1075000000000000002, 1075000000000000001, '导入表',  'F', 'sys:gen:import', 1,  now(), 0),
(1075000000000000003, 1075000000000000001, '配置',    'F', 'sys:gen:edit',   2,  now(), 0),
(1075000000000000004, 1075000000000000001, '预览生成','F', 'sys:gen:preview',3,  now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V47__drop_gen_config.sql ==========
-- G75 代码生成器重写：移除旧生成器的单表配置 gen_config（已由 gen_table + gen_column 双表元数据取代）
DROP TABLE IF EXISTS gen_config;

-- ========== 原 V48__gen_tpl_category.sql ==========
-- G76 主子表 + 树表生成：gen_table 扩展模板类别与关联配置
-- tpl_category：crud（单表，默认）/ tree（树表）/ master（主子表一对多）
ALTER TABLE gen_table ADD COLUMN tpl_category      VARCHAR(16)  NOT NULL DEFAULT 'crud';
-- 树表：父级字段列名（如 parent_id），导入时自动识别
ALTER TABLE gen_table ADD COLUMN tree_parent_field VARCHAR(64);
-- 主子表：子表名 + 子表中指向主表的外键列 + 子表功能名
ALTER TABLE gen_table ADD COLUMN sub_table_name    VARCHAR(128);
ALTER TABLE gen_table ADD COLUMN sub_join_field    VARCHAR(64);

-- ========== 原 V49__gen_demo_tree_master.sql ==========
-- G76 代码生成器 树表 / 主子表 生成演示目标表（供导入→配置→生成→drop-in 验收）
-- 树表：分类（parent_id 自关联）
CREATE TABLE gen_category (
	id            BIGINT       PRIMARY KEY,
	parent_id     BIGINT       NOT NULL DEFAULT 0,
	category_name VARCHAR(64),
	sort          INT          NOT NULL DEFAULT 0,
	create_time   TIMESTAMP,
	update_time   TIMESTAMP,
	is_deleted    INT          NOT NULL DEFAULT 0
);

-- 主子表：订单（主）+ 订单明细（子，order_id 外键）
CREATE TABLE gen_order (
	id          BIGINT         PRIMARY KEY,
	order_no    VARCHAR(64),
	amount      NUMERIC(12, 2),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT            NOT NULL DEFAULT 0
);

CREATE TABLE gen_order_item (
	id           BIGINT       PRIMARY KEY,
	order_id     BIGINT,
	product_name VARCHAR(128),
	qty          INT,
	create_time  TIMESTAMP,
	update_time  TIMESTAMP,
	is_deleted   INT          NOT NULL DEFAULT 0
);

-- ========== 原 V50__gen_ddl_rename.sql ==========
-- G78 动态建表：字段改名追踪列 + DDL 执行权限码。
-- column_name_old 记录列的旧名，增量同步据此走 RENAME 而非 DROP+ADD，保数据不丢。
ALTER TABLE gen_column ADD COLUMN column_name_old VARCHAR(128);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted) VALUES
(1075000000000000005, 1075000000000000001, '动态建表', 'F', 'sys:gen:ddl', 4, now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V51__dept_leader.sql ==========
-- G80 多候选人：部门负责人字段，供"发起人部门负责人"候选人解析（assignment 监听器读取）。
ALTER TABLE sys_dept ADD COLUMN leader_id BIGINT;

-- ========== 原 V52__oper_log_tamper_proof.sql ==========
-- G85 操作日志防篡改：哈希链 + SM2 签名（等保三级审计完整性）
ALTER TABLE sys_oper_log ADD COLUMN prev_hash   VARCHAR(64);
ALTER TABLE sys_oper_log ADD COLUMN record_hash VARCHAR(64);
ALTER TABLE sys_oper_log ADD COLUMN sign        TEXT;

COMMENT ON COLUMN sys_oper_log.prev_hash   IS '前一条记录哈希（链式防篡改，首条为创世 0）';
COMMENT ON COLUMN sys_oper_log.record_hash IS '本条记录 SM3 哈希（含 prev_hash，任一字段改动即失配）';
COMMENT ON COLUMN sys_oper_log.sign        IS '本条记录哈希的 SM2 签名（验签防伪造）';

-- ========== 原 V53__notify.sql ==========
-- G88 多渠道消息统一调度：统一通知模板 + 渠道配置 + 发送流水；sys_user 补邮箱列（邮件渠道联系方式）

-- 统一通知模板（渠道无关）：subject/content 含 ${key} 占位，required_params 保存期由渲染器抽取落库，
-- channels 为该模板默认投递渠道（逗号分隔渠道编码），发送方未显式指定渠道时按此 fan-out
CREATE TABLE sys_notify_template (
	id              BIGINT       PRIMARY KEY,
	code            VARCHAR(64)  NOT NULL,
	name            VARCHAR(128) NOT NULL,
	subject         VARCHAR(255) NOT NULL,
	content         TEXT         NOT NULL,
	required_params VARCHAR(255),
	channels        VARCHAR(128) NOT NULL DEFAULT 'in_app',
	status          INT          NOT NULL DEFAULT 1,
	remark          VARCHAR(255),
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_notify_template_code ON sys_notify_template (code);
COMMENT ON TABLE sys_notify_template IS '统一通知模板（渠道无关，${key} 占位，缺参发送期 fail-fast）';
COMMENT ON COLUMN sys_notify_template.required_params IS '必传占位参数（保存期正则从 subject/content 抽取，逗号分隔）';
COMMENT ON COLUMN sys_notify_template.channels IS '默认投递渠道（逗号分隔：in_app/mail/sms，wechat_mp 预留）';

-- 渠道配置（平台级基础设施，与 sys_sms 平台级通道先例一致；config JSON 禁类名，按渠道编码映射 Java 配置类）
CREATE TABLE sys_notify_channel (
	id          BIGINT       PRIMARY KEY,
	channel     VARCHAR(16)  NOT NULL,
	name        VARCHAR(64)  NOT NULL,
	status      INT          NOT NULL DEFAULT 0,
	config      TEXT,
	secret      VARCHAR(255),
	remark      VARCHAR(255),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_notify_channel ON sys_notify_channel (channel);
COMMENT ON TABLE sys_notify_channel IS '通知渠道配置（库表驱动热更新；secret 列 SM4 加密存凭据，如 SMTP 密码）';
COMMENT ON COLUMN sys_notify_channel.config IS '渠道非敏感配置 JSON（mail: host/port/username/from；in_app/sms 为空）';

-- 发送流水：一次 fan-out 按（渠道 × 接收人）一行，batch_id 关联同一次业务事件——
-- 重试/回执粒度须精确到单渠道单接收人（邮件 A 成功 B 失败可独立重试），故不采用"一单到底"。
-- append-only 流水不带 is_deleted（无逻辑删除语义，清理走归档/物理删除）
CREATE TABLE sys_notify_record (
	id               BIGINT      PRIMARY KEY,
	batch_id         BIGINT      NOT NULL,
	tenant_id        VARCHAR(12),
	template_code    VARCHAR(64)  NOT NULL,
	channel          VARCHAR(16)  NOT NULL,
	receiver_id      BIGINT,
	receiver_contact VARCHAR(255),
	subject          VARCHAR(255),
	content          TEXT,
	content_summary  VARCHAR(512),
	status           VARCHAR(16)  NOT NULL DEFAULT 'INIT',
	error_msg        VARCHAR(512),
	cost_ms          BIGINT,
	retry_count      INT          NOT NULL DEFAULT 0,
	next_retry_time  TIMESTAMP,
	create_time      TIMESTAMP,
	update_time      TIMESTAMP
);
CREATE INDEX idx_notify_record_batch ON sys_notify_record (batch_id);
CREATE INDEX idx_notify_record_retry ON sys_notify_record (status, next_retry_time);
COMMENT ON TABLE sys_notify_record IS '通知发送流水（租户隔离；状态 INIT/IGNORE/SUCCESS/FAILURE/DEAD，回执异步回填）';
COMMENT ON COLUMN sys_notify_record.receiver_contact IS '发送时联系方式快照（站内信=用户id，邮件=邮箱，短信=手机号）';
COMMENT ON COLUMN sys_notify_record.content_summary IS '渲染后内容摘要（截断 500 字符，列表展示用；重试取 content 全量）';

-- 邮件渠道联系方式：sys_user 补邮箱列（可空，管理端/API 录入，邮件渠道缺邮箱时该接收人记 IGNORE）
ALTER TABLE sys_user ADD COLUMN email VARCHAR(128);
COMMENT ON COLUMN sys_user.email IS '邮箱（通知邮件渠道联系方式）';

-- 种子：welcome 统一模板（自 V26 站内信模板迁移，去掉建用户时未知的 ${role}，仅保留 ${name}；默认仅站内信渠道）
INSERT INTO sys_notify_template (id, code, name, subject, content, required_params, channels, status, remark, create_time, is_deleted) VALUES
(1090000000000000010, 'welcome', '新用户欢迎通知', '欢迎 ${name}',
 '<p>你好 ${name}，欢迎加入 Mugsun 平台！如有疑问可查看右侧帮助文档。</p>',
 'name', 'in_app', 1, '新建用户成功后多渠道触达（V26 sys_message_template welcome 的统一模板化）', now(), 0);

-- 种子：渠道配置——站内信/短信默认启用（短信委托 sys_sms 平台级通道），邮件停用占位（配真实 SMTP 后启用）
INSERT INTO sys_notify_channel (id, channel, name, status, config, remark, create_time, is_deleted) VALUES
(1090000000000000021, 'in_app', '站内信', 1, '{}', '委托消息中心 MessageService（保留实时推送）', now(), 0),
(1090000000000000022, 'mail', '邮件', 0, '{"host":"smtp.example.com","port":465,"username":"","from":"noreply@mugsun.com"}', 'SMTP 凭据密码存 secret 列（SM4 加密）', now(), 0),
(1090000000000000023, 'sms', '短信', 1, '{}', '委托 SmsService（复用 sys_sms 库表热配置，不手写厂商签名）', now(), 0);

-- 种子：失败重试参数（代码常量兜底默认，此处落库支持运行时调整）
INSERT INTO sys_param (id, param_name, param_key, param_value, remark, create_time, is_deleted) VALUES
(900012, '通知重试扫描间隔(毫秒)', 'notify.retry.scan-interval-ms', '60000', '调度按此间隔扫描 FAILURE 且到期的发送流水', now(), 0),
(900013, '通知重试最大次数', 'notify.retry.max-times', '3', '单条流水最大重试次数，达到后转 DEAD 死信', now(), 0),
(900014, '通知重试退避基数(毫秒)', 'notify.retry.backoff-ms', '300000', '线性退避：下次重试时间 = 当前 + 基数 × 已重试次数', now(), 0);

-- ========== 原 V54__oss_cloud.sql ==========
-- G89 对象存储多云：分片登记（FileRecorder.saveFilePart 落库，支撑分片上传/断点续传的分片追踪与清理）
-- + 私有附件预签名下载 URL 有效期参数种子

-- 分片上传的分片登记：x-file-storage FileRecorder 语义对齐 FilePartInfo 字段，
-- upload_id 关联一次分片上传会话，完成/中止时按 upload_id 级联清理（deleteFilePartByUploadId）
CREATE TABLE sys_attach_part (
	id            BIGINT       PRIMARY KEY,
	tenant_id     VARCHAR(12),
	platform      VARCHAR(64)  NOT NULL,
	upload_id     VARCHAR(128) NOT NULL,
	e_tag         VARCHAR(255),
	part_number   INT,
	part_size     BIGINT,
	hash_info     VARCHAR(512),
	last_modified TIMESTAMP,
	create_time   TIMESTAMP,
	update_time   TIMESTAMP,
	is_deleted    INT          NOT NULL DEFAULT 0
);
CREATE INDEX idx_attach_part_upload ON sys_attach_part (upload_id);
COMMENT ON TABLE sys_attach_part IS '附件分片登记（x-file-storage 分片上传会话的分片追踪，upload_id 级联清理）';
COMMENT ON COLUMN sys_attach_part.upload_id IS '分片上传会话标识（平台侧 multipart uploadId）';
COMMENT ON COLUMN sys_attach_part.e_tag IS '分片 ETag（completeMultipartUpload 合并凭证）';
COMMENT ON COLUMN sys_attach_part.hash_info IS '分片摘要 JSON（x-file-storage HashInfo 序列化，可空）';

-- 私有附件预签名下载 URL 有效期（秒）：云平台私有附件 download/{id} 签发限时 URL 用
INSERT INTO sys_param (id, param_name, param_key, param_value, remark, create_time, is_deleted) VALUES
(1091000000000000001, '私有下载签名URL有效期', 'oss.presigned-url-expire-seconds', '300',
 '云平台私有附件 download/{id} 签发的预签名 URL 有效期（秒），过期须重新授权获取', now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V55__monitor_observability.sql ==========
-- G90 全局可观测：访问日志（轻表无哈希链）+ 错误日志处理闭环 + 监控参数种子 + 权限锚点

-- 访问日志：全量请求流量（含 GET，采样仅对 GET 生效），轻表无哈希链——
-- 刻意不复用 sys_oper_log 落库路径（其 SM3 哈希链 synchronized 串行点无法承受全量 GET 流量）
CREATE TABLE sys_api_log (
	id            BIGINT PRIMARY KEY,
	trace_id      VARCHAR(64),
	title         VARCHAR(255),
	method        VARCHAR(255),
	request_method VARCHAR(8),
	request_uri   VARCHAR(512),
	ip            VARCHAR(64),
	user_agent    VARCHAR(512),
	operator      VARCHAR(64),
	tenant_id     VARCHAR(12),
	status        INT,
	duration      BIGINT,
	slow          INT          NOT NULL DEFAULT 0,
	params        TEXT,
	error_msg     VARCHAR(512),
	create_time   TIMESTAMP,
	update_time   TIMESTAMP,
	is_deleted    INT          NOT NULL DEFAULT 0
);
CREATE INDEX idx_api_log_trace ON sys_api_log (trace_id);
CREATE INDEX idx_api_log_time ON sys_api_log (create_time);
CREATE INDEX idx_api_log_slow ON sys_api_log (slow);
COMMENT ON TABLE sys_api_log IS '访问日志（全量请求流水；慢接口 slow=1 必记不受采样影响；params 结构化递归脱敏截断）';
COMMENT ON COLUMN sys_api_log.trace_id IS '全站链路追踪号（与响应头 X-Trace-Id 一致）';
COMMENT ON COLUMN sys_api_log.title IS '接口标题（@OperationLog ＞ @Operation ＞ @Tag ＞ uri 回退链）';
COMMENT ON COLUMN sys_api_log.status IS 'HTTP 响应状态码';
COMMENT ON COLUMN sys_api_log.slow IS '慢接口标记：1 超 monitor.access-log.slow-ms';

-- 错误日志：全局未捕获异常落库，栈顶四元组精确定位 + 处理闭环（0 未处理/1 已处理/2 已忽略认领）
CREATE TABLE sys_error_log (
	id              BIGINT PRIMARY KEY,
	trace_id        VARCHAR(64),
	request_uri     VARCHAR(512),
	request_method  VARCHAR(8),
	operator        VARCHAR(64),
	tenant_id       VARCHAR(12),
	exception_class VARCHAR(512),
	message         VARCHAR(1024),
	location_class  VARCHAR(255),
	location_file   VARCHAR(255),
	location_method VARCHAR(255),
	location_line   INT,
	stacktrace      TEXT,
	status          INT          NOT NULL DEFAULT 0,
	handle_user     VARCHAR(64),
	handle_note     VARCHAR(512),
	handle_time     TIMESTAMP,
	create_time     TIMESTAMP,
	update_time     TIMESTAMP,
	is_deleted      INT          NOT NULL DEFAULT 0
);
CREATE INDEX idx_error_log_trace ON sys_error_log (trace_id);
CREATE INDEX idx_error_log_status ON sys_error_log (status);
COMMENT ON TABLE sys_error_log IS '错误日志（未捕获异常；栈顶四元组定位；处理闭环 0 未处理/1 已处理/2 已忽略）';
COMMENT ON COLUMN sys_error_log.location_class IS '栈顶定位：首个 com.mugsun 业务栈帧类名（无则首帧）';
COMMENT ON COLUMN sys_error_log.stacktrace IS '完整堆栈（截断 8000）';

-- 监控参数种子（代码常量兜底默认，此处落库支持运行时调整）
INSERT INTO sys_param (id, param_name, param_key, param_value, remark, create_time, is_deleted) VALUES
(900015, '访问日志采样率(%)', 'monitor.access-log.sample-rate', '100', '仅对 GET 生效（写操作有 oper_log 留痕）；慢接口必记不受采样影响', now(), 0),
(900016, '慢接口阈值(毫秒)', 'monitor.access-log.slow-ms', '1000', '超过必记且 slow=1 标记', now(), 0),
(900017, '日志保留天数', 'monitor.log.retention-days', '30', 'api_log/error_log/oper_log 超期物理清理', now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- 监控权限锚点：页面菜单为前端静态路由驱动（不走 DB），此处仅作权限码载体供角色→菜单派生 buttons
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted) VALUES
(1092000000000000001, 0, '访问日志', 'C', 'sys:api-log:list', 90, now(), 0),
(1092000000000000002, 0, '错误日志', 'C', 'sys:error-log:list', 91, now(), 0),
(1092000000000000003, 1092000000000000002, '处理错误', 'F', 'sys:error-log:handle', 1, now(), 0),
(1092000000000000004, 1092000000000000002, '删除错误', 'F', 'sys:error-log:remove', 2, now(), 0),
(1092000000000000005, 0, '服务监控', 'C', 'sys:monitor:list', 92, now(), 0),
(1092000000000000006, 1092000000000000005, '数据库文档', 'F', 'sys:monitor:db-doc', 1, now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V56__production_hardening.sql ==========
-- V56 生产就绪加固：存量数据回填 + 唯一约束补齐 + 查询索引 + 脏数据清洗
-- 背景：15 路对抗审查发现的数据层问题集中修复（详见审查报告）。全部幂等（IF NOT EXISTS / 条件 UPDATE）。

-- ① sys_oauth_client 存量行 tenant_id 回填：G66 加列后存量为 NULL 形成「UI 不可见但仍可用」的鬼影凭据
UPDATE sys_oauth_client SET tenant_id = '000000' WHERE tenant_id IS NULL;

-- ② OAuth 客户端 redirect_uri 回填：fail-closed 改造后未登记 redirect_uri 的客户端禁止授权码模式，
--    存量两个 dev 客户端补登记前端调试回调（生产环境应改登真实客户端地址）
UPDATE sys_oauth_client SET redirect_uri = 'http://localhost:3006/#/oauth-debug'
WHERE (redirect_uri IS NULL OR redirect_uri = '') AND is_deleted = 0;

-- ③ 核心唯一约束（部分索引 WHERE is_deleted=0，与平台逻辑删除规约一致）
CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_code ON sys_tenant (tenant_code);
CREATE UNIQUE INDEX IF NOT EXISTS uk_param_key ON sys_param (param_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_dict_code_key ON sys_dict (code, dict_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_role ON sys_user_role (user_id, role_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_role_menu ON sys_role_menu (role_id, menu_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_role_dept ON sys_role_dept (role_id, dept_id);
CREATE INDEX IF NOT EXISTS uk_user_tenant_phone ON sys_user (tenant_id, phone);

-- ④ uk_serial_record 改部分唯一：原全量唯一使逻辑删除的旧记录永久阻塞同 code+date 新记录
DROP INDEX IF EXISTS uk_serial_record;
CREATE UNIQUE INDEX uk_serial_record ON sys_serial_number_record (serial_code, record_date);

-- ⑤ 高频日志表查询索引（Flex 租户过滤 + id 倒序分页场景）
CREATE INDEX IF NOT EXISTS idx_oper_log_tenant_id ON sys_oper_log (tenant_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_login_log_id ON sys_login_log (id DESC);
CREATE INDEX IF NOT EXISTS idx_api_log_tenant_id ON sys_api_log (tenant_id, id DESC);

-- ⑥ 脱敏值污染清洗：sensitive1 的手机号曾被脱敏串写回（写门控已堵路径，存量置 NULL）
UPDATE sys_user SET phone = NULL WHERE phone LIKE '%*%';

-- ⑦ G85 篡改样本复原：验收期被改 IP 的操作日志记录恢复原值（record_hash 系按原值计算，复原后链验签通过）
UPDATE sys_oper_log SET ip = '0:0:0:0:0:0:0:1' WHERE id = 103432781770000138 AND ip = '9.9.9.9';

-- ⑧ 已逻辑删除租户级联清理：其下用户/角色/部门同步逻辑删除（防「租户恢复」场景旧账号静默复活）
UPDATE sys_user SET is_deleted = 1, update_time = now()
  WHERE is_deleted = 0 AND tenant_id IN (SELECT tenant_code FROM sys_tenant WHERE is_deleted = 1);
UPDATE sys_role SET is_deleted = 1, update_time = now()
  WHERE is_deleted = 0 AND tenant_id IN (SELECT tenant_code FROM sys_tenant WHERE is_deleted = 1);
UPDATE sys_dept SET is_deleted = 1, update_time = now()
  WHERE is_deleted = 0 AND tenant_id IN (SELECT tenant_code FROM sys_tenant WHERE is_deleted = 1);

-- ⑨ V39/V45/V46 硬编码雪花 ID 说明：种子菜单/授权的父级/角色锚定需待 DataInitializer 播种后按业务键修正
--    （迁移期锚点菜单尚不存在，无法在此修复；由 DataInitializer 启动时按 permission/role_code 幂等重锚）

-- ⑩ 孤儿历史密码清理：user_id 在 sys_user（含逻辑删除）完全不存在的审计残留
DELETE FROM sys_password_log WHERE user_id NOT IN (SELECT id FROM sys_user);

-- ========== 原 V57__gen_table_tenant.sql ==========
-- V57 存量低代码表租户隔离补齐：gen 托管物理表统一补 tenant_id 列并回填平台租户
-- 背景：对抗审查发现低代码建表（V57 前）无 tenant_id 列，多租户共享读写违反隔离铁律；
--       DdlService.buildCreate 已改为新建默认携带，本迁移补齐存量。幂等（存在即跳过）。

DO $$
DECLARE t RECORD;
BEGIN
  FOR t IN SELECT table_name FROM gen_table WHERE is_deleted = 0 LOOP
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = current_schema() AND table_name = t.table_name)
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = current_schema() AND table_name = t.table_name
                         AND column_name = 'tenant_id') THEN
      EXECUTE format('ALTER TABLE %I ADD COLUMN tenant_id VARCHAR(12)', t.table_name);
      -- 存量数据归平台租户（演示数据平台持有；租户自此只见本租户行）
      EXECUTE format('UPDATE %I SET tenant_id = ''000000'' WHERE tenant_id IS NULL', t.table_name);
    END IF;
  END LOOP;
END $$;

-- ========== 原 V58__menu_enhance.sql ==========
-- V58 菜单管理字段补齐（BladeX 标配）：图标 / 隐藏 / 页面缓存(keep-alive) / 外链新窗口
-- 全部幂等（ADD COLUMN）；存量行由 DEFAULT 回填：默认显示、缓存、非外链

ALTER TABLE sys_menu ADD COLUMN icon          VARCHAR(64);
ALTER TABLE sys_menu ADD COLUMN is_hide       INT NOT NULL DEFAULT 0;
ALTER TABLE sys_menu ADD COLUMN is_keep_alive INT NOT NULL DEFAULT 1;
ALTER TABLE sys_menu ADD COLUMN is_external   INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN sys_menu.icon IS '图标（Iconify 名，如 ri:user-line，与前端路由 meta.icon 同体系）';
COMMENT ON COLUMN sys_menu.is_hide IS '是否隐藏（0 显示 / 1 隐藏，隐藏后不出现在侧边栏）';
COMMENT ON COLUMN sys_menu.is_keep_alive IS '是否缓存页面（0 不缓存 / 1 缓存，对应路由 keepAlive）';
COMMENT ON COLUMN sys_menu.is_external IS '是否外链（0 否 / 1 是，外链新窗口打开）';

-- ========== 原 V59__login_log_ua_location.sql ==========
-- W2 登录日志增强：UA 解析（浏览器/操作系统）+ IP 归属地
-- browser/os 由登录写入时 Hutool UserAgentUtil 解析 user_agent 落列；login_location 由 ip2region 离线库解析
-- （开关 mugsun.ip2region.enabled 缺省关闭、xdb 外置，未开启/历史行一律为 NULL，列表直接展示空值）

ALTER TABLE sys_login_log ADD COLUMN browser        VARCHAR(64);
ALTER TABLE sys_login_log ADD COLUMN os             VARCHAR(64);
ALTER TABLE sys_login_log ADD COLUMN login_location VARCHAR(128);

COMMENT ON COLUMN sys_login_log.browser IS '浏览器（登录时 UA 解析落列）';
COMMENT ON COLUMN sys_login_log.os IS '操作系统（登录时 UA 解析落列）';
COMMENT ON COLUMN sys_login_log.login_location IS 'IP 归属地（ip2region 离线解析，开关关闭/内网/未命中为 NULL）';

-- ========== 原 V60__menu_route_seed.sql ==========
-- W3 后端菜单驱动：sys_menu 补 component/is_public 列 + 全量菜单树种子（幂等）
-- component：前端动态路由的视图路径（ComponentLoader 兼容），代码生成 menu.sql 产物已引用此列
-- is_public：公共菜单（任意登录用户可见：工作台/我的通知/我的消息/待办/审批/个人中心）

ALTER TABLE sys_menu ADD COLUMN is_public INT NOT NULL DEFAULT 0;
COMMENT ON COLUMN sys_menu.component IS '前端视图路径（如 /system/user → views/system/user/index.vue；目录为 /index/index）';
COMMENT ON COLUMN sys_menu.is_public IS '是否公共菜单（0 按角色授权 / 1 任意登录可见）';

-- 清理历史 E2E 遗留垃圾菜单
DELETE FROM sys_role_menu WHERE menu_id IN (SELECT id FROM sys_menu WHERE menu_name LIKE 'e2e_menu_%');
DELETE FROM sys_menu WHERE menu_name LIKE 'e2e_menu_%';

-- 系统管理目录自播种（全新库 Flyway 先于 DataInitializer 运行，须自给自足；幂等。
-- 必须先于下方 UPDATE/子菜单插入：否则全新库父级不存在，日志三件套滞留根级）
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, sort, icon, is_public, create_time, is_deleted)
SELECT 1200000000000000001, 0, '系统管理', '/system', '/index/index', 'M', 2, 'ri:settings-2-line', 0, now(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system' AND is_deleted = 0);
-- 用户管理自播种（按钮行（F）按业务键重锚依赖此节点存在）
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_public, create_time, is_deleted)
SELECT 1200000000000000101, (SELECT id FROM sys_menu WHERE path = '/system' AND is_deleted = 0 LIMIT 1),
	'用户管理', '/system/user', '/system/user', 'C', 'sys:user:list', 1, 'ri:user-line', 0, now(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/system/user' AND is_deleted = 0);

-- 既有节点归位（图标/组件/父级/排序），不动主键（角色授权引用不失效）
UPDATE sys_menu SET icon = 'ri:settings-2-line', component = '/index/index', sort = 2 WHERE path = '/system' AND is_deleted = 0;
UPDATE sys_menu SET icon = 'ri:user-line', component = '/system/user', menu_type = 'C', sort = 1, permission = 'sys:user:list' WHERE path = '/system/user' AND is_deleted = 0;
UPDATE sys_menu SET path = '/system/gen', component = '/system/gen', icon = 'ri:code-box-line', sort = 15
	WHERE permission = 'sys:gen:list' AND is_deleted = 0 AND (path IS NULL OR path = '');
UPDATE sys_menu SET path = '/system/api-log', component = '/system/api-log', icon = 'ri:global-line', sort = 32,
	parent_id = (SELECT id FROM sys_menu WHERE path = '/system' AND is_deleted = 0 LIMIT 1)
	WHERE permission = 'sys:api-log:list' AND is_deleted = 0 AND (path IS NULL OR path = '');
UPDATE sys_menu SET path = '/system/error-log', component = '/system/error-log', icon = 'ri:error-warning-line', sort = 33,
	parent_id = (SELECT id FROM sys_menu WHERE path = '/system' AND is_deleted = 0 LIMIT 1)
	WHERE permission = 'sys:error-log:list' AND is_deleted = 0 AND (path IS NULL OR path = '');
UPDATE sys_menu SET path = '/system/monitor', component = '/system/monitor', icon = 'ri:line-chart-line', sort = 34,
	parent_id = (SELECT id FROM sys_menu WHERE path = '/system' AND is_deleted = 0 LIMIT 1)
	WHERE permission = 'sys:monitor:list' AND is_deleted = 0 AND (path IS NULL OR path = '');

-- 工作台分组（console 公共：任何登录用户落点）
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, sort, icon, is_public, create_time, is_deleted)
SELECT v.id, v.pid, v.name, v.path, v.component, v.mtype, v.sort, v.icon, v.pub, now(), 0
FROM (VALUES
	(1200000000000000010, 0::bigint, '工作台', '/dashboard', '/index/index', 'M', 1, 'ri:dashboard-line', 0),
	(1200000000000000011, 1200000000000000010::bigint, '工作台', '/dashboard/console', '/dashboard/console', 'C', 1, 'ri:dashboard-line', 1)
) AS v(id, pid, name, path, component, mtype, sort, icon, pub)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = v.path AND x.is_deleted = 0);

-- 系统管理子菜单（parent 按 /system 解析；user/gen/api-log/error-log/monitor 已存在不重复插）
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, sort, icon, is_public, create_time, is_deleted)
SELECT v.id, p.id, v.name, v.path, v.component, 'C', v.sort, v.icon, v.pub, now(), 0
FROM (VALUES
	(1200000000000000102, '角色管理', '/system/role', '/system/role', 2, 'ri:user-settings-line', 0),
	(1200000000000000103, '部门管理', '/system/dept', '/system/dept', 3, 'ri:organization-chart', 0),
	(1200000000000000104, '岗位管理', '/system/post', '/system/post', 4, 'ri:contacts-book-line', 0),
	(1200000000000000105, '参数管理', '/system/param', '/system/param', 5, 'ri:settings-3-line', 0),
	(1200000000000000106, '接口加解密', '/system/crypto', '/system/crypto', 6, 'ri:shield-keyhole-line', 0),
	(1200000000000000107, '邮件模板', '/system/mail-template', '/system/mail-template', 7, 'ri:mail-line', 0),
	(1200000000000000108, '字典管理', '/system/dict', '/system/dict', 8, 'ri:book-2-line', 0),
	(1200000000000000109, '业务字典', '/system/dict-biz', '/system/dict-biz', 9, 'ri:book-marked-line', 0),
	(1200000000000000110, '通知公告', '/system/notice', '/system/notice', 10, 'ri:notification-2-line', 0),
	(1200000000000000111, '我的通知', '/system/my-notice', '/system/my-notice', 11, 'ri:mail-open-line', 1),
	(1200000000000000112, '附件管理', '/system/attach', '/system/attach', 12, 'ri:folder-2-line', 0),
	(1200000000000000113, '存储配置', '/system/oss', '/system/oss', 13, 'ri:cloud-line', 0),
	(1200000000000000114, '短信配置', '/system/sms', '/system/sms', 14, 'ri:message-2-line', 0),
	(1200000000000000116, '在线表单', '/system/online-form', '/system/online-form', 16, 'ri:table-line', 0),
	(1200000000000000117, '动态建表', '/system/gen-modeling', '/system/gen-modeling', 17, 'ri:database-2-line', 0),
	(1200000000000000118, '表单设计', '/system/form-designer', '/system/form-designer', 18, 'ri:file-edit-line', 0),
	(1200000000000000119, '流程定义', '/system/flow-def', '/system/flow-def', 19, 'ri:git-branch-line', 0),
	(1200000000000000120, '待办工作台', '/system/flow-todo', '/system/flow-todo', 20, 'ri:task-line', 1),
	(1200000000000000121, '审批中心', '/system/flow-center', '/system/flow-center', 21, 'ri:inbox-archive-line', 1),
	(1200000000000000122, '流程设计', '/system/flow-graph', '/system/flow-graph', 22, 'ri:node-tree', 0),
	(1200000000000000123, '定时任务', '/system/job', '/system/job', 23, 'ri:timer-line', 0),
	(1200000000000000124, '报表管理', '/system/report', '/system/report', 24, 'ri:bar-chart-2-line', 0),
	(1200000000000000125, '登录日志', '/system/login-log', '/system/login-log', 25, 'ri:shield-keyhole-line', 0),
	(1200000000000000126, '在线会话', '/system/online', '/system/online', 26, 'ri:computer-line', 0),
	(1200000000000000127, '登录客户端', '/system/client', '/system/client', 27, 'ri:device-line', 0),
	(1200000000000000128, '行政区划', '/system/region', '/system/region', 28, 'ri:map-pin-line', 0),
	(1200000000000000129, '操作日志', '/system/log', '/system/log', 29, 'ri:file-list-3-line', 0),
	(1200000000000000130, '变更记录', '/system/data-audit', '/system/data-audit', 30, 'ri:history-line', 0),
	(1200000000000000131, '帮助文档', '/system/help-doc', '/system/help-doc', 31, 'ri:question-line', 0),
	(1200000000000000135, '更新日志', '/system/changelog', '/system/changelog', 35, 'ri:git-branch-line', 0),
	(1200000000000000136, '意见反馈', '/system/feedback', '/system/feedback', 36, 'ri:feedback-line', 0),
	(1200000000000000137, '我的消息', '/system/message', '/system/message', 37, 'ri:notification-2-line', 1),
	(1200000000000000138, '发送站内信', '/system/message-send', '/system/message-send', 38, 'ri:send-plane-line', 0),
	(1200000000000000139, '消息模板', '/system/message-template', '/system/message-template', 39, 'ri:mail-settings-line', 0),
	(1200000000000000140, '缓存管理', '/system/cache', '/system/cache', 40, 'ri:database-2-line', 0),
	(1200000000000000141, '个人中心', '/system/user-center', '/system/user-center', 41, 'ri:user-line', 1),
	(1200000000000000142, '菜单管理', '/system/menu', '/system/menu', 42, 'ri:menu-line', 0)
) AS v(id, name, path, component, sort, icon, pub)
JOIN sys_menu p ON p.path = '/system' AND p.is_deleted = 0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = v.path AND x.is_deleted = 0);

-- 个人中心默认隐藏（经头像菜单进入，不占侧边栏）
UPDATE sys_menu SET is_hide = 1 WHERE path = '/system/user-center' AND is_deleted = 0;

-- 租户运营分组
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, sort, icon, is_public, create_time, is_deleted)
SELECT v.id, v.pid, v.name, v.path, v.component, v.mtype, v.sort, v.icon, v.pub, now(), 0
FROM (VALUES
	(1200000000000000020, 0::bigint, '租户运营', '/saas', '/index/index', 'M', 3, 'ri:community-line', 0),
	(1200000000000000201, 1200000000000000020::bigint, '租户管理', '/saas/tenant', '/system/tenant', 'C', 1, 'ri:building-line', 0),
	(1200000000000000202, 1200000000000000020::bigint, '租户套餐', '/saas/tenant-package', '/system/tenant-package', 'C', 2, 'ri:price-tag-3-line', 0),
	(1200000000000000203, 1200000000000000020::bigint, '租户数据源', '/saas/tenant-datasource', '/system/tenant-datasource', 'C', 3, 'ri:database-2-line', 0),
	(1200000000000000204, 1200000000000000020::bigint, '客户管理', '/saas/customer', '/system/customer', 'C', 4, 'ri:contacts-book-line', 0)
) AS v(id, pid, name, path, component, mtype, sort, icon, pub)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = v.path AND x.is_deleted = 0);

-- 开放平台分组
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, sort, icon, is_public, create_time, is_deleted)
SELECT v.id, v.pid, v.name, v.path, v.component, v.mtype, v.sort, v.icon, v.pub, now(), 0
FROM (VALUES
	(1200000000000000030, 0::bigint, '开放平台', '/open-platform', '/index/index', 'M', 4, 'ri:apps-2-line', 0),
	(1200000000000000031, 1200000000000000030::bigint, 'API密钥', '/open-platform/api-key', '/system/api-key', 'C', 1, 'ri:key-2-line', 0),
	(1200000000000000032, 1200000000000000030::bigint, '客户端管理', '/open-platform/oauth-client', '/system/oauth-client', 'C', 2, 'ri:apps-2-line', 0),
	(1200000000000000033, 1200000000000000030::bigint, '接口调试', '/open-platform/oauth-debug', '/system/oauth-debug', 'C', 3, 'ri:terminal-box-line', 0),
	(1200000000000000034, 1200000000000000030::bigint, '调用日志', '/open-platform/oauth-log', '/system/oauth-log', 'C', 4, 'ri:file-list-3-line', 0)
) AS v(id, pid, name, path, component, mtype, sort, icon, pub)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = v.path AND x.is_deleted = 0);

-- ========== 原 V61__user_avatar_forget_password.sql ==========
-- W5 登录体验增强：sys_user 补头像列 + 忘记密码邮件模板种子
-- avatar：个人中心头像（附件体系公开区 URL，实体未建模——SysUser 冻结，读写走 Db 行级 SQL）
-- forget_password 模板：忘记密码链路（/auth/forget-code）按 ${code} 渲染下发 6 位重置验证码

ALTER TABLE sys_user ADD COLUMN avatar VARCHAR(255);
COMMENT ON COLUMN sys_user.avatar IS '头像 URL（个人中心上传，/system/file/upload 公开区产物）';

-- 忘记密码验证码邮件模板（与 login_2fa 同格式；id 段沿用 91xxxx 邮件模板序列）
INSERT INTO sys_mail_template (id, code, name, subject, content, status, create_time, is_deleted) VALUES
 (910002, 'forget_password', '忘记密码重置验证码', '【Mugsun】密码重置验证码', '您的密码重置验证码是 ${code}，5 分钟内有效，请勿泄露。如非本人操作请忽略。', 1, NOW(), 0);

-- ========== 原 V62__status_dict_seed.sql ==========
-- W5-H6 字典标签铺开：notice 分类 / feedback 状态 / 流程实例状态 / 错误日志处理状态 字典种子，
-- 供前端 ArtDictTag 纯字典驱动着色，替代各页手写文案 map（颜色沿用 Element 色系）
INSERT INTO sys_dict (id, parent_id, code, dict_key, dict_value, sort, is_sealed, color, create_time, is_deleted) VALUES
-- 通知公告分类（views/system/notice 分类列）
(1050000000000000101, 0,                   'notice_category', 'notice_category', '通知公告分类', 0, 1, NULL,      now(), 0),
(1050000000000000102, 1050000000000000101, 'notice_category', 'notice',          '通知',        1, 0, '#409EFF', now(), 0),
(1050000000000000103, 1050000000000000101, 'notice_category', 'announcement',    '公告',        2, 0, '#67C23A', now(), 0),
(1050000000000000104, 1050000000000000101, 'notice_category', 'warning',         '预警',        3, 0, '#E6A23C', now(), 0),
-- 反馈处理状态（views/system/feedback 状态列：0 未处理 / 1 已处理）
(1050000000000000111, 0,                   'feedback_status', 'feedback_status', '反馈处理状态', 0, 1, NULL,      now(), 0),
(1050000000000000112, 1050000000000000111, 'feedback_status', '0',               '未处理',      1, 0, '#909399', now(), 0),
(1050000000000000113, 1050000000000000111, 'feedback_status', '1',               '已处理',      2, 0, '#67C23A', now(), 0),
-- 流程实例状态（views/system/flow-todo 抄送状态列/时间线，对应 warm-flow FlowStatus）
(1050000000000000121, 0,                   'flow_status', 'flow_status', '流程实例状态', 0,  1, NULL,      now(), 0),
(1050000000000000122, 1050000000000000121, 'flow_status', '0',           '待提交',       1,  0, '#909399', now(), 0),
(1050000000000000123, 1050000000000000121, 'flow_status', '1',           '审批中',       2,  0, '#409EFF', now(), 0),
(1050000000000000124, 1050000000000000121, 'flow_status', '2',           '已通过',       3,  0, '#67C23A', now(), 0),
(1050000000000000125, 1050000000000000121, 'flow_status', '3',           '自动完成',     4,  0, '#67C23A', now(), 0),
(1050000000000000126, 1050000000000000121, 'flow_status', '4',           '已终止',       5,  0, '#F56C6C', now(), 0),
(1050000000000000127, 1050000000000000121, 'flow_status', '5',           '已作废',       6,  0, '#F56C6C', now(), 0),
(1050000000000000128, 1050000000000000121, 'flow_status', '6',           '已撤销',       7,  0, '#909399', now(), 0),
(1050000000000000129, 1050000000000000121, 'flow_status', '7',           '已取回',       8,  0, '#909399', now(), 0),
(1050000000000000130, 1050000000000000121, 'flow_status', '8',           '已完成',       9,  0, '#67C23A', now(), 0),
(1050000000000000131, 1050000000000000121, 'flow_status', '9',           '已退回',       10, 0, '#E6A23C', now(), 0),
(1050000000000000132, 1050000000000000121, 'flow_status', '10',          '已失效',       11, 0, '#909399', now(), 0),
(1050000000000000133, 1050000000000000121, 'flow_status', '11',          '已拿回',       12, 0, '#E6A23C', now(), 0),
(1050000000000000134, 1050000000000000121, 'flow_status', '12',          '已重启',       13, 0, '#409EFF', now(), 0),
(1050000000000000135, 1050000000000000121, 'flow_status', '13',          '暂存',         14, 0, '#909399', now(), 0),
-- 错误日志处理状态（views/system/error-log 状态列：0 未处理 / 1 已处理 / 2 已忽略）
(1050000000000000141, 0,                   'error_log_status', 'error_log_status', '错误日志处理状态', 0, 1, NULL,      now(), 0),
(1050000000000000142, 1050000000000000141, 'error_log_status', '0',               '未处理',        1, 0, '#E6A23C', now(), 0),
(1050000000000000143, 1050000000000000141, 'error_log_status', '1',               '已处理',        2, 0, '#67C23A', now(), 0),
(1050000000000000144, 1050000000000000141, 'error_log_status', '2',               '已忽略',        3, 0, '#909399', now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V63__track.sql ==========
-- G99 埋点：业务库权限锚点与参数种子（埋点业务表在独立 track 库，见 db/track/migration/T1 起）
-- 菜单按 V60 全量路由风格（path/component/icon 驱动前端动态路由），挂 /system 目录下，sort 接续既有子菜单（已用至 42）

-- 埋点分析五页（C）：概览/事件/性能/错误/接入
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_public, create_time, is_deleted)
SELECT v.id, p.id, v.name, v.path, v.component, 'C', v.perm, v.sort, v.icon, 0, now(), 0
FROM (VALUES
	(1093000000000000001, '埋点概览', '/system/track-overview', '/system/track-overview', 'sys:track-overview:list', 43, 'ri:bar-chart-box-line'),
	(1093000000000000002, '事件分析', '/system/track-event',    '/system/track-event',    'sys:track-event:list',    44, 'ri:cursor-line'),
	(1093000000000000003, '性能分析', '/system/track-perf',     '/system/track-perf',     'sys:track-perf:list',     45, 'ri:speed-line'),
	(1093000000000000004, '错误监控', '/system/track-error',    '/system/track-error',    'sys:track-error:list',    46, 'ri:bug-line'),
	(1093000000000000005, '埋点接入', '/system/track-app',      '/system/track-app',      'sys:track-app:list',      47, 'ri:plug-line')
) AS v(id, name, path, component, perm, sort, icon)
JOIN sys_menu p ON p.path = '/system' AND p.is_deleted = 0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = v.path AND x.is_deleted = 0);

-- 埋点接入按钮权限（F，挂「埋点接入」页下；回放查看为 G100 预留）
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT v.id, 1093000000000000005, v.name, 'F', v.perm, v.sort, now(), 0
FROM (VALUES
	(1093000000000000006, '新增应用', 'sys:track-app:add',      1),
	(1093000000000000007, '编辑应用', 'sys:track-app:edit',     2),
	(1093000000000000008, '回放查看', 'sys:track-replay:view',  3)
) AS v(id, name, perm, sort)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.permission = v.perm AND x.is_deleted = 0);

-- 埋点参数种子（代码常量兜底默认见 TrackConstants，此处落库支持运行时调整）
INSERT INTO sys_param (id, param_name, param_key, param_value, remark, create_time, is_deleted) VALUES
(900018, '埋点摄入限流(次/分/IP)', 'track.collect.rate-limit', '600', 'collect 端点单 IP 滑窗限流', now(), 0),
(900019, '埋点单批最大事件数', 'track.collect.batch-max', '100', '超过截断并计数', now(), 0),
(900020, '埋点明细保留天数(默认)', 'track.retention-days', '90', '新应用默认值；分区清理依据', now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V64__track_menu_top.sql ==========
-- 埋点看板菜单升级：「埋点分析」从系统管理子菜单提升为顶级菜单（与工作台/系统管理同级，幂等）
-- 新结构：埋点分析(M /track) ├ 数据概览 /track/overview ├ 事件分析 /track/event
--         ├ 性能分析 /track/perf ├ 错误监控 /track/error └ 接入管理 /track/app
-- 会话回放页（/track/replay）为后续波次预留：页面未建不播菜单，避免菜单指向 404

-- 埋点分析顶级目录自播种（全新库 Flyway 先于 DataInitializer 运行，须自给自足；幂等。
-- 必须先于下方子菜单 UPDATE：否则全新库父级不存在，看板页滞留原目录）
INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, sort, icon, is_public, create_time, is_deleted)
SELECT 1093100000000000001, 0, '埋点分析', '/track', '/index/index', 'M', 2, 'ri:line-chart-line', 0, now(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/track' AND is_deleted = 0);

-- 根级排序归位：埋点分析紧跟工作台(1)之后，系统管理/租户运营/开放平台顺移一位（只动 sort，不动主键）
UPDATE sys_menu SET menu_name = '埋点分析', component = '/index/index', menu_type = 'M', parent_id = 0, sort = 2, icon = 'ri:line-chart-line'
	WHERE path = '/track' AND is_deleted = 0;
UPDATE sys_menu SET sort = 3 WHERE path = '/system' AND is_deleted = 0;
UPDATE sys_menu SET sort = 4 WHERE path = '/saas' AND is_deleted = 0;
UPDATE sys_menu SET sort = 5 WHERE path = '/open-platform' AND is_deleted = 0;

-- 看板五页（C）迁入埋点分析目录：path/component/parent/sort/名称归位，按权限业务键锚定，主键不动（角色授权不失效）
UPDATE sys_menu SET menu_name = '数据概览', path = '/track/overview', component = '/track/overview', sort = 1,
	parent_id = (SELECT id FROM sys_menu WHERE path = '/track' AND is_deleted = 0 LIMIT 1)
	WHERE permission = 'sys:track-overview:list' AND is_deleted = 0;
UPDATE sys_menu SET menu_name = '事件分析', path = '/track/event', component = '/track/event', sort = 2,
	parent_id = (SELECT id FROM sys_menu WHERE path = '/track' AND is_deleted = 0 LIMIT 1)
	WHERE permission = 'sys:track-event:list' AND is_deleted = 0;
UPDATE sys_menu SET menu_name = '性能分析', path = '/track/perf', component = '/track/perf', sort = 3,
	parent_id = (SELECT id FROM sys_menu WHERE path = '/track' AND is_deleted = 0 LIMIT 1)
	WHERE permission = 'sys:track-perf:list' AND is_deleted = 0;
UPDATE sys_menu SET menu_name = '错误监控', path = '/track/error', component = '/track/error', sort = 4,
	parent_id = (SELECT id FROM sys_menu WHERE path = '/track' AND is_deleted = 0 LIMIT 1)
	WHERE permission = 'sys:track-error:list' AND is_deleted = 0;
UPDATE sys_menu SET menu_name = '接入管理', path = '/track/app', component = '/track/app', sort = 5,
	parent_id = (SELECT id FROM sys_menu WHERE path = '/track' AND is_deleted = 0 LIMIT 1)
	WHERE permission = 'sys:track-app:list' AND is_deleted = 0;

-- 接入管理页按钮（F：sys:track-app:add/edit、sys:track-replay:view）parent 仍锚接入管理页主键（未变），无需调整

-- ========== 原 V65__track_replay.sql ==========
-- G100 会话回放：业务库权限锚点与参数种子（回放元数据/本体在 track 库与对象存储，见 T3 起）
-- 「会话回放」页挂 V64 提升后的顶级「埋点分析」目录（/track）下，sort 接续接入管理(5)；
-- V63 预留的「回放查看」按钮（F，sys:track-replay:view）改挂该页下，权限码不变（角色授权不失效）

INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_public, create_time, is_deleted)
SELECT 1093000000000000009, p.id, '会话回放', '/track/replay', '/track/replay', 'C', 'sys:track-replay:list', 6, 'ri:play-circle-line', 0, now(), 0
FROM sys_menu p
WHERE p.path = '/track' AND p.is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = '/track/replay' AND x.is_deleted = 0);

UPDATE sys_menu SET parent_id = 1093000000000000009
WHERE id = 1093000000000000008 AND permission = 'sys:track-replay:view' AND is_deleted = 0;

-- 回放参数种子（代码常量兜底默认见 TrackConstants，此处落库支持运行时调整）
INSERT INTO sys_param (id, param_name, param_key, param_value, remark, create_time, is_deleted) VALUES
(900021, '回放单会话累计上限(字节)', 'track.replay.session-max-bytes', '20971520', '解压后口径；超限 413 并封禁该会话后续块', now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V66__track_user_api.sql ==========
-- G102 用户细查：业务库权限锚点与参数种子（时间线索引/应用开关列在 track 库，见 T7）
-- 「用户细查」页挂顶级「埋点分析」目录（/track）下，sort 接续会话回放(6)；
-- 「查看接口响应体」按钮（F，sys:track-user:view-body）挂该页下（最高敏感，读取必留痕审计）

INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_public, create_time, is_deleted)
SELECT 1093000000000000010, p.id, '用户细查', '/track/user', '/track/user', 'C', 'sys:track-user:list', 7, 'ri:user-search-line', 0, now(), 0
FROM sys_menu p
WHERE p.path = '/track' AND p.is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = '/track/user' AND x.is_deleted = 0);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT 1093000000000000011, 1093000000000000010, '查看接口响应体', 'F', 'sys:track-user:view-body', 1, now(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.permission = 'sys:track-user:view-body' AND x.is_deleted = 0);

-- 响应体参数种子（代码常量兜底默认见 TrackConstants，此处落库支持运行时调整）
INSERT INTO sys_param (id, param_name, param_key, param_value, remark, create_time, is_deleted) VALUES
(900022, '接口响应体上限(字节)', 'track.api-body.max-bytes', '1048576', '单个接口响应体采集上限（安全阀，防大导出响应打爆存储）；解压后口径，超限 413 不采', now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V67__track_funnel_retention.sql ==========
-- G103 漏斗分析 + 留存分析：业务库权限锚点（两能力走埋点库明细限窗即席查询，无新表）
-- 「漏斗分析」「留存分析」页挂顶级「埋点分析」目录（/track）下，sort 接续用户细查(7)

INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_public, create_time, is_deleted)
SELECT 1093000000000000012, p.id, '漏斗分析', '/track/funnel', '/track/funnel', 'C', 'sys:track-funnel:list', 8, 'ri:filter-3-line', 0, now(), 0
FROM sys_menu p
WHERE p.path = '/track' AND p.is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = '/track/funnel' AND x.is_deleted = 0);

INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_public, create_time, is_deleted)
SELECT 1093000000000000013, p.id, '留存分析', '/track/retention', '/track/retention', 'C', 'sys:track-retention:list', 9, 'ri:user-heart-line', 0, now(), 0
FROM sys_menu p
WHERE p.path = '/track' AND p.is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = '/track/retention' AND x.is_deleted = 0);

-- ========== 原 V68__track_visual.sql ==========
-- G104 圈选式可视化埋点：业务库权限锚点（规则表 track_visual_rule 在埋点库，见 T8）
-- 「圈选规则」以 tab 形态挂在「接入管理」页内，不产生新 C 级菜单；此处仅落两个 F 级按钮权限码，
-- parent 锚定接入管理页（path=/track/app），幂等按 permission 查重（同既有 F 种子风格）

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT 1093000000000000014, p.id, '圈选规则查看', 'F', 'sys:track-visual:list', 10, now(), 0
FROM sys_menu p
WHERE p.path = '/track/app' AND p.is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.permission = 'sys:track-visual:list' AND x.is_deleted = 0);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT 1093000000000000015, p.id, '圈选规则管理', 'F', 'sys:track-visual:edit', 11, now(), 0
FROM sys_menu p
WHERE p.path = '/track/app' AND p.is_deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.permission = 'sys:track-visual:edit' AND x.is_deleted = 0);

-- ========== 原 V69__gis.sql ==========
-- GIS 可选模块（默认开启）：底图供应商表 + 场景表 + 顶级菜单 + 参数开关
-- 无密钥也可登录；工作台空态引导去配置底图。关闭 gis.module.enabled 后菜单隐藏、接口拒绝。

CREATE TABLE gis_map_provider (
	id          BIGINT       PRIMARY KEY,
	tenant_id   VARCHAR(12),
	provider    VARCHAR(32)  NOT NULL,
	enabled     INT          NOT NULL DEFAULT 1,
	api_key     VARCHAR(512),
	secret      VARCHAR(512),
	extra_json  TEXT,
	remark      VARCHAR(255),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_gis_map_provider ON gis_map_provider (tenant_id, provider);

CREATE TABLE gis_scene (
	id          BIGINT       PRIMARY KEY,
	tenant_id   VARCHAR(12),
	name        VARCHAR(128) NOT NULL,
	scene_json  TEXT,
	status      INT          NOT NULL DEFAULT 1,
	remark      VARCHAR(255),
	create_time TIMESTAMP,
	update_time TIMESTAMP,
	is_deleted  INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_gis_scene_tenant ON gis_scene (tenant_id);

COMMENT ON TABLE gis_map_provider IS 'GIS 底图供应商密钥（租户隔离，密钥密文）';
COMMENT ON COLUMN gis_map_provider.provider IS 'tianditu/amap/baidu/google';
COMMENT ON TABLE gis_scene IS 'GIS 场景（相机、底图、图层 JSON）';

INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, sort, icon, is_public, create_time, is_deleted)
SELECT 1094000000000000001, 0, '地理信息', '/gis', '/index/index', 'M', 6, 'ri:earth-line', 0, now(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/gis' AND is_deleted = 0);

INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_public, create_time, is_deleted)
SELECT v.id, p.id, v.name, v.path, v.component, 'C', v.perm, v.sort, v.icon, 0, now(), 0
FROM (VALUES
	(1094000000000000002, '地图工作台', '/gis/workspace', '/gis/workspace', 'gis:workspace:list', 1, 'ri:map-2-line'),
	(1094000000000000003, '底图配置', '/gis/provider', '/gis/provider', 'gis:provider:list', 2, 'ri:key-2-line')
) AS v(id, name, path, component, perm, sort, icon)
JOIN sys_menu p ON p.path = '/gis' AND p.is_deleted = 0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = v.path AND x.is_deleted = 0);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT v.id, v.parent, v.name, 'F', v.perm, v.sort, now(), 0
FROM (VALUES
	(1094000000000000004, 1094000000000000003, '保存底图', 'gis:provider:save', 1),
	(1094000000000000005, 1094000000000000003, '删除底图', 'gis:provider:remove', 2),
	(1094000000000000006, 1094000000000000002, '保存场景', 'gis:scene:save', 1),
	(1094000000000000007, 1094000000000000002, '删除场景', 'gis:scene:remove', 2)
) AS v(id, parent, name, perm, sort)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.permission = v.perm AND x.is_deleted = 0);

INSERT INTO sys_param (id, param_name, param_key, param_value, remark, create_time, is_deleted) VALUES
(900030, '地理信息模块开关', 'gis.module.enabled', 'true', 'false 时隐藏 GIS 菜单并拒绝 /system/gis 接口；无需任何底图 Key 即可关', now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V70__gis_layer.sql ==========
-- GIS 图层库 + 独立模块菜单（与埋点同级：工作台 / 图层 / 场景 / 底图）
CREATE TABLE gis_layer (
	id             BIGINT        PRIMARY KEY,
	tenant_id      VARCHAR(12),
	name           VARCHAR(128)  NOT NULL,
	kind           VARCHAR(32)   NOT NULL DEFAULT 'vector',
	crs            VARCHAR(32)   NOT NULL DEFAULT 'EPSG:4326',
	data_json      TEXT          NOT NULL,
	style_json     TEXT,
	feature_count  INT           NOT NULL DEFAULT 0,
	bbox           VARCHAR(128),
	status         INT           NOT NULL DEFAULT 1,
	remark         VARCHAR(255),
	create_time    TIMESTAMP,
	update_time    TIMESTAMP,
	is_deleted     INT           NOT NULL DEFAULT 0
);

CREATE INDEX idx_gis_layer_tenant ON gis_layer (tenant_id);

COMMENT ON TABLE gis_layer IS '通用 GIS 图层（WGS84 GeoJSON，跨模块叠加）';
COMMENT ON COLUMN gis_layer.kind IS 'vector/heatmap';
COMMENT ON COLUMN gis_layer.crs IS '规范化后固定 EPSG:4326';

UPDATE sys_menu SET sort = 3, menu_name = '地理信息', icon = 'ri:earth-line', parent_id = 0, menu_type = 'M'
	WHERE path = '/gis' AND is_deleted = 0;
UPDATE sys_menu SET sort = 4 WHERE path = '/system' AND is_deleted = 0;
UPDATE sys_menu SET sort = 5 WHERE path = '/saas' AND is_deleted = 0;
UPDATE sys_menu SET sort = 6 WHERE path = '/open-platform' AND is_deleted = 0;

UPDATE sys_menu SET sort = 1, menu_name = '地图工作台'
	WHERE path = '/gis/workspace' AND is_deleted = 0;
UPDATE sys_menu SET sort = 4, menu_name = '底图配置'
	WHERE path = '/gis/provider' AND is_deleted = 0;

INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_public, create_time, is_deleted)
SELECT v.id, p.id, v.name, v.path, v.component, 'C', v.perm, v.sort, v.icon, 0, now(), 0
FROM (VALUES
	(1094000000000000008, '图层', '/gis/layer', '/gis/layer', 'gis:layer:list', 2, 'ri:stack-line'),
	(1094000000000000009, '场景', '/gis/scene', '/gis/scene', 'gis:scene:list', 3, 'ri:landscape-line')
) AS v(id, name, path, component, perm, sort, icon)
JOIN sys_menu p ON p.path = '/gis' AND p.is_deleted = 0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = v.path AND x.is_deleted = 0);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT v.id, v.parent, v.name, 'F', v.perm, v.sort, now(), 0
FROM (VALUES
	(1094000000000000010, 1094000000000000008, '保存图层', 'gis:layer:save', 1),
	(1094000000000000011, 1094000000000000008, '删除图层', 'gis:layer:remove', 2)
) AS v(id, parent, name, perm, sort)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.permission = v.perm AND x.is_deleted = 0);

-- ========== 原 V71__gis_analyze.sql ==========
-- GIS 空间分析菜单 + 底图排序后移；图层 kind 注释扩至 xyz/wms
COMMENT ON COLUMN gis_layer.kind IS 'vector/heatmap/xyz/wms';

UPDATE sys_menu SET sort = 5, menu_name = '底图配置'
	WHERE path = '/gis/provider' AND is_deleted = 0;

INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_public, create_time, is_deleted)
SELECT v.id, p.id, v.name, v.path, v.component, 'C', v.perm, v.sort, v.icon, 0, now(), 0
FROM (VALUES
	(1094000000000000012, '空间分析', '/gis/analyze', '/gis/analyze', 'gis:analyze:run', 4, 'ri:shape-line')
) AS v(id, name, path, component, perm, sort, icon)
JOIN sys_menu p ON p.path = '/gis' AND p.is_deleted = 0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = v.path AND x.is_deleted = 0);

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, permission, sort, create_time, is_deleted)
SELECT v.id, v.parent, v.name, 'F', v.perm, v.sort, now(), 0
FROM (VALUES
	(1094000000000000013, 1094000000000000012, '执行空间运算', 'gis:analyze:run', 1)
) AS v(id, parent, name, perm, sort)
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.permission = v.perm AND x.id = v.id AND x.is_deleted = 0);

-- ========== 原 V72__gis_lab.sql ==========
-- GIS 示例中心（独立页，不挤工作台）；工作台仍为业务编辑入口
UPDATE sys_menu SET sort = 3 WHERE path = '/gis/layer' AND is_deleted = 0;
UPDATE sys_menu SET sort = 4 WHERE path = '/gis/scene' AND is_deleted = 0;
UPDATE sys_menu SET sort = 5 WHERE path = '/gis/analyze' AND is_deleted = 0;
UPDATE sys_menu SET sort = 6, menu_name = '底图配置' WHERE path = '/gis/provider' AND is_deleted = 0;

INSERT INTO sys_menu (id, parent_id, menu_name, path, component, menu_type, permission, sort, icon, is_public, create_time, is_deleted)
SELECT v.id, p.id, v.name, v.path, v.component, 'C', v.perm, v.sort, v.icon, 0, now(), 0
FROM (VALUES
	(1094000000000000014, '示例中心', '/gis/lab', '/gis/lab', 'gis:demo:list', 2, 'ri:play-circle-line')
) AS v(id, name, path, component, perm, sort, icon)
JOIN sys_menu p ON p.path = '/gis' AND p.is_deleted = 0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu x WHERE x.path = v.path AND x.is_deleted = 0);

-- ========== 原 V73__gis_ia.sql ==========
-- GIS 信息架构收口：侧栏只留「地图 / 示例 / 图层 / 底图」。
-- 场景 = 工作台里的文件切换；空间分析 = 工作台「更多」入口。路由保留，仅隐藏菜单。

UPDATE sys_menu SET menu_name = '地图', sort = 1
	WHERE path = '/gis/workspace' AND is_deleted = 0;

UPDATE sys_menu SET menu_name = '示例', sort = 2
	WHERE path = '/gis/lab' AND is_deleted = 0;

UPDATE sys_menu SET menu_name = '图层', sort = 3
	WHERE path = '/gis/layer' AND is_deleted = 0;

UPDATE sys_menu SET menu_name = '底图', sort = 4
	WHERE path = '/gis/provider' AND is_deleted = 0;

UPDATE sys_menu SET is_hide = 1, sort = 8
	WHERE path IN ('/gis/scene', '/gis/analyze') AND is_deleted = 0;

-- ========== 原 V74__gis_no_keepalive.sql ==========
-- GIS 页面全部关闭 keep-alive：地图实例不宜缓存，避免切页后空白 / 路由监听互抢。
UPDATE sys_menu SET is_keep_alive = 0
	WHERE path LIKE '/gis/%' AND is_deleted = 0;

-- ========== 原 V75__app_client.sql ==========
-- App 登录客户端：图形验证码仍开，避免 /auth/login + clientId=app 绕过 PC 图形码。
-- 移动端登录走 /app/auth/login + 滑块一次性 ticket。
INSERT INTO sys_client (id, client_id, client_name, captcha_enabled, max_online, token_timeout, status, create_time, is_deleted)
VALUES (7500000000000001, 'app', '移动工作台', 1, 0, 2592000, 1, now(), 0);

-- ========== 原 V76__user_profile_leader.sql ==========
-- 用户档案对齐：真实姓名/性别/生日/工号/直属主管/是否主管（头像列已在 V61）
ALTER TABLE sys_user ADD COLUMN real_name VARCHAR(64);
ALTER TABLE sys_user ADD COLUMN sex SMALLINT;
ALTER TABLE sys_user ADD COLUMN birthday DATE;
ALTER TABLE sys_user ADD COLUMN code VARCHAR(64);
ALTER TABLE sys_user ADD COLUMN leader_id BIGINT;
ALTER TABLE sys_user ADD COLUMN is_leader SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN sys_user.real_name IS '真实姓名';
COMMENT ON COLUMN sys_user.sex IS '性别：0 未知 / 1 男 / 2 女';
COMMENT ON COLUMN sys_user.birthday IS '生日';
COMMENT ON COLUMN sys_user.code IS '工号/用户编号';
COMMENT ON COLUMN sys_user.leader_id IS '直属主管用户 id';
COMMENT ON COLUMN sys_user.is_leader IS '是否主管：1 是 / 0 否（可供流程 deptLeader 等选用）';

-- 性别字典（id 与 notice_category 冲突时 ON CONFLICT 跳过；正式补种见 V77）
INSERT INTO sys_dict (id, parent_id, code, dict_key, dict_value, sort, is_sealed, create_time, is_deleted) VALUES
(1050000000000000101, 0,                   'user_sex', 'user_sex', '用户性别', 0, 1, now(), 0),
(1050000000000000102, 1050000000000000101, 'user_sex', '0',        '未知',     1, 0, now(), 0),
(1050000000000000103, 1050000000000000101, 'user_sex', '1',        '男',       2, 0, now(), 0),
(1050000000000000104, 1050000000000000101, 'user_sex', '2',        '女',       3, 0, now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V77__user_sex_dict.sql ==========
-- 用户性别字典（V76 误用 id 与 notice_category 冲突，ON CONFLICT 跳过；此处用空闲 id 补种）
INSERT INTO sys_dict (id, parent_id, code, dict_key, dict_value, sort, is_sealed, create_time, is_deleted) VALUES
(1050000000000000151, 0,                   'user_sex', 'user_sex', '用户性别', 0, 1, now(), 0),
(1050000000000000152, 1050000000000000151, 'user_sex', '0',        '未知',     1, 0, now(), 0),
(1050000000000000153, 1050000000000000151, 'user_sex', '1',        '男',       2, 0, now(), 0),
(1050000000000000154, 1050000000000000151, 'user_sex', '2',        '女',       3, 0, now(), 0) ON DUPLICATE KEY UPDATE NOTHING;

-- ========== 原 V78__gis_3dtiles.sql ==========
-- 三维切片图层：kind 增加 3dtiles（倾斜摄影 / 实景模型），data_json 存 tileset 入口与渲染参数。
COMMENT ON COLUMN gis_layer.kind IS 'vector/heatmap/xyz/wms/3dtiles';

-- ========== 原 V79__gis_feature_postgis.sql ==========
-- 要素行表：把整层 GeoJSON（gis_layer.data_json）拆成一要素一行，落真实 geometry 列 + GiST 索引，
-- 让「视野范围内 / 半径内 / 与某面相交 / 最近邻」四类高频查询能下沉数据库，不再全量捞回 Java 遍历。
--
-- 两点刻意设计：
-- 1) 全部包在 DO 块里。没装 PostGIS 的库（含达梦，转换器会把 DO 块整段跳过）不建表也不报错，
--    应用层按 gis_feature 是否存在决定走下沉查询还是回落 data_json 的 Java 路径。
-- 2) gis_layer.data_json 保持不动。要素行是「加速副本」而非唯一真相，回填失败不影响既有读写。

DO $$
BEGIN
	CREATE EXTENSION IF NOT EXISTS postgis;
EXCEPTION WHEN OTHERS THEN
	RAISE NOTICE 'PostGIS 扩展不可用（%），gis_feature 不创建，GIS 空间查询回落 Java 侧', SQLERRM;
END $$;

-- openGauss-lite 无 PostGIS：不建 gis_feature，空间查询走 Java 回落

