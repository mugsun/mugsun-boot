# mugsun-boot

**中文** | [English](./README_EN.md)

可私有化的中后台后端：一条 compose 起库，一个 jar 对外服务。  
系统管理与工作流打底；GIS、埋点、AI 按模块装配，数据留在你机器上。

[文档](https://docs.mugsun.com/boot/) · [快速开始](https://docs.mugsun.com/guide/getting-started) · [安全清单](https://docs.mugsun.com/boot/security-checklist)

![Version](https://img.shields.io/badge/version-0.1.0-blue)
![JDK](https://img.shields.io/badge/JDK-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

前置：JDK 21、Maven 3.9+、Docker；与 [mugsun-core](https://github.com/mugsun/mugsun-core) **平级** clone（core 未发中央仓，须先本地 `mvn install`）。

```bash
cd ../mugsun-core && mvn clean install -DskipTests && cd ../mugsun-boot
docker compose up -d
cp config/application-local.yml.example config/application-local.yml
mvn -pl mugsun-boot-server -am spring-boot:run
```

打开 http://localhost:8080/ping 。默认 **`admin` / `123456`**（`MUGSUN_INIT_PASSWORD` 可覆盖）。生产必须改密；`prod` 未注入初始口令会拒绝启动。管理台：[mugsun-pc](https://github.com/mugsun/mugsun-pc)（`:3006` → `:8080`）。逐步说明：[快速开始](https://docs.mugsun.com/guide/getting-started)。目前没有公网 Demo，以本地 compose 为准。

## 它解决什么

boot 提供可带走的单体底座：Postgres + Redis + 本仓可执行应用。认证、权限、租户、工作流、开放平台在 core；地理信息、行为分析、AI 是可选模块，可用 Maven profile 或环境变量裁掉。免费开源主线是 `boot` · `core` · `pc` · `track`；微服务 `cloud` 与 uniappx-ui 仍在规划或商业意向，不要当成已随本仓开源的依赖。

技术基线（当前 POM）：JDK 21、Spring Boot **3.5.13**、本仓 / core **0.1.0**、MyBatis-Flex、Sa-Token、JetCache、Warm-Flow、Flyway。主推 PostgreSQL 16（compose 含 PostGIS + pgvector），Redis 默认库号 `3`。

## 架构

<p align="center">
  <img src="https://raw.githubusercontent.com/mugsun/mugsun-docs/main/public/brand/arch/boot-iso-zh.jpg" alt="Architecture" width="100%" />
</p>

```text
mugsun-boot/
  mugsun-boot-core/     # 认证、系统、流程、租户、开放平台…
  mugsun-boot-gis/      # 可选 GIS
  mugsun-boot-track/    # 可选埋点域
  mugsun-boot-ai/       # 可选 AI（Spring AI）
  mugsun-boot-server/   # 装配壳；默认 profile = full
```

默认 `full`；`-Pbasic,'!full'` 只要核心；另有 `gis-only` / `track-only` / `ai-only` 与信创库 profile。运行时可用 `MUGSUN_GIS_ENABLED` / `MUGSUN_TRACK_ENABLED` / `MUGSUN_AI_ENABLED=false` 关闭能力。见 [可选模块装配](docs/可选模块装配.md)。

## 模块说明

core 覆盖用户角色菜单、数据权限、审计、消息、文件、代码生成与在线表单、Warm-Flow、开放平台（OAuth2 + PKCE、API Key）等；传输与审计含国密相关能力，上线前对照 [安全清单](https://docs.mugsun.com/boot/security-checklist)。track 模块提供 `/track/collect` 与分析相关 API，和 [track SDK](https://github.com/mugsun/mugsun-track)、pc 看板组成竖切。GIS / AI 仅在启用后计入对外能力。

PowerJob Worker 默认关闭（需独立 Server + `POWERJOB_ENABLED=true`）。OpenAPI：http://localhost:8080/swagger-ui/index.html（`prod` 关闭）。接口与配置键以源码和文档为准。

## 相关仓库

| 仓 | 关系 |
| --- | --- |
| [mugsun-core](https://github.com/mugsun/mugsun-core) | BOM / starter；构建前 `mvn install` |
| [mugsun-pc](https://github.com/mugsun/mugsun-pc) | 管理端；`MUGSUN_FRONT_URL` 默认 `http://localhost:3006` |
| [mugsun-track](https://github.com/mugsun/mugsun-track) | 浏览器 SDK；本仓 track 模块接收采集 |
| [mugsun-docs](https://github.com/mugsun/mugsun-docs) | [docs.mugsun.com](https://docs.mugsun.com/) |

## 本地开发

工作目录须为本仓根目录，以便加载 `config/application-local.yml`（gitignore；示例里的 SM2 占位避免每次重启密钥漂移）。

```bash
mvn -pl mugsun-boot-server -am package -DskipTests
java -jar mugsun-boot-server/target/mugsun-boot.jar
```

compose：`5432` / `6379`；库账号默认 `mugsun` / `mugsun`。无 Docker 时可用 `scripts/init-db.sql`。演示账号仅用于本地；`fronttest` 默认不播种。配置见 [文档 · 配置](https://docs.mugsun.com/boot/configuration)。

## License

[Apache-2.0](./LICENSE)
