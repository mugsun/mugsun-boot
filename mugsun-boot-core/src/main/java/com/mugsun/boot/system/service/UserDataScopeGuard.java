package com.mugsun.boot.system.service;

import com.mugsun.boot.datascope.DataScope;
import com.mugsun.boot.datascope.DataScopeContext;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Component;

/**
 * 用户写操作的数据范围检查：用户列表里看不到的人，也不能改、删、停用、锁定、重置密码或分配角色。
 * <p>数据权限只在 SELECT 时注入，写接口按 id 取人不经过它，所以写之前要在这里显式查一次。
 * 判断方式和用户列表相同（同一份角色范围、同一个查询方言）。平台超管不受限。
 * <p>用法：一次请求先取一次 {@link #operatorScope()}，再用它逐个判断。保存后的复核也用这份操作前的范围，
 * 操作者在同一次保存里改了自己的部门或角色，也不能借改完以后的范围放行。
 */
@Component
public class UserDataScopeGuard {

	private final SysUserMapper userMapper;

	public UserDataScopeGuard(SysUserMapper userMapper) {
		this.userMapper = userMapper;
	}

	/** 操作者此刻的数据范围。由 {@link DataScope} 切面解析，所以只能从别的 Bean 调用，类内部自调用拿不到。 */
	@DataScope
	public DataScopeContext.Ctx operatorScope() {
		return DataScopeContext.current();
	}

	/** 按这份数据范围，是否看得到这名用户。范围为 null 一律按看不到处理。 */
	public boolean covers(DataScopeContext.Ctx scope, Long userId) {
		if (userId == null) {
			return false;
		}
		if (TenantContext.isPlatformSuperAdmin()) {
			return true;
		}
		if (scope == null) {
			return false;
		}
		DataScopeContext.Ctx outer = DataScopeContext.current();
		DataScopeContext.activate(scope);
		try {
			return userMapper.selectCountByQuery(QueryWrapper.create().eq("id", userId)) > 0;
		} finally {
			if (outer != null) {
				DataScopeContext.activate(outer);
			} else {
				DataScopeContext.clear();
			}
		}
	}

	/** 写之前：目标用户不在范围内就拒绝。 */
	public void assertCovers(DataScopeContext.Ctx scope, Long userId) {
		if (!covers(scope, userId)) {
			throw new ServiceException("这个用户不在你的数据范围内，不能操作。请刷新列表后再试；确实需要处理，请联系数据范围更大的管理员");
		}
	}

	/** 写之后、提交之前：保存的结果落到范围之外就拒绝，由调用方所在的事务回滚。 */
	public void assertStillCovers(DataScopeContext.Ctx scope, Long userId) {
		if (!covers(scope, userId)) {
			throw new ServiceException("按你的数据范围，保存后你将看不到这名用户，所以没有保存。请把部门选在你的数据范围内；确实要放到别的部门，请联系数据范围更大的管理员");
		}
	}
}
