# mugsun-boot

**中文** | [English](./README_EN.md)

可私有化部署的中后台后端服务。提供系统管理与工作流等基础能力；地理信息、行为分析与人工智能以可选模块提供。

[文档](https://docs.mugsun.com/boot/) · [快速开始](https://docs.mugsun.com/guide/getting-started) · [安全清单](https://docs.mugsun.com/boot/security-checklist)

![Version](https://img.shields.io/badge/version-0.1.0-blue)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

## 运行环境与启动

需要 JDK 21、Maven 3.9 及以上、Docker，并与 [mugsun-core](https://github.com/mugsun/mugsun-core) 仓库并列克隆。core 尚未发布至 Maven Central，构建前须执行本地安装。

```bash
cd ../mugsun-core && mvn clean install -DskipTests && cd ../mugsun-boot
docker compose up -d
cp config/application-local.yml.example config/application-local.yml
mvn -pl mugsun-boot-server -am spring-boot:run
```

服务默认监听 http://localhost:8080 。健康检查：`GET /ping`。初始管理员账号为 `admin`，初始口令为 `123456`，可通过环境变量 `MUGSUN_INIT_PASSWORD` 覆盖。生产环境须修改口令；在 `prod` 配置下未注入初始口令时，进程将拒绝启动。管理端见 [mugsun-pc](https://github.com/mugsun/mugsun-pc)。操作说明见 [快速开始](https://docs.mugsun.com/guide/getting-started)。公网演示环境尚未提供。

## 概述

本仓库以单体应用形式交付：依赖 PostgreSQL 与 Redis，输出可执行服务。认证、权限、多租户、工作流与开放平台位于 core 模块；地理信息、行为分析与人工智能为可选模块，可通过 Maven profile 或环境变量裁剪。

免费开源组成部分为 boot、core、pc、track。微服务版本（cloud）与 uniappx-ui 尚处于规划或商业安排阶段，不作为本仓库的已发布开源依赖。

技术基线（以当前 POM 为准）：JDK 21、Spring Boot 3.5.13、本仓库与 core 版本 0.1.0、MyBatis-Flex、Sa-Token、JetCache、Warm-Flow、Flyway。数据库主推 PostgreSQL 16（compose 镜像包含 PostGIS 与 pgvector）；Redis 默认使用逻辑库号 3。

## 架构

<p align="center">
  <img src="https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/arch/boot-iso-zh.jpg" alt="Architecture" width="100%" />
</p>

```text
mugsun-boot/
  mugsun-boot-core/      # 认证、系统管理、流程、租户、开放平台等
  mugsun-boot-gis/       # 可选：地理信息
  mugsun-boot-track/     # 可选：行为分析
  mugsun-boot-ai/        # 可选：人工智能（Spring AI）
  mugsun-boot-server/    # 装配入口；默认 profile 为 full
```

默认 profile 为 `full`。仅核心能力时使用 `-Pbasic,'!full'`。另提供 `gis-only`、`track-only`、`ai-only` 及信创数据库相关 profile。运行期可通过 `MUGSUN_GIS_ENABLED`、`MUGSUN_TRACK_ENABLED`、`MUGSUN_AI_ENABLED` 控制模块启用状态。详见 [可选模块装配](docs/可选模块装配.md)。

## 模块说明

core 模块覆盖用户与权限、数据权限、审计、消息、文件、代码生成与在线表单、Warm-Flow 工作流、开放平台（OAuth2 授权码与 PKCE、API 密钥）等。传输与审计链路包含国密相关能力；上线前须按 [安全清单](https://docs.mugsun.com/boot/security-checklist) 核对。

track 模块提供 `/track/collect` 及分析相关接口，与 [mugsun-track](https://github.com/mugsun/mugsun-track)、mugsun-pc 组成采集至展示的完整路径。gis 与 ai 模块仅在启用后纳入对外能力范围。

PowerJob Worker 默认关闭，启用时须另行部署 Server 并设置 `POWERJOB_ENABLED=true`。OpenAPI 文档地址：http://localhost:8080/swagger-ui/index.html（`prod` 环境关闭）。接口与配置项以源码及文档为准。

## 相关仓库

| 仓库 | 说明 |
| --- | --- |
| [mugsun-core](https://github.com/mugsun/mugsun-core) | BOM 与 starter；构建前须本地安装 |
| [mugsun-pc](https://github.com/mugsun/mugsun-pc) | 管理端；默认前端地址由 `MUGSUN_FRONT_URL` 配置（开发环境常用 `http://localhost:3006`） |
| [mugsun-track](https://github.com/mugsun/mugsun-track) | 浏览器端采集 SDK |
| [mugsun-docs](https://github.com/mugsun/mugsun-docs) | 文档站点 |

## 本地构建说明

须在本仓库根目录执行构建与启动，以便加载 `config/application-local.yml`（该文件已列入 gitignore）。

```bash
mvn -pl mugsun-boot-server -am package -DskipTests
java -jar mugsun-boot-server/target/mugsun-boot.jar
```

compose 默认映射端口：PostgreSQL `5432`，Redis `6379`。开发库账号默认与 JDBC 配置一致。无 Docker 时可使用 `scripts/init-db.sql`。初始账号仅用于本地验证；`fronttest` 账号默认不创建。配置说明见 [文档 · 配置](https://docs.mugsun.com/boot/configuration)。

## 许可证

[Apache-2.0](./LICENSE)
