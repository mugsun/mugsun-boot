# 更新日志

本文件记录 [mugsun-boot](https://github.com/mugsun/mugsun-boot) 的重要变更，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### 说明

- 许可证由 Apache-2.0 改为 MIT（见 `LICENSE`）
- 当前仓库版本号为 `0.1.0`（见根 `pom.xml`），尚未打 GitHub Release tag
- 依赖 `com.mugsun` 已可从 Maven Central 拉取（`0.1.0`）

### 升级须知

- **破坏性变更：内置 MCP Server 已移除**（mugsun-boot#89）。Spring AI 与 MCP SDK 整体移出依赖，`/sse`、`/mcp`、`/mcp/message` 不再存在（任何配置下都返回 404），`MUGSUN_AI_MCP_SERVER` 等相关变量与 `spring.ai.mcp.server.*` 配置不再有任何作用。原因：Spring AI 的 MCP Server 自动装配不经过本项目鉴权，打开即是匿名可调的端点。AI 模块调用外部 MCP 工具（「MCP 工具箱」、工作流 MCP 节点）不受影响。根 pom 的 maven-enforcer `bannedDependencies` 禁止 `org.springframework.ai`、`io.modelcontextprotocol*`（含传递依赖）重新引入，`scripts/check-banned-dependencies.sh` 在 CI 中证明规则会触发；任何 pom 都不允许写 `enforcer.skip`（`NoEnforcerSkipGuardTest`）。曾通过内置 MCP Server 对外提供工具的部署，需要改为自建 MCP 服务
- 埋点错误堆栈：顶层 `props.stack` 的保留长度从 1024 放宽到 4096 字符，与 SDK error 插件一致；其它字符串属性仍是 1024，单事件 props 总量仍是 16KB。需配合 `@mugsun/track-web` 的同名修复，旧版 SDK 在客户端已截到 1024。props 超过 16KB 时先缩短 `stack`（按 UTF-8 字节、不拆字符），再从尾部摘键；堆栈帧 URL 的 query / hash 去掉（防 token 落库）；所有截断不再拆开代理对。错误指纹（服务端计算的 `error_fingerprint`）改为先去掉帧 URL 的 query / hash 再计算：升级后错误指纹会变化，已有的错误分组会断开一次（升级前后的同一错误分属新旧两组），之后不再因 URL 参数不同而拆组
- 埋点来源域名：Web SDK 只上报完整 `referrer` URL，此前服务端只读 `referrer_domain`，导致来源维度恒为空。现在没有 `referrer_domain` 时从 `referrer` 取主机名（小写，不剥 `www.`）；会话的来源域名和 `utm_source` 跟随最早的事件，同一会话的事件分批到达时也能回填，只对升级后接收的新事件生效，历史数据不回填

- 埋点日表历史 UV 回填：新增迁移 `T2`（备份表 `track_stats_day_uv_backup`）和可重复执行的回填任务，把 `track_stats_day` 历史 UV 改成 `distinct_id` 口径，默认最近 90 天。单个「应用 × 天」超时只跳过该天、不卡住其它应用，可按应用放宽超时；PowerJob 结果仅在全部完成时为成功。步骤见 [docs/upgrade/track-uv-backfill.md](docs/upgrade/track-uv-backfill.md)
- 埋点看板按租户时区切日（默认 `Asia/Shanghai`，配置 `mugsun.track.timezone.default-zone` / `MUGSUN_TRACK_TIMEZONE`，按租户编号覆盖 `mugsun.track.timezone.tenants`）：趋势图、错误列表、概览卡片、页面、性能、漏斗、留存、事件分析事件列表、地域地图的「今天」都从当地 0 点开始，之前按 UTC 切日。行为变化：
  - 漏斗时间窗从「最近 days×24 小时」滚动窗口改为从首日当地 0 点算起，`days=1` 即今天 0 点至今
  - UV 口径：日趋势里每天的 UV 是该当地日内去重设备数（`distinct_id`）；维度趋势未指定维度值时不再把各维度值的 UV 相加
  - 概览「会话数」= 每个当地日的活跃会话数之和
  - 时区不是 UTC 时，日趋势/概览/性能直接按明细统计（日表按 UTC 日汇总），大库上会比读日表慢；长期方案见 PR 说明里的 15 分钟预聚合设计

- GIS 空间分析 `/gis/analyze` 的 `engine=java` 语义变更（mugsun-boot#48）：不再转交图层查询（tryOffload），整份入参——包括 `layerId` 指向的整层 `data_json`——全部载入 JVM 内存用 JTS 分析。大图层会明显占内存、变慢，只建议用于诊断和对比。不带 `engine` 时行为不变（有 PostGIS 时缓冲 / 包含仍下沉数据库）
- GIS 米制计算的 Java 回落路径（mugsun-boot#48）：
  - 缓冲 / 距离 / 面积 / 长度改为按要素局部方位等距投影计算，不再在 Web Mercator 上直接按米算（旧版北京 500 m 缓冲实际约 383 m）。升级后同一请求的结果会变化
  - 图层查询（半径、最近邻、缓冲）遇到算不出的要素不再让整次查询失败：半径 / 最近邻回落求几何最近点，缓冲跳过。（#48 时极区指 |纬度| > 85°；mugsun-boot#57 起极区照常计算，只有「缓冲包住极点」和跨 180° 经线的要素会跳过）响应新增 `skipped`（Java 分支总带，PostGIS 分支不带），并按「操作 + 图层」每分钟最多记一条 WARN
  - 空间分析转交图层查询时同样带回 `skipped`；命中结果里的极区 / 跨 180° 要素不计入面积 / 长度汇总，数量记在 `metrics.metricsSkipped`
  - 分析单个几何（`/gis/analyze` 入参，或带 `engine=java`）遇算不出的几何报业务异常（mugsun-boot#57 起只剩「缓冲包住极点」和跨 180° 经线两种）；半径 / 最近邻的查询点在极区不再报错（#48 时 |纬度| > 85° 报错，mugsun-boot#57 起照常计算）
  - 空几何：距离视为 +∞，缓冲为空，面积 / 长度为 0
- GIS 库里存的跨 180° 要素（mugsun-boot#64）：bbox / intersects / contains 不再在 0° 附近误命中、也不再在 180° 附近（含西>东 bbox）漏命中；半径预筛越过 ±180° 时拆框；Java 路径半径对 180° 附近的面不再误判为 0 米。查询时识别（平面包络宽于 180° 且 `ST_ShiftLongitude` 后不宽于 180°），换到 [0, 360) 经度判断，不改存储、无迁移
- GIS 回落路径跨 180° 经线按 RFC 7946 §3.1.9 处理：要素逐部件展开成连续经度后计算距离 / 面积 / 长度，在 180° 拆开的多部件要素按一个整体算；缓冲结果越过 180° 时用 JTS 与 [-180,180] 两侧框求交切成 MultiPolygon，不再报错或跳过。只有单个部件展开后仍宽于 180°（走向无法确定）时才报错 / 计入 `skipped`。视野查询 `/gis/spatial/bbox` 支持 minLon > maxLon（RFC 7946 §5.2，跨 180° 的范围，拆成两个框查询）
- GIS 回落路径的投影与面积（mugsun-boot#57）：缓冲 / 量距改用由 GeographicLib 正反算定义的椭球方位等距投影（|纬度| ≥ 88° 以极点为原点），去掉 85° 极区限制；面积改用 GeographicLib `PolygonArea`，长度逐段测地距离求和，边都按测地线（与 PostGIS `geography` 一致）。高纬长边要素的面积、长度、距离会与旧版（按经纬度直边）明显不同
- GIS 测地距离改用 GeographicLib-Java 2.1（MIT，Karney 算法，与 PostGIS 2.2+ `geography` 同源），替换自写的 Vincenty 和大圆回落；近对蹠点也收敛。新增第三方依赖 `net.sf.geographiclib:GeographicLib-Java`，见 NOTICE
- 只读演示模式（`demo` profile）数据库分账号：业务库用只读账号、登录/访问/操作日志用只插入的日志账号、埋点库账号只有 INSERT/SELECT；demo 下关闭 Flyway 与写库启动任务/定时任务，启动时校验迁移版本一致，迁移改由 owner 账号以 `--mugsun.demo.migrate-only=true` 执行（建账号脚本见 `docs/demo/sql/demo-accounts.sql`）。demo 下埋点 upsert 降级为 `ON CONFLICT DO NOTHING`，会话统计只记首批，普通模式不变。demo 下登录按「账号+IP」锁定、登录与埋点按 IP 限流（Bucket4j + Redis）、导出/SSE 并发上限（Resilience4j Bulkhead），关闭两步验证、短信/邮件发送、社交登录与 AI 通道，Redis 用独立 db。新增第三方依赖 Bucket4j、Resilience4j，见 NOTICE
- 一键全栈（`docker-compose.full.yml`）：`docker compose -f docker-compose.full.yml run --rm init show` 在 admin 改过口令后只提示「初始密码已失效」，不再打印初始口令。判断方式是用初始口令校验库里 admin 当前的口令哈希；连不上库时照常显示并提示无法确认。secrets 卷里的 `MUGSUN_INIT_PASSWORD` 保留（后端启动守卫与重置口令仍要用）。init 镜像新增 `postgresql16-client`、`py3-bcrypt`

### 计划

- （暂无已定条目）

## [0.1.0] - 开发中

### 新增

- 只读演示模式访问日志（B4）：`AccessLogFilter` 每个请求一行（IP、方法、路径、状态、耗时、用户、traceId；不记 query 和 body），demo profile 下单独写 `*-access.log`，按天滚动 gzip，默认保留 200 天、不设总量上限（`mugsun.demo.access-log.path/max-history`），日志盘剩余空间低于 `mugsun.demo.access-log.min-free-space`（默认 10GB，单位写 `GB` 或 `G`（也认 `GiB`、`K/KB`、无单位即字节，1024 进位），可带小数并向下取整到整字节，如 `10GB`/`10 G`/`1.5GB`/`512M`；只去首尾空白，`1 0GB` 无效；超出 long 视为无法解析，解析失败回落 10GB 并 WARN，≤0 关闭告警并 WARN）时 WARN，每小时检查、只在状态变化时记录；`max-history` 低于 180 或无法解析时启动改用 180 并 WARN，≥180 的小数（如 `200.5`）向下取整、不会改为 180，超过 int 上限（含 `1E+999999999`）取 Integer.MAX_VALUE，解析或取整出错回落 180 并 WARN；读磁盘余量抛运行时异常（如 SecurityException）时同样进入「读不到」状态、只 WARN 一次（WARN 带上异常信息），不受 LogCleanJob 影响；demo 下日志库清理保留期不低于 180 天。普通模式不装配、不改变
- 模块化 Spring Boot 后端：`mugsun-boot-core` / `gis` / `track` / `ai` / `server`
- Docker Compose 仅启动 PostgreSQL、Redis 等依赖；应用由 Maven 启动
- 与 `mugsun-pc`、`mugsun-track` 联调路径见 README 与文档站

[Unreleased]: https://github.com/mugsun/mugsun-boot/commits/main
[0.1.0]: https://github.com/mugsun/mugsun-boot/tree/main
