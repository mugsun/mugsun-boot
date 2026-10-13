package com.mugsun.boot.system.service;

import com.mugsun.boot.config.BizTables;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.row.Db;
import org.springframework.stereotype.Component;

/** sys_user.avatar 行级写入（SysUser 实体冻结未建模 avatar 列）；表名经 BizTables 带 schema，适配信创库 */
@Component
public class UserAvatarWriter {

	public void write(long userId, String avatar) {
		String table = BizTables.of("sys_user");
		TenantContext.ignore(() -> Db.updateBySql("UPDATE " + table + " SET avatar = ? WHERE id = ?", avatar, userId));
	}
}
