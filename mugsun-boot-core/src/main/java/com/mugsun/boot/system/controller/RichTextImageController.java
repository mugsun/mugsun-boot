package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.annotation.SaMode;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.service.RichTextImageService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import jakarta.servlet.http.HttpServletResponse;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 富文本正文图片：能管理公告、帮助文档、更新日志其中任一项的人可以上传，返回结构同 /system/file/upload；
 * 图片经匿名 GET /system/richtext/image/raw/{id}/{filename} 输出（仅 public/richtext/ 范围），可直接作正文里的 &lt;img src&gt;。
 */
@RestController
@RequestMapping("/system/richtext/image")
@SaCheckLogin
public class RichTextImageController {

	private static final Logger log = LoggerFactory.getLogger(RichTextImageController.class);

	private final RichTextImageService richTextImageService;
	private final FileStorageService fileStorageService;

	public RichTextImageController(RichTextImageService richTextImageService, FileStorageService fileStorageService) {
		this.richTextImageService = richTextImageService;
		this.fileStorageService = fileStorageService;
	}

	/** 上传一张正文图片：data.url 是写进正文的读取地址 */
	@PostMapping("/upload")
	@SaCheckPermission(value = {"sys:notice:manage", "sys:help:manage", "sys:changelog:manage"}, mode = SaMode.OR)
	public R<SysAttach> upload(@RequestParam(value = "file", required = false) MultipartFile file) {
		return R.data(richTextImageService.upload(file));
	}

	/** 匿名图片输出：编号和文件名都对得上的正文图片，其余一律 404 */
	@SaIgnore
	@GetMapping("/raw/{id}/{filename}")
	public void raw(@PathVariable String id, @PathVariable String filename, HttpServletResponse response)
		throws IOException {
		SysAttach attach;
		try {
			attach = richTextImageService.requireServable(idOf(id), filename);
		} catch (ServiceException e) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		FileInfo fileInfo = new FileInfo();
		fileInfo.setPlatform(attach.getPlatform());
		fileInfo.setBasePath(attach.getBasePath() == null ? "" : attach.getBasePath());
		fileInfo.setPath(attach.getPath());
		fileInfo.setFilename(attach.getFilename());
		response.setContentType(RichTextImageService.contentTypeOf(attach));
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("Cache-Control", "public, max-age=86400");
		try {
			fileStorageService.download(fileInfo).outputStream(response.getOutputStream());
		} catch (Exception e) {
			log.warn("正文图片读取失败 id={}: {}", attach.getId(), e.getMessage());
			// 已经开始往外写（大图传到一半浏览器断开）就改不了状态码了，这时 reset 会抛异常
			if (!response.isCommitted()) {
				response.reset();
				response.sendError(HttpServletResponse.SC_NOT_FOUND);
			}
		}
	}

	/** 地址里的编号：不是纯数字就当作不存在，不进参数转换的报错分支 */
	private static Long idOf(String raw) {
		if (raw.isEmpty() || raw.length() > 19 || !raw.chars().allMatch(c -> c >= '0' && c <= '9')) {
			return null;
		}
		try {
			return Long.valueOf(raw);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
