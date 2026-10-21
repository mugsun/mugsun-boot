package com.mugsun.boot.system.controller;

import com.mugsun.boot.system.file.PendingObjectCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.dromara.x.file.storage.core.get.RemoteFileInfo;
import com.mugsun.boot.system.file.UploadPolicy;
import java.util.Locale;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.lang.Dict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.mapper.SysAttachMapper;
import com.mugsun.boot.system.service.FileAccessService;
import com.mugsun.boot.system.service.OssService;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.constant.Constant;
import org.dromara.x.file.storage.core.presigned.GeneratePresignedUrlResult;
import org.dromara.x.file.storage.core.recorder.FileRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件上传与附件登记：multipart 上传经 FileRecorder 落 sys_attach；
 * 两段式直传（presigned-put 签发一次性凭证 → 前端 PUT 直传云 → create 消费凭证回填登记）。
 */
@RestController
@RequestMapping("/system/file")
@SaCheckLogin
public class FileController {

	private static final Logger log = LoggerFactory.getLogger(FileController.class);

	private final FileStorageService fileStorageService;
	private final FileRecorder fileRecorder;
	private final SysAttachMapper attachMapper;
	private final OssService ossService;
	private final ParamService paramService;
	private final StringRedisTemplate redisTemplate;
	private final FileAccessService fileAccessService;
	private final com.mugsun.boot.system.file.LocalFileUrlSigner localFileUrlSigner;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private PendingObjectCleaner pendingObjectCleaner;

	@Autowired
	void setPendingObjectCleaner(PendingObjectCleaner pendingObjectCleaner) {
		this.pendingObjectCleaner = pendingObjectCleaner;
	}

	public FileController(FileStorageService fileStorageService, FileRecorder fileRecorder,
						  SysAttachMapper attachMapper, OssService ossService, ParamService paramService,
						  StringRedisTemplate redisTemplate, FileAccessService fileAccessService,
						  com.mugsun.boot.system.file.LocalFileUrlSigner localFileUrlSigner) {
		this.localFileUrlSigner = localFileUrlSigner;
		this.fileStorageService = fileStorageService;
		this.fileRecorder = fileRecorder;
		this.attachMapper = attachMapper;
		this.ossService = ossService;
		this.paramService = paramService;
		this.redisTemplate = redisTemplate;
		this.fileAccessService = fileAccessService;
	}

	/** 上传文件并登记附件（access=public 公开直取 / private 私有授权下载；登记由 FileRecorder 统一落库） */
	@PostMapping("/upload")
	@SaCheckPermission("sys:file:upload")
	public R<SysAttach> upload(@RequestParam(value = "file", required = false) MultipartFile file,
							   @RequestParam(defaultValue = OssConstants.ACCESS_PRIVATE) String access) {
		if (file == null || file.isEmpty()) {
			throw new ServiceException("请选择一个有内容的文件再上传");
		}
		// 白名单扩展名 + 魔数 + 大小；存储名由服务端生成，原文件名只作展示
		String ext = UploadPolicy.validate(file);
		// 普通用户强制 private；仅持 sys:file:public 可设公开
		boolean isPublic = fileAccessService.resolvePublic(access, StpUtil::hasPermission);
		String folder = isPublic ? OssConstants.PATH_PUBLIC : OssConstants.PATH_PRIVATE;
		FileInfo info;
		try {
			info = fileStorageService.of(file)
				.setPlatform(ossService.activePlatform())
				.setPath(folder)
				.setSaveFilename(UploadPolicy.storedName(ext))
				// Content-Type 按白名单扩展名定，不用客户端上报；云存储对象一律带 attachment（本地存储由 LocalFileAccessFilter 加）
				.setContentType(UploadPolicy.mimeOf(ext))
				.setNotSupportMetadataThrowException(false)
				.putMetadata("Content-Disposition", "attachment")
				.putAttr(OssConstants.ATTR_ACCESS, isPublic ? OssConstants.ACCESS_PUBLIC : OssConstants.ACCESS_PRIVATE)
				.upload();
		} catch (RuntimeException ex) {
			log.warn("附件上传失败");
			throw new ServiceException("文件没有上传成功。请到存储配置里检查当前启用的目录或地址，然后再试");
		}
		SysAttach attach = toAttach(info, isPublic);
		// 私有文件不在响应中直接暴露可访问 url，需走授权下载
		if (!isPublic) {
			attach.setUrl(null);
		}
		return R.data(attach);
	}

	/**
	 * 两段式直传·签发：校验文件名策略后签发 PUT 预签名 URL，并将路径身份存一次性 Redis 凭证（防任意路径登记）。
	 * 当前平台不支持预签名（如本地存储）时返回 supported=false，前端回退 multipart 上传。
	 */
	@PostMapping("/presigned-put")
	@SaCheckPermission("sys:file:upload")
	public R<PresignedPutResult> presignedPut(@RequestBody PresignedPutParam param) {
		String filename = param.filename();
		if (filename == null || filename.isBlank() || filename.length() > OssConstants.PRESIGNED_FILENAME_MAX_LEN
			|| filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
			throw new ServiceException("文件名不能包含斜杠，也不能太长。请换一个文件名后再上传");
		}
		String allowedExt = UploadPolicy.requireAllowedExt(filename);
		long size = UploadPolicy.requireSize(param.size());
		String contentType = UploadPolicy.mimeOf(allowedExt);
		boolean isPublic = fileAccessService.resolvePublic(param.access(), StpUtil::hasPermission);
		String platform = ossService.activePlatform();
		if (!fileStorageService.isSupportPresignedUrl(platform)) {
			return R.data(new PresignedPutResult(false, null, null, null, null));
		}
		// 服务端生成存储文件名（uuid 防同名覆盖），原始文件名仅作展示存凭证
		String ext = extOf(filename);
		String storedFilename = UUID.randomUUID().toString().replace("-", "") + (ext.isEmpty() ? "" : "." + ext);
		Date expiration = new Date(System.currentTimeMillis() + OssConstants.PRESIGNED_TICKET_SECONDS * 1000);
		GeneratePresignedUrlResult result = fileStorageService.generatePresignedUrl()
			.setPlatform(platform)
			.setPath(isPublic ? OssConstants.PATH_PUBLIC : OssConstants.PATH_PRIVATE)
			.setFilename(storedFilename)
			.setMethod(Constant.GeneratePresignedUrl.Method.PUT)
			.setExpiration(expiration)
			// 签名绑定大小、类型和下载方式：客户端 PUT 时必须原样带上，改了签名就失效
			.putHeaders("Content-Length", Long.toString(size))
			.putHeaders("Content-Type", contentType)
			.putHeaders("Content-Disposition", "attachment")
			.generatePresignedUrl();
		String ticket = UUID.randomUUID().toString().replace("-", "");
		ObjectNode credential = objectMapper.createObjectNode();
		credential.put("platform", platform);
		credential.put("basePath", result.getBasePath());
		credential.put("path", result.getPath());
		credential.put("filename", result.getFilename());
		credential.put("name", filename);
		credential.put("size", size);
		credential.put("contentType", contentType);
		credential.put("access", isPublic ? OssConstants.ACCESS_PUBLIC : OssConstants.ACCESS_PRIVATE);
		redisTemplate.opsForValue().set(OssConstants.PRESIGNED_TICKET_PREFIX + ticket, credential.toString(),
			Duration.ofSeconds(OssConstants.PRESIGNED_TICKET_SECONDS));
		return R.data(new PresignedPutResult(true, result.getUrl(), result.getHeaders(), ticket,
			OssConstants.PRESIGNED_TICKET_SECONDS));
	}

	/**
	 * 两段式直传·回填：消费一次性凭证（GETDEL）登记附件——路径身份全部取自凭证，客户端不可指定，
	 * 伪造/重放/过期凭证一律拒绝。size 为客户端上报的展示值（安全不依赖）。
	 */
	@PostMapping("/create")
	@SaCheckPermission("sys:file:upload")
	public R<SysAttach> create(@RequestBody CreateParam param) {
		if (param.ticket() == null || param.ticket().isBlank()) {
			throw new ServiceException("上传凭证缺失。请重新选择文件再上传");
		}
		String json = redisTemplate.opsForValue().getAndDelete(OssConstants.PRESIGNED_TICKET_PREFIX + param.ticket());
		if (json == null) {
			throw new ServiceException("上传凭证已失效。请重新选择文件再上传");
		}
		JsonNode credential;
		try {
			credential = objectMapper.readTree(json);
		} catch (Exception e) {
			throw new ServiceException("上传凭证读不出来。请重新选择文件再上传");
		}
		String platform = credential.path("platform").asText();
		String path = credential.path("path").asText();
		String filename = credential.path("filename").asText();
		boolean isPublic = OssConstants.ACCESS_PUBLIC.equals(credential.path("access").asText());
		FileInfo info = new FileInfo();
		info.setPlatform(platform);
		info.setBasePath(credential.path("basePath").asText(""));
		info.setPath(path);
		info.setFilename(filename);
		info.setOriginalFilename(credential.path("name").asText(filename));
		info.setExt(extOf(filename));
		// 大小和类型取自签发凭证（已绑定进签名），不用回填时客户端上报的值
		info.setSize(credential.path("size").asLong());
		info.setContentType(credential.path("contentType").asText("application/octet-stream"));
		// 签名是否真的绑定了这些头取决于存储平台实现，登记前回查对象，不符就删对象并拒绝
		verifyUploadedObject(info);
		info.setUrl(ossService.predictUrl(platform, path, filename));
		info.setAttr(Dict.create().set(OssConstants.ATTR_ACCESS,
			isPublic ? OssConstants.ACCESS_PUBLIC : OssConstants.ACCESS_PRIVATE));
		// 预签名 URL 在有效期内还能再 PUT：回验通过后在服务端复制到客户端不知道的新 key，删掉原 key，
		// 只登记新 key（copy 内部经 FileRecorder 落库）。之后再用原 URL 覆盖，也碰不到已登记的对象
		FileInfo stored = relocate(info);
		SysAttach attach = toAttach(stored, isPublic);
		if (!isPublic) {
			attach.setUrl(null);
		}
		return R.data(attach);
	}

	/**
	 * 授权下载：登录后按 id 获取可访问 url。
	 * 云平台私有附件签发限时预签名 GET URL（有效期走 sys_param，过期须重新授权）；
	 * 本地平台/公开附件返回登记 url（本地私有走 download-stream 授权流式下载）。
	 */
	@GetMapping("/download/{id}")
	public R<String> download(@PathVariable Long id) {
		// 租户过滤 + 归属/公开/manage 裁决；跨租户、不存在、无权一律 404
		SysAttach attach = fileAccessService.requireDownloadable(id, TenantContext.current(),
			StpUtil.getLoginIdAsLong(), StpUtil::hasPermission);
		boolean cloudPrivate = OssConstants.ACCESS_PRIVATE.equals(attach.getAccess())
			&& !OssConstants.CATEGORY_LOCAL.equals(ossService.categoryOf(attach.getPlatform()));
		if (cloudPrivate) {
			FileInfo fileInfo = new FileInfo();
			fileInfo.setPlatform(attach.getPlatform());
			fileInfo.setBasePath(attach.getBasePath() == null ? "" : attach.getBasePath());
			fileInfo.setPath(attach.getPath());
			fileInfo.setFilename(attach.getFilename());
			return R.data(fileStorageService.generatePresignedUrl(fileInfo,
				new Date(System.currentTimeMillis() + presignedUrlExpireSeconds() * 1000)));
		}
		// 本地私有：直出 /file/** 需短时 HMAC 签名（LocalFileAccessFilter 校验），登记 url 本身不可匿名访问
		if (OssConstants.ACCESS_PRIVATE.equals(attach.getAccess()) && attach.getUrl() != null) {
			return R.data(localFileUrlSigner.signUrl(attach.getUrl()));
		}
		return R.data(attach.getUrl());
	}

	/**
	 * 授权流式下载：登录后按 id 从存储平台读取文件字节流写回响应。
	 * 补平台级文件服务缺口——本地/云存储均经 x-file-storage 统一下载，token 由 Sa-Token 校验。
	 */
	@GetMapping("/download-stream/{id}")
	public void downloadStream(@PathVariable Long id, jakarta.servlet.http.HttpServletResponse response)
			throws java.io.IOException {
		SysAttach attach;
		try {
			attach = fileAccessService.requireDownloadable(id, TenantContext.current(),
				StpUtil.getLoginIdAsLong(), StpUtil::hasPermission);
		} catch (ServiceException denied) {
			attach = null;
		}
		if (attach == null) {
			response.sendError(jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND, "这个附件不存在，或已被删除。请刷新列表后再下载");
			return;
		}
		FileInfo fileInfo = new FileInfo();
		fileInfo.setPlatform(attach.getPlatform());
		fileInfo.setBasePath(attach.getBasePath() == null ? "" : attach.getBasePath());
		fileInfo.setPath(attach.getPath());
		fileInfo.setFilename(attach.getFilename());
		String downloadName = java.net.URLEncoder.encode(
			attach.getName() == null ? attach.getFilename() : attach.getName(), java.nio.charset.StandardCharsets.UTF_8);
		String contentType = attach.getContentType();
		// 可执行脚本类（html/svg/js/xml）一律按二进制下发，配合 attachment + nosniff，不在同域内联执行
		if (contentType == null || UploadPolicy.isActiveContentType(contentType)) {
			contentType = "application/octet-stream";
		}
		response.setContentType(contentType);
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + downloadName);
		// 不信任客户端上报 size（直传登记值为展示用途）： chunked 传输，防错配 Content-Length 挂起/截断
		try {
			fileStorageService.download(fileInfo).outputStream(response.getOutputStream());
		} catch (Exception e) {
			log.warn("文件下载失败 id={}: {}", id, e.getMessage());
			response.reset();
			// 存储平台下线/文件已物删 → 404（前端预览类调用据此静默降级，不污染错误监控）
			response.sendError(jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND, "文件读不出来。请到存储配置里确认这套存储还在，然后再下载");
		}
	}

	/** 附件分页：持码可见（防任意登录用户枚举附件定位信息）；filename 按原始文件名 LIKE、ext 精确匹配 */
	@GetMapping("/page")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:file:list")
	public R<com.mybatisflex.core.paginate.Page<SysAttach>> page(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "10") long pageSize,
									 @RequestParam(required = false) String filename,
									 @RequestParam(required = false) String ext) {
		QueryWrapper query = QueryWrapper.create().orderBy("id", false);
		// 查询条件（值走参数化绑定，LIKE 前后模糊）
		if (filename != null && !filename.isBlank()) {
			query.like("name", filename.trim());
		}
		if (ext != null && !ext.isBlank()) {
			query.eq("ext", ext.trim());
		}
		com.mybatisflex.core.paginate.Page<SysAttach> page = attachMapper.paginate(pageNum, Math.min(pageSize, 500), query);
		// 平台在册标记富化（历史附件平台已下线 → 前端预览静默跳过，不制造 404 噪音）
		for (SysAttach attach : page.getRecords()) {
			attach.setDownloadable(ossService.platformRegistered(attach.getPlatform()));
		}
		return R.data(page);
	}

	/**
	 * 删除附件：经 fileStorageService.delete 物理删除并由 FileRecorder 级联销登记。
	 * 物理删除失败不回退登记、明确报错回执（失败 id 上抛），杜绝「物删失败仅 warn、登记照删」的静默断层。
	 */
	@RepeatSubmit(message = "请勿重复删除。请刷新列表确认这些附件是否还在")
	@PostMapping("/remove")
	@SaCheckPermission("sys:file:remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先勾选要删除的附件");
		}
		List<Long> failed = new ArrayList<>();
		int removed = 0;
		for (Long id : ids) {
			if (id == null) {
				continue;
			}
			// 仅上传者或 sys:file:manage；跨租户/他人文件按不存在处理
			SysAttach attach = fileAccessService.findRemovable(id, TenantContext.current(),
				StpUtil.getLoginIdAsLong(), StpUtil::hasPermission);
			if (attach == null) {
				continue;
			}
			if (attach.getPath() != null) {
				FileInfo fileInfo = new FileInfo();
				fileInfo.setPlatform(attach.getPlatform());
				fileInfo.setBasePath(attach.getBasePath() == null ? "" : attach.getBasePath());
				fileInfo.setPath(attach.getPath());
				fileInfo.setFilename(attach.getFilename());
				fileInfo.setUrl(attach.getUrl());
				boolean deleted;
				try {
					deleted = fileStorageService.delete(fileInfo);
				} catch (Exception e) {
					deleted = false;
					log.warn("附件物理删除失败 id={}, url={}: {}", id, attach.getUrl(), e.getMessage());
				}
				if (!deleted) {
					failed.add(id);
					continue;
				}
			}
			// recorder 已级联销登记；兜底幂等删（path 为空的历史登记行）
			attachMapper.deleteById(id);
			removed++;
		}
		if (!failed.isEmpty()) {
			throw new ServiceException("有的附件没有从存储里删掉，列表里仍会保留。请确认存储还能用，然后再删一次");
		}
		if (removed == 0) {
			throw new ServiceException("这些附件不存在，或已被删除。请刷新列表后再试");
		}
		return R.success("已删除。列表里不会再出现这些附件");
	}

	/** FileInfo → 响应附件（登记行已由 recorder 落库，主键经 FileInfo.id 回传） */
	private SysAttach toAttach(FileInfo info, boolean isPublic) {
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
		attach.setAccess(isPublic ? OssConstants.ACCESS_PUBLIC : OssConstants.ACCESS_PRIVATE);
		return attach;
	}

	/** 私有预签名下载 URL 有效期（秒）：sys_param 取值，缺省/非法回退默认 */
	private long presignedUrlExpireSeconds() {
		try {
			String value = paramService.getValue(OssConstants.PARAM_PRESIGNED_URL_EXPIRE);
			long seconds = value == null ? 0 : Long.parseLong(value.trim());
			return seconds > 0 ? seconds : OssConstants.DEFAULT_PRESIGNED_URL_EXPIRE_SECONDS;
		} catch (NumberFormatException e) {
			return OssConstants.DEFAULT_PRESIGNED_URL_EXPIRE_SECONDS;
		}
	}

	private String extOf(String filename) {
		int idx = filename == null ? -1 : filename.lastIndexOf('.');
		return idx < 0 ? "" : filename.substring(idx + 1);
	}

	/** 直传签发请求：filename 原始文件名（展示用），access=public|private */
	public record PresignedPutParam(String filename, String access, Long size) {
	}

	/** 直传签发回执：supported=false 表示当前平台不支持预签名，前端回退 multipart */
	public record PresignedPutResult(boolean supported, String uploadUrl, Map<String, String> headers,
									 String ticket, Long expiresIn) {
	}

	/** 直传回填请求：ticket 一次性凭证；size 客户端上报展示值（可空） */
	public record CreateParam(String ticket, Long size) {
	}

	/**
	 * 直传回验：HEAD 对象核对实际大小、Content-Type 与凭证一致，再读文件头跑魔数校验。
	 * 任一不符就删掉对象并拒绝登记，避免绕过签名约束的 PUT 落成附件。
	 */
	private FileInfo relocate(FileInfo info) {
		String newName = UploadPolicy.storedName(info.getExt());
		FileInfo stored;
		try {
			stored = fileStorageService.copy(info).setFilename(newName).copy();
		} catch (Exception e) {
			log.warn("直传对象搬到新 key 失败：{}", info.getFilename());
			pendingObjectCleaner.deleteOrEnqueue(info);
			throw new ServiceException("文件登记没有成功。请重新选择文件再上传");
		}
		pendingObjectCleaner.deleteOrEnqueue(info);
		return stored;
	}

	private static String baseType(String contentType) {
		return contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
	}

	private void verifyUploadedObject(FileInfo info) {
		RemoteFileInfo remote;
		try {
			remote = fileStorageService.getFile(info);
		} catch (Exception e) {
			remote = null;
		}
		if (remote == null) {
			throw new ServiceException("没找到上传的文件。请重新选择文件再上传");
		}
		boolean ok = remote.getSize() != null && remote.getSize().equals(info.getSize())
			&& baseType(info.getContentType()).equals(baseType(remote.getContentType()));
		if (ok) {
			byte[] head = new byte[UploadPolicy.HEAD_BYTES];
			int[] n = {0};
			try {
				fileStorageService.download(info).inputStream(in -> {
					try {
						n[0] = in.readNBytes(head, 0, head.length);
					} catch (java.io.IOException e) {
						n[0] = -1;
					}
				});
			} catch (Exception e) {
				n[0] = -1;
			}
			ok = n[0] > 0 && UploadPolicy.magicMatches(info.getExt(), head, n[0]);
		}
		if (!ok) {
			pendingObjectCleaner.deleteOrEnqueue(info);
			throw new ServiceException("上传的文件和申请时登记的大小或类型对不上。请重新选择文件再上传");
		}
	}

}
