package com.mugsun.boot.feedback;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.feedback.entity.SysFeedback;
import com.mugsun.boot.feedback.mapper.SysFeedbackMapper;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.file.UploadPolicy;
import com.mugsun.boot.system.service.AttachService;
import com.mugsun.boot.tenant.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 意见反馈的附件：管理员按反馈读提交人传的那张图。
 * <p>和 {@link FeedbackController} 分开放：反馈的其它接口只读写数据库，只有这一个要读文件存储。
 * 微服务版里文件存储在单独的服务上，这个类跟着存储走；地址和权限都不变。
 * 类上的前缀写到 {@code /attachment} 为止：微服务版的网关按接口类的前缀转发，反馈的其它接口不归这个类。
 */
@RestController
@RequestMapping("/system/feedback/attachment")
@SaCheckLogin
public class FeedbackAttachmentController {

	private static final Logger log = LoggerFactory.getLogger(FeedbackAttachmentController.class);

	private final SysFeedbackMapper feedbackMapper;
	private final AttachService attachService;
	private final FileStorageService fileStorageService;

	public FeedbackAttachmentController(SysFeedbackMapper feedbackMapper, AttachService attachService,
										FileStorageService fileStorageService) {
		this.feedbackMapper = feedbackMapper;
		this.attachService = attachService;
		this.fileStorageService = fileStorageService;
	}

	/**
	 * 管理员读一条反馈的附件。反馈不分租户，附件登记在提交人的租户下，所以不看租户取登记行；
	 * 只输出提交人自己上传的那一个，其余一律 404。响应头同 /system/file/download-stream：
	 * 按下载输出，页面取到字节后自己显示，不在本站地址下直接打开。
	 */
	@GetMapping("/{id}")
	@SaCheckPermission("sys:feedback:manage")
	public void attachment(@PathVariable Long id, HttpServletResponse response) throws IOException {
		SysFeedback feedback = feedbackMapper.selectOneById(id);
		Long attachId = feedback == null ? null : feedback.getAttachId();
		SysAttach attach = attachId == null ? null : TenantContext.ignore(() -> attachService.getById(attachId));
		if (attach == null || attach.getPath() == null || attach.getFilename() == null
			|| attach.getCreateUser() == null || !attach.getCreateUser().equals(feedback.getUserId())) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		FileInfo fileInfo = new FileInfo();
		fileInfo.setPlatform(attach.getPlatform());
		fileInfo.setBasePath(attach.getBasePath() == null ? "" : attach.getBasePath());
		fileInfo.setPath(attach.getPath());
		fileInfo.setFilename(attach.getFilename());
		String contentType = attach.getContentType();
		if (contentType == null || UploadPolicy.isActiveContentType(contentType)) {
			contentType = "application/octet-stream";
		}
		String downloadName = URLEncoder.encode(attach.getName() == null ? attach.getFilename() : attach.getName(),
			StandardCharsets.UTF_8).replace("+", "%20");
		response.setContentType(contentType);
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + downloadName);
		try {
			fileStorageService.download(fileInfo).outputStream(response.getOutputStream());
		} catch (Exception e) {
			log.warn("反馈附件读取失败 feedbackId={}: {}", id, e.getMessage());
			// 已经开始往外写就改不了状态码了，这时 reset 会抛异常
			if (!response.isCommitted()) {
				response.reset();
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
			}
		}
	}
}
