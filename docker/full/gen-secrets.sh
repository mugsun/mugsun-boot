#!/bin/sh
# 首次启动生成全套口令与密钥，写入 secrets 卷（/secrets/mugsun.env，0600）。
# 文件已存在则原样复用：重启、重建容器口令和密钥都不变；docker compose down -v 才会重新生成。
set -eu
F=/secrets/mugsun.env
if [ -s "$F" ]; then
  echo ">> 复用已有密钥文件（管理员口令见首次启动日志，或：docker compose -f docker-compose.full.yml run --rm init show）"
  [ "${1:-}" = show ] && grep MUGSUN_INIT_PASSWORD "$F"
  exit 0
fi
umask 077
rnd() { openssl rand -base64 64 | tr -dc 'A-Za-z0-9' | cut -c1-"$1"; }
# SM2×2 / SM4×2 / 管理员口令 / 文件签名密钥：复用仓库 scripts/gen-keys.sh（与手工部署同一来源）
KEYS=$(/gen-keys.sh --all)
ADMIN=$(printf '%s\n' "$KEYS" | sed -n 's/^MUGSUN_INIT_PASSWORD=//p')
DBPW=$(rnd 32)
REDISPW=$(rnd 32)
printf 'requirepass %s\nappendonly yes\n' "$REDISPW" > /secrets/redis.conf
{
  echo "export MUGSUN_DB_PASSWORD=$DBPW"
  echo "export JDBC_PASSWORD=$DBPW"
  echo "export TRACK_JDBC_PASSWORD=$DBPW"
  echo "export REDIS_PASSWORD=$REDISPW"
  printf '%s\n' "$KEYS" | sed 's/^/export /'
} > "$F.tmp"
mv "$F.tmp" "$F"
chmod 644 "$F" /secrets/redis.conf # 各服务以不同用户读取；卷只挂给本 compose 的容器
cat <<MSG
============================================================
 mugsun 首次启动：已生成管理员口令与全部密钥（保存在 secrets 卷）
   管理员账号：admin
   管理员口令：$ADMIN
 登录后请修改口令。再次查看：
   docker compose -f docker-compose.full.yml run --rm init show
============================================================
MSG
