#!/usr/bin/env bash
# 一键全栈验收：从零 up → 登录 → 上报并查到第一条埋点 → 打印耗时 → down -v 清理。
# 用独立 compose 项目名（默认 mugsun-full-smoke），不碰本机其它容器与卷。
#   scripts/compose-full-smoke.sh            # 本地/CI
#   KEEP=1 scripts/compose-full-smoke.sh     # 验完不清理
set -euo pipefail
cd "$(dirname "$0")/.."
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-mugsun-full-smoke}"
export MUGSUN_HTTP_PORT="${MUGSUN_HTTP_PORT:-18088}"
DC=(docker compose -f docker-compose.full.yml)
cleanup() { [ "${KEEP:-0}" = 1 ] || "${DC[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT
"${DC[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
start=$(date +%s)
"${DC[@]}" up -d --build --wait --wait-timeout "${WAIT_TIMEOUT:-900}"
up=$(date +%s)
echo ">> 栈已就绪（$((up-start)) 秒）"
# 先落到变量再匹配：pipefail 下 grep -q 提前退出会让上游吃 SIGPIPE 而误判失败
fail() { echo "FAIL $*"; exit 1; }
admin_line=$("${DC[@]}" run --rm -T init show)
admin_pw=${admin_line#*管理员口令：}
[ ${#admin_pw} -eq 19 ] || fail "init show 没给出管理员口令"
init_log=$("${DC[@]}" logs init)
[[ "$init_log" == *"init show"* ]] || fail "首启日志里没有查看口令的提示"
[[ "$init_log" != *"$admin_pw"* ]] || fail "管理员口令被打进了 init 日志"
home=$(curl -fsS "http://127.0.0.1:${MUGSUN_HTTP_PORT}/")
[[ "$home" == *"<html"* || "$home" == *"<!DOCTYPE"* || "$home" == *"<!doctype"* ]] || fail "管理台首页打不开"
# 8088 默认只绑回环
bind=$("${DC[@]}" port pc 8080)
[[ "$bind" == 127.0.0.1:* ]] || fail "管理台端口没有只绑 127.0.0.1：$bind"
# pc 用 mugsun-pc 的加固镜像：非 root、带 CSP、XFF 用 \$remote_addr 覆盖
[ "$("${DC[@]}" exec -T pc id -u)" != 0 ] || fail "pc 容器以 root 运行"
hdr=$(curl -fsSI "http://127.0.0.1:${MUGSUN_HTTP_PORT}/")
[[ "$hdr" == *[Cc]ontent-[Ss]ecurity-[Pp]olicy* ]] || fail "管理台响应没有 CSP 头"
ngx=$("${DC[@]}" exec -T pc nginx -T 2>/dev/null)
[[ "$ngx" == *'X-Forwarded-For $remote_addr'* ]] || fail "pc nginx 没有用 \$remote_addr 覆盖 X-Forwarded-For"
[[ "$ngx" != *proxy_add_x_forwarded_for* ]] || fail "pc nginx 仍在追加客户端自带的 X-Forwarded-For"
# pc#37：非法网关网段（在 compose 网络里跑，upstream boot 才能解析）
# / CSP 域名必须让 nginx 拒绝启动
docker run --rm --network "${COMPOSE_PROJECT_NAME}_default" -e MUGSUN_API_UPSTREAM=http://boot:8080 mugsun-full-pc:local nginx -t >/dev/null 2>&1 || fail "pc 默认配置 nginx -t 不通过"
for bad in "MUGSUN_GATEWAY_CIDRS=0.0.0.0/0" "MUGSUN_CSP_TILE_HOSTS=a;b"; do
  ! docker run --rm --network "${COMPOSE_PROJECT_NAME}_default" -e MUGSUN_API_UPSTREAM=http://boot:8080 -e "$bad" mugsun-full-pc:local nginx -t >/dev/null 2>&1 || fail "pc 没有拒绝 $bad"
done
# 网络与 pc 仓一致：pc 固定 .10，动态段 .128/25
pcip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$("${DC[@]}" ps -q pc)")
[ "$pcip" = 172.30.88.10 ] || fail "pc IP 不是 172.30.88.10：$pcip"
iprange=$(docker network inspect -f '{{range .IPAM.Config}}{{.IPRange}}{{end}}' "${COMPOSE_PROJECT_NAME}_default")
[ "$iprange" = 172.30.88.128/25 ] || fail "网络 ip_range 不是 172.30.88.128/25：$iprange"
# 后端只信任 pc 的固定 IP
trusted=$("${DC[@]}" exec -T boot printenv MUGSUN_OPENAPI_TRUSTED_PROXIES)
[ "$trusted" = 172.30.88.10/32 ] || fail "MUGSUN_OPENAPI_TRUSTED_PROXIES 不是 pc 的单个 IP：$trusted"
# Redis 口令不得出现在进程参数里
cmdline=$("${DC[@]}" exec -T redis sh -c 'tr "\0" " " < /proc/1/cmdline')
[[ "$cmdline" != *requirepass* ]] || fail "Redis 口令出现在进程参数里"
# 密钥按服务拆分：每个服务只看得到自己的文件，0600 且属主是该服务用户
chk() { # 服务 期望文件 期望属主uid
  out=$("${DC[@]}" exec -T "$1" sh -c 'ls -A /secrets; stat -c "%a %u" /secrets/'"$2")
  [ "$out" = "$(printf '%s\n600 %s' "$2" "$3")" ] || fail "$1 的 /secrets 不符合预期：$(echo $out)"
}
chk boot boot.env 10001
chk postgres db.env 999
chk redis redis.conf 999
[ "$("${DC[@]}" exec -T redis id -u redis)" = 999 ] || fail "redis 用户 uid 不是 999"
[ "$("${DC[@]}" exec -T postgres id -u postgres)" = 999 ] || fail "postgres 用户 uid 不是 999"
# Python 依赖版本 + sha256 锁死（--require-hashes）
docker run --rm --network "${COMPOSE_PROJECT_NAME}_default" \
  -v "${COMPOSE_PROJECT_NAME}_secrets-boot:/secrets/boot:ro" \
  -v "$PWD/docker/full/smoke.py:/smoke.py:ro" -v "$PWD/docker/full/smoke-requirements.txt:/req.txt:ro" \
  python:3.12-alpine@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4 \
  sh -c "pip install -q --disable-pip-version-check --root-user-action=ignore --no-deps --only-binary=:all: --require-hashes -r /req.txt && python /smoke.py"
# smoke.py 最后改了管理员口令：init show 不能再给出初始口令
after_line=$("${DC[@]}" run --rm -T init show)
[[ "$after_line" == *"初始密码已失效"* ]] || fail "改密后 init show 仍未提示初始密码已失效：${after_line%%：*}"
[[ "$after_line" != *"$admin_pw"* ]] || fail "改密后 init show 仍打印了初始口令"
end=$(date +%s)
echo ">> PASS 从零到登录并看到第一条埋点：$((end-start)) 秒"
