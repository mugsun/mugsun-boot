package com.mugsun.boot.system.service;

import com.mugsun.boot.system.entity.SysUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 选人下拉的选项文字：昵称（用户名）。没填昵称的用用户名，不能显示成只有括号的「（用户名）」。
 */
class UserSelectOptionsTest {

	@Test
	void labelIsNicknameFollowedByUsername() {
		assertThat(labelOf("张三", "zhangsan")).isEqualTo("张三（zhangsan）");
	}

	@Test
	void labelFallsBackToUsernameWhenNicknameIsMissingOrBlank() {
		assertThat(labelOf(null, "demo_escalated")).isEqualTo("demo_escalated（demo_escalated）");
		assertThat(labelOf("", "demo_escalated")).isEqualTo("demo_escalated（demo_escalated）");
		assertThat(labelOf("   ", "demo_escalated")).isEqualTo("demo_escalated（demo_escalated）");
	}

	@Test
	void valueIsUserId() {
		SysUser user = user("李四", "lisi");
		user.setId(42L);
		List<Map<String, Object>> options = UserSelectOptions.of(List.of(user));
		assertThat(options).hasSize(1);
		assertThat(options.get(0).get("value")).isEqualTo(42L);
	}

	private static String labelOf(String nickname, String username) {
		return (String) UserSelectOptions.of(List.of(user(nickname, username))).get(0).get("label");
	}

	private static SysUser user(String nickname, String username) {
		SysUser user = new SysUser();
		user.setNickname(nickname);
		user.setUsername(username);
		return user;
	}
}
