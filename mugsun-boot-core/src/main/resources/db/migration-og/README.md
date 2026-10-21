# openGauss-lite 5.1 迁移脚本

与 `classpath:db/migration` 同版本号。由 `scripts/pg_to_opengauss.py` 按 5.1 SQL 参考改写，不要手改 PG 原脚本。

目录与 PG 并列，避免 Flyway 递归扫进 `db/migration/**`。

## 怎么灌

先建 PG 兼容库（默认库是 Oracle 兼容，原脚本会失败）：

```sql
CREATE DATABASE mugsun DBCOMPATIBILITY 'PG';
```

然后用 Flyway（`locations: classpath:db/migration-og`）按版本执行。`V1__baseline_0_1.sql` 是 0.1.0 发布基线，**已发布，不可修改**；之后每次改库新增 `Vn__*.sql`，与 PG 同号（规则见 [`../migration/README.md`](../migration/README.md)）。5.1 的改写点：

- `ON CONFLICT DO NOTHING` → `ON DUPLICATE KEY UPDATE NOTHING`
- 去掉 `ADD COLUMN IF NOT EXISTS`（列已在 `CREATE TABLE` 里的整句删掉）
- 去掉部分索引的 `WHERE`（带 `IS NOT NULL` 的唯一索引降成普通索引）
- V7 起：PG 里 `WHERE is_deleted = 0` 的部分唯一索引，在这里写成表达式唯一索引，每个键列包 `CASE WHEN is_deleted = 0 THEN 列 END`（已删除的行整组键为 NULL，不参与唯一）。不要写成 `(列, is_deleted)`：同一个键删两次就会撞键。注：2026-10-18 实测 openGauss-lite 5.1 也支持部分唯一索引，为与达梦同一写法仍用表达式索引
- lite 没有 PostGIS / pgvector：不建 `gis_feature`，向量表没有 embedding 列

镜像：`enmotech/opengauss-lite:5.1.0`，`--privileged`，宿主机端口 15432。不要用企业版 `enmotech/opengauss:5.0.0`。

版本号只增不补，可能跳号（如没有 V4–V6、V12），属正常。新脚本取合入时 main 上的最大号加 1，不要回填空号：已执行过更高版本的库再遇到低版本新脚本，Flyway 默认校验会拒绝启动。
