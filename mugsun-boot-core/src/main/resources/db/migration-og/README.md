# openGauss-lite 5.1 迁移脚本

与 `classpath:db/migration` 同版本号。由 `scripts/pg_to_opengauss.py` 按 5.1 SQL 参考改写，不要手改 PG 原脚本。

目录与 PG 并列，避免 Flyway 递归扫进 `db/migration/**`。

## 怎么灌

先建 PG 兼容库（默认库是 Oracle 兼容，原脚本会失败）：

```sql
CREATE DATABASE mugsun DBCOMPATIBILITY 'PG';
```

然后按版本执行 `V1` → `V4`。5.1 的改写点：

- `ON CONFLICT DO NOTHING` → `ON DUPLICATE KEY UPDATE NOTHING`
- 去掉 `ADD COLUMN IF NOT EXISTS`（列已在 `CREATE TABLE` 里的整句删掉）
- 去掉部分索引的 `WHERE`（带 `IS NOT NULL` 的唯一索引降成普通索引）
- lite 没有 PostGIS / pgvector：不建 `gis_feature`，向量表没有 embedding 列

镜像：`enmotech/opengauss-lite:5.1.0`，`--privileged`，宿主机端口 15432。不要用企业版 `enmotech/opengauss:5.0.0`。
