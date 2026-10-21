package com.mugsun.boot.demo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静态检查 docs/demo/sql/demo-accounts.sql：日志账号的授权覆盖了它在 demo 下会写的所有表。
 * <ul>
 *   <li>「会写的表」从代码推出：凡是用 {@link LogDataSourceRouter} 的服务，所用 Mapper 对应实体的 {@code @Table} 都要有 INSERT；</li>
 *   <li>操作日志链（V8）：sys_oper_log 与 sys_oper_log_chain 要有 SELECT，链头表要有 UPDATE(update_time)；</li>
 *   <li>迁移脚本（V1–当前）里新建的每张 *_log / *_log_chain 表都必须被授权或在 {@link #EXEMPT} 里写明理由，新表不会悄悄漏掉；</li>
 *   <li>只读账号只有 SELECT。</li>
 * </ul>
 */
class DemoAccountsSqlGrantTest {

	private static final Path MODULE = Path.of("").toAbsolutePath();
	private static final Path SQL = MODULE.resolve("../docs/demo/sql/demo-accounts.sql").normalize();
	private static final Path MIGRATIONS = MODULE.resolve("src/main/resources/db/migration");
	private static final Path JAVA = MODULE.resolve("src/main/java");

	/** 迁移里有、但 demo 下日志账号不写的日志表（对应入口在 demo 下整体关闭） */
	private static final Map<String, String> EXEMPT = Map.of(
		"sys_api_key_log", "开放平台 /open 在 demo 下任何方法都拒绝",
		"sys_oauth_log", "OAuth2 /oauth2 在 demo 下任何方法都拒绝",
		"sys_password_log", "改密码/找回密码是写接口，demo 下被只读过滤器拒绝");

	private static final Pattern GRANT = Pattern.compile(
		"(?is)GRANT\\s+([^;]+?)\\s+ON\\s+(?!ALL\\b|DATABASE\\b|SCHEMA\\b|TABLES\\b)(?:TABLE\\s+)?([^;]+?)\\s+TO\\s+([\\w\\s,]+?);");

	/** role -> table -> 权限（列级写成 UPDATE(col)） */
	private static Map<String, Map<String, Set<String>>> grants() throws IOException {
		String sql = Files.readString(SQL, StandardCharsets.UTF_8).replaceAll("(?m)--.*$", "");
		Map<String, Map<String, Set<String>>> out = new HashMap<>();
		Matcher m = GRANT.matcher(sql);
		while (m.find()) {
			Set<String> privs = new HashSet<>();
			for (String p : m.group(1).split(",(?![^(]*\\))")) {
				privs.add(p.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""));
			}
			for (String role : m.group(3).split(",")) {
				for (String table : m.group(2).split(",")) {
					out.computeIfAbsent(role.trim(), k -> new HashMap<>())
						.computeIfAbsent(table.trim().toLowerCase(Locale.ROOT), k -> new HashSet<>()).addAll(privs);
				}
			}
		}
		return out;
	}

	/** 用 LogDataSourceRouter 的服务所写的表（Mapper → 实体 @Table） */
	private static Set<String> tablesWrittenThroughLogRouter() throws IOException {
		Map<String, String> entityTable = new HashMap<>();
		Pattern table = Pattern.compile("@Table\\(\\s*(?:value\\s*=\\s*)?\"(\\w+)\"");
		Set<String> mappers = new TreeSet<>();
		Pattern mapper = Pattern.compile("\\b(\\w+)Mapper\\b");
		try (Stream<Path> files = Files.walk(JAVA)) {
			for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
				String src = Files.readString(f, StandardCharsets.UTF_8);
				Matcher t = table.matcher(src);
				if (t.find()) {
					entityTable.put(f.getFileName().toString().replace(".java", ""), t.group(1));
				}
				if (!f.getFileName().toString().equals("LogDataSourceRouter.java") && src.contains("LogDataSourceRouter logRouter")) {
					Matcher mm = mapper.matcher(src);
					while (mm.find()) {
						if (!mm.group(1).equals("Base") && !mm.group(1).equals("Row")) {
							mappers.add(mm.group(1));
						}
					}
				}
			}
		}
		Set<String> tables = new TreeSet<>();
		for (String entity : mappers) {
			String t = entityTable.get(entity);
			if (t != null) {
				tables.add(t);
			}
		}
		return tables;
	}

	private static Set<String> logTablesInMigrations() throws IOException {
		Set<String> out = new TreeSet<>();
		Pattern create = Pattern.compile("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?\"?(\\w+)");
		try (Stream<Path> files = Files.list(MIGRATIONS)) {
			for (Path f : (Iterable<Path>) files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))::iterator) {
				Matcher m = create.matcher(Files.readString(f, StandardCharsets.UTF_8));
				while (m.find()) {
					String t = m.group(1).toLowerCase(Locale.ROOT);
					if (t.endsWith("_log") || t.endsWith("_log_chain")) {
						out.add(t);
					}
				}
			}
		}
		return out;
	}

	@Test
	void logAccountCanInsertEveryTableWrittenThroughLogRouter() throws IOException {
		Map<String, Set<String>> log = grants().getOrDefault("mugsun_demo_log", Map.of());
		Set<String> written = tablesWrittenThroughLogRouter();
		assertThat(written).as("从代码推出的日志表").contains("sys_login_log", "sys_api_log", "sys_error_log",
			"sys_oper_log", "sys_usual_log");
		for (String t : written) {
			assertThat(log.getOrDefault(t, Set.of())).as("mugsun_demo_log 对 " + t + " 的权限").contains("INSERT");
		}
	}

	@Test
	void logAccountCoversOperLogChain() throws IOException {
		Map<String, Set<String>> log = grants().getOrDefault("mugsun_demo_log", Map.of());
		assertThat(log.getOrDefault("sys_oper_log", Set.of())).contains("INSERT", "SELECT");
		assertThat(log.getOrDefault("sys_oper_log_chain", Set.of()))
			.as("链头锁 FOR UPDATE 需要 UPDATE 权限，只给 update_time 一列").contains("SELECT", "UPDATE(UPDATE_TIME)")
			.doesNotContain("UPDATE", "INSERT", "DELETE");
	}

	@Test
	void everyLogTableInMigrationsIsGrantedOrExempt() throws IOException {
		Map<String, Set<String>> log = grants().getOrDefault("mugsun_demo_log", Map.of());
		for (String t : logTablesInMigrations()) {
			assertThat(log.containsKey(t) || EXEMPT.containsKey(t))
				.as(t + "：给 mugsun_demo_log 授权，或在 EXEMPT 里写明 demo 下为什么不写").isTrue();
		}
	}

	@Test
	void readOnlyAccountHasOnlySelect() throws IOException {
		Map<String, Set<String>> ro = grants().getOrDefault("mugsun_demo_ro", Map.of());
		ro.values().forEach(p -> assertThat(p).containsOnly("SELECT"));
		String sql = Files.readString(SQL, StandardCharsets.UTF_8).replaceAll("(?m)--.*$", "");
		assertThat(sql).as("ro 的全表授权只能是 SELECT")
			.containsPattern("(?i)GRANT\\s+SELECT\\s+ON\\s+ALL\\s+TABLES\\s+IN\\s+SCHEMA\\s+public\\s+TO\\s+mugsun_demo_ro;")
			.doesNotContainPattern("(?i)GRANT\\s+[^;]*(UPDATE|INSERT|DELETE|TRUNCATE)[^;]*TO\\s+[^;]*mugsun_demo_ro");
	}
}
