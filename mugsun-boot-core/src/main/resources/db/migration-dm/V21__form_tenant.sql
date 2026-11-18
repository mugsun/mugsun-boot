-- pg2dm: manual
-- 0.1.1：低代码表单按租户隔离（达梦）。与 PG V21 同号。幂等：先查列是否存在，不吞其它错误。
-- 存量的表单和填报一律归平台租户 000000，原因见 PG V21。
-- 表单标识改为租户内唯一，写法同 V7：每个键列都包 CASE WHEN is_deleted = 0，已删除的表单不占标识。
DECLARE
	cnt INT;
BEGIN
	SELECT COUNT(*) INTO cnt FROM ALL_TAB_COLUMNS
	 WHERE OWNER = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')
	   AND TABLE_NAME = UPPER('sys_form') AND COLUMN_NAME = UPPER('tenant_id');
	IF cnt = 0 THEN
		EXECUTE IMMEDIATE 'ALTER TABLE sys_form ADD tenant_id VARCHAR(12)';
	END IF;
	SELECT COUNT(*) INTO cnt FROM ALL_TAB_COLUMNS
	 WHERE OWNER = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')
	   AND TABLE_NAME = UPPER('sys_form_data') AND COLUMN_NAME = UPPER('tenant_id');
	IF cnt = 0 THEN
		EXECUTE IMMEDIATE 'ALTER TABLE sys_form_data ADD tenant_id VARCHAR(12)';
	END IF;
END;
/
UPDATE sys_form SET tenant_id = '000000' WHERE tenant_id IS NULL;
UPDATE sys_form_data SET tenant_id = '000000' WHERE tenant_id IS NULL;

DROP INDEX IF EXISTS uk_form_key;
DROP INDEX IF EXISTS uk_form_tenant_key;
CREATE UNIQUE INDEX uk_form_tenant_key ON sys_form (
	CASE WHEN is_deleted = 0 THEN tenant_id END, CASE WHEN is_deleted = 0 THEN form_key END);

COMMENT ON COLUMN sys_form.tenant_id IS '租户编号';
COMMENT ON COLUMN sys_form_data.tenant_id IS '租户编号（与所属表单相同）';
