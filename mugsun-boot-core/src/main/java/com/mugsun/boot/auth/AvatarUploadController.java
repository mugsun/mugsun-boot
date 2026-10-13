package com.mugsun.boot.auth;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.service.AvatarUploadService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import jakarta.servlet.http.HttpServletResponse;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 个人中心·头像：登录用户上传自己的头像（公开存储，同事务更新 sys_user.avatar），返回结构同 /system/file/upload；
 * 头像经匿名 GET /auth/avatar/raw/{id} 输出（仅 public/avatar/ 范围），可直接作 &lt;img src&gt;，与存储平台无关。
 */
@RestController
@RequestMapping("/auth/avatar")
@SaCheckLogin
public class AvatarUploadController {

	private static final Logger log = LoggerFactory.getLogger(AvatarUploadController.class);

	private final AvatarUploadService avatarUploadService;
	private final FileStorageService fileStorageService;

	public AvatarUploadController(AvatarUploadService avatarUploadService, FileStorageService fileStorageService) {
		this.avatarUploadService = avatarUploadService;
		this.fileStorageService = fileStorageService;
	}

	/** 上传并设为本人头像：data.url 即已落库头像地址，无需再调 update-avatar */
	@PostMapping("/upload")
	public R<SysAttach> upload(@RequestParam(value = "file", required = false) MultipartFile file) {
		return R.data(avatarUploadService.upload(file, StpUtil.getLoginIdAsLong()));
	}

	/** 匿名头像输出：仅 public/avatar/ 附件，其余一律 404 */
	@SaIgnore
	@GetMapping("/raw/{id}")
	public void raw(@PathVariable Long id, HttpServletResponse response) throws IOException {
		SysAttach attach;
		try {
			attach = avatarUploadService.requireServable(id);
		} catch (ServiceException e) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		FileInfo fileInfo = new FileInfo();
		fileInfo.setPlatform(attach.getPlatform());
		fileInfo.setBasePath(attach.getBasePath() == null ? "" : attach.getBasePath());
		fileInfo.setPath(attach.getPath());
		fileInfo.setFilename(attach.getFilename());
		response.setContentType(attach.getContentType() == null ? "application/octet-stream" : attach.getContentType());
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("Cache-Control", "public, max-age=86400");
		try {
			fileStorageService.download(fileInfo).outputStream(response.getOutputStream());
		} catch (Exception e) {
			log.warn("头像读取失败 id={}: {}", id, e.getMessage());
			response.reset();
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
		}
	}
}
