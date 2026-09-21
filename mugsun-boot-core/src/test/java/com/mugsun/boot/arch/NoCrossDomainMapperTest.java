package com.mugsun.boot.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构红线：业务域之间不得直连对方 Mapper，跨域读写必须经对方 Service。
 * <p>存量违规以 {@link #FROZEN} 冻结（G109 盘点结果，共 15 个文件），只允许变少不允许变多：
 * 新增违规会让本用例失败；还清一个就从冻结表里删一行。
 */
class NoCrossDomainMapperTest {

	/** 源码根（相对模块根目录） */
	private static final Path SOURCE_ROOT = Paths.get("src/main/java/com/mugsun/boot");

	private static final Pattern MAPPER_IMPORT = Pattern.compile("import com\\.mugsun\\.boot\\.([a-z]+)\\.mapper\\.(\\w+)");

	/**
	 * 存量违规冻结表：相对 {@link #SOURCE_ROOT} 的文件路径。
	 * 这些文件多为登录、数据权限、全局搜索等横切能力，拆解需连同 Service 门面一起做，另行还债。
	 */
	private static final Set<String> FROZEN = new TreeSet<>(List.of(
		"app/AppHomeService.java",
		"app/AppInboxService.java",
		"app/AppNoticeSupport.java",
		"auth/AuthController.java",
		"auth/AuthLoginService.java",
		"auth/ForgetPasswordService.java",
		"auth/MugsunStpInterface.java",
		"auth/OnlineController.java",
		"config/DataInitializer.java",
		"datascope/DataScopeAspect.java",
		"oauth/OpenApiController.java",
		"online/OnlineService.java",
		"search/GlobalSearchController.java",
		"tenant/TenantValidator.java"
	));

	@Test
	@DisplayName("没有新的跨域直连 Mapper（存量已冻结）")
	void noNewCrossDomainMapperUsage() throws IOException {
		Set<String> actual = scanViolations();

		Set<String> added = new LinkedHashSet<>(actual);
		added.removeAll(FROZEN);
		assertThat(added)
			.as("新增跨域直连 Mapper：请改为调用对方域的 Service，或在确有必要时更新冻结表并说明理由")
			.isEmpty();
	}

	@Test
	@DisplayName("已还清的违规要从冻结表移除，避免红线名存实亡")
	void frozenListStaysTight() throws IOException {
		Set<String> actual = scanViolations();

		Set<String> stale = new LinkedHashSet<>(FROZEN);
		stale.removeAll(actual);
		assertThat(stale)
			.as("这些文件已不再跨域直连 Mapper，请从 FROZEN 中删除")
			.isEmpty();
	}

	/** 扫描源码，返回存在跨域 Mapper import 的文件相对路径 */
	private Set<String> scanViolations() throws IOException {
		Set<String> violations = new TreeSet<>();
		try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				Path relative = SOURCE_ROOT.relativize(file);
				if (relative.getNameCount() < 2) {
					continue;
				}
				String ownDomain = relative.getName(0).toString();
				String text = Files.readString(file, StandardCharsets.UTF_8);
				Matcher m = MAPPER_IMPORT.matcher(text);
				List<String> foreign = new ArrayList<>();
				while (m.find()) {
					if (!m.group(1).equals(ownDomain)) {
						foreign.add(m.group(1) + "." + m.group(2));
					}
				}
				if (!foreign.isEmpty()) {
					violations.add(relative.toString().replace('\\', '/'));
				}
			}
		}
		return violations;
	}
}
