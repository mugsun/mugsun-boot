package com.mugsun.boot.common.crypto;

import cn.hutool.core.util.HexUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * 启动时初始化 {@link Sm4Util}，供 {@link Sm4TypeHandler} 字段落库加解密使用。
 * <p>没有任何内置密钥。密钥来源只有两种：
 * <ol>
 *   <li>{@code mugsun.crypto.sm4-key}（环境变量 {@code MUGSUN_SM4_KEY}），所有环境都可用；</li>
 *   <li>{@code mugsun.crypto.sm4-local-key-file}：本地开发用，首次启动生成随机密钥写入该文件
 *       （须在 gitignore 里，默认 {@code .mugsun/dev-sm4.key}），之后每次启动复用，重启后旧数据仍能解开。</li>
 * </ol>
 * 两者都没有，或开了 {@code strict-keys} 却没配 {@code sm4-key}，一律拒绝启动。
 * 字段加密密钥不能每次启动随机生成，否则重启后库里的数据解不开。
 */
@Component
public class Sm4CryptoConfig {

	private static final Logger log = LoggerFactory.getLogger(Sm4CryptoConfig.class);
	private static final Pattern HEX_KEY = Pattern.compile("^[0-9a-fA-F]{32}$");

	/** SM4 字段加密密钥；环境变量 MUGSUN_SM4_KEY 注入 */
	@Value("${mugsun.crypto.sm4-key:}")
	private String sm4Key;

	/** 已有的生产开关（prod 配置为 true）：为 true 时只认 sm4-key，本地密钥文件也不允许 */
	@Value("${mugsun.crypto.strict-keys:false}")
	private boolean strictKeys;

	/** 本地开发密钥文件（仅 dev 配置里设置；必须被 gitignore） */
	@Value("${mugsun.crypto.sm4-local-key-file:}")
	private String localKeyFile;

	@PostConstruct
	public void init() {
		if (sm4Key != null && !sm4Key.isBlank()) {
			Sm4Util.init(sm4Key.trim());
			return;
		}
		if (strictKeys || localKeyFile == null || localKeyFile.isBlank()) {
			throw new IllegalStateException("未配置 SM4 字段加密密钥 MUGSUN_SM4_KEY，拒绝启动；"
				+ "本地开发用 dev profile（设 mugsun.crypto.sm4-local-key-file）");
		}
		Path file = Path.of(localKeyFile.trim()).toAbsolutePath().normalize();
		Sm4Util.initRaw(loadOrCreate(file));
		log.warn("未配置 MUGSUN_SM4_KEY，字段加密使用本地开发密钥文件 {}（仅限本地开发；"
			+ "删掉该文件后，用它加密的数据将无法解开）", file);
	}

	static byte[] loadOrCreate(Path file) {
		try {
			if (!Files.exists(file)) {
				byte[] k = new byte[16];
				new SecureRandom().nextBytes(k);
				Files.createDirectories(file.getParent());
				try {
					Files.writeString(Files.createFile(file), HexUtil.encodeHexStr(k) + "\n", StandardCharsets.US_ASCII);
					restrictPermissions(file);
					return k;
				} catch (FileAlreadyExistsException race) {
					// 并发启动时别的进程刚写好，按读取处理
				}
			}
			String s = Files.readString(file, StandardCharsets.US_ASCII).trim();
			if (!HEX_KEY.matcher(s).matches()) {
				throw new IllegalStateException("本地 SM4 密钥文件格式不对（须为 32 位 hex）：" + file);
			}
			return HexUtil.decodeHex(s);
		} catch (IOException e) {
			throw new UncheckedIOException("读写本地 SM4 密钥文件失败：" + file, e);
		}
	}

	private static void restrictPermissions(Path file) {
		try {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException | IOException ignored) {
			// 非 POSIX 文件系统（Windows）跳过
		}
	}
}
