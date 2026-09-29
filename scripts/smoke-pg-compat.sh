#!/usr/bin/env bash
# 金仓 / openGauss（PG 兼容系）冒烟清单——需可连通的实例与（金仓）厂商 JDBC。
# 2026-09-29 本机 Docker Desktop（arm64 仿真 amd64）：enmotech/opengauss-lite:5.1.0 --privileged 可启动。
# 企业版 enmotech/opengauss:5.0.0 仍会在 gs_ctl 退出。lite 起来后，PG 基线里的 ON CONFLICT、
# ADD COLUMN IF NOT EXISTS 不能原样执行，不要把「容器起来」写成「脚本已灌完」。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

: "${DB_HOST:?set DB_HOST}"
: "${DB_PORT:=54321}"
: "${DB_NAME:=mugsun}"
: "${DB_USER:?set DB_USER}"
: "${DB_PASSWORD:?set DB_PASSWORD}"
: "${JDBC_URL:=jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}}"

echo "==> 1) 方言单测（无实例）"
mvn -q -Dtest=SqlDialectTest test

echo "==> 2) 打包（可选 -Pkingbase 需本地已 install 厂商 jar）"
if [[ "${USE_KINGBASE_PROFILE:-0}" == "1" ]]; then
  mvn -q -Pkingbase -DskipTests package
else
  mvn -q -DskipTests package
fi

echo "==> 3) 请用下列配置启动应用后手工验收（实际键为 mybatis-flex，非 spring.datasource）："
cat <<EOF
mybatis-flex.datasource.primary.url=${JDBC_URL}
mybatis-flex.datasource.primary.username=${DB_USER}
mybatis-flex.datasource.primary.password=${DB_PASSWORD}
mybatis-flex.datasource.track.url=jdbc:postgresql://${DB_HOST}:${DB_PORT}/mugsun_track
mybatis-flex.datasource.track.username=${DB_USER}
mybatis-flex.datasource.track.password=${DB_PASSWORD}
spring.flyway.locations=classpath:db/migration

验收：启动无报错 → 登录 → /system/user/page → /system/gen DDL 建表出型为 PG 族。
EOF
