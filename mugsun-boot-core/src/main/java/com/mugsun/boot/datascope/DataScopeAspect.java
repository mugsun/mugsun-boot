package com.mugsun.boot.datascope;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.DataScopeConstants;
import com.mugsun.boot.system.entity.SysDept;
import com.mugsun.boot.system.entity.SysRole;
import com.mugsun.boot.system.entity.SysRoleDept;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.entity.SysUserRole;
import com.mugsun.boot.system.mapper.SysDeptMapper;
import com.mugsun.boot.system.mapper.SysRoleDeptMapper;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据权限切面：在标注 {@link DataScope} 的方法上，<b>先</b>（旁路、不受任何外层数据权限影响）解析当前用户全部角色的
 * 数据范围与关联部门/子树/自定义SQL，存入 {@link DataScopeContext} 并激活；方法内的受控表查询由
 * {@link MugsunDataScopeDialect} 自动注入 OR 并集条件，无需手工 apply。方法结束恢复外层上下文（可嵌套）。
 */
@Aspect
@Component
public class DataScopeAspect {

	private final SysUserMapper userMapper;
	private final SysUserRoleMapper userRoleMapper;
	private final SysRoleMapper roleMapper;
	private final SysRoleDeptMapper roleDeptMapper;
	private final SysDeptMapper deptMapper;

	public DataScopeAspect(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper, SysRoleMapper roleMapper,
						   SysRoleDeptMapper roleDeptMapper, SysDeptMapper deptMapper) {
		this.userMapper = userMapper;
		this.userRoleMapper = userRoleMapper;
		this.roleMapper = roleMapper;
		this.roleDeptMapper = roleDeptMapper;
		this.deptMapper = deptMapper;
	}

	@Around("@annotation(com.mugsun.boot.datascope.DataScope)")
	public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
		DataScopeContext.Ctx prev = DataScopeContext.current();
		// 解析走旁路：不被外层激活或本身受控表误伤
		DataScopeContext.Ctx ctx = DataScopeContext.without(this::resolve);
		DataScopeContext.activate(ctx);
		try {
			return joinPoint.proceed();
		} finally {
			if (prev != null) {
				DataScopeContext.activate(prev);
			} else {
				DataScopeContext.clear();
			}
		}
	}

	/** 预解析当前用户全部角色数据范围（fail-closed：未登录/无角色 → 空范围，方言处拒绝返全量） */
	private DataScopeContext.Ctx resolve() {
		if (!StpUtil.isLogin()) {
			return new DataScopeContext.Ctx(null, null, Set.of(), List.of(), List.of(), List.of());
		}
		Long userId = StpUtil.getLoginIdAsLong();
		SysUser user = userMapper.selectOneById(userId);
		Long deptId = user == null ? null : user.getDeptId();
		List<Long> roleIds = userRoleMapper.selectListByQuery(QueryWrapper.create().eq("user_id", userId))
			.stream().map(SysUserRole::getRoleId).toList();
		List<SysRole> roles = roleIds.isEmpty() ? List.of() : roleMapper.selectListByIds(roleIds);
		Set<Integer> scopes = roles.stream().map(SysRole::getDataScope).filter(Objects::nonNull)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		List<String> customSqls = roles.stream()
			.filter(r -> Integer.valueOf(DataScopeConstants.CUSTOM_SQL).equals(r.getDataScope()))
			.map(SysRole::getCustomSql).filter(s -> s != null && !s.isBlank()).toList();
		List<Long> customDeptIds = scopes.contains(DataScopeConstants.CUSTOM_DEPT) && !roleIds.isEmpty()
			? roleDeptMapper.selectListByQuery(QueryWrapper.create().in("role_id", roleIds))
				.stream().map(SysRoleDept::getDeptId).distinct().toList()
			: List.of();
		List<Long> subtreeDeptIds = scopes.contains(DataScopeConstants.DEPT_AND_CHILD) && deptId != null
			? subtreeDeptIds(deptId) : List.of();
		return new DataScopeContext.Ctx(userId, deptId, scopes, customDeptIds, subtreeDeptIds, customSqls);
	}

	/** 本部门及子部门 id 集合（含自身），按 parent_id 向下广度遍历（org_pids 物化路径作后续 perf 增量） */
	private List<Long> subtreeDeptIds(Long rootId) {
		List<SysDept> all = deptMapper.selectAll();
		Set<Long> result = new HashSet<>();
		Deque<Long> stack = new ArrayDeque<>();
		stack.push(rootId);
		result.add(rootId);
		while (!stack.isEmpty()) {
			Long cur = stack.pop();
			for (SysDept d : all) {
				if (cur.equals(d.getParentId()) && result.add(d.getId())) {
					stack.push(d.getId());
				}
			}
		}
		return new ArrayList<>(result);
	}
}
