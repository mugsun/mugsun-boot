package com.mugsun.boot.system.service;

import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.system.entity.SysAttach;
import com.mugsun.boot.system.file.UploadPolicy;
import com.mugsun.boot.system.mapper.SysAttachMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.api.ResultCode;
import com.mugsun.core.tool.exception.ServiceException;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * 富文本正文里的图片（公告、帮助文档、更新日志的编辑器）：专用上传，匿名读取。
 * <p>正文里的 &lt;img&gt; 由浏览器直接请求，带不了登录令牌，读正文的人也不一定有文件权限；页面的 CSP 又只允许本站图片。
 * 所以图片统一经匿名端点 {@link #RAW_PREFIX}{id}/{filename} 输出（仅 public/richtext/ 范围），和头像一样，
 * 与存储平台（本地 enable-access 开关 / MinIO / 云存储桶权限）无关。
 * <p>存储文件名是服务端生成的随机 uuid，读取时地址里的文件名必须对得上：编号是递增的，只知道编号读不到别的图片。
 * 只收位图（png/jpg/jpeg/gif/webp；svg 能带脚本，不收），校验扩展名和文件头，限 5MB，存公开区 public/richtext/。
 * <p><b>边界</b>：传了但没有写进正文、或者正文后来删掉的图片不会自动清理，要到附件管理里删。
 */
@Service
public class RichTextImageService {

	private static final Logger log = LoggerFactory.getLogger(RichTextImageService.class);

	public static final long MAX_SIZE = 5L * 1024 * 1024;
	public static final String PATH_RICHTEXT = OssConstants.PATH_PUBLIC + "richtext/";
	/** 图片对外地址前缀（相对后端根，前端按其它接口同样的 baseURL 拼接） */
	public static final String RAW_PREFIX = "/system/richtext/image/raw/";

	private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp");
	/** 文件头校验要读的字节数：webp 要看到第 12 字节 */
	private static final int HEAD_BYTES = 12;
	private static final String UPLOAD_FAILED = "图片没有上传成功。请稍后再试，或联系管理员检查存储配置";

	private final FileStorageService fileStorageService;
	private final OssService ossService;
	private final SysAttachMapper attachMapper;

	public RichTextImageService(FileStorageService fileStorageService, OssService ossService,
								SysAttachMapper attachMapper) {
		this.fileStorageService = fileStorageService;
		this.ossService = ossService;
		this.attachMapper = attachMapper;
	}

	/** 上传一张正文图片；返回的 url 是可以直接写进正文的读取地址 */
	public SysAttach upload(MultipartFile file) {
		String ext = validate(file);
		FileInfo info;
		try {
			info = fileStorageService.of(file)
				.setPlatform(ossService.activePlatform())
				.setPath(PATH_RICHTEXT)
				.setSaveFilename(UploadPolicy.storedName(ext))
				// Content-Type 按扩展名定，不用客户端上报的
				.setContentType(UploadPolicy.mimeOf(ext))
				.putAttr(OssConstants.ATTR_ACCESS, OssConstants.ACCESS_PUBLIC)
				.upload();
		} catch (RuntimeException ex) {
			log.warn("正文图片上传失败: {}", ex.getClass().getSimpleName());
			throw new ServiceException(UPLOAD_FAILED);
		}
		if (info == null || info.getId() == null) {
			throw new ServiceException(UPLOAD_FAILED);
		}
		SysAttach attach = new SysAttach();
		attach.setId(Long.valueOf(info.getId()));
		attach.setName(info.getOriginalFilename());
		attach.setPath(info.getPath());
		attach.setFilename(info.getFilename());
		attach.setExt(info.getExt());
		attach.setContentType(info.getContentType());
		attach.setSize(info.getSize());
		attach.setPlatform(info.getPlatform());
		attach.setBasePath(info.getBasePath());
		attach.setAccess(OssConstants.ACCESS_PUBLIC);
		attach.setUrl(RAW_PREFIX + attach.getId() + "/" + attach.getFilename());
		return attach;
	}

	/** 匿名读取的范围裁决：只认 public、位于 public/richtext/、文件名对得上的位图，其余一律当作不存在 */
	public SysAttach requireServable(Long id, String filename) {
		SysAttach attach = id == null || filename == null ? null
			: TenantContext.ignore(() -> attachMapper.selectOneById(id));
		if (attach == null || !isRichTextImage(attach) || !sameName(attach.getFilename(), filename)) {
			throw new ServiceException(ResultCode.NOT_FOUND);
		}
		return attach;
	}

	/** 输出时的 Content-Type：按存储文件名的扩展名定，不用登记表里的值 */
	public static String contentTypeOf(SysAttach attach) {
		return UploadPolicy.mimeOf(UploadPolicy.extOf(attach.getFilename()));
	}

	private static boolean isRichTextImage(SysAttach attach) {
		return OssConstants.ACCESS_PUBLIC.equals(attach.getAccess()) && attach.getPath() != null
			&& attach.getPath().startsWith(PATH_RICHTEXT) && !attach.getPath().contains("..")
			&& attach.getFilename() != null && IMAGE_EXT.contains(UploadPolicy.extOf(attach.getFilename()));
	}

	/** 文件名在这里起口令的作用，比较耗时不随内容变化 */
	private static boolean sameName(String stored, String asked) {
		return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), asked.getBytes(StandardCharsets.UTF_8));
	}

	/** 扩展名 + 大小 + 文件头校验，通过后返回小写扩展名 */
	private static String validate(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ServiceException("请选择一张有内容的图片再上传");
		}
		String ext = UploadPolicy.extOf(file.getOriginalFilename());
		if (!IMAGE_EXT.contains(ext)) {
			throw new ServiceException("正文里只能插入 png、jpg、gif、webp 图片。请换一张图片再上传");
		}
		if (file.getSize() > MAX_SIZE) {
			throw new ServiceException("图片不能超过 5MB。请压缩后再上传");
		}
		byte[] head = new byte[HEAD_BYTES];
		int n;
		try (InputStream in = file.getInputStream()) {
			n = in.readNBytes(head, 0, head.length);
		} catch (IOException e) {
			throw new ServiceException("图片读不出来。请换一张图片再试");
		}
		if (!UploadPolicy.magicMatches(ext, head, n)) {
			throw new ServiceException("图片内容和扩展名对不上。请上传原始图片，不要只改扩展名");
		}
		return ext;
	}
}
