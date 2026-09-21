-- Mugsun 埋点库 Flyway 基线（独立数据源，前缀 T）
-- 0.1.0 基线：合并原 T1–T9 为单脚本；全新安装只跑本文件。
-- 后续版本演进（如 0.1.1）再追加 T2__*.sql / T3__*.sql，勿再拆回历史增量。
-- 生成方式：scripts/squash_flyway_baseline.py（本仓库一次性收口）。

-- ========== 原 T1__track_init.sql ==========
-- G99 埋点库首版迁移（track 独立数据源，版本序列独立于主库 V 序列）
-- 物理隔离：本脚本在 mugsun_track 库执行（TrackFlywayConfig 建库 + 独立 Flyway），业务库零 track_* 表
-- 锁定 PostgreSQL（分区/DETACH/ON CONFLICT/JSONB/BRIN 均为 PG 特化，不做多方言改写）
-- 流水表（track_event/track_event_data）为亿级不可变追加流：豁免 is_deleted，清理走 DROP 分区；可变表保留逻辑删除 + 审计时间
-- 幂等：全量 IF NOT EXISTS；月分区经 DO 块动态命名（含当月 + 次月预建）

-- 4.1 接入应用（appKey/采样/开关/保留期/回放配置；配置下发数据源）
CREATE TABLE IF NOT EXISTS track_app (
    id                 BIGINT PRIMARY KEY,
    app_key            VARCHAR(32)  NOT NULL,
    app_name           VARCHAR(64)  NOT NULL,
    platform           VARCHAR(16)  NOT NULL DEFAULT 'web',
    tenant_id          VARCHAR(12),
    sample_rate        INT          NOT NULL DEFAULT 100,
    enabled            INT          NOT NULL DEFAULT 1,
    mask_selectors     VARCHAR(1024),
    retention_days     INT          NOT NULL DEFAULT 90,
    replay_enabled     INT          NOT NULL DEFAULT 0,
    replay_sample_rate INT          NOT NULL DEFAULT 10,
    replay_retention_days INT       NOT NULL DEFAULT 14,
    remark             VARCHAR(255),
    create_time        TIMESTAMP,
    update_time        TIMESTAMP,
    is_deleted         INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_track_app_key ON track_app (app_key) WHERE is_deleted = 0;
COMMENT ON TABLE track_app IS '埋点接入应用（appKey/采样/开关/保留期/回放配置；配置下发数据源）';
COMMENT ON COLUMN track_app.id IS '雪花主键';
COMMENT ON COLUMN track_app.app_key IS '接入标识（浏览器可见，非机密，仅作应用标识+限流维度）';
COMMENT ON COLUMN track_app.app_name IS '应用显示名';
COMMENT ON COLUMN track_app.platform IS '平台：web/android/ios（跨端预留）';
COMMENT ON COLUMN track_app.tenant_id IS '归属租户（服务端映射，禁止客户端上报）';
COMMENT ON COLUMN track_app.sample_rate IS '事件采样率 %';
COMMENT ON COLUMN track_app.enabled IS '总开关（0=采集端直接拒收）';
COMMENT ON COLUMN track_app.mask_selectors IS '前端屏蔽选择器，逗号分隔，配置下发';
COMMENT ON COLUMN track_app.retention_days IS '明细保留天数（分区清理依据）';
COMMENT ON COLUMN track_app.replay_enabled IS '会话回放开关（G100）';
COMMENT ON COLUMN track_app.replay_sample_rate IS '回放会话采样率 %（回放重，单独采样）';
COMMENT ON COLUMN track_app.replay_retention_days IS '回放保留天数（远短于事件）';
COMMENT ON COLUMN track_app.create_time IS '创建时间';
COMMENT ON COLUMN track_app.update_time IS '更新时间';
COMMENT ON COLUMN track_app.is_deleted IS '逻辑删除（0 正常 / 1 删除）';

-- 4.2 事件流水（按 received_at 月分区；fillfactor=100 追加不更新）
-- 三时间戳：client_ts 客户端原始时间（永不改写）/ ts 校时修正后发生时间（仅展示下钻）/ received_at 服务端接收时间（分区键 + rollup 分窗基准，单调）
-- 幂等三段式：event_id 客户端稳定幂等键 → Redis SETNX 跨重发幂等 → UNIQUE(event_id, received_at) 同接收窗兜底
CREATE TABLE IF NOT EXISTS track_event (
    id              BIGINT       NOT NULL,
    event_id        VARCHAR(36)  NOT NULL,
    app_key         VARCHAR(32)  NOT NULL,
    event_name      VARCHAR(64)  NOT NULL,
    client_ts       TIMESTAMPTZ  NOT NULL,
    ts              TIMESTAMPTZ  NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL,
    clock_skewed    INT          NOT NULL DEFAULT 0,
    distinct_id     VARCHAR(64)  NOT NULL,
    user_id         BIGINT,
    session_id      VARCHAR(36)  NOT NULL,
    tenant_id       VARCHAR(12)  NOT NULL,
    url_path        VARCHAR(512),
    route_path      VARCHAR(255),
    page_title      VARCHAR(255),
    referrer_domain VARCHAR(255),
    utm_source      VARCHAR(255),
    utm_medium      VARCHAR(255),
    utm_campaign    VARCHAR(255),
    browser         VARCHAR(32),
    os              VARCHAR(32),
    device          VARCHAR(16),
    ip              VARCHAR(64),
    ip_region       VARCHAR(64),
    duration_ms     INT,
    error_fingerprint VARCHAR(64),
    props           JSONB,
    create_time     TIMESTAMP    DEFAULT now(),
    PRIMARY KEY (id, received_at),
    UNIQUE (event_id, received_at)
) PARTITION BY RANGE (received_at);
-- fillfactor=100（追加不更新）：PG 不允许分区父表携带存储参数，只能设到叶子分区（DEFAULT 分区与 DO 块月分区均带 WITH）
-- 明细下钻（app+事件+接收时间范围，分区裁剪）
CREATE INDEX IF NOT EXISTS idx_event_query ON track_event (app_key, event_name, received_at);
-- received_at 单调，BRIN 仅 KB 级；rollup 增量扫窗用
CREATE INDEX IF NOT EXISTS idx_event_brin ON track_event USING brin (received_at) WITH (pages_per_range = 32);
-- 兜底分区，防"缺分区插入报错"；默认分区有数据即说明预建失败（监控告警）
CREATE TABLE IF NOT EXISTS track_event_default PARTITION OF track_event DEFAULT WITH (fillfactor = 100);
COMMENT ON TABLE track_event IS '埋点事件流水（按 received_at 月分区；热点属性成列，长尾 props jsonb）';
COMMENT ON COLUMN track_event.id IS '雪花主键（分区表复合主键含 received_at）';
COMMENT ON COLUMN track_event.event_id IS '客户端 UUID，跨重发幂等键（配合 Redis SETNX）';
COMMENT ON COLUMN track_event.app_key IS '接入应用标识';
COMMENT ON COLUMN track_event.event_name IS '事件名（$pageview/$click 等内置或自定义）';
COMMENT ON COLUMN track_event.client_ts IS '客户端原始时间（不改写；幂等判定 + 校时诊断）';
COMMENT ON COLUMN track_event.ts IS '分析用发生时间（校时修正后；仅供展示/下钻，不参与聚合分窗）';
COMMENT ON COLUMN track_event.received_at IS '服务端接收时间（分区键 + rollup 分窗基准，单调）';
COMMENT ON COLUMN track_event.clock_skewed IS '1=发生校时修正';
COMMENT ON COLUMN track_event.distinct_id IS '匿名 ID（anonymous_id）';
COMMENT ON COLUMN track_event.user_id IS '服务端裁定的登录用户（非客户端直采；统计唯一事实源走 track_identity 归并）';
COMMENT ON COLUMN track_event.session_id IS '会话 ID';
COMMENT ON COLUMN track_event.tenant_id IS '归属租户（恒非空：从 app_key 服务端映射，客户端传了也丢弃）';
COMMENT ON COLUMN track_event.url_path IS '原始路径（明细展示用）';
COMMENT ON COLUMN track_event.route_path IS '路由模板（如 /user/:id/detail），page 维度聚合用它防高基数';
COMMENT ON COLUMN track_event.page_title IS '页面标题';
COMMENT ON COLUMN track_event.referrer_domain IS '来源域名';
COMMENT ON COLUMN track_event.utm_source IS 'UTM 来源';
COMMENT ON COLUMN track_event.utm_medium IS 'UTM 媒介';
COMMENT ON COLUMN track_event.utm_campaign IS 'UTM 活动';
COMMENT ON COLUMN track_event.browser IS '浏览器';
COMMENT ON COLUMN track_event.os IS '操作系统';
COMMENT ON COLUMN track_event.device IS '设备类型：desktop/mobile/tablet';
COMMENT ON COLUMN track_event.ip IS '客户端 IP（可配匿名化截断）';
COMMENT ON COLUMN track_event.ip_region IS 'IP 归属地';
COMMENT ON COLUMN track_event.duration_ms IS '时长（$pageleave/计时事件）';
COMMENT ON COLUMN track_event.error_fingerprint IS '错误指纹（仅 $error 有值：message+首帧 hash，错误分组聚合用）';
COMMENT ON COLUMN track_event.props IS '长尾自定义属性（截断：键≤64/值≤1024/总量≤16KB/深度≤3）';
COMMENT ON COLUMN track_event.create_time IS '落库时间';

-- 4.3 会话物化表（事件流 upsert 维护；乱序安全：LEAST/GREATEST/累加/置位，绝不用裸 EXCLUDED 覆盖）
CREATE TABLE IF NOT EXISTS track_session (
    id              BIGINT PRIMARY KEY,
    session_id      VARCHAR(36)  NOT NULL,
    app_key         VARCHAR(32)  NOT NULL,
    tenant_id       VARCHAR(12),
    distinct_id     VARCHAR(64)  NOT NULL,
    user_id         BIGINT,
    start_time      TIMESTAMP    NOT NULL,
    end_time        TIMESTAMP    NOT NULL,
    duration_ms     INT          NOT NULL DEFAULT 0,
    pageviews       INT          NOT NULL DEFAULT 0,
    event_count     INT          NOT NULL DEFAULT 0,
    is_bounce       INT          NOT NULL DEFAULT 0,
    entry_path      VARCHAR(512),
    exit_path       VARCHAR(512),
    referrer_domain VARCHAR(255),
    utm_source      VARCHAR(255),
    browser         VARCHAR(32),
    os              VARCHAR(32),
    device          VARCHAR(16),
    ip_region       VARCHAR(64),
    has_error       INT          NOT NULL DEFAULT 0,
    has_replay      INT          NOT NULL DEFAULT 0,
    settled         INT          NOT NULL DEFAULT 0,
    create_time     TIMESTAMP,
    update_time     TIMESTAMP,
    is_deleted      INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_track_session_sid ON track_session (session_id) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_session_query ON track_session (app_key, start_time);
-- 部分索引：结算任务只扫未结会话
CREATE INDEX IF NOT EXISTS idx_session_settle ON track_session (end_time) WHERE settled = 0 AND is_deleted = 0;
COMMENT ON TABLE track_session IS '会话物化表（事件流 upsert 维护；会话指标查询行数比事件少1-2个数量级）';
COMMENT ON COLUMN track_session.id IS '雪花主键';
COMMENT ON COLUMN track_session.session_id IS '会话 ID';
COMMENT ON COLUMN track_session.app_key IS '接入应用标识';
COMMENT ON COLUMN track_session.tenant_id IS '归属租户';
COMMENT ON COLUMN track_session.distinct_id IS '匿名 ID';
COMMENT ON COLUMN track_session.user_id IS '登录用户（服务端裁定）';
COMMENT ON COLUMN track_session.start_time IS '会话开始时间（upsert 取 LEAST）';
COMMENT ON COLUMN track_session.end_time IS '会话末事件时间（upsert 取 GREATEST）';
COMMENT ON COLUMN track_session.duration_ms IS '会话时长（结算定稿）';
COMMENT ON COLUMN track_session.pageviews IS '页面浏览数（累加）';
COMMENT ON COLUMN track_session.event_count IS '事件数（累加）';
COMMENT ON COLUMN track_session.is_bounce IS '1=单 PV 跳出会话';
COMMENT ON COLUMN track_session.entry_path IS '入口路径（仅更早事件到达时更新）';
COMMENT ON COLUMN track_session.exit_path IS '出口路径（仅更晚事件到达时更新）';
COMMENT ON COLUMN track_session.referrer_domain IS '来源域名';
COMMENT ON COLUMN track_session.utm_source IS 'UTM 来源';
COMMENT ON COLUMN track_session.browser IS '浏览器';
COMMENT ON COLUMN track_session.os IS '操作系统';
COMMENT ON COLUMN track_session.device IS '设备类型';
COMMENT ON COLUMN track_session.ip_region IS 'IP 归属地';
COMMENT ON COLUMN track_session.has_error IS '1=会话内发生过 $error（回放筛选用）';
COMMENT ON COLUMN track_session.has_replay IS '1=有回放数据（G100）';
COMMENT ON COLUMN track_session.settled IS '1=会话已结算定稿（结算任务扫描依据）';
COMMENT ON COLUMN track_session.create_time IS '创建时间';
COMMENT ON COLUMN track_session.update_time IS '更新时间';
COMMENT ON COLUMN track_session.is_deleted IS '逻辑删除（0 正常 / 1 删除）';

-- 4.4 rollup 窄表与游标（分窗基准一律 received_at；写入幂等 = 窗口全量重算 + SET 覆盖，禁止累加）
CREATE TABLE IF NOT EXISTS track_stats_5m (
    id            BIGINT PRIMARY KEY,
    app_key       VARCHAR(32)  NOT NULL,
    bucket_time   TIMESTAMP    NOT NULL,
    dim_type      VARCHAR(16)  NOT NULL,
    dim_key       VARCHAR(255) NOT NULL,
    tenant_id     VARCHAR(12),
    pv            BIGINT       NOT NULL DEFAULT 0,
    event_count   BIGINT       NOT NULL DEFAULT 0,
    session_count BIGINT       NOT NULL DEFAULT 0,
    duration_sum  BIGINT       NOT NULL DEFAULT 0,
    create_time   TIMESTAMP,
    is_deleted    INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_stats_5m ON track_stats_5m (app_key, dim_type, dim_key, bucket_time);
COMMENT ON TABLE track_stats_5m IS '5 分钟窗口聚合（只放可加和指标；去重类只进 day 表；page 维度 dim_key 用路由模板防高基数）';
COMMENT ON COLUMN track_stats_5m.id IS '雪花主键';
COMMENT ON COLUMN track_stats_5m.app_key IS '接入应用标识';
COMMENT ON COLUMN track_stats_5m.bucket_time IS '5 分钟窗口起点（按 received_at 分窗）';
COMMENT ON COLUMN track_stats_5m.dim_type IS '维度类型：event/page/referrer/device';
COMMENT ON COLUMN track_stats_5m.dim_key IS '维度值：事件名/路由模板/域名/设备类型';
COMMENT ON COLUMN track_stats_5m.tenant_id IS '归属租户';
COMMENT ON COLUMN track_stats_5m.pv IS '页面浏览数';
COMMENT ON COLUMN track_stats_5m.event_count IS '事件数';
COMMENT ON COLUMN track_stats_5m.session_count IS '窗口内活跃会话（去重类，采样时仅标注口径不外推）';
COMMENT ON COLUMN track_stats_5m.duration_sum IS '时长合计（毫秒）';
COMMENT ON COLUMN track_stats_5m.create_time IS '创建时间';
COMMENT ON COLUMN track_stats_5m.is_deleted IS '逻辑删除（0 正常 / 1 删除）';

CREATE TABLE IF NOT EXISTS track_stats_day (
    id            BIGINT PRIMARY KEY,
    app_key       VARCHAR(32)  NOT NULL,
    stat_date     DATE         NOT NULL,
    dim_type      VARCHAR(16)  NOT NULL,
    dim_key       VARCHAR(255) NOT NULL,
    tenant_id     VARCHAR(12),
    pv            BIGINT       NOT NULL DEFAULT 0,
    uv            BIGINT       NOT NULL DEFAULT 0,
    session_count BIGINT       NOT NULL DEFAULT 0,
    bounce_count  BIGINT       NOT NULL DEFAULT 0,
    duration_sum  BIGINT       NOT NULL DEFAULT 0,
    event_count   BIGINT       NOT NULL DEFAULT 0,
    create_time   TIMESTAMP,
    update_time   TIMESTAMP,
    is_deleted    INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_stats_day ON track_stats_day (app_key, dim_type, dim_key, stat_date);
COMMENT ON TABLE track_stats_day IS '天级聚合（UV 不可从子窗口相加，必须从明细经 track_identity 归并精确算；CH 阶段改 uniqState 可合并）';
COMMENT ON COLUMN track_stats_day.id IS '雪花主键';
COMMENT ON COLUMN track_stats_day.app_key IS '接入应用标识';
COMMENT ON COLUMN track_stats_day.stat_date IS '统计日（按 received_at 分日）';
COMMENT ON COLUMN track_stats_day.dim_type IS '维度类型：overview/event/page/referrer/device';
COMMENT ON COLUMN track_stats_day.dim_key IS '维度值（page 维度用路由模板）';
COMMENT ON COLUMN track_stats_day.tenant_id IS '归属租户';
COMMENT ON COLUMN track_stats_day.pv IS '页面浏览数';
COMMENT ON COLUMN track_stats_day.uv IS '独立访客（精确去重：count(distinct coalesce(user_id, distinct_id))）';
COMMENT ON COLUMN track_stats_day.session_count IS '会话数';
COMMENT ON COLUMN track_stats_day.bounce_count IS '跳出会话数';
COMMENT ON COLUMN track_stats_day.duration_sum IS '时长合计（毫秒）';
COMMENT ON COLUMN track_stats_day.event_count IS '事件数';
COMMENT ON COLUMN track_stats_day.create_time IS '创建时间';
COMMENT ON COLUMN track_stats_day.update_time IS '更新时间';
COMMENT ON COLUMN track_stats_day.is_deleted IS '逻辑删除（0 正常 / 1 删除）';

CREATE TABLE IF NOT EXISTS track_stats_vitals (
    id          BIGINT PRIMARY KEY,
    app_key     VARCHAR(32)  NOT NULL,
    stat_date   DATE         NOT NULL,
    metric      VARCHAR(16)  NOT NULL,
    url_path    VARCHAR(512),
    bucket      INT          NOT NULL,
    cnt         BIGINT       NOT NULL DEFAULT 0,
    tenant_id   VARCHAR(12),
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_stats_vitals ON track_stats_vitals (app_key, stat_date, metric, url_path, bucket);
COMMENT ON TABLE track_stats_vitals IS 'Web Vitals 分位直方图预聚合（桶计数增量累加；看板插值 p50/p75/p95，替实时 percentile）';
COMMENT ON COLUMN track_stats_vitals.id IS '雪花主键';
COMMENT ON COLUMN track_stats_vitals.app_key IS '接入应用标识';
COMMENT ON COLUMN track_stats_vitals.stat_date IS '统计日（按 received_at 分日）';
COMMENT ON COLUMN track_stats_vitals.metric IS '指标：lcp/inp/cls/fcp/ttfb（桶定义按 metric 独立，入 TrackConstants）';
COMMENT ON COLUMN track_stats_vitals.url_path IS '可选按页维度（路由模板）';
COMMENT ON COLUMN track_stats_vitals.bucket IS '直方图桶序号（值域已知，对数桶）';
COMMENT ON COLUMN track_stats_vitals.cnt IS '桶计数（增量累加）';
COMMENT ON COLUMN track_stats_vitals.tenant_id IS '归属租户';
COMMENT ON COLUMN track_stats_vitals.create_time IS '创建时间';
COMMENT ON COLUMN track_stats_vitals.update_time IS '更新时间';

CREATE TABLE IF NOT EXISTS track_rollup_cursor (
    id          BIGINT PRIMARY KEY,
    job_key     VARCHAR(32) NOT NULL,
    app_key     VARCHAR(32) NOT NULL,
    last_bucket TIMESTAMP   NOT NULL,
    create_time TIMESTAMP,
    update_time TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_rollup_cursor ON track_rollup_cursor (job_key, app_key);
COMMENT ON TABLE track_rollup_cursor IS 'rollup 游标：任务从游标后一窗口补扫至当前窗口，跳窗/宕机不永久缺数（配合幂等重算）';
COMMENT ON COLUMN track_rollup_cursor.id IS '雪花主键';
COMMENT ON COLUMN track_rollup_cursor.job_key IS '任务键：stats_5m / stats_day / stats_vitals';
COMMENT ON COLUMN track_rollup_cursor.app_key IS '接入应用标识';
COMMENT ON COLUMN track_rollup_cursor.last_bucket IS '已聚合到的窗口（含）';
COMMENT ON COLUMN track_rollup_cursor.create_time IS '创建时间';
COMMENT ON COLUMN track_rollup_cursor.update_time IS '更新时间';

-- 4.7 匿名↔登录身份映射（identify 落库；user_id 首绑写入后绝不覆盖，重复 identify 只刷 last_seen_time）
CREATE TABLE IF NOT EXISTS track_identity (
    id              BIGINT PRIMARY KEY,
    app_key         VARCHAR(32) NOT NULL,
    distinct_id     VARCHAR(64) NOT NULL,
    user_id         BIGINT      NOT NULL,
    tenant_id       VARCHAR(12) NOT NULL,
    first_bind_time TIMESTAMP   NOT NULL,
    last_seen_time  TIMESTAMP,
    create_time     TIMESTAMP,
    update_time     TIMESTAMP,
    is_deleted      INT         NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_identity ON track_identity (app_key, distinct_id) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_identity_user ON track_identity (app_key, user_id);
COMMENT ON TABLE track_identity IS '匿名ID↔登录用户映射（identify 落库；UV/留存去重的归并依据：coalesce(user_id, distinct_id)）';
COMMENT ON COLUMN track_identity.id IS '雪花主键';
COMMENT ON COLUMN track_identity.app_key IS '接入应用标识';
COMMENT ON COLUMN track_identity.distinct_id IS '匿名 ID（anonymous_id）';
COMMENT ON COLUMN track_identity.user_id IS 'identify() 绑定的登录用户（首绑写入后绝不覆盖，防共享设备串号归并）';
COMMENT ON COLUMN track_identity.tenant_id IS '归属租户（恒非空）';
COMMENT ON COLUMN track_identity.first_bind_time IS '首次绑定时间';
COMMENT ON COLUMN track_identity.last_seen_time IS '最近出现时间（重复 identify 只刷本列）';
COMMENT ON COLUMN track_identity.create_time IS '创建时间';
COMMENT ON COLUMN track_identity.update_time IS '更新时间';
COMMENT ON COLUMN track_identity.is_deleted IS '逻辑删除（0 正常 / 1 删除）';

-- 4.5 事件元数据治理（采集端自动注册 first/last_seen，管理端认领补充；停用=采集端拒收）
CREATE TABLE IF NOT EXISTS track_event_def (
    id              BIGINT PRIMARY KEY,
    app_key         VARCHAR(32) NOT NULL,
    event_name      VARCHAR(64) NOT NULL,
    display_name    VARCHAR(64),
    description     VARCHAR(255),
    status          INT         NOT NULL DEFAULT 1,
    owner           VARCHAR(64),
    first_seen_time TIMESTAMP,
    last_seen_time  TIMESTAMP,
    tenant_id       VARCHAR(12),
    create_time     TIMESTAMP,
    update_time     TIMESTAMP,
    is_deleted      INT         NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_event_def ON track_event_def (app_key, event_name) WHERE is_deleted = 0;
COMMENT ON TABLE track_event_def IS '事件元数据治理（自动注册 + 认领；事件名白名单与属性"可分析"标记的载体）';
COMMENT ON COLUMN track_event_def.id IS '雪花主键';
COMMENT ON COLUMN track_event_def.app_key IS '接入应用标识';
COMMENT ON COLUMN track_event_def.event_name IS '事件名';
COMMENT ON COLUMN track_event_def.display_name IS '显示名（管理端认领补充）';
COMMENT ON COLUMN track_event_def.description IS '事件说明';
COMMENT ON COLUMN track_event_def.status IS '状态：1 启用 / 0 停用（停用=采集端拒收）';
COMMENT ON COLUMN track_event_def.owner IS '负责人';
COMMENT ON COLUMN track_event_def.first_seen_time IS '首次采集时间（自动注册）';
COMMENT ON COLUMN track_event_def.last_seen_time IS '最近采集时间';
COMMENT ON COLUMN track_event_def.tenant_id IS '归属租户';
COMMENT ON COLUMN track_event_def.create_time IS '创建时间';
COMMENT ON COLUMN track_event_def.update_time IS '更新时间';
COMMENT ON COLUMN track_event_def.is_deleted IS '逻辑删除（0 正常 / 1 删除）';

-- 4.6 回放元数据（G100；rrweb 本体存对象存储私有桶压缩块，绝不进数据库事实表）
CREATE TABLE IF NOT EXISTS track_replay (
    id            BIGINT PRIMARY KEY,
    session_id    VARCHAR(36)  NOT NULL,
    app_key       VARCHAR(32)  NOT NULL,
    tenant_id     VARCHAR(12),
    distinct_id   VARCHAR(64)  NOT NULL,
    user_id       BIGINT,
    start_time    TIMESTAMP    NOT NULL,
    duration_ms   INT          NOT NULL DEFAULT 0,
    page_count    INT          NOT NULL DEFAULT 0,
    rrweb_events  INT          NOT NULL DEFAULT 0,
    size_bytes    BIGINT       NOT NULL DEFAULT 0,
    has_error     INT          NOT NULL DEFAULT 0,
    entry_path    VARCHAR(512),
    storage_key   VARCHAR(255),
    create_time   TIMESTAMP,
    update_time   TIMESTAMP,
    is_deleted    INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_replay_session ON track_replay (session_id) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_replay_query ON track_replay (app_key, start_time);
COMMENT ON TABLE track_replay IS '会话回放元数据（rrweb 本体存对象存储压缩块；短保留期）';
COMMENT ON COLUMN track_replay.id IS '雪花主键';
COMMENT ON COLUMN track_replay.session_id IS '会话 ID';
COMMENT ON COLUMN track_replay.app_key IS '接入应用标识';
COMMENT ON COLUMN track_replay.tenant_id IS '归属租户';
COMMENT ON COLUMN track_replay.distinct_id IS '匿名 ID';
COMMENT ON COLUMN track_replay.user_id IS '登录用户';
COMMENT ON COLUMN track_replay.start_time IS '会话开始时间';
COMMENT ON COLUMN track_replay.duration_ms IS '回放时长（毫秒）';
COMMENT ON COLUMN track_replay.page_count IS '页面数';
COMMENT ON COLUMN track_replay.rrweb_events IS 'rrweb 事件条数';
COMMENT ON COLUMN track_replay.size_bytes IS '压缩后体积（字节）';
COMMENT ON COLUMN track_replay.has_error IS '1=会话内发生过 $error';
COMMENT ON COLUMN track_replay.entry_path IS '入口路径';
COMMENT ON COLUMN track_replay.storage_key IS '对象存储对象键（私有桶）';
COMMENT ON COLUMN track_replay.create_time IS '创建时间';
COMMENT ON COLUMN track_replay.update_time IS '更新时间';
COMMENT ON COLUMN track_replay.is_deleted IS '逻辑删除（0 正常 / 1 删除）';

-- 4.8 长尾属性 EAV（按需启用：仅 track_event_def 标记"可分析"的属性才拆入，供属性分布聚合；分区键跟随主表）
CREATE TABLE IF NOT EXISTS track_event_data (
    id          BIGINT       NOT NULL,
    event_id    VARCHAR(36)  NOT NULL,
    app_key     VARCHAR(32)  NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL,
    prop_key    VARCHAR(64)  NOT NULL,
    str_value   VARCHAR(512),
    num_value   NUMERIC,
    tenant_id   VARCHAR(12)  NOT NULL,
    PRIMARY KEY (id, received_at)
) PARTITION BY RANGE (received_at);
CREATE INDEX IF NOT EXISTS idx_event_data_key ON track_event_data (app_key, prop_key, received_at);
-- 兜底分区（同主表）
CREATE TABLE IF NOT EXISTS track_event_data_default PARTITION OF track_event_data DEFAULT;
COMMENT ON TABLE track_event_data IS '长尾自定义属性 EAV（仅标记"可分析"的属性才拆入，供属性分布聚合；主表 JSONB 不建 GIN）';
COMMENT ON COLUMN track_event_data.id IS '雪花主键（分区表复合主键含 received_at）';
COMMENT ON COLUMN track_event_data.event_id IS '关联事件 event_id';
COMMENT ON COLUMN track_event_data.app_key IS '接入应用标识';
COMMENT ON COLUMN track_event_data.received_at IS '服务端接收时间（跟随主表分区键）';
COMMENT ON COLUMN track_event_data.prop_key IS '属性键';
COMMENT ON COLUMN track_event_data.str_value IS '字符串值';
COMMENT ON COLUMN track_event_data.num_value IS '数值值';
COMMENT ON COLUMN track_event_data.tenant_id IS '归属租户（恒非空）';

-- 预建当月与次月分区（动态分区名；维护任务每月 25 日预建次月，此处保证全新库启动即可写）
DO $$
DECLARE
	month_start DATE;
	month_end   DATE;
	part_name   TEXT;
BEGIN
	FOR i IN 0..1 LOOP
		month_start := (date_trunc('month', now()) + (i || ' month')::interval)::date;
		-- 上界 = 次月 1 日（date + interval 须显式转 date；month_start + 1 是加 1 天，经典坑）
		month_end := (month_start + interval '1 month')::date;
		part_name := 'track_event_' || to_char(month_start, 'YYYY_MM');
		EXECUTE format(
			'CREATE TABLE IF NOT EXISTS %I PARTITION OF track_event FOR VALUES FROM (%L) TO (%L) WITH (fillfactor = 100)',
			part_name, month_start, month_end);
		part_name := 'track_event_data_' || to_char(month_start, 'YYYY_MM');
		EXECUTE format(
			'CREATE TABLE IF NOT EXISTS %I PARTITION OF track_event_data FOR VALUES FROM (%L) TO (%L)',
			part_name, month_start, month_end);
	END LOOP;
END $$;

-- ========== 原 T2__track_default_app.sql ==========
-- G99 默认应用种子：mugsun-pc 自埋点（本系统管理台自监控）
-- 固定 app_key 供 mugsun-pc 前端缺省接入（VITE_TRACK_APP_KEY 可覆盖）；appKey 非机密，安全不依赖其保密
-- app_key 格式与 TrackAdminService 生成规则一致：ak_ 前缀 + 24 位 hex（此处为固定常量，便于开发联调零配置）
-- 幂等：命中部分唯一索引 uk_track_app_key（is_deleted = 0）即跳过，重复迁移/多节点启动安全

INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,
    retention_days, replay_enabled, replay_sample_rate, replay_retention_days, remark,
    create_time, update_time, is_deleted)
VALUES (1899000000000000001, 'ak_000000000000000000000001', 'mugsun-pc 自监控', 'web', '000000', 100, 1,
    90, 0, 10, 14, '默认应用种子：mugsun-pc 自埋点（开发联调固定 app_key，生产环境请新建应用并改用下发配置）',
    now(), now(), 0)
ON CONFLICT (app_key) WHERE is_deleted = 0 DO NOTHING;

-- ========== 原 T3__track_replay_block_meta.sql ==========
-- G100 回放元数据补列：块序号上限 + 存储坐标（storage_key 只存首块对象键，块清单按 seq 推导需 last_seq；
-- 读取/删除重建 FileInfo 需写入时的平台与 basePath，防默认存储平台配置变更后读不到旧对象）

ALTER TABLE track_replay ADD COLUMN IF NOT EXISTS last_seq INT NOT NULL DEFAULT -1;
ALTER TABLE track_replay ADD COLUMN IF NOT EXISTS storage_platform VARCHAR(64);
ALTER TABLE track_replay ADD COLUMN IF NOT EXISTS storage_base_path VARCHAR(255);

COMMENT ON COLUMN track_replay.last_seq IS '已持久化的最大块序号（seq 自 0 连续递增；块键清单纯推导：dir(storage_key)+seq+".gz"，个别被拒/丢失的 seq 读取时 404 由前端跳过；-1=尚无块）';
COMMENT ON COLUMN track_replay.storage_platform IS '首块写入的 x-file-storage 平台名（读取/删除按原平台寻址，不随默认平台切换漂移）';
COMMENT ON COLUMN track_replay.storage_base_path IS '首块写入时平台的 basePath（FileInfo 重建坐标；storage_key 含此前缀）';

-- ========== 原 T4__track_default_app_replay.sql ==========
-- G100 开发联调：默认种子应用（mugsun-pc 自监控）开启会话回放
-- dev 自监控全量录（sample_rate=100）便于联调回归；生产部署请按容量自行调低采样率或关闭
-- 幂等：UPDATE 天然幂等，重复迁移/多节点启动安全；仅命中 T2 固定 app_key 种子行，用户自建应用不受影响

UPDATE track_app SET replay_enabled = 1, replay_sample_rate = 100, update_time = now()
WHERE app_key = 'ak_000000000000000000000001' AND is_deleted = 0;

-- ========== 原 T5__track_replay_wall_duration.sql ==========
-- G100 补丁：回放会话时长改墙钟口径（首末 rrweb 事件时间戳极差），与播放器时间轴一致
-- 原 duration_ms 为逐块时长累加（只计活跃段，含静止间隙的墙钟跨度对不上播放器）

ALTER TABLE track_replay ADD COLUMN IF NOT EXISTS first_event_ts BIGINT;
ALTER TABLE track_replay ADD COLUMN IF NOT EXISTS last_event_ts BIGINT;

COMMENT ON COLUMN track_replay.first_event_ts IS '会话内首个 rrweb 事件时间戳（epoch 毫秒，upsert 取 LEAST）';
COMMENT ON COLUMN track_replay.last_event_ts IS '会话内末个 rrweb 事件时间戳（epoch 毫秒，upsert 取 GREATEST）';

-- 存量行回填：duration_ms 已是累加口径，无法精确还原墙钟，以 start_time + duration 近似末时刻
UPDATE track_replay SET first_event_ts = EXTRACT(EPOCH FROM start_time) * 1000,
	last_event_ts = (EXTRACT(EPOCH FROM start_time) * 1000)::BIGINT + duration_ms
	WHERE first_event_ts IS NULL;

-- ========== 原 T6__track_sourcemap_alert.sql ==========
-- G101 错误监控增强：sourcemap 元数据（堆栈还原支撑）+ 应用级错误告警配置
-- sourcemap 本体存对象存储私有区（x-file-storage），本表仅元数据；告警状态走 Redis，不落表
-- 幂等：全量 IF NOT EXISTS / ADD COLUMN IF NOT EXISTS，重复迁移/多节点启动安全

-- 应用级错误告警配置（消费侧对 $error 评估告警规则的依据；告警状态在 Redis，见 TrackConstants alert-* 键）
ALTER TABLE track_app ADD COLUMN IF NOT EXISTS alert_enabled INT NOT NULL DEFAULT 0;
ALTER TABLE track_app ADD COLUMN IF NOT EXISTS alert_threshold INT NOT NULL DEFAULT 10;
COMMENT ON COLUMN track_app.alert_enabled IS '错误告警开关（G101：1=消费侧对 $error 评估新指纹/频次阈值规则并站内信告警）';
COMMENT ON COLUMN track_app.alert_threshold IS '同指纹告警频次阈值（次/10 分钟窗；规则 B 触发线，1..1000）';

-- sourcemap 元数据（.map 本体在对象存储；storage_* 记录写入时平台坐标，读取/删除按原坐标重建 FileInfo）
CREATE TABLE IF NOT EXISTS track_sourcemap (
    id                BIGINT PRIMARY KEY,
    app_key           VARCHAR(32)  NOT NULL,
    release           VARCHAR(128) NOT NULL,
    filename          VARCHAR(255) NOT NULL,
    storage_key       VARCHAR(512) NOT NULL,
    storage_platform  VARCHAR(64)  NOT NULL,
    storage_base_path VARCHAR(255) NOT NULL DEFAULT '',
    size_bytes        BIGINT       NOT NULL,
    tenant_id         VARCHAR(12),
    create_by         BIGINT,
    create_time       TIMESTAMP,
    update_time       TIMESTAMP,
    is_deleted        INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_track_sourcemap ON track_sourcemap (app_key, release, filename) WHERE is_deleted = 0;
COMMENT ON TABLE track_sourcemap IS 'sourcemap 元数据（G101：.map 本体在对象存储私有区，唯一键 app_key+release+filename）';
COMMENT ON COLUMN track_sourcemap.id IS '雪花主键';
COMMENT ON COLUMN track_sourcemap.app_key IS '接入应用标识';
COMMENT ON COLUMN track_sourcemap.release IS '发布版本号（与 $error props.release 对齐，堆栈还原选图依据）';
COMMENT ON COLUMN track_sourcemap.filename IS '原始 .map 文件名（同时作对象键文件名段）';
COMMENT ON COLUMN track_sourcemap.storage_key IS '对象存储完整对象键（含平台 basePath 前缀；管理端不下发）';
COMMENT ON COLUMN track_sourcemap.storage_platform IS '写入时的 x-file-storage 平台名（读取/删除按原平台寻址）';
COMMENT ON COLUMN track_sourcemap.storage_base_path IS '写入时平台的 basePath（FileInfo 重建坐标；storage_key 含此前缀）';
COMMENT ON COLUMN track_sourcemap.size_bytes IS '文件大小（字节）';
COMMENT ON COLUMN track_sourcemap.tenant_id IS '归属租户（服务端裁定，取上传操作人会话租户）';
COMMENT ON COLUMN track_sourcemap.create_by IS '上传操作人用户 id';
COMMENT ON COLUMN track_sourcemap.create_time IS '创建时间';
COMMENT ON COLUMN track_sourcemap.update_time IS '更新时间';
COMMENT ON COLUMN track_sourcemap.is_deleted IS '逻辑删除（0 正常 / 1 删除）';

-- ========== 原 T7__track_user_api.sql ==========
-- G102 用户细查 + 接口监控 + 响应体采集：时间线索引 + 应用级三开关与 body 保留期
-- 响应体本体存对象存储私有区（x-file-storage，键 api-body/{app_key}/{yyyyMM}/{event_id}.json.gz），
-- 不落任何元数据表：读取/清理一律按 track_event.props->>'body_ref'（= 事件 event_id）+ 事件 received_at 纯推导
-- 幂等：全量 IF NOT EXISTS / ADD COLUMN IF NOT EXISTS，重复迁移/多节点启动安全

-- 按人查时间线索引：分区父表建索引自动落各分区（含未来新分区）；范围硬限 ≤7 天由 API 层强制
CREATE INDEX IF NOT EXISTS idx_event_user_timeline ON track_event (app_key, user_id, received_at);
COMMENT ON INDEX idx_event_user_timeline IS '用户细查时间线（G102）：按 app+user+接收时间倒序游标分页，防全分区扫描';
-- 访客直查同思路（distinct_id 匿名行为时间线）
CREATE INDEX IF NOT EXISTS idx_event_distinct_timeline ON track_event (app_key, distinct_id, received_at);
COMMENT ON INDEX idx_event_distinct_timeline IS '用户细查时间线（G102）：按 app+distinct+接收时间倒序游标分页（访客口径）';

-- 应用级三开关 + body 保留期（默认全关：接口元数据/响应体/业务字段脱敏；开关经 /track/config 下发 SDK）
ALTER TABLE track_app ADD COLUMN IF NOT EXISTS api_monitor_enabled INT NOT NULL DEFAULT 0;
ALTER TABLE track_app ADD COLUMN IF NOT EXISTS api_body_enabled INT NOT NULL DEFAULT 0;
ALTER TABLE track_app ADD COLUMN IF NOT EXISTS api_body_mask_enabled INT NOT NULL DEFAULT 0;
ALTER TABLE track_app ADD COLUMN IF NOT EXISTS api_body_retention_days INT NOT NULL DEFAULT 7;
COMMENT ON COLUMN track_app.api_monitor_enabled IS '接口元数据采集开关（G102：1=SDK 包装 fetch/XHR 上报 api_request 事件）';
COMMENT ON COLUMN track_app.api_body_enabled IS '接口响应体采集开关（G102：1=SDK 经独立通道 /track/api-body 上传响应体）';
COMMENT ON COLUMN track_app.api_body_mask_enabled IS '响应体业务字段脱敏开关（G102：默认关；凭证端点硬屏蔽不可关，此为业务字段附加脱敏）';
COMMENT ON COLUMN track_app.api_body_retention_days IS '响应体保留天数（G102：远短于事件明细；清理任务到期线，1..30）';

-- ========== 原 T8__track_visual_rule_session_idx.sql ==========
-- G104 圈选式可视化埋点：圈选规则表（TRACK-PLAN §21.1）
-- G105 遗留收口：回放会话事件打点索引（TRACK-PLAN §22.3，按会话查事件走此索引免全分区扫描）

CREATE TABLE track_visual_rule (
    id          BIGINT PRIMARY KEY,                  -- 雪花
    app_key     VARCHAR(32)  NOT NULL,
    event_name  VARCHAR(64)  NOT NULL,               -- 命中后上报的自定义事件名（须过 CUSTOM_EVENT_NAME 正则，$ 前缀必拒）
    selector    VARCHAR(512) NOT NULL,               -- 圈选生成的 CSS selector（SDK 端已验唯一）
    route_path  VARCHAR(255),                        -- 路由模板限定；NULL = 全站生效
    match_text  VARCHAR(128),                        -- 元素文本包含匹配；NULL = 不限
    status      INT          NOT NULL DEFAULT 1,     -- 1 启用 / 0 停用（停用 = 不下发不命中）
    source      VARCHAR(16)  NOT NULL DEFAULT 'visual', -- 规则来源（当前恒 visual；留列防未来手工规则混入）
    tenant_id   VARCHAR(12),                          -- 服务端裁定（令牌归属），禁止客户端上报
    remark      VARCHAR(255),
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_by   BIGINT,
    update_by   BIGINT,
    is_deleted  INT          NOT NULL DEFAULT 0
);
-- 重复圈选（同应用+事件名+selector+路由+文本）= 更新而非堆行；NULL 维经 coalesce 归一参与唯一约束
CREATE UNIQUE INDEX uk_visual_rule ON track_visual_rule
    (app_key, event_name, selector, coalesce(route_path, ''), coalesce(match_text, '')) WHERE is_deleted = 0;
-- config 下发查询（status=1 启用集）与按应用分页双命中
CREATE INDEX idx_visual_rule_app ON track_visual_rule (app_key, status) WHERE is_deleted = 0;
COMMENT ON TABLE track_visual_rule IS '圈选式可视化埋点规则（G104：inspect 圈选→草稿确认→/track/config 下发→SDK 命中上报自定义事件）';

-- 回放会话事件时间轴打点：按 (app_key, session_id, received_at) 查会话事件流（分区表建索引自动传播到各叶子分区）
CREATE INDEX idx_event_session ON track_event (app_key, session_id, received_at);
COMMENT ON INDEX idx_event_session IS 'G105：回放打点/会话事件流查询（会话墙钟窗裁剪分区后命中本索引）';

-- ========== 原 T9__track_geo.sql ==========
-- G106 埋点地理：精确坐标成列 + 应用级定位开关
-- 坐标由摄入侧校验后写入（WGS84、4 位小数 ≈ 11m，非法值不落列）；属地分析仍走既有 ip_region
-- 幂等：ADD COLUMN IF NOT EXISTS；分区父表加列自动落到各分区（含未来新分区）

ALTER TABLE track_app ADD COLUMN IF NOT EXISTS geo_enabled INT NOT NULL DEFAULT 0;
COMMENT ON COLUMN track_app.geo_enabled IS '精确位置采集开关（G106：1=SDK 征求定位后随会话上报 geo_lon/geo_lat；默认关）';

ALTER TABLE track_event ADD COLUMN IF NOT EXISTS geo_lon NUMERIC(9, 4);
ALTER TABLE track_event ADD COLUMN IF NOT EXISTS geo_lat NUMERIC(9, 4);
COMMENT ON COLUMN track_event.geo_lon IS 'WGS84 经度（G106：摄入侧校验圆整，非法不落列）';
COMMENT ON COLUMN track_event.geo_lat IS 'WGS84 纬度（G106：摄入侧校验圆整，非法不落列）';

CREATE INDEX IF NOT EXISTS idx_event_geo ON track_event (app_key, received_at)
	WHERE geo_lon IS NOT NULL;
COMMENT ON INDEX idx_event_geo IS '埋点热力点查询（G106）：仅有坐标行，防全分区扫 JSONB';
