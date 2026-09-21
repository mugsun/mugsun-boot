package com.mugsun.boot.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.config.BizTables;
import com.mybatisflex.core.row.Db;
import org.dromara.warm.flow.core.handler.PermissionHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * warm-flow 办理人权限处理器（单例 @Component，引擎自动装配）：
 * <ul><li>{@link #getHandler()}：当前操作人唯一标识（用户 id）</li>
 * <li>{@link #permissions()}：当前用户可匹配的办理标识集（用户 id + 角色码，兼容旧流程裸角色码办理人）</li>
 * <li>{@link #convertPermissions}：落 flow_user 前把 role:/dept:/post:/user: 前缀解析成用户 id（上下文无关部分）；
 * 发起人/部门负责人占位交 assignment 监听器解析</li></ul>
 */
@Component
public class MugsunPermissionHandler implements PermissionHandler {

	private final HandlerSelectService selectService;

	public MugsunPermissionHandler(HandlerSelectService selectService) {
		this.selectService = selectService;
	}

	@Override
	public String getHandler() {
		return StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null;
	}

	@Override
	public List<String> permissions() {
		if (!StpUtil.isLogin()) {
			return Collections.emptyList();
		}
		List<String> flags = new ArrayList<>();
		flags.add(StpUtil.getLoginIdAsString());
		Db.selectListBySql(
			"select r.role_code as \"rc\" from " + BizTables.of("sys_user_role") + " ur "
				+ "join " + BizTables.of("sys_role") + " r on r.id = ur.role_id and r.is_deleted = 0 "
				+ "where ur.user_id = ? and ur.is_deleted = 0", StpUtil.getLoginIdAsLong())
			.forEach(row -> flags.add(row.getString("rc")));
		return flags;
	}

	@Override
	public List<String> convertPermissions(List<String> flags) {
		// 空输入（如无办理人的开始/结束节点）不加兜底，避免给非任务节点塞人
		if (flags == null || flags.isEmpty()) {
			return flags;
		}
		// 静态前缀→用户 id；发起人/部门负责人占位透传，最终空/==发起人兜底在 assignment 监听器统一处理
		return selectService.resolveStatic(flags);
	}
}
