# 交付库：PostGIS + pgvector。官方 postgis 镜像不带 vector 类型，
# AI 迁移在无扩展时会回落，但本镜像要求两者都在，缺一容器起不来。
#
# 多架构：amd64 用官方 postgis/postgis:16-3.4；arm64 用同系 Debian+PGDG
# 的 imresamu/postgis:16-3.4（官方 PostGIS 镜像仅 amd64，故 arm64 用该
# 第三方镜像）。buildx 按 TARGETARCH 选 FROM，推到
# ghcr.io/mugsun/postgis-pgvector:16-3.4 为同一 multi-arch manifest。
# 各 base 阶段钉 --platform 与 @sha256 digest，避免构建时解析浮动 tag，
# 也避免构建 arm64 时去拉官方 amd64-only 镜像。
# 更新 digest：docker buildx imagetools inspect <image:tag>
#   - 官方单架构：用输出的 Digest
#   - imresamu 多架构：用 Manifests 里 Platform linux/arm64 那一行的 Digest
#   改下方两行 FROM 后本地 docker compose build postgres 验证。
ARG TARGETARCH=amd64
FROM --platform=linux/amd64 postgis/postgis:16-3.4@sha256:44126d872ac91993766c341e369c539e8196614321765d36a6f1bab0419a5fa5 AS base-amd64
FROM --platform=linux/arm64 imresamu/postgis:16-3.4@sha256:63675555a4df2dc1f2874e860e50a5d3dcda1b21488696ab23577b7318c75a35 AS base-arm64
FROM base-${TARGETARCH}

USER root
# bullseye 已从 apt.postgresql.org 移到 apt-archive，旧基础镜像需改源才能装 pgvector
RUN if grep -q bullseye /etc/os-release; then \
		sed -i 's#apt.postgresql.org/pub/repos/apt#apt-archive.postgresql.org/pub/repos/apt#' /etc/apt/sources.list.d/*.list; \
	fi \
	&& apt-get update \
	&& apt-get install -y --no-install-recommends postgresql-16-pgvector \
	&& rm -rf /var/lib/apt/lists/*
