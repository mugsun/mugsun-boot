package com.mugsun.boot.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * openGauss-lite 5.1 不支持 {@code ADD COLUMN IF NOT EXISTS}（#102）。
 * 禁止 {@code db/migration-og} 再出现该语法；新增列用 information_schema DO 块（同 V9/V10）。
 */
class OpenGaussMigrationBanTest {

	private static final Pattern BANNED = Pattern.compile(
		"(?i)ADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS");

	/** 去掉 -- 行注释后再扫，避免 README/说明文字误伤 */
	private static String sqlWithoutLineComments(String script) {
		return script.lines().map(l -> {
			int i = l.indexOf("--");
			return i < 0 ? l : l.substring(0, i);
		}).reduce("", (a, b) -> a + b + "\n");
	}

	@Test
	@DisplayName("migration-og 全部脚本不得含 ADD COLUMN IF NOT EXISTS")
	void migrationOgForbidsAddColumnIfNotExists() throws Exception {
		List<String> hits = new ArrayList<>();
		URL dir = OpenGaussMigrationBanTest.class.getClassLoader().getResource("db/migration-og");
		assertThat(dir).as("classpath:db/migration-og").isNotNull();
		if ("file".equals(dir.getProtocol())) {
			Path root = Path.of(dir.toURI());
			try (Stream<Path> walk = Files.walk(root)) {
				walk.filter(p -> p.getFileName().toString().endsWith(".sql")).forEach(p -> {
					try {
						String sql = sqlWithoutLineComments(Files.readString(p, StandardCharsets.UTF_8));
						if (BANNED.matcher(sql).find()) {
							hits.add(root.relativize(p).toString());
						}
					}
					catch (IOException e) {
						throw new RuntimeException(e);
					}
				});
			}
		}
		else {
			// jar：至少核对已知会改列的版本仍可读且合规
			for (String name : List.of("V8__oper_log_chain_guard.sql", "V9__tenant_captcha.sql",
				"V10__tenant_captcha_types.sql")) {
				String sql = sqlWithoutLineComments(read("db/migration-og/" + name));
				if (BANNED.matcher(sql).find()) {
					hits.add(name);
				}
			}
		}
		assertThat(hits).as("og 脚本含禁止语法").isEmpty();
	}

	@Test
	@DisplayName("V8 已是 DO + information_schema，与 lite 替代脚本同语义")
	void v8UsesDoBlockLikeLite() throws Exception {
		String v8 = read("db/migration-og/V8__oper_log_chain_guard.sql");
		assertThat(sqlWithoutLineComments(v8)).doesNotContainPattern(BANNED)
			.contains("information_schema.columns")
			.contains("chain_prev")
			.contains("sys_oper_log_chain");
	}

	private static String read(String path) throws Exception {
		try (InputStream in = OpenGaussMigrationBanTest.class.getClassLoader().getResourceAsStream(path)) {
			assertThat(in).as(path).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
