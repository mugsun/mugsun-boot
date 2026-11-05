package com.mugsun.boot.ai.support;

import com.mugsun.core.tool.exception.ServiceException;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 知识库文档白名单：仅 pdf / docx / pptx（扩展名 + 合理 MIME + 文件头魔数）。
 * 不做 OCR；假后缀在魔数校验处拒绝。
 */
public final class AiKbDocumentTypes {

	public static final Set<String> ALLOWED_EXT = Set.of("pdf", "docx", "pptx");

	private static final Map<String, Set<String>> MIME = Map.of(
		"pdf", Set.of("application/pdf", "application/x-pdf", "application/octet-stream"),
		"docx", Set.of(
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
			"application/zip",
			"application/octet-stream"),
		"pptx", Set.of(
			"application/vnd.openxmlformats-officedocument.presentationml.presentation",
			"application/zip",
			"application/octet-stream"));

	private AiKbDocumentTypes() {
	}

	public static String extOf(String filename) {
		if (filename == null || filename.isBlank()) {
			return "";
		}
		String base = filename.replace('\\', '/');
		int slash = base.lastIndexOf('/');
		if (slash >= 0) {
			base = base.substring(slash + 1);
		}
		int q = base.indexOf('?');
		if (q >= 0) {
			base = base.substring(0, q);
		}
		int dot = base.lastIndexOf('.');
		return dot < 0 ? "" : base.substring(dot + 1).toLowerCase(Locale.ROOT).trim();
	}

	/** 扩展名必须在白名单；返回小写扩展名 */
	public static String requireExt(String filename) {
		String ext = extOf(filename);
		if (!ALLOWED_EXT.contains(ext)) {
			throw new ServiceException("知识库资料只支持 PDF、Word（.docx）、PPT（.pptx）。请换一种格式再上传");
		}
		return ext;
	}

	/**
	 * 校验扩展名 + 可选 MIME + 文件头。mime 为空或 octet-stream 时只靠魔数。
	 * @return 小写扩展名
	 */
	public static String validate(String filename, String mime, byte[] head, int headLen) {
		String ext = requireExt(filename);
		if (mime != null && !mime.isBlank()) {
			String base = mime.toLowerCase(Locale.ROOT).trim();
			int semi = base.indexOf(';');
			if (semi >= 0) {
				base = base.substring(0, semi).trim();
			}
			if (!MIME.get(ext).contains(base)) {
				throw new ServiceException("文件类型和扩展名对不上。请上传原始 PDF / DOCX / PPTX，不要只改后缀");
			}
		}
		if (!magicMatches(ext, head, headLen)) {
			throw new ServiceException("文件内容和扩展名对不上。请上传原始文件，不要只改扩展名");
		}
		return ext;
	}

	public static boolean magicMatches(String ext, byte[] h, int n) {
		if (h == null || n <= 0) {
			return false;
		}
		return switch (ext) {
			case "pdf" -> n >= 5 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F' && h[4] == '-';
			case "docx", "pptx" -> n >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4;
			default -> false;
		};
	}

	public static String mimeOf(String ext) {
		return switch (ext) {
			case "pdf" -> "application/pdf";
			case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
			case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
			default -> "application/octet-stream";
		};
	}
}
