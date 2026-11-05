package com.mugsun.boot.system.file;

import org.apache.tika.Tika;
import org.apache.tika.mime.MimeTypes;
import org.dromara.x.file.storage.core.tika.TikaFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 固定 x-file-storage 的 ContentTypeDetect 只用 MimeTypes（魔数+扩展名），
 * 不加载 classpath 上 tika-parsers 的 Detector SPI。
 * <p>AI 模块引入 {@code tika-parsers-standard-package} 后，默认 {@code new Tika()}
 * 会经 {@code DefaultZipContainerDetector} 对 gzip/任意字节抛
 * {@code ArchiveException: No Archiver found for the stream signature}，
 * 导致埋点 {@code TrackApiBodyStorage.save} 等在 {@code of(bytes)} 阶段 500。
 * 知识库抽文仍用独立的 {@code AutoDetectParser}，不受本 Bean 影响。
 */
@Configuration
public class FileStorageTikaConfig {

	@Bean
	public TikaFactory tikaFactory() {
		return new MimeTypesOnlyTikaFactory();
	}

	/** 仅 MimeTypes；避免 parsers SPI 污染 x-file-storage 探测 */
	public static final class MimeTypesOnlyTikaFactory implements TikaFactory {

		private final Tika tika = new Tika(MimeTypes.getDefaultMimeTypes());

		@Override
		public Tika getTika() {
			return tika;
		}
	}
}
