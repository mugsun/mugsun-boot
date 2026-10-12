package com.mugsun.boot.monitor;

import com.mugsun.boot.system.service.UserService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 把登录 id 换成昵称（没有昵称用账号）。查不到的人保留原 id。
 */
@Component
public class OperatorNameLookup {

	private final UserService userService;

	public OperatorNameLookup(UserService userService) {
		this.userService = userService;
	}

	public Map<String, String> namesOf(Collection<String> operators) {
		Map<String, String> names = new HashMap<>();
		if (operators == null || operators.isEmpty()) {
			return names;
		}
		Set<Long> ids = new HashSet<>();
		for (String operator : operators) {
			if (operator != null && operator.matches("\\d+")) {
				ids.add(Long.valueOf(operator));
			}
		}
		if (!ids.isEmpty()) {
			userService.displayNamesByIds(ids).forEach((id, name) -> names.put(String.valueOf(id), name));
		}
		return names;
	}
}
