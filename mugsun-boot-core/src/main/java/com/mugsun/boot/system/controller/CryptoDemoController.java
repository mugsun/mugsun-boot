package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.web.crypto.ApiDecrypt;
import com.mugsun.core.web.crypto.ApiEncrypt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 接口国密加解密演示：请求体 SM4 解密（@ApiDecrypt）、响应 data SM4 加密（@ApiEncrypt）。
 */
@RestController
@RequestMapping("/system/crypto")
@SaCheckLogin
public class CryptoDemoController {

	/** 回声：入参已由 @ApiDecrypt 解密为明文，返回值将由 @ApiEncrypt 加密为密文；附国密摘要（SM3/SHA-256 随开关） */
	@ApiDecrypt
	@ApiEncrypt
	@PostMapping("/echo")
	public R<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
		Map<String, Object> result = new HashMap<>();
		String text = String.valueOf(body.get("text"));
		result.put("received", body.get("text"));
		result.put("echo", "服务端已解密收到并将加密返回：" + body.get("text"));
		result.put("digest", com.mugsun.boot.common.crypto.GmDigestUtil.digest(text));
		result.put("digestAlgo", com.mugsun.boot.common.crypto.GmDigestUtil.algorithm());
		result.put("time", LocalDateTime.now().toString());
		return R.data(result);
	}
}
