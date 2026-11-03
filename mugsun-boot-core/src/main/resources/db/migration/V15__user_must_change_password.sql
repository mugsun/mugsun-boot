-- 0.1.1：首次登录强制改密标记（#81）。重置/默认一次性口令置 1，用户改密后清 0。
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS must_change_password SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN sys_user.must_change_password IS '首次登录须改密：1 是 / 0 否（重置或发放一次性口令时置 1，用户改密后清 0）';
