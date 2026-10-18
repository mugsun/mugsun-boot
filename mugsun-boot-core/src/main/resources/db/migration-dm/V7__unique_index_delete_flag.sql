-- pg2dm: manual
-- 0.1.1：唯一索引只约束未删除的行（达梦）。与 PG V7 同号。
-- 背景：V1 在达梦上把 6 个唯一索引建成全量唯一（PG 是 WHERE is_deleted = 0 的部分唯一）。
-- 逻辑删除后的行仍占着键，删租户后再建同编号租户、撤销菜单后再授权都会撞键（#95）。
-- 做法：函数唯一索引，键列包一层 CASE WHEN is_deleted = 0 THEN 列 END。
-- 已删除的行整组键都是 NULL。达梦按 Oracle 口径：整组键全为 NULL 的行互不冲突，部分为 NULL 仍会冲突，
-- 所以每一列都要包 CASE，不能只包一列。同一个键可以反复删除、反复新建；未删除的行与原索引同样唯一。
-- 不用 (列, is_deleted)：同一个键删两次会有两行 (列, 1)，第二次删除就撞键。
-- 手机号：V1 在达梦上只是普通索引。改成与 PG 相同的口径：未删除、手机号非空非空串时，同租户内唯一。
-- 达梦的 DDL 自动提交，所以第 1 步查重放在最前面：有重复就中止，此时还没有改任何索引。

-- 1. 先查重复手机号
DECLARE
	dup_groups INT;
BEGIN
	SELECT COUNT(*) INTO dup_groups FROM (
		SELECT tenant_id, phone FROM sys_user
		WHERE is_deleted = 0 AND phone IS NOT NULL AND phone <> ''
		GROUP BY tenant_id, phone HAVING COUNT(*) > 1
	) d;
	IF dup_groups > 0 THEN
		RAISE_APPLICATION_ERROR(-20001, 'V7 升级中止：sys_user 有 ' || dup_groups
			|| ' 组同租户重复手机号（未删除用户），手机号唯一索引建不了。请按 https://docs.mugsun.com/boot/upgrade 的「同租户重复手机号」一节查出并处理，再重新执行迁移');
	END IF;
END;
/

-- 2. 6 个唯一索引改为只约束未删除的行
DROP INDEX IF EXISTS uk_tenant_code;
CREATE UNIQUE INDEX uk_tenant_code ON sys_tenant (CASE WHEN is_deleted = 0 THEN tenant_code END);

DROP INDEX IF EXISTS uk_user_tenant_username;
CREATE UNIQUE INDEX uk_user_tenant_username ON sys_user (
	CASE WHEN is_deleted = 0 THEN tenant_id END, CASE WHEN is_deleted = 0 THEN username END);

DROP INDEX IF EXISTS uk_user_role;
CREATE UNIQUE INDEX uk_user_role ON sys_user_role (
	CASE WHEN is_deleted = 0 THEN user_id END, CASE WHEN is_deleted = 0 THEN role_id END);

DROP INDEX IF EXISTS uk_role_menu;
CREATE UNIQUE INDEX uk_role_menu ON sys_role_menu (
	CASE WHEN is_deleted = 0 THEN role_id END, CASE WHEN is_deleted = 0 THEN menu_id END);

DROP INDEX IF EXISTS uk_role_dept;
CREATE UNIQUE INDEX uk_role_dept ON sys_role_dept (
	CASE WHEN is_deleted = 0 THEN role_id END, CASE WHEN is_deleted = 0 THEN dept_id END);

DROP INDEX IF EXISTS uk_tenant_ds_code;
CREATE UNIQUE INDEX uk_tenant_ds_code ON sys_tenant_datasource (CASE WHEN is_deleted = 0 THEN tenant_code END);

-- 3. 手机号：普通索引换成唯一索引。已删除、手机号为 NULL 或空串的行整组键为 NULL，不参与唯一
DROP INDEX IF EXISTS uk_user_tenant_phone;
CREATE UNIQUE INDEX uk_user_tenant_phone ON sys_user (
	CASE WHEN is_deleted = 0 AND phone IS NOT NULL AND phone <> '' THEN tenant_id END,
	CASE WHEN is_deleted = 0 AND phone IS NOT NULL AND phone <> '' THEN phone END);
