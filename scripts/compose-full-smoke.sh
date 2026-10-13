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
init_log=$("${DC[@]}" logs init)
[[ "$init_log" == *管理员口令* ]] || { echo "FAIL 首启日志里没有打印管理员口令"; exit 1; }
home=$(curl -fsS "http://localhost:${MUGSUN_HTTP_PORT}/")
[[ "$home" == *"<html"* || "$home" == *"<!DOCTYPE"* || "$home" == *"<!doctype"* ]] || { echo "FAIL 管理台首页打不开"; exit 1; }
# Redis 口令不得出现在进程参数里
cmdline=$("${DC[@]}" exec -T redis sh -c 'tr "\0" " " < /proc/1/cmdline')
[[ "$cmdline" != *requirepass* ]] || { echo "FAIL Redis 口令出现在进程参数里"; exit 1; }
# 后端只信任 pc 的固定 IP
trusted=$("${DC[@]}" exec -T boot printenv MUGSUN_OPENAPI_TRUSTED_PROXIES)
[[ "$trusted" == */32 ]] || { echo "FAIL MUGSUN_OPENAPI_TRUSTED_PROXIES 不是单个 IP：$trusted"; exit 1; }
docker run --rm --network "${COMPOSE_PROJECT_NAME}_default" \
  -v "${COMPOSE_PROJECT_NAME}_secrets:/secrets:ro" -v "$PWD/docker/full/smoke.py:/smoke.py:ro" \
  python:3.12-alpine sh -c "pip install -q --disable-pip-version-check --root-user-action=ignore gmssl redis && python /smoke.py"
end=$(date +%s)
echo ">> PASS 从零到登录并看到第一条埋点：$((end-start)) 秒"
