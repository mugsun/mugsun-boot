package com.mugsun.boot.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * 审计参数脱敏 + 截断（两条审计切面共用）：密码/密钥/令牌类字段 JSON 值置 ***，超长截断。
 */
public final class AuditMask {

	private static final int MAX_LEN = 2000;
	// 名单与 ApiParamMask 同源：密码/密钥/令牌/凭据类 + PII（手机号/身份证）+ 验证码类（一次性码亦不落明文）
	private static final String SENSITIVE_KEYS = "password|oldPassword|newPassword|dsPassword|pwd|passwd|"
		+ "secret|clientSecret|client_secret|apiKey|accessToken|access_token|refreshToken|refresh_token|"
		+ "token|credential|credentials|privateKey|sign|"
		+ "phone|mobile|idCard|idcard|id_card|code|smsCode|captchaCode|captcha_code";
	private static final Pattern MASK = Pattern.compile(
		"(?i)(\"(?:" + SENSITIVE_KEYS + ")\"\\s*:\\s*)\"[^\"]*\"");

	private AuditMask() {
	}

	/** 序列化入参 + 脱敏 + 截断；不可序列化时降级 Arrays.toString（并对 key=val 形态也做脱敏） */
	public static String maskAndTruncate(ObjectMapper objectMapper, Object[] args) {
		String raw;
		boolean json = true;
		try {
			Object[] data = Arrays.stream(args)
				.filter(a -> a != null && !(a instanceof jakarta.servlet.http.HttpServletRequest)
					&& !(a instanceof HttpServletResponse) && !(a instanceof MultipartFile))
				.toArray();
			raw = objectMapper.writeValueAsString(data);
		} catch (Exception e) {
			json = false;
			raw = Arrays.toString(args);
		}
		String masked = json
			? MASK.matcher(raw).replaceAll("$1\"***\"")
			// 降级串形态 password=xxx / password:xxx 也脱敏
			: raw.replaceAll("(?i)((?:" + SENSITIVE_KEYS + ")\\s*[=:]\\s*)[^,\\s\\]}]+", "$1***");
		return masked.length() > MAX_LEN ? masked.substring(0, MAX_LEN) + "...(truncated)" : masked;
	}
}
