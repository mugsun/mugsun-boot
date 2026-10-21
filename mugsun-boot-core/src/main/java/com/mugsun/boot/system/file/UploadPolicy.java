package com.mugsun.boot.system.file;

import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 通用附件上传策略：扩展名白名单 + 文件头魔数 + 大小上限；存储文件名由服务端生成。
 * <p>白名单外的类型（html、svg、js、xml、exe 等可执行或可脚本化内容）一律拒绝。
 * 文本类（txt/csv/md）不能开头就是 HTML/XML 标签，防止改扩展名夹带页面。
 */
public final class UploadPolicy {

	/** 单个附件上限 20MB（低于 spring.servlet.multipart 的 25MB，超出给友好提示） */
	public static final long MAX_BYTES = 20L * 1024 * 1024;

	public static final Set<String> ALLOWED_EXT = Set.of(
		"png", "jpg", "jpeg", "gif", "webp", "bmp",
		"pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
		"txt", "csv", "md", "zip", "mp4", "mp3");

	private static final java.util.Map<String, String> MIME = java.util.Map.ofEntries(
		java.util.Map.entry("png", "image/png"), java.util.Map.entry("jpg", "image/jpeg"),
		java.util.Map.entry("jpeg", "image/jpeg"), java.util.Map.entry("gif", "image/gif"),
		java.util.Map.entry("webp", "image/webp"), java.util.Map.entry("bmp", "image/bmp"),
		java.util.Map.entry("pdf", "application/pdf"), java.util.Map.entry("doc", "application/msword"),
		java.util.Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
		java.util.Map.entry("xls", "application/vnd.ms-excel"),
		java.util.Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
		java.util.Map.entry("ppt", "application/vnd.ms-powerpoint"),
		java.util.Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
		java.util.Map.entry("txt", "text/plain; charset=utf-8"), java.util.Map.entry("csv", "text/csv; charset=utf-8"),
		java.util.Map.entry("md", "text/plain; charset=utf-8"), java.util.Map.entry("zip", "application/zip"),
		java.util.Map.entry("mp4", "video/mp4"), java.util.Map.entry("mp3", "audio/mpeg"));

	private static final Set<String> TEXT_EXT = Set.of("txt", "csv", "md");

	private UploadPolicy() {
	}

	/** 取小写扩展名；没有扩展名返回空串 */
	public static String extOf(String filename) {
		if (filename == null) {
			return "";
		}
		int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
		String base = filename.substring(slash + 1);
		int dot = base.lastIndexOf('.');
		return dot < 0 ? "" : base.substring(dot + 1).toLowerCase(Locale.ROOT).trim();
	}

	/** 只校验扩展名（直传签发时拿不到文件内容） */
	public static String requireAllowedExt(String filename) {
		String ext = extOf(filename);
		if (!ALLOWED_EXT.contains(ext)) {
			throw new ServiceException("不支持这种文件类型。可上传图片、PDF、Office 文档、txt/csv/md、zip、mp4、mp3");
		}
		return ext;
	}

	/** 扩展名 + 大小 + 魔数校验，通过后返回小写扩展名 */
	public static String validate(MultipartFile file) {
		String ext = requireAllowedExt(file.getOriginalFilename());
		if (file.getSize() > MAX_BYTES) {
			throw new ServiceException("文件超过 20MB。请压缩或拆分后再上传");
		}
		byte[] head = new byte[16];
		int n;
		try (InputStream in = file.getInputStream()) {
			n = in.readNBytes(head, 0, head.length);
		} catch (IOException e) {
			throw new ServiceException("文件读不出来。请换一个文件再试");
		}
		if (!magicMatches(ext, head, n)) {
			throw new ServiceException("文件内容和扩展名对不上。请上传原始文件，不要只改扩展名");
		}
		return ext;
	}

	/** 服务端按白名单扩展名决定的 Content-Type，不采用客户端上报值 */
	public static String mimeOf(String ext) {
		return MIME.getOrDefault(ext, "application/octet-stream");
	}

	/** 直传声明大小校验：必须给出且在 (0, MAX_BYTES] 内，签名时绑定 Content-Length */
	public static long requireSize(Long size) {
		if (size == null || size <= 0) {
			throw new ServiceException("请带上文件大小再申请上传");
		}
		if (size > MAX_BYTES) {
			throw new ServiceException("文件超过 20MB。请压缩或拆分后再上传");
		}
		return size;
	}

	/** 服务端生成的存储文件名：随机 uuid + 白名单扩展名，不沿用用户文件名 */
	public static String storedName(String ext) {
		return UUID.randomUUID().toString().replace("-", "") + "." + ext;
	}

	/** 浏览器会执行脚本的类型：下载时必须 attachment，不能同域 inline */
	public static boolean isActiveContentType(String contentType) {
		if (contentType == null) {
			return false;
		}
		String t = contentType.toLowerCase(Locale.ROOT);
		return t.contains("html") || t.contains("svg") || t.contains("javascript") || t.contains("ecmascript")
			|| t.contains("xml") || t.startsWith("text/") && !t.startsWith("text/plain") && !t.startsWith("text/csv");
	}

	static boolean magicMatches(String ext, byte[] h, int n) {
		return switch (ext) {
			case "png" -> n >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
			case "jpg", "jpeg" -> n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
			case "gif" -> n >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8';
			case "webp" -> n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
				&& h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
			case "bmp" -> n >= 2 && h[0] == 'B' && h[1] == 'M';
			case "pdf" -> n >= 5 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F' && h[4] == '-';
			case "docx", "xlsx", "pptx", "zip" -> n >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4;
			case "doc", "xls", "ppt" -> n >= 8 && (h[0] & 0xFF) == 0xD0 && (h[1] & 0xFF) == 0xCF
				&& (h[2] & 0xFF) == 0x11 && (h[3] & 0xFF) == 0xE0;
			case "mp4" -> n >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p';
			case "mp3" -> n >= 3 && (h[0] == 'I' && h[1] == 'D' && h[2] == '3'
				|| (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0);
			case "txt", "csv", "md" -> !looksLikeMarkup(h, n);
			default -> false;
		};
	}

	private static boolean looksLikeMarkup(byte[] h, int n) {
		String s = new String(h, 0, Math.max(n, 0), StandardCharsets.UTF_8).replace("\uFEFF", "").stripLeading();
		return s.startsWith("<");
	}

	static boolean isText(String ext) {
		return TEXT_EXT.contains(ext);
	}
}
