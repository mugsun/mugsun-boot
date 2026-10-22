package com.mugsun.boot.common.crypto;

import com.mugsun.boot.common.env.DeployProfiles;
import com.mugsun.core.web.crypto.ApiCryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 本地开发零配置：把接口传输密钥写进 mugsun-pc 的 {@code .env.local}（{@code VITE_CRYPTO_API_KEY}），
 * 前端 dev server 构建期就拿到与后端同一把密钥。
 * <ul>
 *   <li>只在严格判定的非生产环境执行（{@link DeployProfiles}：active 非空、含 dev/test/local、不含 prod）；</li>
 *   <li>pc 目录：{@code mugsun.crypto.pc-env-local.pc-dir}（环境变量 {@code MUGSUN_PC_DIR}），
 *       没配就找工作目录的 {@code ../mugsun-pc}、{@code ../../mugsun-pc}，须有 package.json；</li>
 *   <li>写入前确认 pc 的 .gitignore 已忽略 .env.local（{@code .env.local}、{@code *.local}、{@code .env*.local}），否则拒绝写入；</li>
 *   <li>只改 VITE_CRYPTO_API_KEY 一行，其余行原样保留；值相同不重写；文件 600；日志只写路径。</li>
 * </ul>
 * 开关 {@code mugsun.crypto.pc-env-local.enabled}（默认 true，集成测试关闭）。
 */
@Component
public class PcEnvLocalWriter {

	private static final Logger log = LoggerFactory.getLogger(PcEnvLocalWriter.class);
	static final String VAR = "VITE_CRYPTO_API_KEY";
	private static final Set<String> IGNORE_LINES = Set.of(".env.local", "/.env.local", "*.local", ".env*.local", "/.env*.local");

	private final ApiCryptoService apiCryptoService;
	private final Environment env;

	public PcEnvLocalWriter(ApiCryptoService apiCryptoService, Environment env) {
		this.apiCryptoService = apiCryptoService;
		this.env = env;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		if (!env.getProperty("mugsun.crypto.pc-env-local.enabled", Boolean.class, true)) {
			return;
		}
		if (!DeployProfiles.isNonProd(env)) {
			return;
		}
		Path pcDir = locatePcDir(env.getProperty("mugsun.crypto.pc-env-local.pc-dir", ""), Path.of(System.getProperty("user.dir")));
		if (pcDir == null) {
			log.debug("没找到 mugsun-pc 目录，跳过写 .env.local（可设 MUGSUN_PC_DIR）");
			return;
		}
		write(pcDir, apiCryptoService.transportKeyHex());
	}

	static Path locatePcDir(String configured, Path cwd) {
		List<Path> candidates = new ArrayList<>();
		if (configured != null && !configured.isBlank()) {
			candidates.add(Path.of(configured.strip()));
		} else {
			candidates.add(cwd.resolve("../mugsun-pc"));
			candidates.add(cwd.resolve("../../mugsun-pc"));
		}
		for (Path c : candidates) {
			Path p = c.toAbsolutePath().normalize();
			if (Files.isRegularFile(p.resolve("package.json"))) {
				return p;
			}
		}
		return null;
	}

	static boolean gitignored(Path pcDir) {
		Path gi = pcDir.resolve(".gitignore");
		if (!Files.isRegularFile(gi)) {
			return false;
		}
		try {
			return Files.readAllLines(gi, StandardCharsets.UTF_8).stream().map(String::strip).anyMatch(IGNORE_LINES::contains);
		} catch (IOException e) {
			return false;
		}
	}

	/** 返回是否写入（或已一致）；拒绝写入返回 false */
	static boolean write(Path pcDir, String keyHex) {
		Path target = pcDir.resolve(".env.local");
		if (!gitignored(pcDir)) {
			log.warn("{} 的 .gitignore 没有忽略 .env.local，拒绝写入接口密钥；请先把 .env.local 加进 .gitignore", pcDir);
			return false;
		}
		try {
			List<String> lines = Files.exists(target)
				? new ArrayList<>(Files.readAllLines(target, StandardCharsets.UTF_8)) : new ArrayList<>();
			String line = VAR + "=" + keyHex;
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				String l = lines.get(i).strip();
				if (l.startsWith(VAR + "=") || l.startsWith(VAR + " ")) {
					if (lines.get(i).equals(line)) {
						return true;
					}
					lines.set(i, line);
					found = true;
				}
			}
			if (!found) {
				lines.add("# 由 mugsun-boot 在 dev/test/local 启动时自动写入，与后端接口传输密钥一致；勿提交");
				lines.add(line);
			}
			Path tmp = Files.createTempFile(pcDir, ".env.local.", ".tmp");
			try {
				Files.write(tmp, lines, StandardCharsets.UTF_8);
				try {
					Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
				} catch (UnsupportedOperationException ignored) {
					log.warn("文件系统不支持 POSIX 权限，没法把 {} 设成 600", target);
				}
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} finally {
				Files.deleteIfExists(tmp);
			}
			log.info("已把接口传输密钥写入 {}（{}），重启前端 dev server 后生效", target, VAR);
			return true;
		} catch (IOException e) {
			log.warn("写 {} 失败，前端可改用运行时下发接口：{}", target, e.getClass().getSimpleName());
			return false;
		}
	}
}
