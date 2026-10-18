#!/usr/bin/env bash
# B89 白名单守卫的「生效配置」一侧：用 help:effective-pom 算出 mugsun-boot-server 的实际 pom，
# 确认根 pom 里的 b89-banned-dependencies execution 继承下来后仍然完整：绑定 validate、goal 为 enforce、
# searchTransitive=true、两条 exclude 都在、fail=true，插件与 execution 上都没有 skip 或 inherited=false。
# 这样即使有人经 profile、pluginManagement、combine.* 之类的合并规则改写了配置，也能在 CI 里看出来。
# 默认 profile 与 Docker 镜像用的 full,kingbase,dameng 各算一次。
# 额外的 Maven 参数原样传下去，例如：scripts/check-effective-enforcer.sh -s /tmp/empty-settings.xml -Dmaven.repo.local=$PWD/.m2repo
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MVN="${MVN:-mvn}"
OUT_DIR="$ROOT/target/effective-pom"
mkdir -p "$OUT_DIR"
failures=0

check() {
	local label="$1" out="$OUT_DIR/$1.xml"
	shift
	"$MVN" -B -q -ntp -f "$ROOT/pom.xml" "$@" -pl mugsun-boot-server \
		org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom -Doutput="$out"
	if python3 - "$out" <<'PY'
import sys
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse(sys.argv[1]).getroot()
problems = []

def text(el, path):
    found = el.find(path, NS)
    return None if found is None or found.text is None else found.text.strip()

plugins = [p for p in root.findall("m:build/m:plugins/m:plugin", NS)
           if text(p, "m:artifactId") == "maven-enforcer-plugin"]
if len(plugins) != 1:
    problems.append(f"build/plugins 里 maven-enforcer-plugin 应恰好 1 个，实际 {len(plugins)} 个")
else:
    plugin = plugins[0]
    if text(plugin, "m:inherited") == "false":
        problems.append("插件 inherited=false")
    if text(plugin, "m:configuration/m:skip") not in (None, "false"):
        problems.append("插件级 configuration 有 skip")
    execs = [e for e in plugin.findall("m:executions/m:execution", NS) if text(e, "m:id") == "b89-banned-dependencies"]
    if len(execs) != 1:
        problems.append(f"execution b89-banned-dependencies 应恰好 1 个，实际 {len(execs)} 个")
    else:
        e = execs[0]
        if text(e, "m:phase") != "validate":
            problems.append(f"phase 应为 validate，实际 {text(e, 'm:phase')}")
        if [g.text.strip() for g in e.findall("m:goals/m:goal", NS)] != ["enforce"]:
            problems.append("goals 应只有 enforce")
        if text(e, "m:inherited") == "false":
            problems.append("execution inherited=false")
        cfg = e.find("m:configuration", NS)
        if cfg is None:
            problems.append("execution 没有 configuration")
        else:
            if text(cfg, "m:skip") not in (None, "false"):
                problems.append("execution configuration 有 skip")
            if text(cfg, "m:fail") != "true":
                problems.append("fail 应为 true")
            bd = cfg.findall("m:rules/m:bannedDependencies", NS)
            if len(bd) != 1:
                problems.append(f"rules 里 bannedDependencies 应恰好 1 个，实际 {len(bd)} 个")
            else:
                b = bd[0]
                if text(b, "m:searchTransitive") != "true":
                    problems.append("searchTransitive 应为 true")
                ex = sorted(x.text.strip() for x in b.findall("m:excludes/m:exclude", NS))
                if ex != ["io.modelcontextprotocol*", "org.springframework.ai"]:
                    problems.append(f"excludes 应为 org.springframework.ai 与 io.modelcontextprotocol*，实际 {ex}")
                if b.find("m:includes", NS) is not None:
                    problems.append("bannedDependencies 有 includes（会放行被禁的依赖）")

for p in problems:
    print("  - " + p)
sys.exit(1 if problems else 0)
PY
	then
		echo "OK   ${label}：mugsun-boot-server 的生效 pom 里 b89-banned-dependencies 完整"
	else
		echo "FAIL ${label}：mugsun-boot-server 的生效 pom 里 b89-banned-dependencies 被改写（见上）"
		failures=$((failures + 1))
	fi
}

check default "$@"
check full-kingbase-dameng -Pfull,kingbase,dameng "$@"

if [ "$failures" -ne 0 ]; then
	exit 1
fi
