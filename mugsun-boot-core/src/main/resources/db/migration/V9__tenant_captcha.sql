-- 0.1.1：租户级行为验证码开关（默认开；关闭仅平台超管可操作并留审计）。幂等。
ALTER TABLE sys_tenant ADD COLUMN IF NOT EXISTS captcha_enabled INT DEFAULT 1;
UPDATE sys_tenant SET captcha_enabled = 1 WHERE captcha_enabled IS NULL;
COMMENT ON COLUMN sys_tenant.captcha_enabled IS '行为验证码开关（1/NULL 开，0 关）';
