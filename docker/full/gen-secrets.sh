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
sm2() { # 输出 "私钥hex 公钥hex(04+128)"
  openssl genpkey -algorithm SM2 -out /tmp/k.pem 2>/dev/null
  openssl pkey -in /tmp/k.pem -text -noout 2>/dev/null | awk '
    /^priv:/{m="priv";next} /^pub:/{m="pub";next} /^[A-Za-z]/{m=""}
    m!=""{gsub(/[ :]/,""); v[m]=v[m] $0}
    END{p=v["priv"]; sub(/^00/,"",p); while(length(p)<64)p="0" p; print p, v["pub"]}'
  rm -f /tmp/k.pem
}
set -- $(sm2); SM2_PRI=$1 SM2_PUB=$2
set -- $(sm2); SIGN_PRI=$1 SIGN_PUB=$2
[ ${#SM2_PRI} -eq 64 ] && [ ${#SM2_PUB} -eq 130 ] && [ ${#SIGN_PRI} -eq 64 ] || { echo "SM2 密钥生成失败" >&2; exit 1; }
ADMIN="Mg$(rnd 4)-$(rnd 10)9a"
cat > "$F.tmp" <<ENV
export MUGSUN_DB_PASSWORD=$(rnd 32)
export JDBC_PASSWORD=\$MUGSUN_DB_PASSWORD
export TRACK_JDBC_PASSWORD=\$MUGSUN_DB_PASSWORD
export REDIS_PASSWORD=$(rnd 32)
export MUGSUN_INIT_PASSWORD=$ADMIN
export MUGSUN_SM4_KEY=$(rnd 16)
export MUGSUN_API_KEY=$(rnd 16)
export MUGSUN_FILE_SIGN_SECRET=$(rnd 48)
export MUGSUN_SM2_PRIVATE_KEY=$SM2_PRI
export MUGSUN_SM2_PUBLIC_KEY=$SM2_PUB
export MUGSUN_SIGN_PRIVATE_KEY=$SIGN_PRI
export MUGSUN_SIGN_PUBLIC_KEY=$SIGN_PUB
ENV
mv "$F.tmp" "$F"
chmod 644 "$F" # 各服务以不同用户读取；卷只挂给本 compose 的容器
cat <<MSG
============================================================
 mugsun 首次启动：已生成管理员口令与全部密钥（保存在 secrets 卷）
   管理员账号：admin
   管理员口令：$ADMIN
 登录后请修改口令。再次查看：
   docker compose -f docker-compose.full.yml run --rm init show
============================================================
MSG
