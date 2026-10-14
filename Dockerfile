# syntax=docker/dockerfile:1
# ============================================================
# Mugsun 后端一体化镜像（多阶段构建）
#
# 构建上下文必须是四仓共同的父目录（与 CI 一致的平级布局）：
#   mugsun/
#   ├── mugsun-core/    ← 本镜像会先行 install
#   └── mugsun-boot/    ← 本仓库
#
# 构建（在父目录或本仓库执行均可）：
#   docker build -f mugsun-boot/Dockerfile -t mugsun/boot:latest <父目录>
# 日常开发先起基础设施：在本仓库执行 `docker compose up -d`（Postgres + Redis）。
# 应用本体默认在宿主机 `mvn spring-boot:run` / `java -jar`，以便加载 config/application-local.yml。
# ============================================================

# ---------- 阶段 1：源码构建 ----------
# 基础镜像固定 digest（多架构 index；升级时同步更新 tag 与 digest）
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /build

# 后端带 -Pkingbase,dameng 构建：镜像内置人大金仓 / 达梦 JDBC 驱动，换库只改 JDBC_URL。
# 先装核心底座（mugsun-starter-* 未发布中央仓库，须本地 install）
# 单独 COPY 充分利用层缓存：core 不变时跳过本层
COPY mugsun-core/ mugsun-core/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -f mugsun-core/pom.xml install -DskipTests

COPY mugsun-boot/ mugsun-boot/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -Pkingbase,dameng -f mugsun-boot/pom.xml -pl mugsun-boot-server -am package -DskipTests

# ---------- 阶段 2：精简运行时（非 root） ----------
FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b

LABEL org.opencontainers.image.title="mugsun-boot" \
      org.opencontainers.image.description="Mugsun 快速开发平台后端" \
      org.opencontainers.image.source="https://github.com/mugsun/mugsun-boot"

RUN groupadd -r mugsun && useradd -r -g mugsun -d /app mugsun \
    && mkdir -p /app/logs /var/lib/mugsun/files \
    && chown -R mugsun:mugsun /app /var/lib/mugsun

COPY --from=build --chown=mugsun:mugsun /build/mugsun-boot/mugsun-boot-server/target/mugsun-boot.jar /app/app.jar

USER mugsun
WORKDIR /app

# 容器内内存按 cgroup 配额自适应；JAVA_OPTS 可在 compose / k8s 覆盖追加
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom" \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

# exec 保证 java 为 PID 1，SIGTERM 直达，配合 server.shutdown=graceful 优雅停机
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
