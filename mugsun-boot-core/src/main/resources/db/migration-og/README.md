# openGauss-lite 5.1 迁移脚本

与 `classpath:db/migration` 同版本号。由 `scripts/pg_to_opengauss.py` 按 5.1 SQL 参考改写，不要手改 PG 原脚本。

目录与 PG 并列，避免 Flyway 递归扫进 `db/migration/**`。

## 怎么灌（只用 gsql，不要 Flyway migrate）

**openGauss 只支持 gsql 手工灌库。** 社区 Flyway PostgreSQL 插件在恢复连接时会发 `SET ROLE`，openGauss 要求带密码 → `Invalid username/password, set role denied`，`flyway_schema_history` 建不出来，一个迁移都不会执行（见 #102）。因此：

- 应用侧关掉 `spring.flyway`，或不要把 `locations` 指到 `classpath:db/migration-og`
- **不要**指望 Flyway `migrate()`；也不必为 og 补登 `flyway_schema_history`（与金仓社区手工灌库同口径）
- 用镜像自带 `gsql` 按版本号顺序执行本目录脚本

先建 PG 兼容库（默认库是 Oracle 兼容，原脚本会失败）：

```sql
CREATE DATABASE mugsun DBCOMPATIBILITY 'PG';
```

示例（端口、口令按环境替换；每个文件带 `-1 -v ON_ERROR_STOP=1`）：

```bash
# V1 → V2 → V3 → V7 → …；V8 用 lite 替代脚本，不要直接灌本目录 V8 的历史「IF NOT EXISTS」稿
gsql -d mugsun -U gaussdb -h 127.0.0.1 -p 15432 -f …/V1__baseline_0_1.sql -1 -v ON_ERROR_STOP=1
# …
gsql -d mugsun -U gaussdb -h 127.0.0.1 -p 15432 -f scripts/og/V8_oper_log_chain_guard_lite.sql -1 -v ON_ERROR_STOP=1
# 再继续 V9、V10…
```

### V8 替代脚本

lite 5.1 不支持 `ADD COLUMN IF NOT EXISTS`。手工灌库时 **V8 请用仓库根下**
`scripts/og/V8_oper_log_chain_guard_lite.sql`（仓库根相对路径）
（DO 块查 `information_schema.columns`，与 V9/V10 一致）。本目录当前 `V8__oper_log_chain_guard.sql` 已是同语义的 DO 写法；从 PG 重新转换时转换器也会把 `ADD COLUMN IF NOT EXISTS` 改成 DO 块，且静态测试禁止本目录再出现该语法。

`V1__baseline_0_1.sql` 是 0.1.0 发布基线，**已发布，不可修改**；之后每次改库新增 `Vn__*.sql`，与 PG 同号（规则见 [`../migration/README.md`](../migration/README.md)）。5.1 的改写点：

- `ON CONFLICT DO NOTHING` → `ON DUPLICATE KEY UPDATE NOTHING`
- **禁止** `ADD COLUMN IF NOT EXISTS`：列已在 `CREATE TABLE` 里的整句删掉；否则改成 information_schema DO 块（转换器与 `scripts/ci/test_og_migration_ban.py` / `OpenGaussMigrationBanTest` 会拦）
- 去掉部分索引的 `WHERE`（带 `IS NOT NULL` 的唯一索引降成普通索引）
- V7 起：PG 里 `WHERE is_deleted = 0` 的部分唯一索引，在这里写成表达式唯一索引，每个键列包 `CASE WHEN is_deleted = 0 THEN 列 END`（已删除的行整组键为 NULL，不参与唯一）。不要写成 `(列, is_deleted)`：同一个键删两次就会撞键。注：2026-10-18 实测 openGauss-lite 5.1 也支持部分唯一索引，为与达梦同一写法仍用表达式索引
- lite 没有 PostGIS / pgvector：不建 `gis_feature`，向量表没有 embedding 列

镜像：`enmotech/opengauss-lite:5.1.0`，`--privileged`，宿主机端口 15432。不要用企业版 `enmotech/opengauss:5.0.0`。

版本号只增不补，可能跳号（如没有 V4–V6、V12），属正常。新脚本取合入时 main 上的最大号加 1，不要回填空号：已执行过更高版本的库再遇到低版本新脚本，Flyway 默认校验会拒绝启动（og 虽不走 Flyway，仍与 PG/达梦同号纪律对齐）。
