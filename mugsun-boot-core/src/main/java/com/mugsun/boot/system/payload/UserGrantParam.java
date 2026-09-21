package com.mugsun.boot.system.payload;

import java.util.List;

/**
 * 用户角色授权入参：用户主键 + 授权的角色主键集合。
 */
public record UserGrantParam(Long userId, List<Long> roleIds) {
}
