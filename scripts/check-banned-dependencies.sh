#!/usr/bin/env bash
# B89-0：证明根 pom 的 maven-enforcer bannedDependencies 规则确实会拦下 Spring AI 与 MCP SDK。
# 在 target/enforcer-probe/ 下生成几个继承根 pom 的探针工程，各自只加一个依赖，跑 validate：
#   - 对照组（无禁用依赖）必须通过，证明探针本身能构建；
#   - 直接依赖 org.springframework.ai / io.modelcontextprotocol.sdk 必须失败，且输出里有规则的提示语；
#   - Spring AI 的 MCP Server starter（B89-0 的来源）失败；只引入 MCP SDK 的 json 子包也失败（组名通配）；
#   - B89-2 纯传递依赖：包装模块自己设 enforcer.skip 并依赖 mcp-json，使用方模块只依赖包装模块，使用方的构建必须失败。
# 探针工程都生成在 target/ 下，不进仓库，也不在 B89-3 守卫（NoEnforcerSkipGuardTest）扫描的范围内。
# 额外的 Maven 参数原样传下去，例如：scripts/check-banned-dependencies.sh -s .empty-settings.xml -Dmaven.repo.local=$PWD/.m2repo
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROBE_ROOT="$ROOT/target/enforcer-probe"
MVN="${MVN:-mvn}"
PARENT_GROUP="$(sed -n 's:^\t<groupId>\(.*\)</groupId>:\1:p' "$ROOT/pom.xml" | head -1)"
PARENT_ARTIFACT="$(sed -n 's:^\t<artifactId>\(.*\)</artifactId>:\1:p' "$ROOT/pom.xml" | head -1)"
PARENT_VERSION="$(sed -n 's:^\t<version>\(.*\)</version>:\1:p' "$ROOT/pom.xml" | head -1)"
MARKER="B89-0"
failures=0

probe() {
	local name="$1" expect="$2" deps="$3"
	shift 3
	local dir="$PROBE_ROOT/$name"
	rm -rf "$dir" && mkdir -p "$dir"
	cat > "$dir/pom.xml" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>$PARENT_GROUP</groupId>
		<artifactId>$PARENT_ARTIFACT</artifactId>
		<version>$PARENT_VERSION</version>
		<relativePath>../../../pom.xml</relativePath>
	</parent>
	<artifactId>enforcer-probe-$name</artifactId>
	<packaging>jar</packaging>
	<dependencies>$deps
	</dependencies>
</project>
POM
	local log="$dir/build.log"
	"$MVN" -B -ntp -f "$dir/pom.xml" "$@" validate > "$log" 2>&1
	local code=$?
	if [ "$expect" = pass ]; then
		if [ $code -eq 0 ]; then
			echo "OK   ${name}：通过（对照组）"
		else
			echo "FAIL ${name}：对照组应通过，实际退出码 ${code}"; tail -40 "$log"; failures=$((failures + 1))
		fi
	else
		if [ $code -ne 0 ] && grep -q "$MARKER" "$log"; then
			echo "OK   ${name}：被拦下"; grep -E "banned|Found Banned|$MARKER" "$log" | grep -v "^\[INFO\]" | head -4 | sed 's/^/       /'
		else
			echo "FAIL ${name}：应被 bannedDependencies 拦下（退出码 ${code}）"; tail -40 "$log"; failures=$((failures + 1))
		fi
	fi
}

dep() { printf '\n\t\t<dependency><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>%s</dependency>' "$1" "$2" "$3" "${4:-}"; }

# B89-2：纯传递依赖。target/enforcer-probe/transitive/ 是一个小 reactor：
#   wrapper（packaging=pom，自己设 enforcer.skip=true，依赖 mcp-json）→ 自身通过；
#   user（只依赖 wrapper，不直接声明任何禁用依赖）→ 必须因传递进来的 mcp-json 失败。
probe_transitive() {
	local dir="$PROBE_ROOT/transitive"
	rm -rf "$dir" && mkdir -p "$dir/wrapper" "$dir/user"
	local parent="<parent><groupId>$PARENT_GROUP</groupId><artifactId>$PARENT_ARTIFACT</artifactId><version>$PARENT_VERSION</version><relativePath>RELPATH</relativePath></parent>"
	cat > "$dir/pom.xml" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
	<modelVersion>4.0.0</modelVersion>
	${parent//RELPATH/../../../pom.xml}
	<artifactId>enforcer-probe-transitive</artifactId>
	<name>enforcer-probe-transitive</name>
	<packaging>pom</packaging>
	<modules>
		<module>wrapper</module>
		<module>user</module>
	</modules>
</project>
POM
	cat > "$dir/wrapper/pom.xml" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
	<modelVersion>4.0.0</modelVersion>
	${parent//RELPATH/../../../../pom.xml}
	<artifactId>enforcer-probe-transitive-wrapper</artifactId>
	<name>enforcer-probe-transitive-wrapper</name>
	<packaging>pom</packaging>
	<properties>
		<enforcer.skip>true</enforcer.skip>
	</properties>
	<dependencies>$(dep io.modelcontextprotocol.sdk mcp-json 0.17.0)
	</dependencies>
</project>
POM
	cat > "$dir/user/pom.xml" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
	<modelVersion>4.0.0</modelVersion>
	${parent//RELPATH/../../../../pom.xml}
	<artifactId>enforcer-probe-transitive-user</artifactId>
	<name>enforcer-probe-transitive-user</name>
	<packaging>jar</packaging>
	<dependencies>$(dep "$PARENT_GROUP" enforcer-probe-transitive-wrapper "$PARENT_VERSION" '<type>pom</type>')
	</dependencies>
</project>
POM
	local log="$dir/build.log"
	"$MVN" -B -ntp -f "$dir/pom.xml" "$@" validate > "$log" 2>&1
	local code=$?
	# reactor 汇总行按 <name> 显示（父 pom 的 name 会被继承，所以每个探针都显式写了 name）：包装模块 SUCCESS（它跳过了 enforcer），使用方模块 FAILURE（被传递依赖拦下）
	if [ $code -ne 0 ] && grep -q "$MARKER" "$log" \
		&& grep -Eq "enforcer-probe-transitive-wrapper .*SUCCESS" "$log" \
		&& grep -Eq "enforcer-probe-transitive-user .*FAILURE" "$log" \
		&& grep -q "io.modelcontextprotocol.sdk:mcp-json" "$log"; then
		echo "OK   transitive-via-wrapper：包装模块跳过 enforcer 仍通过，使用方模块因传递依赖 mcp-json 被拦下"
		grep -E "enforcer-probe-transitive-(wrapper|user) " "$log" | sed 's/^/       /'
	else
		echo "FAIL transitive-via-wrapper：使用方模块应因传递依赖被拦下（退出码 ${code}）"; tail -60 "$log"; failures=$((failures + 1))
	fi
}

shift_args=("$@")
probe control pass "" "${shift_args[@]}"
probe spring-ai-direct fail "$(dep org.springframework.ai spring-ai-model 1.1.2 '<exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>')" "${shift_args[@]}"
probe spring-ai-mcp-server-starter fail "$(dep org.springframework.ai spring-ai-starter-mcp-server-webmvc 1.1.2)" "${shift_args[@]}"
probe mcp-sdk fail "$(dep io.modelcontextprotocol.sdk mcp 0.17.0)" "${shift_args[@]}"
probe mcp-json-only fail "$(dep io.modelcontextprotocol.sdk mcp-json 0.17.0)" "${shift_args[@]}"
probe_transitive "${shift_args[@]}"

if [ $failures -ne 0 ]; then
	echo "bannedDependencies 规则验证失败：${failures} 项"
	exit 1
fi
echo "bannedDependencies 规则验证通过"
