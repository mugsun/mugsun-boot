package com.mugsun.boot.auth;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.service.AvatarUploadService;
import com.mugsun.core.tool.api.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 个人中心·头像上传：登录用户上传自己的头像（公开存储），返回结构同 /system/file/upload。
 * 前端拿 data.url 后照旧调用 POST /auth/update-avatar 落库。
 */
@RestController
@RequestMapping("/auth/avatar")
@SaCheckLogin
public class AvatarUploadController {

	private final AvatarUploadService avatarUploadService;

	public AvatarUploadController(AvatarUploadService avatarUploadService) {
		this.avatarUploadService = avatarUploadService;
	}

	@PostMapping("/upload")
	public R<SysAttach> upload(@RequestParam(value = "file", required = false) MultipartFile file) {
		return R.data(avatarUploadService.upload(file));
	}
}
