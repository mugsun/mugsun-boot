-- pg2dm: manual
-- 0.1.1：租户编号永不复用（达梦）。与 PG V11 同号。
-- sys_tenant.tenant_code 改回全量唯一（含已删除行）。
-- uk_tenant_ds_code 不动，仍是 V7 的 CASE 表达式索引、只约束未删除行：现存租户可以删掉数据源配置再用同一编号重建。
-- 背景：删租户只逻辑删除组织数据，业务表、凭据、会话、数据源路由仍按编号关联。编号一旦被新租户复用，
-- 新租户会接手旧租户的残留数据（boot#100 复核 B1）。应用层 genCode 查重已包含已删除行，这里在库层兜底。
-- V1 是全量唯一，V7 把 uk_tenant_code 改成了只约束未删除行，本版本改回。
-- 达梦的 DDL 自动提交，所以第 1 步查重放在最前面：已经有编号被复用就中止，此时还没有改任何索引。

-- 1. 先查重复编号（含已删除行）
DECLARE
	dup_tenant INT;
BEGIN
	SELECT COUNT(*) INTO dup_tenant FROM (
		SELECT tenant_code FROM sys_tenant GROUP BY tenant_code HAVING COUNT(*) > 1) d;
	IF dup_tenant > 0 THEN
		RAISE_APPLICATION_ERROR(-20001, 'V11 升级中止：sys_tenant 有 ' || dup_tenant
			|| ' 个租户编号被重复使用（含已删除行），全量唯一索引建不了。请按 https://docs.mugsun.com/boot/upgrade 的「V11 中止：租户编号被复用」一节查出并处理，再重新执行迁移');
	END IF;
END;
/

-- 2. uk_tenant_code 改回全量唯一
DROP INDEX IF EXISTS uk_tenant_code;
CREATE UNIQUE INDEX uk_tenant_code ON sys_tenant (tenant_code);
