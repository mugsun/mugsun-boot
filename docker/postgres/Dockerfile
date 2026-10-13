# 交付库：PostGIS + pgvector。官方 postgis 镜像不带 vector 类型，
# AI 迁移在无扩展时会回落，但本镜像要求两者都在，缺一容器起不来。
# 官方 postgis/postgis 仅 amd64；Apple Silicon 可换多架构镜像 imresamu/postgis:16-3.4（同为 Debian + PGDG，见 README）
ARG POSTGIS_IMAGE=postgis/postgis:16-3.4
FROM ${POSTGIS_IMAGE}

USER root
RUN apt-get update \
	&& apt-get install -y --no-install-recommends postgresql-16-pgvector \
	&& rm -rf /var/lib/apt/lists/*
