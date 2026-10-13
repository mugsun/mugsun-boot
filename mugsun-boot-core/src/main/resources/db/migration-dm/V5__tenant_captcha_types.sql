-- pg2dm: manual
-- 0.1.1：租户启用的行为验证码题型（达梦）。幂等：先查列是否存在，不吞其它错误。
DECLARE
	cnt INT;
BEGIN
	SELECT COUNT(*) INTO cnt FROM USER_TAB_COLUMNS
	 WHERE TABLE_NAME = UPPER('sys_tenant') AND COLUMN_NAME = UPPER('captcha_types');
	IF cnt = 0 THEN
		EXECUTE IMMEDIATE 'ALTER TABLE sys_tenant ADD captcha_types VARCHAR(128)';
	END IF;
END;
/
COMMENT ON COLUMN sys_tenant.captcha_types IS '启用的验证码题型（SLIDER,ROTATE,WORD_CLICK,ICON_CLICK,JIGSAW；NULL 用平台默认）';
