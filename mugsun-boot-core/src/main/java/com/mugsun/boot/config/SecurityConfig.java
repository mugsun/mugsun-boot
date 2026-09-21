package com.mugsun.boot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;

/**
 * 密码编码器：DelegatingPasswordEncoder（新密码带 {bcrypt} 前缀，可平滑升级算法）。
 * 兼容历史无前缀的裸 BCrypt 哈希——匹配时回退 BCrypt，实现存量密码无痛过渡。
 */
@Configuration
public class SecurityConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		Map<String, PasswordEncoder> encoders = new HashMap<>();
		encoders.put("bcrypt", new BCryptPasswordEncoder());
		DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt", encoders);
		// 存量无前缀哈希回退 BCrypt 校验
		encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
		return encoder;
	}
}
