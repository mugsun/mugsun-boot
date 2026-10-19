-- 0.1.1：租户启用的行为验证码题型（openGauss-lite 5.1）。幂等。
DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM information_schema.columns
	               WHERE table_schema = current_schema() AND table_name = 'sys_tenant' AND column_name = 'captcha_types') THEN
		ALTER TABLE sys_tenant ADD COLUMN captcha_types VARCHAR(128);
	END IF;
END $$;
COMMENT ON COLUMN sys_tenant.captcha_types IS '启用的验证码题型（SLIDER,ROTATE,WORD_CLICK,ICON_CLICK,JIGSAW；NULL 用平台默认）';
