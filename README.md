![mugsun-boot](https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/banners/repos/mugsun-boot.png)

# mugsun-boot

**中文** | [English](./README_EN.md)

本仓库为 mugsun 中后台单体后端。基础设施由 Docker Compose 提供，应用以可执行 JAR 对外服务。系统管理与工作流位于核心模块；地理信息、行为分析与人工智能能力以可选模块装配。

[文档](https://docs.mugsun.com/boot/) · [快速开始](https://docs.mugsun.com/guide/getting-started) · [安全清单](https://docs.mugsun.com/boot/security-checklist)

![Version](https://img.shields.io/badge/version-0.1.0-blue)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](./LICENSE)

## 概述

| 项 | 说明 |
| --- | --- |
| 坐标 | `com.mugsun:mugsun-boot:0.1.0` |
| 运行时 | JDK 21；Maven 3.9+ |
| 框架 | Spring Boot 3.5.13 |
| 数据层 | PostgreSQL 16（PostGIS + pgvector）；Redis 7（默认库号 `3`） |
| 依赖工程 | [mugsun-core](https://github.com/mugsun/mugsun-core) `com.mugsun:mugsun-*:0.1.0`（已发布至 [Maven Central](https://repo1.maven.org/maven2/com/mugsun/)） |
| 许可 | [MIT](./LICENSE) |
| 文档 | [docs.mugsun.com/boot](https://docs.mugsun.com/boot/) |

技术组件（以当前 POM 为准）：MyBatis-Flex、Sa-Token、JetCache、Warm-Flow、Flyway。AI 模块不依赖 Spring AI：模型、向量检索与 MCP 工具调用均由模块内的 HTTP 客户端和 pgvector 查询实现。

## 部署

前置条件：JDK 21、Maven 3.9+、Docker。`mugsun-core` 0.1.0 已发布至 Maven Central，构建时由 Maven 自动解析，无需平级 clone。

首次 `docker compose up -d` 会在本地构建数据库镜像（`postgis/postgis:16-3.4` + pgvector）。官方 PostGIS 镜像只有 amd64，Apple Silicon 上默认走仿真，首次构建要多等几分钟，运行也偏慢。想原生运行可换多架构基础镜像：

```bash
MUGSUN_PG_BASE_IMAGE=imresamu/postgis:16-3.4 MUGSUN_PG_PLATFORM=linux/arm64 docker compose up -d --build
```

```bash
docker compose up -d
cp config/application-local.yml.example config/application-local.yml
mvn -pl mugsun-boot-server -am package -DskipTests
java -jar mugsun-boot-server/target/mugsun-boot.jar
```

须在仓库根目录执行 `java -jar`，否则读不到 `config/application-local.yml`。开发时也可在根目录直接运行（可选）：`mvn -pl mugsun-boot-server -am spring-boot:run`。

> **请始终在仓库根目录、带 `-am` 构建**（或用 `mvn clean package -DskipTests` 构建整个工程）。只构建 server 模块（`-pl mugsun-boot-server` 不带 `-am`、在 `mugsun-boot-server` 目录里执行 `mvn package`，或 IDE 只构建该模块）时，`mugsun-boot-core` 会从本地 Maven 仓库 `~/.m2/repository/com/mugsun/mugsun-boot-core/0.1.0/` 取。所有分支和工作区共用这一个 0.1.0 版本，里面是最后一次 `mvn install` 的那个分支，打出的 jar 会带上别的分支的旧代码（例如旧的验证码返回结构），这跟加不加 `clean` 无关。拿不准时先在根目录执行一次 `mvn -B install -DskipTests`。

**本地 install（离线或未同步 Central 时的回退）：** 平级克隆 [mugsun-core](https://github.com/mugsun/mugsun-core) 后执行 `cd ../mugsun-core && mvn clean install -DskipTests`，再返回本仓库构建。

健康检查：http://localhost:8080/ping

默认初始账号：`admin` / `123456`。初始口令由代码读取 `mugsun.security.init-password`，未配置时读环境变量 `MUGSUN_INIT_PASSWORD`（见 `SecurityPolicyService`）。`application.yml` 里不写这一项是有意的：prod 启动守卫据此判断口令是否被显式注入。生产环境必须更换口令；`prod` 配置未注入初始口令时拒绝启动。

管理端见 [mugsun-pc](https://github.com/mugsun/mugsun-pc)（开发默认端口 `3006`，代理至本服务 `8080`）。逐步说明见 [文档 · 快速开始](https://docs.mugsun.com/guide/getting-started)。

打包产物：`mugsun-boot-server/target/mugsun-boot.jar`。

Compose 暴露端口：`5432`（PostgreSQL）、`6379`（Redis）。本机已有 PostgreSQL / Redis 占用这两个端口时，换端口启动并让后端用同一变量：

```bash
export MUGSUN_DB_PORT=55432 MUGSUN_REDIS_PORT=56379
docker compose up -d
java -jar mugsun-boot-server/target/mugsun-boot.jar
```

后端可用的环境变量：`MUGSUN_DB_HOST`、`MUGSUN_DB_PORT`、`MUGSUN_DB_NAME`、`MUGSUN_DB_USERNAME`、`MUGSUN_DB_PASSWORD`（或整条 `MUGSUN_DB_URL`）、`MUGSUN_REDIS_HOST`、`MUGSUN_REDIS_PORT`；埋点库 `mugsun_track` 默认跟随同一主机端口，仍可用 `TRACK_JDBC_URL` 单独覆盖。`prod` 配置不读这些变量，仍用 `JDBC_URL`、`REDIS_HOST` 等。开发库账号默认与 JDBC 配置一致（`mugsun` / `mugsun`）。无 Docker 时可参考 `scripts/init-db.sql`。

本地配置文件路径：`optional:file:./config/application-local.yml`（已列入 `.gitignore`）。工作目录须为本仓库根目录。生产配置注入说明见 [配置](https://docs.mugsun.com/boot/configuration)。

OpenAPI：http://localhost:8080/swagger-ui/index.html（`prod` 下关闭）。

## 安全验证（行为验证码）

登录、注册、忘记密码发码、发送短信验证码默认需要行为验证（自研，无第三方依赖；平台默认题型为滑块拼图与词语点选，服务端随机出一种）。流程：`GET /auth/captcha/config` 提示是否需要 → `POST /auth/captcha/challenge` 取题 → `POST /auth/captcha/verify` 换一次性 `captchaToken` → 业务接口请求体带 `captchaToken`。业务接口按服务端解析的租户与客户端重新判定，`config` 只是提示。错误码（HTTP 200 + `R.code`）：`40010` 过期、`40011` 未通过、`40012` 限流（`data.retryAfter` 秒）、`40013` 缺少有效令牌、`40014` 当前无需验证。

| 配置键 | 默认 | 说明 |
|---|---|---|
| `mugsun.captcha.enabled` | `true` | 全局开关；`false` 时所有场景都不要验证码（prod 配置为 true） |
| `mugsun.captcha.default-types` | `SLIDER,WORD_CLICK,ROTATE,ICON_CLICK,JIGSAW` | 租户未单独设置时启用的题型：`SLIDER`、`ROTATE`、`WORD_CLICK`、`ICON_CLICK`、`JIGSAW`（不区分大小写）。默认五种全开、随机出题。非法值启动失败 |
| `mugsun.captcha.type` | 不设 | 已废弃：只认 `SLIDER`（不起作用，启动打 WARN）。旧数字码 `IMAGE` 已在 v0.6.3 移除，写 `IMAGE` 或其它值启动失败 |
| `mugsun.captcha.store` | `redis` | `redis` 或 `memory`；`memory` 仅单机开发，`prod` profile 下启动失败 |
| `mugsun.captcha.slider.width` / `height` | `320` / `180` | 底图原始尺寸 |
| `mugsun.captcha.slider.tolerance-px` | `6` | 落点与轨迹位移容差（原始像素） |
| `mugsun.captcha.slider.expire-seconds` / `token-expire-seconds` | `120` / `120` | 挑战与令牌有效期 |
| `mugsun.captcha.slider.min-duration-ms` | `300` | 服务端计时下限（签发→校验） |
| `mugsun.captcha.slider.decoy-count` | `1` | 假缺口数量（0–2） |
| `mugsun.captcha.slider.keyboard-step-px` | `4` | 键盘模式每次按键移动的原始像素（challenge 返回 `keyboardStep`） |
| `mugsun.captcha.slider.bind-ip` / `bind-ua` | `false` / `false`（prod 的 `bind-ua` 为 `true`） | 令牌强制绑定 IP / UA 哈希（关闭时只记录） |
| `mugsun.captcha.background.dir` | `classpath:captcha/bg/` | 背景图目录（`file:` 亦可），jpg/png，推荐 640×360；为空用纯色占位 |
| `mugsun.captcha.background.rotate` | `random` | `random` 或 `round-robin` |
| `mugsun.captcha.background.reload-seconds` | `600` | 目录重扫间隔，0 只在启动时加载 |
| `mugsun.captcha.rate-limit.*` | 见 `application.yml` | 单 IP 取挑战/分钟、全局每秒上限（Redis 跨节点）、失败锁定（键盘模式更严） |
| `mugsun.captcha.rate-limit.sms-phone-per-day` / `sms-ip-per-hour` | `10` / `20` | 发送短信验证码频控（叠加同号 60 秒节流），与验证码开关无关 |

租户开关：`sys_tenant.captcha_enabled`（`V9__tenant_captcha.sql`，默认 1，新建租户一律开启），仅平台超管可通过 `POST /system/tenant/captcha` 修改并写入审计日志；多节点时其他节点最多 60 秒后生效。发送短信验证码与租户无关，只看全局开关。客户端开关 `sys_client.captcha_enabled` 不参与判定（clientId 由请求方自选）。输入方式（`inputMode`）在取题时声明，校验时不一致即失败。App 端 `/app/captcha/*` 接口形状不变，内部共用同一引擎。

## 组成

<p align="center">
  <img src="https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/arch/boot-iso-zh.jpg" alt="架构示意" width="100%" />
</p>

```text
mugsun-boot/
  mugsun-boot-core/     # 认证、系统、流程、租户、开放平台等
  mugsun-boot-gis/      # 可选：图层、空间查询、矢量瓦片
  mugsun-boot-track/    # 可选：采集摄入、聚合、回放相关 API
  mugsun-boot-ai/       # 可选：模型、对话、知识库、调用外部 MCP 工具
  mugsun-boot-server/   # 装配壳；默认 profile = full
```

Maven Profile：

| Profile | 说明 |
| --- | --- |
| `full`（默认） | 装配 core + gis + track + ai |
| `basic` | 仅核心；须同时关闭 full：`-Pbasic,'!full'` |
| `gis-only` / `track-only` / `ai-only` | 核心 + 单一可选模块；须关闭 full |
| `kingbase` / `dameng` | 信创数据库驱动 |

运行时可通过环境变量关闭能力（JAR 仍可能包含对应类路径）。不设置按开启；设置时只接受 `true` 或 `false`（不区分大小写，前后不能有空格），空值、`1` / `0`、`yes` / `no` 等其它值都会启动失败：

- `MUGSUN_GIS_ENABLED=false`
- `MUGSUN_TRACK_ENABLED=false`
- `MUGSUN_AI_ENABLED=false`

内置 MCP Server 暂时下线，正在加鉴权后恢复。下线期间 `/sse`、`/mcp` 返回 404，`MUGSUN_AI_MCP_SERVER` 不起作用；根 POM 的 maven-enforcer 规则暂时拦下 Spring AI（`org.springframework.ai:*`）与 MCP SDK（`io.modelcontextprotocol*`），包括传递依赖。

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
- `mugsun-boot-ai`：模型、对话、知识库，以及在编排中调用外部 MCP 工具（内置 MCP Server 暂时下线，正在加鉴权后恢复）

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

[MIT](./LICENSE) · 版权见 [NOTICE](./NOTICE) · © 2026 北京定旭晨 / mugsun
