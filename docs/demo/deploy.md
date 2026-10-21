# 只读演示环境部署要点

演示环境用 `--spring.profiles.active=prod,demo` 启动，目前只支持 PostgreSQL。账号和授权见 [`sql/demo-accounts.sql`](sql/demo-accounts.sql)，只读放行名单见 [`readonly-endpoints.md`](readonly-endpoints.md)。

## 部署顺序

1. 用 owner 账号、非 demo profile 跑一次迁移：`--spring.profiles.active=prod --mugsun.demo.migrate-only=true`，跑完进程自己退出。
2. 用超级用户执行 `demo-accounts.sql`（先替换掉 `{{...}}` 占位符），建只读、日志、埋点三个账号。
3. 用三个低权账号以 `prod,demo` 启动。启动时会只读校验迁移版本，库里版本和程序不一致就拒绝启动。

## 连接池余量

按演示库 `max_connections=30` 计算：

| 项目 | 连接数 | 说明 |
|---|---|---|
| `max_connections` | 30 | PostgreSQL 配置 |
| 超级用户保留 | 3 | `superuser_reserved_connections` 默认 3，只有超级用户能用 |
| 普通账号可用 | 27 | 30 − 3 |
| demo 业务只读池 | 5 | `MUGSUN_DEMO_DB_POOL_MAX` |
| demo 日志池 | 4 | `MUGSUN_DEMO_LOG_POOL_MAX` |
| demo 埋点池 | 4 | `MUGSUN_DEMO_TRACK_POOL_MAX` |
| demo 合计 | 13 | 三个池满载时 |
| 剩余余量 | 14 | 27 − 13，留给迁移、psql 运维和备份 |

- **迁移（migrate-only）：** 它用的是非 demo 配置，业务池和埋点池都是 Hikari 默认的 10，最多会占 20 个连接，超过 14 个余量。所以要先停掉 demo 应用再跑迁移，或者跑迁移时限制池大小，例如加 `--mybatis-flex.datasource.primary.maximum-pool-size=4 --mybatis-flex.datasource.track.maximum-pool-size=3`。
- **运维：** 一次 `psql` 或 `pg_dump` 占 1 个连接。超级用户（postgres）还能用保留的 3 个，普通账号连不上时它仍然能连。
- **调大池之前：** 先确认三个池加起来再加上迁移所需，不超过 27；否则就调高 `max_connections`，这需要重启 PostgreSQL。

## 日志与事务

- demo 下操作日志走日志账号的独立事务：锁链头、读最后一条哈希、写入、刷新链头时间在同一个连接里一起提交，只读账号保持纯 SELECT。日志记录的是「操作尝试」，与业务事务无关。
- 日志数据源必须连主库：`MUGSUN_DEMO_LOG_DB_URL` 不设时默认就是主库；设成别的地址时，demo 拒绝启动。
