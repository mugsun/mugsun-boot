-- pg2dm: manual
-- 0.1.1：租户启用的行为验证码题型（达梦）。幂等。
BEGIN
	EXECUTE IMMEDIATE 'ALTER TABLE sys_tenant ADD captcha_types VARCHAR(128)';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
COMMENT ON COLUMN sys_tenant.captcha_types IS '启用的验证码题型（SLIDER,ROTATE,WORD_CLICK,ICON_CLICK,JIGSAW；NULL 用平台默认）';
