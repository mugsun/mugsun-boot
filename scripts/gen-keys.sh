#!/bin/sh
# 生成 mugsun 的 SM2/SM4 密钥，输出 KEY=VALUE，可直接贴进 .env：
#   scripts/gen-keys.sh >> .env
#   scripts/gen-keys.sh --all >> .env   # 另含 MUGSUN_INIT_PASSWORD 与 MUGSUN_FILE_SIGN_SECRET
# 依赖：OpenSSL 1.1.1+（支持 SM2）。SM2 私钥 64 位 hex、公钥 04 开头 130 位 hex（Hutool/BouncyCastle 原生格式）。
set -eu
MODE=${1:-}
rnd() { openssl rand -base64 96 | tr -dc 'A-Za-z0-9' | cut -c1-"$1"; }
sm2() {
  t=$(mktemp)
  openssl genpkey -algorithm SM2 -out "$t" 2>/dev/null
  openssl pkey -in "$t" -text -noout 2>/dev/null | awk '
    /^priv:/{m="priv";next} /^pub:/{m="pub";next} /^[A-Za-z]/{m=""}
    m!=""{gsub(/[ :]/,""); v[m]=v[m] $0}
    END{p=v["priv"]; sub(/^00/,"",p); while(length(p)<64)p="0" p; print p, v["pub"]}'
  rm -f "$t"
}
set -- $(sm2); A_PRI=$1 A_PUB=$2
set -- $(sm2); B_PRI=$1 B_PUB=$2
for k in "$A_PRI" "$B_PRI"; do [ ${#k} -eq 64 ] || { echo "SM2 私钥生成失败（需 OpenSSL 1.1.1+）" >&2; exit 1; }; done
for k in "$A_PUB" "$B_PUB"; do [ ${#k} -eq 130 ] || { echo "SM2 公钥生成失败（需 OpenSSL 1.1.1+）" >&2; exit 1; }; done
cat <<ENV
MUGSUN_SM4_KEY=$(openssl rand -hex 16)
MUGSUN_API_KEY=$(openssl rand -hex 16)
MUGSUN_SM2_PRIVATE_KEY=$A_PRI
MUGSUN_SM2_PUBLIC_KEY=$A_PUB
MUGSUN_SIGN_PRIVATE_KEY=$B_PRI
MUGSUN_SIGN_PUBLIC_KEY=$B_PUB
ENV
if [ "$MODE" = --all ]; then
  echo "MUGSUN_INIT_PASSWORD=Mg$(rnd 4)-$(rnd 10)9a"
  echo "MUGSUN_FILE_SIGN_SECRET=$(rnd 48)"
fi
