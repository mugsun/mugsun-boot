# syntax=docker/dockerfile:1
# 一条命令起全套用的后端镜像：构建上下文是 mugsun-boot 仓库根目录，mugsun-core 0.1.0 从 Maven Central 解析。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY mugsun-boot-core/ mugsun-boot-core/
COPY mugsun-boot-gis/ mugsun-boot-gis/
COPY mugsun-boot-track/ mugsun-boot-track/
COPY mugsun-boot-ai/ mugsun-boot-ai/
COPY mugsun-boot-server/ mugsun-boot-server/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl mugsun-boot-server -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN groupadd -r mugsun && useradd -r -g mugsun -d /app mugsun \
    && mkdir -p /app/logs /var/lib/mugsun/files \
    && chown -R mugsun:mugsun /app /var/lib/mugsun
COPY --from=build --chown=mugsun:mugsun /build/mugsun-boot-server/target/mugsun-boot.jar /app/app.jar
USER mugsun
WORKDIR /app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0" SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
# 先读首启生成的密钥文件（只读挂载），再以 java 为 PID 1 启动
ENTRYPOINT ["sh", "-c", ". /secrets/mugsun.env && exec java $JAVA_OPTS -jar /app/app.jar"]
