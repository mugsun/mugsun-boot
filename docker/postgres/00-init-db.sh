#!/bin/bash
# Mugsun 数据库初始化（仅 PostgreSQL 容器首次启动、数据卷为空时执行）
# 复用仓库内已验证的 scripts/init-db.sql（幂等建账号/主库/埋点库），
# 账号密码由 MUGSUN_DB_PASSWORD 环境变量注入，不落明文进镜像与仓库。
set -euo pipefail

: "${MUGSUN_DB_PASSWORD:?必须设置 MUGSUN_DB_PASSWORD（应用账号 mugsun 的密码，仅限字母数字）}"

sed "s/WITH PASSWORD '[^']*'/WITH PASSWORD '${MUGSUN_DB_PASSWORD}'/" /initdb/init-db.sql \
  | psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"

# GIS 依赖的空间扩展。0.1 基线里也有 CREATE EXTENSION，但那步失败会被 EXCEPTION 吞掉转成
# 静默降级；在建库阶段用 ON_ERROR_STOP 装一次，镜像换错（用了不带 PostGIS 的官方镜像）时
# 容器会直接起不来，比事后发现空间查询悄悄慢了几倍要好。
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname mugsun \
  -c "CREATE EXTENSION IF NOT EXISTS postgis"

# AI 知识库依赖的向量扩展。交付镜像必须带 pgvector；装不上就让容器起不来，
# 避免知识库页面假装向量化成功、实际只能关键词检索。
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname mugsun \
  -c "CREATE EXTENSION IF NOT EXISTS vector"

echo ">> mugsun / mugsun_track 数据库初始化完成（含 PostGIS 与 pgvector）"
