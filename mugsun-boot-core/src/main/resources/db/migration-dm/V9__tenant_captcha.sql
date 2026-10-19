-- pg2dm: manual
-- 0.1.1：租户级行为验证码开关（达梦）。幂等。
BEGIN
	EXECUTE IMMEDIATE 'ALTER TABLE sys_tenant ADD captcha_enabled INT DEFAULT 1';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
UPDATE sys_tenant SET captcha_enabled = 1 WHERE captcha_enabled IS NULL;
COMMENT ON COLUMN sys_tenant.captcha_enabled IS '行为验证码开关（1/NULL 开，0 关）';
