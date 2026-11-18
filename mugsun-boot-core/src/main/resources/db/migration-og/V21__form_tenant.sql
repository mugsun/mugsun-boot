-- 0.1.1：低代码表单按租户隔离（openGauss-lite 5.1）。与 PG V21 同号。幂等。
-- 存量的表单和填报一律归平台租户 000000，原因见 PG V21。
-- 表单标识改为租户内唯一，写法同 V7：每个键列都包 CASE WHEN is_deleted = 0，已删除的表单不占标识。
DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM information_schema.columns
	               WHERE table_schema = current_schema() AND table_name = 'sys_form' AND column_name = 'tenant_id') THEN
		ALTER TABLE sys_form ADD COLUMN tenant_id VARCHAR(12);
	END IF;
	IF NOT EXISTS (SELECT 1 FROM information_schema.columns
	               WHERE table_schema = current_schema() AND table_name = 'sys_form_data' AND column_name = 'tenant_id') THEN
		ALTER TABLE sys_form_data ADD COLUMN tenant_id VARCHAR(12);
	END IF;
END $$;
UPDATE sys_form SET tenant_id = '000000' WHERE tenant_id IS NULL;
UPDATE sys_form_data SET tenant_id = '000000' WHERE tenant_id IS NULL;

DROP INDEX IF EXISTS uk_form_key;
DROP INDEX IF EXISTS uk_form_tenant_key;
CREATE UNIQUE INDEX uk_form_tenant_key ON sys_form (
	(CASE WHEN is_deleted = 0 THEN tenant_id END), (CASE WHEN is_deleted = 0 THEN form_key END));

COMMENT ON COLUMN sys_form.tenant_id IS '租户编号';
COMMENT ON COLUMN sys_form_data.tenant_id IS '租户编号（与所属表单相同）';
