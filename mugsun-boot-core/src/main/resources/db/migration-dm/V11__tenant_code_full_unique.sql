-- pg2dm: manual
-- 0.1.1：租户编号永不复用（达梦）。与 PG V11 同号。
-- sys_tenant.tenant_code 与 sys_tenant_datasource.tenant_code 改回全量唯一（含已删除行）。
-- 背景：删租户只逻辑删除组织数据，业务表、凭据、会话、数据源路由仍按编号关联。编号一旦被新租户复用，
-- 新租户会接手旧租户的残留数据（boot#100 复核 B1）。应用层 genCode 查重已包含已删除行，这里在库层兜底。
-- V1 是全量唯一，V7 改成了只约束未删除行，本版本改回。
-- 达梦的 DDL 自动提交，所以第 1 步查重放在最前面：已经有编号被复用就中止，此时还没有改任何索引。

-- 1. 先查重复编号（含已删除行）
DECLARE
	dup_tenant INT;
	dup_ds INT;
BEGIN
	SELECT COUNT(*) INTO dup_tenant FROM (
		SELECT tenant_code FROM sys_tenant GROUP BY tenant_code HAVING COUNT(*) > 1) d;
	SELECT COUNT(*) INTO dup_ds FROM (
		SELECT tenant_code FROM sys_tenant_datasource GROUP BY tenant_code HAVING COUNT(*) > 1) d;
	IF dup_tenant > 0 OR dup_ds > 0 THEN
		RAISE_APPLICATION_ERROR(-20001, 'V11 升级中止：有租户编号被重复使用（含已删除行），sys_tenant ' || dup_tenant
			|| ' 个、sys_tenant_datasource ' || dup_ds
			|| ' 个，全量唯一索引建不了。请用 SELECT tenant_code, count(*) FROM sys_tenant GROUP BY tenant_code HAVING count(*) > 1（数据源表同理）查出，核对这些编号下的业务数据归属并处理后，再重新执行迁移');
	END IF;
END;
/

-- 2. 改回全量唯一
DROP INDEX IF EXISTS uk_tenant_code;
CREATE UNIQUE INDEX uk_tenant_code ON sys_tenant (tenant_code);

DROP INDEX IF EXISTS uk_tenant_ds_code;
CREATE UNIQUE INDEX uk_tenant_ds_code ON sys_tenant_datasource (tenant_code);
