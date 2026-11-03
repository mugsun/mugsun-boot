-- pg2dm: manual
-- 0.1.1：首次登录强制改密标记（#81，达梦）。幂等。
BEGIN
	EXECUTE IMMEDIATE 'ALTER TABLE sys_user ADD must_change_password SMALLINT DEFAULT 0 NOT NULL';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
COMMENT ON COLUMN sys_user.must_change_password IS '首次登录须改密：1 是 / 0 否（重置或发放一次性口令时置 1，用户改密后清 0）';
