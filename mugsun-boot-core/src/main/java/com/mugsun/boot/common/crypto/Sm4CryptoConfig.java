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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 启动时初始化 {@link Sm4Util}，供 {@link Sm4TypeHandler} 字段落库加解密使用。
 * <p>没有任何内置密钥。密钥来源只有两种：
 * <ol>
 *   <li>{@code mugsun.crypto.sm4-key}（环境变量 {@code MUGSUN_SM4_KEY}），所有环境都可用；</li>
 *   <li>{@code mugsun.crypto.sm4-local-key-file}：本地开发用，首次启动生成随机密钥写入该文件
 *       （默认 {@code ${user.home}/.mugsun/dev-sm4.key}，文件 600、目录 700），之后每次启动复用，重启后旧数据仍能解开。</li>
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

	/** 已有的生产开关（prod 配置为 true），只作否决：为 true 时只认 sm4-key，本地密钥文件也不允许 */
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

	private static final String FILE_PERMS = "rw-------";
	private static final String DIR_PERMS = "rwx------";

	static byte[] loadOrCreate(Path file) {
		try {
			if (!Files.exists(file)) {
				byte[] k = new byte[16];
				new SecureRandom().nextBytes(k);
				createPrivateDirectories(file.getParent());
				try {
					// 创建时就带 600 权限，不留按 umask 可读的窗口
					boolean posix = posix(file.getParent());
					Path created = posix
						? Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(FILE_PERMS)))
						: Files.createFile(file);
					if (!posix) {
						log.warn("文件系统不支持 POSIX 权限，没法把本地 SM4 密钥文件设成 600，请自行限制访问：{}", file);
					}
					Files.writeString(created, HexUtil.encodeHexStr(k) + "\n", StandardCharsets.US_ASCII);
					return k;
				} catch (FileAlreadyExistsException race) {
					// 并发启动时别的进程刚写好，按读取处理
				}
			}
			warnIfTooOpen(file);
			String s = Files.readString(file, StandardCharsets.US_ASCII).trim();
			if (!HEX_KEY.matcher(s).matches()) {
				throw new IllegalStateException("本地 SM4 密钥文件格式不对（须为 32 位 hex）：" + file);
			}
			return HexUtil.decodeHex(s);
		} catch (IOException e) {
			throw new UncheckedIOException("读写本地 SM4 密钥文件失败：" + file, e);
		}
	}

	private static boolean posix(Path p) throws IOException {
		Path existing = p;
		while (existing != null && !Files.exists(existing)) {
			existing = existing.getParent();
		}
		return existing != null && Files.getFileStore(existing).supportsFileAttributeView("posix");
	}

	/** 逐级创建缺失目录，新建的目录一律 700；已有目录不改 */
	private static void createPrivateDirectories(Path dir) throws IOException {
		if (dir == null || Files.isDirectory(dir)) {
			return;
		}
		createPrivateDirectories(dir.getParent());
		if (posix(dir)) {
			Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(DIR_PERMS)));
		} else {
			Files.createDirectory(dir);
			log.warn("文件系统不支持 POSIX 权限，没法把密钥目录设成 700，请自行限制访问：{}", dir);
		}
	}

	/** 读取时权限比 600 宽（组或其他用户可读写）就打 WARN；非 POSIX 跳过 */
	private static void warnIfTooOpen(Path file) {
		try {
			if (!posix(file)) {
				return;
			}
			Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
			Set<PosixFilePermission> extra = EnumSet.copyOf(perms);
			extra.removeAll(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
			if (!extra.isEmpty()) {
				log.warn("本地 SM4 密钥文件权限过宽（{}），建议 chmod 600：{}", PosixFilePermissions.toString(perms), file);
			}
		} catch (IOException | UnsupportedOperationException e) {
			log.warn("读不到本地 SM4 密钥文件的权限，没法确认是否为 600：{}", file);
		}
	}
}
