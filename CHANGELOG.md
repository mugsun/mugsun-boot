# 更新日志

本文件记录 [mugsun-boot](https://github.com/mugsun/mugsun-boot) 的重要变更，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### 说明

- 当前仓库版本号为 `0.1.0`（见根 `pom.xml`），尚未打 GitHub Release tag
- 依赖 `com.mugsun` 已可从 Maven Central 拉取（`0.1.0`）

### 计划

- （暂无已定条目）

## [0.1.0] - 开发中

### 新增

- 模块化 Spring Boot 后端：`mugsun-boot-core` / `gis` / `track` / `ai` / `server`
- Docker Compose 仅启动 PostgreSQL、Redis 等依赖；应用由 Maven 启动
- 与 `mugsun-pc`、`mugsun-track` 联调路径见 README 与文档站

[Unreleased]: https://github.com/mugsun/mugsun-boot/commits/main
[0.1.0]: https://github.com/mugsun/mugsun-boot/tree/main
