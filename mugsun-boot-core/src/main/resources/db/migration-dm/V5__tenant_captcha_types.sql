-- pg2dm: manual
-- 0.1.1：租户启用的行为验证码题型（达梦）。幂等：先查列是否存在，不吞其它错误。
-- 用 ALL_TAB_COLUMNS + OWNER = 当前模式：迁移账号不是表 owner（只授了 ALTER ANY TABLE 等）时也能正确判列。
DECLARE
	cnt INT;
BEGIN
	SELECT COUNT(*) INTO cnt FROM ALL_TAB_COLUMNS
	 WHERE OWNER = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')
	   AND TABLE_NAME = UPPER('sys_tenant') AND COLUMN_NAME = UPPER('captcha_types');
	IF cnt = 0 THEN
		EXECUTE IMMEDIATE 'ALTER TABLE sys_tenant ADD captcha_types VARCHAR(128)';
	END IF;
END;
/
COMMENT ON COLUMN sys_tenant.captcha_types IS '启用的验证码题型（SLIDER,ROTATE,WORD_CLICK,ICON_CLICK,JIGSAW；NULL 用平台默认）';
