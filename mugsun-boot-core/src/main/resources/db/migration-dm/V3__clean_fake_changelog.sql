-- pg2dm: manual
-- 0.1.1：清掉 V1 演示更新日志，换成真实 0.1.0（达梦）。幂等。
DELETE FROM sys_changelog
WHERE id IN (1080000000000000001, 1080000000000000002, 1080000000000000003)
  AND version IN ('v1.2.0', 'v1.1.0', 'v1.0.0');

INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(sys_changelog(id)) */ INTO sys_changelog (id, version, type, title, content, publish_time, sort, create_time) VALUES
(1080000000000000010, '0.1.0', 'feature', 'Mugsun Boot 0.1.0 首个公开版本',
 '<p>首个公开版本：多租户、权限、组织、附件、消息、日志监控、代码生成、开放平台等基础能力，支持 PostgreSQL / 金仓、达梦、openGauss。</p>',
 SYSDATE, 1, SYSDATE);
