-- 错误问题单（指纹聚合工作流）：独立于事件流水，支持指派/解决/复发
-- 幂等：CREATE TABLE IF NOT EXISTS + 索引 IF NOT EXISTS

CREATE TABLE IF NOT EXISTS track_error_issue (
    id                   BIGINT PRIMARY KEY,
    app_key              VARCHAR(32)  NOT NULL,
    fingerprint          VARCHAR(64)  NOT NULL,
    title                VARCHAR(512),
    status               VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    assignee_id          BIGINT,
    first_release        VARCHAR(128),
    last_release         VARCHAR(128),
    first_seen_at        TIMESTAMP    NOT NULL,
    last_seen_at         TIMESTAMP    NOT NULL,
    resolved_at          TIMESTAMP,
    event_count          BIGINT       NOT NULL DEFAULT 1,
    session_count        BIGINT       NOT NULL DEFAULT 1,
    user_count           BIGINT       NOT NULL DEFAULT 0,
    regressed            INT          NOT NULL DEFAULT 0,
    latest_session_id    VARCHAR(36),
    latest_user_id       BIGINT,
    latest_distinct_id   VARCHAR(64),
    latest_event_id      VARCHAR(36),
    latest_url_path      VARCHAR(512),
    latest_route_path    VARCHAR(255),
    latest_stack         VARCHAR(4096),
    releases             TEXT         NOT NULL DEFAULT '[]',
    tenant_id            VARCHAR(12)  NOT NULL,
    create_time          TIMESTAMP,
    update_time          TIMESTAMP,
    is_deleted           INT          NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_track_error_issue
    ON track_error_issue (app_key, fingerprint) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_track_error_issue_app_status
    ON track_error_issue (app_key, status, last_seen_at DESC) WHERE is_deleted = 0;
CREATE INDEX IF NOT EXISTS idx_track_error_issue_assignee
    ON track_error_issue (assignee_id, status) WHERE is_deleted = 0 AND assignee_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_track_error_issue_tenant
    ON track_error_issue (tenant_id, last_seen_at DESC) WHERE is_deleted = 0;

COMMENT ON TABLE track_error_issue IS '错误问题单：按 app_key+fingerprint 聚合，支持指派/解决/复发';
COMMENT ON COLUMN track_error_issue.fingerprint IS '错误指纹（与 track_event.error_fingerprint 对齐）';
COMMENT ON COLUMN track_error_issue.status IS 'OPEN / ASSIGNED / RESOLVED';
COMMENT ON COLUMN track_error_issue.regressed IS '1=曾解决后同指纹再出现（复发）';
COMMENT ON COLUMN track_error_issue.releases IS '见过的 release 列表（JSON 数组文本，如 ["1.0.0"]）';
COMMENT ON COLUMN track_error_issue.tenant_id IS '归属租户（从事件/应用映射，恒非空）';
