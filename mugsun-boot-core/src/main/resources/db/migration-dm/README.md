# 达梦（DM）Flyway 迁移脚本

本目录存放**达梦 / Oracle 语法**脚本，与 `classpath:db/migration`（PostgreSQL）同版本号、同业务语义。由 `scripts/pg_to_dm.py` 从 PG 脚本机械转换后经达梦实例校验。

**0.1.0 基线**：只保留 `V1__baseline_0_1.sql`。AI 表、向量表占位和菜单修正已并进这份基线。0.1 未发布，不要另起 `V2`。带 `pg2dm: manual` 的脚本不要用 `pg_to_dm.py` 覆盖。

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
- 保留字列（如 `type`、`domain`）加双引号小写，对齐 Flex DmDialect
