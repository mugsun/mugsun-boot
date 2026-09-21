package com.mugsun.boot.common.mask;

import cn.dev33.satoken.stp.StpUtil;
import com.mybatisflex.core.mask.MaskProcessor;

import java.util.List;

/**
 * 按角色决策的脱敏处理器：读取字段值时按当前用户权限三级放行。
 * <ul>
 *   <li>无查看权 → 返回 null（该列对该角色不可见）</li>
 *   <li>有查看权、无明文权 → 委托内置脱敏算法（复用 Flex mobile/id_card_number 等，不重复造轮子）</li>
 *   <li>有明文权 → 返回原文</li>
 * </ul>
 * 决策绑权限码而非绑端点：同一字段在任意查询路径（列表/详情）表现一致。
 * 权限匹配复用 Sa-Token 官方通配语义（{@code StpLogic.hasElement}），基于请求维度快照，零嵌套查询。
 */
public class RoleAwareMaskProcessor implements MaskProcessor {

	private final String viewPermission;
	private final String plainPermission;
	private final MaskProcessor delegate;

	public RoleAwareMaskProcessor(String viewPermission, String plainPermission, MaskProcessor delegate) {
		this.viewPermission = viewPermission;
		this.plainPermission = plainPermission;
		this.delegate = delegate;
	}

	@Override
	public Object mask(Object value) {
		if (value == null) {
			return null;
		}
		// 恒脱敏读（审计快照等内部读）：与角色无关地取脱敏值，不落明文、不置空
		if (FieldMaskContext.isMaskOnly()) {
			return delegate.mask(value);
		}
		List<String> permissions = FieldMaskContext.current();
		// fail-closed：无上下文（未登录 / 非受控路径 / 异步内部读）一律不可见，杜绝明文外泄
		if (permissions == null) {
			return null;
		}
		if (!StpUtil.getStpLogic().hasElement(permissions, viewPermission)) {
			return null;
		}
		if (StpUtil.getStpLogic().hasElement(permissions, plainPermission)) {
			return value;
		}
		return delegate.mask(value);
	}
}
