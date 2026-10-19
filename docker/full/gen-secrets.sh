#!/bin/sh
# 首次启动生成全套口令与密钥，按服务拆分写入各自的 secrets 卷，每个文件 0600、属主是读取它的服务用户：
#   /secrets/boot/boot.env       后端（uid 10001）：DB/Redis 口令、SM2/SM4、文件签名、管理员初始口令
#   /secrets/postgres/db.env     PostgreSQL（uid 999）：只有数据库口令
#   /secrets/redis/redis.conf    Redis（uid 999）：只有 requirepass
# 三个卷互相隔离，每个服务只挂自己的那一个（只读）；任一服务被攻破也拿不到其它服务的密钥。
# 文件齐全则原样复用：重启、重建容器口令和密钥都不变。
# 只能用 docker compose down -v 连同数据卷一起清空：单删 secrets 卷而保留 pg-data，新口令与库里的旧口令对不上。
# 管理员口令不打印到日志（日志可能被转发到集中日志）；查看：docker compose -f docker-compose.full.yml run --rm init show
# （admin 改过口令后 show 只提示「初始密码已失效」，见 show_admin_password）
set -eu
BOOT=/secrets/boot/boot.env
PG=/secrets/postgres/db.env
REDIS=/secrets/redis/redis.conf
BOOT_UID=10001 PG_UID=999 REDIS_UID=999

# init show：管理员改过口令后不再打印初始口令。
# 判断依据是库里 admin 当前的口令哈希还能不能用初始口令验证通过（bcrypt），而不是另存一个「已改密」标记：
#   - 任何改密途径（个人中心、找回密码、管理员重置、以后首次登录强制改密）都会换哈希，这里不用跟着改；
#   - 被管理员重置回初始口令时，初始口令确实又能登录了，这时照常显示。
# 不清掉 secrets 卷里的 MUGSUN_INIT_PASSWORD：后端 prod 启动守卫要求它存在，重置口令和新建租户管理员也用它。
# 连不上库（postgres 没起、口令不对）时照常显示，但提示「无法确认」。
show_admin_password() {
  INIT_PW=$(sed -n 's/^export MUGSUN_INIT_PASSWORD=//p' "$BOOT")
  DBPW=$(sed -n 's/^export JDBC_PASSWORD=//p' "$BOOT")
  HASH=$(PGPASSWORD="$DBPW" PGCONNECT_TIMEOUT=5 psql -h "${MUGSUN_DB_HOST:-postgres}" -U "${MUGSUN_DB_USER:-mugsun}" \
    -d "${MUGSUN_DB_NAME:-mugsun}" -tAqX -v ON_ERROR_STOP=1 \
    -c "SELECT password FROM sys_user WHERE username = 'admin' AND tenant_id = '000000' ORDER BY id LIMIT 1" 2>/dev/null) || HASH=
  if [ -z "$HASH" ]; then
    echo "管理员口令：$INIT_PW"
    echo "（无法连接数据库确认是否已改过口令；如果已经改过，这个初始口令已失效）"
    return 0
  fi
  rc=0
  INIT_PW="$INIT_PW" HASH="$HASH" python3 /init-pw-check.py || rc=$?
  case "$rc" in
    0) echo "管理员口令：$INIT_PW" ;;
    1) echo "初始密码已失效：admin 已修改过口令。忘记口令请用其他管理员账号重置，或按文档找回" ;;
    *) echo "管理员口令：$INIT_PW"
       echo "（无法识别库里的口令哈希格式，不能确认是否已改过口令）" ;;
  esac
}

if [ -s "$BOOT" ] && [ -s "$PG" ] && [ -s "$REDIS" ]; then
  if [ "${1:-}" = show ]; then
    show_admin_password
  else
    echo ">> 复用已有密钥（管理员口令：docker compose -f docker-compose.full.yml run --rm init show）"
  fi
  exit 0
fi
if [ -e "$BOOT" ] || [ -e "$PG" ] || [ -e "$REDIS" ]; then
  echo "!! secrets 卷不完整（上次生成中途失败或被部分删除）。请 docker compose -f docker-compose.full.yml down -v 后重来" >&2
  exit 1
fi
[ "${1:-}" = show ] && { echo "!! 还没有生成密钥，先 up 一次" >&2; exit 1; }

umask 077
fail() { echo "!! $1" >&2; exit 1; }
# set -e 管不到命令替换里管道前段的失败（openssl 挂了只会得到空串），所以生成后逐个校验长度
rnd() { openssl rand -base64 96 | tr -dc 'A-Za-z0-9' | cut -c1-"$1"; }
need_len() { [ "${#2}" -eq "$3" ] || fail "$1 生成失败（长度 ${#2}，应为 $3）"; }
# SM2×2 / SM4×2 / 管理员口令 / 文件签名密钥：复用仓库 scripts/gen-keys.sh（与手工部署同一来源）
KEYS=$(/gen-keys.sh --all) || fail "gen-keys.sh 失败"
get() { printf '%s\n' "$KEYS" | sed -n "s/^$1=//p"; }
need_len MUGSUN_SM4_KEY "$(get MUGSUN_SM4_KEY)" 16
need_len MUGSUN_API_KEY "$(get MUGSUN_API_KEY)" 16
need_len MUGSUN_SM2_PRIVATE_KEY "$(get MUGSUN_SM2_PRIVATE_KEY)" 64
need_len MUGSUN_SIGN_PRIVATE_KEY "$(get MUGSUN_SIGN_PRIVATE_KEY)" 64
need_len MUGSUN_INIT_PASSWORD "$(get MUGSUN_INIT_PASSWORD)" 19
need_len MUGSUN_FILE_SIGN_SECRET "$(get MUGSUN_FILE_SIGN_SECRET)" 48
DBPW=$(rnd 32); need_len 数据库口令 "$DBPW" 32
REDISPW=$(rnd 32); need_len Redis口令 "$REDISPW" 32

mkdir -p /secrets/boot /secrets/postgres /secrets/redis
# 先写临时文件，全部成功后再 mv 落位，避免半套密钥
printf 'requirepass %s\nappendonly yes\n' "$REDISPW" > "$REDIS.tmp"
printf 'export MUGSUN_DB_PASSWORD=%s\n' "$DBPW" > "$PG.tmp"
{
  echo "export JDBC_PASSWORD=$DBPW"
  echo "export TRACK_JDBC_PASSWORD=$DBPW"
  echo "export REDIS_PASSWORD=$REDISPW"
  printf '%s\n' "$KEYS" | sed 's/^/export /'
} > "$BOOT.tmp"
for f in "$REDIS" "$PG" "$BOOT"; do chmod 600 "$f.tmp"; done
chown "$REDIS_UID:$REDIS_UID" "$REDIS.tmp" /secrets/redis
chown "$PG_UID:$PG_UID" "$PG.tmp" /secrets/postgres
chown "$BOOT_UID:$BOOT_UID" "$BOOT.tmp" /secrets/boot
chmod 700 /secrets/boot /secrets/postgres /secrets/redis
mv "$REDIS.tmp" "$REDIS"; mv "$PG.tmp" "$PG"; mv "$BOOT.tmp" "$BOOT"
cat <<'MSG'
============================================================
 mugsun 首次启动：已生成管理员口令与全部密钥（按服务分卷保存，0600）
   管理员账号：admin
   管理员口令不写入日志，请执行：
     docker compose -f docker-compose.full.yml run --rm init show
   登录后请立即修改口令。
============================================================
MSG
