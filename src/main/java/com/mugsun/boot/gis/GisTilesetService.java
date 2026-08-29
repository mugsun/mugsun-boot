package com.mugsun.boot.gis;

import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Locale;

/**
 * 内置三维切片下发：只认白名单 code 与固定后缀，路径不接受任何目录跳转。
 * <p>切片随 jar 发布在 {@code classpath:gis/tileset/<code>/}，由 {@code scripts/gen_demo_3dtiles.py} 生成。
 */
@Service
public class GisTilesetService {

	/** 切片体一律不可变，缓存放长；tileset.json 是清单，短缓存便于换数据后及时生效 */
	private static final String CACHE_CONTENT = "private, max-age=604800, immutable";
	private static final String CACHE_MANIFEST = "private, max-age=300";

	public ResponseEntity<byte[]> manifest(String code) {
		return read(code, "tileset.json");
	}

	public ResponseEntity<byte[]> content(String code, String file) {
		return read(code, file);
	}

	private ResponseEntity<byte[]> read(String code, String file) {
		if (!GisConstants.TILESETS.contains(code)) {
			throw new ServiceException(GisConstants.MSG_TILESET_MISSING);
		}
		String name = safeName(file);
		ClassPathResource res = new ClassPathResource(GisConstants.TILESET_ROOT + code + "/" + name);
		if (!res.exists()) {
			return ResponseEntity.notFound().build();
		}
		byte[] body;
		try {
			body = res.getContentAsByteArray();
		} catch (IOException e) {
			return ResponseEntity.notFound().build();
		}
		boolean manifest = name.endsWith(".json");
		return ResponseEntity.ok()
			.contentType(manifest ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_OCTET_STREAM)
			.header(HttpHeaders.CACHE_CONTROL, manifest ? CACHE_MANIFEST : CACHE_CONTENT)
			.body(body);
	}

	/**
	 * 文件名校验：切片目录是平铺的，任何分隔符或 {@code ..} 都视为攻击。
	 */
	private static String safeName(String file) {
		String name = file == null ? "" : file.trim();
		if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
			throw new ServiceException(GisConstants.MSG_TILESET_MISSING);
		}
		String low = name.toLowerCase(Locale.ROOT);
		if (!low.endsWith(".json") && !low.endsWith(".b3dm") && !low.endsWith(".glb")
			&& !low.endsWith(".cmpt") && !low.endsWith(".pnts")) {
			throw new ServiceException(GisConstants.MSG_TILESET_MISSING);
		}
		return name;
	}
}
