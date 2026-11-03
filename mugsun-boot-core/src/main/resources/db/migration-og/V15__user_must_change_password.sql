-- 0.1.1：首次登录强制改密标记（#81，openGauss-lite 5.1）。幂等。
DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM information_schema.columns
	               WHERE table_schema = current_schema() AND table_name = 'sys_user' AND column_name = 'must_change_password') THEN
		ALTER TABLE sys_user ADD COLUMN must_change_password SMALLINT NOT NULL DEFAULT 0;
	END IF;
END $$;
COMMENT ON COLUMN sys_user.must_change_password IS '首次登录须改密：1 是 / 0 否（重置或发放一次性口令时置 1，用户改密后清 0）';
