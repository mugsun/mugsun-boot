package com.mugsun.boot.system.service;

import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.core.tool.exception.ServiceException;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/**
 * 头像专用上传（B4）：登录用户上传自己的头像，无需 sys:file:public。
 * 仅接受位图（png/jpg/jpeg/gif/webp；拒 svg 防 XSS），校验扩展名 + MIME + 文件头魔数，限 2MB，存公开区 public/avatar/。
 */
@Service
public class AvatarUploadService {

	private static final Logger log = LoggerFactory.getLogger(AvatarUploadService.class);

	public static final long MAX_SIZE = 2L * 1024 * 1024;
	public static final String PATH_AVATAR = OssConstants.PATH_PUBLIC + "avatar/";
	/** 扩展名 → 允许的 MIME */
	private static final Map<String, String> ALLOWED = Map.of(
		"png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg", "gif", "image/gif", "webp", "image/webp");

	private final FileStorageService fileStorageService;
	private final OssService ossService;

	public AvatarUploadService(FileStorageService fileStorageService, OssService ossService) {
		this.fileStorageService = fileStorageService;
		this.ossService = ossService;
	}

	public SysAttach upload(MultipartFile file) {
		validate(file);
		FileInfo info;
		try {
			info = fileStorageService.of(file)
				.setPlatform(ossService.activePlatform())
				.setPath(PATH_AVATAR)
				.putAttr(OssConstants.ATTR_ACCESS, OssConstants.ACCESS_PUBLIC)
				.upload();
		} catch (RuntimeException ex) {
			log.warn("头像上传失败");
			throw new ServiceException("头像没有上传成功。请稍后再试，或联系管理员检查存储配置");
		}
		if (info == null) {
			throw new ServiceException("头像没有上传成功。请稍后再试，或联系管理员检查存储配置");
		}
		SysAttach attach = new SysAttach();
		attach.setId(info.getId() == null ? null : Long.valueOf(info.getId()));
		attach.setName(info.getOriginalFilename());
		attach.setUrl(info.getUrl());
		attach.setPath(info.getPath());
		attach.setFilename(info.getFilename());
		attach.setExt(info.getExt());
		attach.setContentType(info.getContentType());
		attach.setSize(info.getSize());
		attach.setPlatform(info.getPlatform());
		attach.setBasePath(info.getBasePath());
		attach.setAccess(OssConstants.ACCESS_PUBLIC);
		return attach;
	}

	/** 扩展名 + 声明 MIME + 文件头魔数三重校验，限大小 */
	public void validate(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ServiceException("请选择一张图片作为头像");
		}
		if (file.getSize() > MAX_SIZE) {
			throw new ServiceException("头像图片不能超过 2MB。请压缩后再上传");
		}
		String name = file.getOriginalFilename();
		int idx = name == null ? -1 : name.lastIndexOf('.');
		String ext = idx < 0 ? "" : name.substring(idx + 1).toLowerCase(Locale.ROOT);
		String expectedMime = ALLOWED.get(ext);
		String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
		if (expectedMime == null || !expectedMime.equals(mime) || !magicMatches(file, ext)) {
			throw new ServiceException("头像只支持 png、jpg、gif、webp 图片。请换一张图片再上传");
		}
	}

	private static boolean magicMatches(MultipartFile file, String ext) {
		byte[] h = new byte[12];
		int n;
		try (InputStream in = file.getInputStream()) {
			n = in.readNBytes(h, 0, h.length);
		} catch (IOException e) {
			return false;
		}
		return switch (ext) {
			case "png" -> n >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
			case "jpg", "jpeg" -> n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
			case "gif" -> n >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8';
			case "webp" -> n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
				&& h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
			default -> false;
		};
	}
}
