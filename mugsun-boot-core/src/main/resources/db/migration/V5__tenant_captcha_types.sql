-- 0.1.1：租户启用的行为验证码题型（逗号分隔；NULL = 平台默认 mugsun.captcha.default-types）。幂等。
ALTER TABLE sys_tenant ADD COLUMN IF NOT EXISTS captcha_types VARCHAR(128);
COMMENT ON COLUMN sys_tenant.captcha_types IS '启用的验证码题型（SLIDER,ROTATE,WORD_CLICK,ICON_CLICK,JIGSAW；NULL 用平台默认）';
