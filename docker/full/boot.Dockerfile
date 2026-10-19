# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
# 一条命令起全套用的后端镜像：构建上下文是 mugsun-boot 仓库根目录，mugsun-core 0.1.0 从 Maven Central 解析。
# 带 -Pfull,kingbase,dameng（显式 -P 会关掉默认激活的 full，所以 full 要写上）：镜像内置人大金仓 / 达梦驱动，换库只改 JDBC_URL，不必重建。
# 基础镜像固定 digest（多架构 index；升级时同步更新 tag 与 digest）
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /build
COPY pom.xml ./
COPY mugsun-boot-core/ mugsun-boot-core/
COPY mugsun-boot-gis/ mugsun-boot-gis/
COPY mugsun-boot-track/ mugsun-boot-track/
COPY mugsun-boot-ai/ mugsun-boot-ai/
COPY mugsun-boot-server/ mugsun-boot-server/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -Pfull,kingbase,dameng -pl mugsun-boot-server -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
# 固定 uid/gid 10001：init 按它 chown 后端专用的密钥文件（0600）
RUN groupadd -r -g 10001 mugsun && useradd -r -u 10001 -g mugsun -d /app mugsun \
    && mkdir -p /app/logs /var/lib/mugsun/files \
    && chown -R mugsun:mugsun /app /var/lib/mugsun
COPY --from=build --chown=mugsun:mugsun /build/mugsun-boot-server/target/mugsun-boot.jar /app/app.jar
USER mugsun
WORKDIR /app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0" SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
# 先读首启生成的后端专用密钥文件（只读挂载、0600 属 mugsun），再以 java 为 PID 1 启动
ENTRYPOINT ["sh", "-c", ". /secrets/boot.env && exec java $JAVA_OPTS -jar /app/app.jar"]
