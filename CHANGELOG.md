# 更新日志

本文件记录 [mugsun-boot](https://github.com/mugsun/mugsun-boot) 的重要变更，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### 说明

- 当前仓库版本号为 `0.1.0`（见根 `pom.xml`），尚未打 GitHub Release tag
- 依赖 `com.mugsun` 已可从 Maven Central 拉取（`0.1.0`）

### 升级须知

- 埋点来源域名：Web SDK 只上报完整 `referrer` URL，此前服务端只读 `referrer_domain`，导致来源维度恒为空。现在没有 `referrer_domain` 时从 `referrer` 取主机名（小写，不剥 `www.`）；会话的来源域名和 `utm_source` 跟随最早的事件，同一会话的事件分批到达时也能回填，只对升级后接收的新事件生效，历史数据不回填

- 埋点日表历史 UV 回填：新增迁移 `T2`（备份表 `track_stats_day_uv_backup`）和可重复执行的回填任务，把 `track_stats_day` 历史 UV 改成 `distinct_id` 口径，默认最近 90 天。单个「应用 × 天」超时只跳过该天、不卡住其它应用，可按应用放宽超时；PowerJob 结果仅在全部完成时为成功。步骤见 [docs/upgrade/track-uv-backfill.md](docs/upgrade/track-uv-backfill.md)
- 埋点看板按租户时区切日（默认 `Asia/Shanghai`，配置 `mugsun.track.timezone.default-zone` / `MUGSUN_TRACK_TIMEZONE`，按租户编号覆盖 `mugsun.track.timezone.tenants`）：趋势图、错误列表、概览卡片、页面、性能、漏斗、留存的「今天」都从当地 0 点开始，之前按 UTC 切日。行为变化：
  - 漏斗时间窗从「最近 days×24 小时」滚动窗口改为从首日当地 0 点算起，`days=1` 即今天 0 点至今
  - UV 口径：日趋势里每天的 UV 是该当地日内去重设备数（`distinct_id`）；维度趋势未指定维度值时不再把各维度值的 UV 相加
  - 概览「会话数」= 每个当地日的活跃会话数之和
  - 时区不是 UTC 时，日趋势/概览/性能直接按明细统计（日表按 UTC 日汇总），大库上会比读日表慢；长期方案见 PR 说明里的 15 分钟预聚合设计

### 计划

- （暂无已定条目）

## [0.1.0] - 开发中

### 新增

- 模块化 Spring Boot 后端：`mugsun-boot-core` / `gis` / `track` / `ai` / `server`
- Docker Compose 仅启动 PostgreSQL、Redis 等依赖；应用由 Maven 启动
- 与 `mugsun-pc`、`mugsun-track` 联调路径见 README 与文档站

[Unreleased]: https://github.com/mugsun/mugsun-boot/commits/main
[0.1.0]: https://github.com/mugsun/mugsun-boot/tree/main
