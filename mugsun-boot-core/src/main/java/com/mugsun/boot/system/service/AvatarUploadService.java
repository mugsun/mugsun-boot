package com.mugsun.boot.system.service;

import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.core.tool.exception.ServiceException;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mugsun.boot.system.mapper.SysAttachMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/**
 * 头像专用上传（B4）：登录用户上传自己的头像，无需 sys:file:public。
 * 上传成功即在同一事务内更新本人 sys_user.avatar；头像统一经匿名端点 {@link #RAW_PREFIX}{id} 输出（仅 public/avatar/ 范围），
 * 与存储平台（本地 enable-access 开关 / 自建本地 / MinIO / 阿里云桶 ACL）解耦。
 * 仅接受位图（png/jpg/jpeg/gif/webp；拒 svg 防 XSS），校验扩展名 + MIME + 文件头魔数，限 2MB，存公开区 public/avatar/。
 */
@Service
public class AvatarUploadService {

	private static final Logger log = LoggerFactory.getLogger(AvatarUploadService.class);

	public static final long MAX_SIZE = 2L * 1024 * 1024;
	public static final String PATH_AVATAR = OssConstants.PATH_PUBLIC + "avatar/";
	/** 头像对外 URL 前缀（相对后端根，前端按其它接口同样的 baseURL 拼接） */
	public static final String RAW_PREFIX = "/auth/avatar/raw/";
	/** 扩展名 → 允许的 MIME */
	private static final Map<String, String> ALLOWED = Map.of(
		"png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg", "gif", "image/gif", "webp", "image/webp");

	private final FileStorageService fileStorageService;
	private final OssService ossService;
	private final SysAttachMapper attachMapper;
	private final UserAvatarWriter avatarWriter;

	public AvatarUploadService(FileStorageService fileStorageService, OssService ossService,
							   SysAttachMapper attachMapper, UserAvatarWriter avatarWriter) {
		this.fileStorageService = fileStorageService;
		this.ossService = ossService;
		this.attachMapper = attachMapper;
		this.avatarWriter = avatarWriter;
	}

	/** 上传头像并更新本人 sys_user.avatar（登记 + 更新同一事务；返回的 url 即已落库的头像地址） */
	@Transactional(rollbackFor = Exception.class)
	public SysAttach upload(MultipartFile file, long userId) {
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
		if (attach.getId() == null) {
			throw new ServiceException("头像没有上传成功。请稍后再试，或联系管理员检查存储配置");
		}
		String url = RAW_PREFIX + attach.getId();
		attach.setUrl(url);
		avatarWriter.write(userId, url);
		return attach;
	}

	/**
	 * update-avatar 绑定：只允许本人上传、本租户、public 的附件；外链/他人/私有/不存在一律拒绝。
	 * 兼容两种入参：本端点返回的 {@code /auth/avatar/raw/{id}}（可带 /api 或域名前缀），或附件登记 url。
	 * 头像区附件落库为规范 raw 地址，其余本人公开附件落库为其登记 url。
	 */
	@Transactional(rollbackFor = Exception.class)
	public String bind(String avatar, long userId, String tenantId) {
		String value = avatar == null ? "" : avatar.trim();
		if (value.isEmpty() || value.length() > 255) {
			throw invalid();
		}
		QueryWrapper query = QueryWrapper.create();
		Long id = rawIdOf(value);
		if (id != null) {
			query.eq("id", id);
		} else {
			query.eq("url", value);
		}
		boolean scoped = tenantId != null && !TenantContext.ALL.equals(tenantId) && !TenantContext.IGNORE.equals(tenantId);
		if (scoped) {
			query.eq("tenant_id", tenantId);
		}
		SysAttach attach = attachMapper.selectOneByQuery(query);
		if (attach == null || (scoped && !tenantId.equals(attach.getTenantId()))
			|| attach.getCreateUser() == null || attach.getCreateUser() != userId
			|| !OssConstants.ACCESS_PUBLIC.equals(attach.getAccess())) {
			throw invalid();
		}
		String url = isAvatarArea(attach) ? RAW_PREFIX + attach.getId() : attach.getUrl();
		avatarWriter.write(userId, url);
		return url;
	}

	/** 匿名头像输出的范围裁决：仅 public 且位于 public/avatar/ 的附件 */
	public SysAttach requireServable(Long id) {
		SysAttach attach = id == null ? null : TenantContext.ignore(() -> attachMapper.selectOneById(id));
		if (attach == null || !isAvatarArea(attach)) {
			throw new ServiceException(com.mugsun.core.tool.api.ResultCode.NOT_FOUND);
		}
		return attach;
	}

	private static boolean isAvatarArea(SysAttach attach) {
		return OssConstants.ACCESS_PUBLIC.equals(attach.getAccess()) && attach.getPath() != null
			&& attach.getPath().startsWith(PATH_AVATAR) && !attach.getPath().contains("..");
	}

	static Long rawIdOf(String value) {
		int idx = value.indexOf(RAW_PREFIX);
		if (idx < 0) {
			return null;
		}
		String tail = value.substring(idx + RAW_PREFIX.length());
		if (tail.isEmpty() || tail.length() > 19 || !tail.chars().allMatch(Character::isDigit)) {
			return null;
		}
		return Long.valueOf(tail);
	}

	private static ServiceException invalid() {
		return new ServiceException("头像只能使用你自己上传的公开图片。请重新上传头像后再保存");
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
