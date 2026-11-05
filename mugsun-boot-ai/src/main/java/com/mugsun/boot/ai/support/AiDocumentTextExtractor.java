package com.mugsun.boot.ai.support;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 用 Apache Tika 从 PDF/DOCX/PPTX 字节抽纯文本。不做 OCR；空文或解析异常返回失败原因。
 * 已校验白名单后会带 {@link TikaCoreProperties#CONTENT_TYPE_USER_OVERRIDE}，
 * 避免个别环境下 Zip 探测器对 PDF 误抛 ArchiveException。
 */
@Component
public class AiDocumentTextExtractor {

	/** 单文档抽文上限：约 20MB 文本缓冲，防止畸形文件撑爆内存 */
	private static final int WRITE_LIMIT = 20 * 1024 * 1024;

	/**
	 * @return 非空正文；失败返回 null（reasonOut[0] 写入可读原因）
	 */
	public String extractOrNull(byte[] bytes, String filename, String mime, String[] reasonOut) {
		if (bytes == null || bytes.length == 0) {
			if (reasonOut != null && reasonOut.length > 0) {
				reasonOut[0] = "文件是空的。请换一份有正文的资料再上传";
			}
			return null;
		}
		try {
			String text = extract(bytes, filename, mime);
			if (text == null || text.isBlank()) {
				if (reasonOut != null && reasonOut.length > 0) {
					reasonOut[0] = "没从文件里抽出正文。可能是扫描件或空文档，当前不支持 OCR";
				}
				return null;
			}
			return text;
		} catch (Exception e) {
			if (reasonOut != null && reasonOut.length > 0) {
				String msg = e.getMessage();
				reasonOut[0] = (msg == null || msg.isBlank())
					? "文件解析失败。请确认是可用的 PDF / DOCX / PPTX"
					: ("文件解析失败：" + msg);
			}
			return null;
		}
	}

	/** 抽文成功返回正文；失败抛 IOException / TikaException / SAXException */
	public String extract(byte[] bytes, String filename, String mime)
		throws IOException, TikaException, SAXException {
		String effectiveMime = mime;
		if (effectiveMime == null || effectiveMime.isBlank()) {
			String ext = AiKbDocumentTypes.extOf(filename);
			if (AiKbDocumentTypes.ALLOWED_EXT.contains(ext)) {
				effectiveMime = AiKbDocumentTypes.mimeOf(ext);
			}
		}
		AutoDetectParser parser = new AutoDetectParser();
		BodyContentHandler handler = new BodyContentHandler(WRITE_LIMIT);
		Metadata metadata = new Metadata();
		if (filename != null && !filename.isBlank()) {
			metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
		}
		if (effectiveMime != null && !effectiveMime.isBlank()) {
			metadata.set(Metadata.CONTENT_TYPE, effectiveMime);
			metadata.set(TikaCoreProperties.CONTENT_TYPE_USER_OVERRIDE, effectiveMime);
		}
		ParseContext context = new ParseContext();
		try (InputStream in = new ByteArrayInputStream(bytes)) {
			parser.parse(in, handler, metadata, context);
		}
		String text = handler.toString();
		return text == null ? "" : text.replace('\u0000', ' ').trim();
	}
}
