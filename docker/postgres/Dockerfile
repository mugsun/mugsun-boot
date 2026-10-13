# 交付库：PostGIS + pgvector。官方 postgis 镜像不带 vector 类型，
# AI 迁移在无扩展时会回落，但本镜像要求两者都在，缺一容器起不来。
# POSTGIS_IMAGE 可换成多架构镜像（如 imresamu/postgis:16-3.4，Apple Silicon 原生 arm64）。
ARG POSTGIS_IMAGE=postgis/postgis:16-3.4
FROM ${POSTGIS_IMAGE}

USER root
# bullseye 已从 apt.postgresql.org 移到 apt-archive，旧基础镜像需改源才能装 pgvector
RUN if grep -q bullseye /etc/os-release; then \
		sed -i 's#apt.postgresql.org/pub/repos/apt#apt-archive.postgresql.org/pub/repos/apt#' /etc/apt/sources.list.d/*.list; \
	fi \
	&& apt-get update \
	&& apt-get install -y --no-install-recommends postgresql-16-pgvector \
	&& rm -rf /var/lib/apt/lists/*
