package com.mugsun.boot.common.module;

import com.mugsun.boot.system.entity.SysMenu;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可选模块菜单过滤：模块未装配（无 jar）或被关闭（yml ∩ sys_param）时，其菜单在所有菜单出口统一剔除。
 * <p>按 path 前缀 {@code /gis}、{@code /track}、{@code /ai} 判定目录/菜单，再沿 parent_id 把子孙（含无 path 的按钮 F）一并剔除，
 * 保证「当前用户菜单树」「菜单管理树」「角色授权树」三处口径一致。
 */
@Component
public class ModuleMenuFilter {

	private final GisModuleStatus gisModuleStatus;
	private final TrackModuleStatus trackModuleStatus;
	private final AiModuleStatus aiModuleStatus;

	public ModuleMenuFilter(GisModuleStatus gisModuleStatus, TrackModuleStatus trackModuleStatus,
							AiModuleStatus aiModuleStatus) {
		this.gisModuleStatus = gisModuleStatus;
		this.trackModuleStatus = trackModuleStatus;
		this.aiModuleStatus = aiModuleStatus;
	}

	/** 当前不可见的模块路由根（如 {@code /gis}） */
	public List<String> disabledRoots() {
		List<String> roots = new ArrayList<>(3);
		if (!gisModuleStatus.enabled()) {
			roots.add("/gis");
		}
		if (!trackModuleStatus.enabled()) {
			roots.add("/track");
		}
		if (!aiModuleStatus.enabled()) {
			roots.add("/ai");
		}
		return roots;
	}

	/** 给定菜单集合中，属于已关闭/未装配模块的菜单 id（含子孙） */
	public Set<Long> hiddenMenuIds(Collection<SysMenu> menus) {
		List<String> roots = disabledRoots();
		Set<Long> hidden = new HashSet<>();
		if (roots.isEmpty() || menus == null || menus.isEmpty()) {
			return hidden;
		}
		Map<Long, List<Long>> children = new HashMap<>();
		for (SysMenu m : menus) {
			if (m.getId() == null) {
				continue;
			}
			if (m.getParentId() != null) {
				children.computeIfAbsent(m.getParentId(), k -> new ArrayList<>()).add(m.getId());
			}
			if (underRoot(m.getPath(), roots)) {
				hidden.add(m.getId());
			}
		}
		// 子孙闭包：按钮（F）没有 path，只能靠父链判定
		List<Long> queue = new ArrayList<>(hidden);
		for (int i = 0; i < queue.size(); i++) {
			for (Long child : children.getOrDefault(queue.get(i), List.of())) {
				if (hidden.add(child)) {
					queue.add(child);
				}
			}
		}
		return hidden;
	}

	/** 剔除已关闭/未装配模块的菜单，返回新列表（不改入参） */
	public List<SysMenu> filter(List<SysMenu> menus) {
		Set<Long> hidden = hiddenMenuIds(menus);
		if (hidden.isEmpty()) {
			return menus;
		}
		return menus.stream().filter(m -> !hidden.contains(m.getId())).toList();
	}

	private static boolean underRoot(String path, List<String> roots) {
		if (path == null) {
			return false;
		}
		for (String root : roots) {
			if (path.equals(root) || path.startsWith(root + "/")) {
				return true;
			}
		}
		return false;
	}
}
