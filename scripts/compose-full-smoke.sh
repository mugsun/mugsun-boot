#!/usr/bin/env bash
# 一键全栈验收：从零 up → 登录 → 上报并查到第一条埋点 → 全部停掉、打乱顺序重启后自己恢复 → 打印耗时 → down -v 清理。
# 用独立 compose 项目名（默认 mugsun-full-smoke），不碰本机其它容器与卷。
#   scripts/compose-full-smoke.sh            # 本地/CI
#   KEEP=1 scripts/compose-full-smoke.sh     # 验完不清理
set -euo pipefail
cd "$(dirname "$0")/.."
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-mugsun-full-smoke}"
export MUGSUN_HTTP_PORT="${MUGSUN_HTTP_PORT:-18088}"
DC=(docker compose -f docker-compose.full.yml)
cleanup() { [ "${KEEP:-0}" = 1 ] || "${DC[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; }
# 失败时先打日志再走 cleanup，避免 CI 后置 KEEP=1 时栈已被 trap down 掉
dump_logs() {
  echo ">> FAIL 收集 compose 状态与日志" >&2
  "${DC[@]}" ps -a >&2 || true
  "${DC[@]}" logs --tail 300 >&2 || true
}
trap dump_logs ERR
trap cleanup EXIT
"${DC[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
start=$(date +%s)
# ready 是 oneshot 横幅：exit 0 后 compose up --wait 仍判失败（docker/compose#10596）。
# init 已有 postgres/redis 的 service_completed_successfully 依赖，可被 --wait 正确对待；
# 这里只 wait 长驻服务，不把 ready 拉进 wait 集合（依赖仍会拉起 init/postgres/redis）。
# 见 #104
"${DC[@]}" up -d --build --wait --wait-timeout "${WAIT_TIMEOUT:-900}" \
  postgres redis boot pc
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
# 网段前三段：和 docker-compose.full.yml 用同一个变量、同一个默认值
net="${MUGSUN_NET_PREFIX:-172.30.88}"
# pc#37：非法网关网段 / CSP 域名必须让 nginx 拒绝启动（upstream 和 compose 里一样写后端的固定地址）
docker run --rm --network "${COMPOSE_PROJECT_NAME}_default" -e "MUGSUN_API_UPSTREAM=http://${net}.12:8080" mugsun-full-pc:local nginx -t >/dev/null 2>&1 || fail "pc 默认配置 nginx -t 不通过"
for bad in "MUGSUN_GATEWAY_CIDRS=0.0.0.0/0" "MUGSUN_CSP_TILE_HOSTS=a;b"; do
  ! docker run --rm --network "${COMPOSE_PROJECT_NAME}_default" -e "MUGSUN_API_UPSTREAM=http://${net}.12:8080" -e "$bad" mugsun-full-pc:local nginx -t >/dev/null 2>&1 || fail "pc 没有拒绝 $bad"
done
# 网络：四个常驻容器用网段里的固定地址（.10 管理台、.12 后端、.13 数据库、.14 Redis），动态段 .128/25
cid() { "${DC[@]}" ps -aq "$1"; }
ip_of() { docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(cid "$1")"; }
for pair in pc:10 boot:12 postgres:13 redis:14; do
  svc=${pair%%:*}
  want="${net}.${pair##*:}"
  got=$(ip_of "$svc")
  [ "$got" = "$want" ] || fail "${svc} 的地址不是 ${want}：${got}"
done
iprange=$(docker network inspect -f '{{range .IPAM.Config}}{{.IPRange}}{{end}}' "${COMPOSE_PROJECT_NAME}_default")
[ "$iprange" = "${net}.128/25" ] || fail "网络 ip_range 不是 ${net}.128/25：$iprange"
# 后端只信任 pc 的固定 IP
trusted=$("${DC[@]}" exec -T boot printenv MUGSUN_OPENAPI_TRUSTED_PROXIES)
[ "$trusted" = "${net}.10/32" ] || fail "MUGSUN_OPENAPI_TRUSTED_PROXIES 不是 pc 的单个 IP：$trusted"
# 容器之间按固定地址找对方，不按名字：对方没在运行时，名字会被交给宿主机的 DNS 去查；
# Nginx 还只在启动时查一次，查错了要等重启才改得过来（见 docker-compose.full.yml 开头）
[[ "$ngx" == *"proxy_pass http://${net}.12:8080/;"* ]] || fail "管理台没有按固定地址把请求转给后端"
jdbc=$("${DC[@]}" exec -T boot printenv JDBC_URL)
[ "$jdbc" = "jdbc:postgresql://${net}.13:5432/mugsun" ] || fail "后端没有按固定地址连数据库：$jdbc"
rhost=$("${DC[@]}" exec -T boot printenv REDIS_HOST)
[ "$rhost" = "${net}.14" ] || fail "后端没有按固定地址连 Redis：$rhost"
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
# 时区（默认 Asia/Shanghai，MUGSUN_TZ 可改）：库里的时间列不带时区，按后端 JVM 的时区写入、页面原样显示。
# 四个常驻容器的时区偏移要一致；上面刚登录过，最新一条登录日志里应用写的 login_time 和数据库 now() 写的
# create_time 都得是这个时区“刚才”的钟点（后端没跟上设定的时区时，会差出整段时区偏移）
tz=$("${DC[@]}" exec -T boot printenv TZ || true)
[ -n "$tz" ] || fail "boot 容器没有设置 TZ"
offset=$("${DC[@]}" exec -T boot date +%z)
for svc in postgres redis pc; do
  got=$("${DC[@]}" exec -T "$svc" date +%z)
  [ "$got" = "$offset" ] || fail "$svc 容器的时区偏移是 $got，boot 是 $offset（$tz）"
done
lag=$("${DC[@]}" exec -T postgres psql -U postgres -d mugsun -At -F ' ' -c \
  "SELECT round(extract(epoch FROM (now() AT TIME ZONE '$tz') - login_time)), round(extract(epoch FROM (now() AT TIME ZONE '$tz') - create_time)) FROM sys_login_log ORDER BY id DESC LIMIT 1")
read -r lag_login lag_create <<<"$lag" || true
for v in "$lag_login" "$lag_create"; do
  { [[ "$v" =~ ^-?[0-9]+$ ]] && [ "$v" -ge -60 ] && [ "$v" -le 600 ]; } \
    || fail "最新一条登录日志的时间不是 $tz 的当前时间（login_time、create_time 落后的秒数：${lag:-查不到记录}）"
done
end=$(date +%s)
# 机器重启以后 Docker 拉起容器不看依赖顺序。这里把四个常驻容器全部停掉，故意先起管理台，15 秒以后才起数据库、
# Redis 和后端：整套要能自己恢复，不用人去重启哪个容器。
# 直接用 docker stop 和 docker start：compose 的 start 会按依赖关系排好先后
front=$(cid pc)
back="$(cid postgres) $(cid redis) $(cid boot)"
rebooted=$(date +%s)
# shellcheck disable=SC2086
docker stop $front $back >/dev/null
# 停止时每个进程都要来得及自己收尾退出。退出码 137 表示等到了时间、被强杀的
for svc in postgres redis boot pc; do
  [ "$(docker inspect -f '{{.State.ExitCode}}' "$(cid "$svc")")" != 137 ] || fail "${svc} 停止时没有来得及自己退出，被强杀了"
done
docker start "$front" >/dev/null
sleep 15
# shellcheck disable=SC2086
docker start $back >/dev/null
recovered=""
for _ in $(seq 1 60); do
  if [ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://127.0.0.1:${MUGSUN_HTTP_PORT}/api/ping" || true)" = 200 ]; then
    recovered=1
    break
  fi
  sleep 3
done
[ -n "$recovered" ] || fail "全部容器停掉、打乱顺序重新启动以后，经管理台访问后端的接口 3 分钟内没有恢复"
echo ">> 全部容器停掉、打乱顺序重新启动以后 $(($(date +%s) - rebooted)) 秒，经管理台访问后端的接口恢复"
echo ">> PASS 从零到登录并看到第一条埋点：$((end-start)) 秒"
