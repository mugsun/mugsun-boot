# 达梦（DM）Flyway 迁移脚本

本目录存放**达梦 / Oracle 语法**脚本，与 `classpath:db/migration`（PostgreSQL）同版本号、同业务语义。由 `scripts/pg_to_dm.py` 从 PG 脚本机械转换后经达梦实例校验。

`V1__baseline_0_1.sql` 是 0.1.0 发布基线，**已发布，不可修改**；之后每次改库新增 `Vn__*.sql`，与 PG 同号（规则见 [`../migration/README.md`](../migration/README.md)，清单 `db/RELEASED.sha256`）。带 `pg2dm: manual` 的脚本（如 V2、V3）不要用 `pg_to_dm.py` 覆盖。

> **目录故意与 PG 并列**（`db/migration-dm`，而非 `db/migration/dm`）：Flyway 默认会递归扫描 `db/migration/**`。

## 如何启用

```yaml
spring:
  datasource:
    url: jdbc:dm://<host>:5236?schema=MUGSUN
    username: MUGSUN
    password: <pwd>
  flyway:
    enabled: true
    locations: classpath:db/migration-dm
```

JDBC 不要加 `compatibleMode=oracle`。打包带上 `db-migration-dameng-flyway` 4.1.2：`mvn -Pfull,dameng package -DskipTests`。不要用 disql 灌这些脚本。

## 编写约定

- 主键/整型：`BIGINT` / `INT` / `SMALLINT`（达梦均支持，避免与 Flex 未加引号标识符大小写纠缠时再转 NUMBER）
- 大文本 `CLOB`；时间戳 `TIMESTAMP`；`now()` → `SYSDATE`
- `ON CONFLICT` / 部分索引 WHERE / `VALUES (...) AS v()` / `DO $$` 已改写或跳过
- V7 起：PG 里 `WHERE is_deleted = 0` 的部分唯一索引，在这里写成函数唯一索引，**每个**键列都包 `CASE WHEN is_deleted = 0 THEN 列 END`。达梦按 Oracle 口径：整组键全为 NULL 的行互不冲突，只有部分列为 NULL 仍会冲突，所以不能只包一列；也不要写成 `(列, is_deleted)`（同一个键删两次就撞键）
- 达梦 DDL 自动提交、不随迁移回滚：需要中止迁移的检查（如 V7 的重复手机号）放在任何 DDL 之前。迁移失败后 `flyway_schema_history` 会留一条 `success = 0` 的记录，处理完要删掉它（或 `flyway repair`）再重跑
- 保留字列（如 `type`、`domain`）加双引号小写，对齐 Flex DmDialect
