package com.mugsun.boot.system.payload;

import java.util.List;

/**
 * 角色授权入参：角色主键 + 授权的菜单主键集合。
 */
public record GrantParam(Long roleId, List<Long> menuIds) {
}
