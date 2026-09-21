package com.mugsun.boot.oauth;

import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 开放接口演示：由 {@link OpenApiInterceptor} 校验令牌与 scope，并按令牌租户隔离
 * （Flex 依 {@code SaTokenTenantFactory} 回退的令牌租户自动过滤，A 租户令牌取不到 B 租户数据）。
 * 仅暴露非敏感字段，供第三方按授权范围调用。
 */
@RestController
@RequestMapping("/open")
public class OpenApiController {

	private final SysUserMapper userMapper;

	public OpenApiController(SysUserMapper userMapper) {
		this.userMapper = userMapper;
	}

	/** 无 scope 要求，仅需有效令牌：连通性探测 */
	@GetMapping("/ping")
	public R<String> ping() {
		return R.data("open api pong");
	}

	/** 需 user:read：返回用户基础信息（不含密码/手机/身份证等敏感字段），按令牌租户隔离 */
	@GetMapping("/user/list")
	@OpenScope("user:read")
	public R<List<Map<String, Object>>> userList() {
		List<SysUser> users = userMapper.selectListByQuery(
			QueryWrapper.create().select("username", "nickname").limit(20));
		List<Map<String, Object>> data = users.stream().map(u -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("username", u.getUsername());
			m.put("nickname", u.getNickname());
			return m;
		}).collect(Collectors.toList());
		return R.data(data);
	}

	/** 需 user:read：用户总数，按令牌租户隔离 */
	@GetMapping("/user/count")
	@OpenScope("user:read")
	public R<Long> userCount() {
		return R.data(userMapper.selectCountByQuery(QueryWrapper.create()));
	}

	/** 需 user:write：回显（演示写范围，无实际写库） */
	@PostMapping("/echo")
	@OpenScope("user:write")
	public R<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("echo", body);
		return R.data(resp);
	}
}
