![mugsun-boot](https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/banners/repos/mugsun-boot.png)

# mugsun-boot

**中文** | [English](./README_EN.md)

本仓库为 mugsun 中后台单体后端。基础设施由 Docker Compose 提供，应用以可执行 JAR 对外服务。系统管理与工作流位于核心模块；地理信息、行为分析与人工智能能力以可选模块装配。

[文档](https://docs.mugsun.com/boot/) · [快速开始](https://docs.mugsun.com/guide/getting-started) · [安全清单](https://docs.mugsun.com/boot/security-checklist)

![Version](https://img.shields.io/badge/version-0.1.0-blue)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

## 概述

| 项 | 说明 |
| --- | --- |
| 坐标 | `com.mugsun:mugsun-boot:0.1.0` |
| 运行时 | JDK 21；Maven 3.9+ |
| 框架 | Spring Boot 3.5.13 |
| 数据层 | PostgreSQL 16（PostGIS + pgvector）；Redis 7（默认库号 `3`） |
| 依赖工程 | [mugsun-core](https://github.com/mugsun/mugsun-core) `com.mugsun:mugsun-*:0.1.0`（已发布至 [Maven Central](https://repo1.maven.org/maven2/com/mugsun/)） |
| 许可 | [Apache-2.0](./LICENSE) |
| 文档 | [docs.mugsun.com/boot](https://docs.mugsun.com/boot/) |

技术组件（以当前 POM 为准）：MyBatis-Flex、Sa-Token、JetCache、Warm-Flow、Flyway、Spring AI 1.1.2（AI 模块）。

## 部署

前置条件：JDK 21、Maven 3.9+、Docker。`mugsun-core` 0.1.0 已发布至 Maven Central，构建时由 Maven 自动解析，无需平级 clone。

```bash
docker compose up -d
cp config/application-local.yml.example config/application-local.yml
mvn -pl mugsun-boot-server -am package -DskipTests
java -jar mugsun-boot-server/target/mugsun-boot.jar
```

须在仓库根目录执行 `java -jar`，否则读不到 `config/application-local.yml`。开发时也可在根目录直接运行（可选）：`mvn -pl mugsun-boot-server -am spring-boot:run`。

**本地 install（离线或未同步 Central 时的回退）：** 平级克隆 [mugsun-core](https://github.com/mugsun/mugsun-core) 后执行 `cd ../mugsun-core && mvn clean install -DskipTests`，再返回本仓库构建。

健康检查：http://localhost:8080/ping

默认初始账号：`admin` / `123456`（可通过环境变量 `MUGSUN_INIT_PASSWORD` 覆盖）。生产环境必须更换口令；`prod` 配置未注入初始口令时拒绝启动。

管理端见 [mugsun-pc](https://github.com/mugsun/mugsun-pc)（开发默认端口 `3006`，代理至本服务 `8080`）。逐步说明见 [文档 · 快速开始](https://docs.mugsun.com/guide/getting-started)。

打包产物：`mugsun-boot-server/target/mugsun-boot.jar`。

Compose 暴露端口：`5432`（PostgreSQL）、`6379`（Redis）。开发库账号默认与 JDBC 配置一致（`mugsun` / `mugsun`）。无 Docker 时可参考 `scripts/init-db.sql`。

本地配置文件路径：`optional:file:./config/application-local.yml`（已列入 `.gitignore`）。工作目录须为本仓库根目录。生产配置注入说明见 [配置](https://docs.mugsun.com/boot/configuration)。

OpenAPI：http://localhost:8080/swagger-ui/index.html（`prod` 下关闭）。

## 安全验证（滑块验证码）

登录、注册、忘记密码发码、发送短信验证码默认需要滑块拼图验证（自研，无第三方依赖）。流程：`GET /auth/captcha/config` 提示是否需要 → `POST /auth/captcha/challenge` 取底图/拼图块 → `POST /auth/captcha/verify` 换一次性 `captchaToken` → 业务接口请求体带 `captchaToken`。业务接口按服务端解析的租户与客户端重新判定，`config` 只是提示。错误码（HTTP 200 + `R.code`）：`40010` 过期、`40011` 未通过、`40012` 限流（`data.retryAfter` 秒）、`40013` 缺少有效令牌、`40014` 当前无需验证。

| 配置键 | 默认 | 说明 |
|---|---|---|
| `mugsun.captcha.enabled` | `true` | 全局开关（prod 配置为 true） |
| `mugsun.captcha.type` | `SLIDER` | `SLIDER` 或 `IMAGE`（旧数字码，保留一个版本）；只由服务端决定 |
| `mugsun.captcha.store` | `redis` | `redis` 或 `memory`；`memory` 仅单机开发，`prod` profile 下启动失败 |
| `mugsun.captcha.slider.width` / `height` | `320` / `180` | 底图原始尺寸 |
| `mugsun.captcha.slider.tolerance-px` | `6` | 落点与轨迹位移容差（原始像素） |
| `mugsun.captcha.slider.expire-seconds` / `token-expire-seconds` | `120` / `120` | 挑战与令牌有效期 |
| `mugsun.captcha.slider.min-duration-ms` | `300` | 服务端计时下限（签发→校验） |
| `mugsun.captcha.slider.decoy-count` | `1` | 假缺口数量（0–2） |
| `mugsun.captcha.slider.bind-ip` / `bind-ua` | `false` | 令牌强制绑定 IP / UA 哈希（默认只记录） |
| `mugsun.captcha.background.dir` | `classpath:captcha/bg/` | 背景图目录（`file:` 亦可），jpg/png，推荐 640×360；为空用纯色占位 |
| `mugsun.captcha.background.rotate` | `random` | `random` 或 `round-robin` |
| `mugsun.captcha.background.reload-seconds` | `600` | 目录重扫间隔，0 只在启动时加载 |
| `mugsun.captcha.rate-limit.*` | 见 `application.yml` | 单 IP 取挑战/分钟、全局令牌桶、失败锁定（键盘模式更严） |

租户开关：`sys_tenant.captcha_enabled`（`V4__tenant_captcha.sql`，默认 1），仅平台超管可通过 `POST /system/tenant/captcha` 修改并写入审计日志。客户端开关 `sys_client.captcha_enabled` 仍只作用于登录。App 端 `/app/captcha/*` 接口形状不变，内部共用同一引擎。

## 组成

<p align="center">
  <img src="https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/arch/boot-iso-zh.jpg" alt="架构示意" width="100%" />
</p>

```text
mugsun-boot/
  mugsun-boot-core/     # 认证、系统、流程、租户、开放平台等
  mugsun-boot-gis/      # 可选：图层、空间查询、矢量瓦片
  mugsun-boot-track/    # 可选：采集摄入、聚合、回放相关 API
  mugsun-boot-ai/       # 可选：模型、对话、知识库、MCP（Spring AI）
  mugsun-boot-server/   # 装配壳；默认 profile = full
```

Maven Profile：

| Profile | 说明 |
| --- | --- |
| `full`（默认） | 装配 core + gis + track + ai |
| `basic` | 仅核心；须同时关闭 full：`-Pbasic,'!full'` |
| `gis-only` / `track-only` / `ai-only` | 核心 + 单一可选模块；须关闭 full |
| `kingbase` / `dameng` | 信创数据库驱动 |

运行时可通过环境变量关闭能力（JAR 仍可能包含对应类路径）：

- `MUGSUN_GIS_ENABLED=false`
- `MUGSUN_TRACK_ENABLED=false`
- `MUGSUN_AI_ENABLED=false`

模块装配说明见 [docs/可选模块装配.md](docs/可选模块装配.md)。

## 能力范围

**核心模块（mugsun-boot-core）**

- 认证与权限：用户、角色、菜单、部门、数据权限；登录与操作审计
- 多租户、消息、文件存储、代码生成、在线表单
- 工作流（Warm-Flow）
- 开放平台：OAuth2（含 PKCE）、API Key
- 传输与审计链路含国密相关能力；上线前须对照 [安全清单](https://docs.mugsun.com/boot/security-checklist)

**可选模块**

- `mugsun-boot-track`：`/track/collect`、配置下发与分析相关 API，与浏览器 SDK、管理端看板构成完整链路
- `mugsun-boot-gis`：空间数据与矢量瓦片（依赖 PostGIS）
- `mugsun-boot-ai`：模型、对话、知识库与 MCP（Spring AI）

**运行约定**

- PowerJob Worker 默认关闭；启用须独立部署 Server，并设置 `POWERJOB_ENABLED=true`
- 演示口令仅用于本地；`fronttest` 实验室数据须显式打开开关后方可播种，默认关闭

**暂未提供**

- 已开源的微服务 `cloud` 发行版
- 可公开访问的在线演示环境（见 [演示说明](https://docs.mugsun.com/demo)）

接口与配置键以源码及文档为准。

## 关联项目

开源主线：`boot` · `core` · `pc` · `track` · [mugsun-docs](https://github.com/mugsun/mugsun-docs)。

| 仓库 | 与本仓库的关系 |
| --- | --- |
| [mugsun-core](https://github.com/mugsun/mugsun-core) | BOM 与 starter；默认经 Maven Central 引入，本地 install 仅作离线/开发回退 |
| [mugsun-pc](https://github.com/mugsun/mugsun-pc) | 管理端；CORS / OAuth 回跳默认 `MUGSUN_FRONT_URL=http://localhost:3006` |
| [mugsun-track](https://github.com/mugsun/mugsun-track) | 浏览器 SDK；由本仓库 track 模块接收采集流量 |
| cloud / uniappx-ui | 规划或商业意向，非本仓库已开源依赖 |

## 许可

[Apache-2.0](./LICENSE) · 版权见 [NOTICE](./NOTICE) · © 2026 北京定旭晨 / mugsun
