-- 0.1.1：租户级行为验证码开关（openGauss-lite 5.1）。幂等。
DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM information_schema.columns
	               WHERE table_schema = current_schema() AND table_name = 'sys_tenant' AND column_name = 'captcha_enabled') THEN
		ALTER TABLE sys_tenant ADD COLUMN captcha_enabled INT DEFAULT 1;
	END IF;
END $$;
UPDATE sys_tenant SET captcha_enabled = 1 WHERE captcha_enabled IS NULL;
COMMENT ON COLUMN sys_tenant.captcha_enabled IS '行为验证码开关（1/NULL 开，0 关）';
