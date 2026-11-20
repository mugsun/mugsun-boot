# 达梦的迁移脚本

这个目录是主库迁移脚本的达梦版本，用达梦的 Oracle 语法编写。版本号和含义与 [`../migration/`](../migration/README.md) 里的 PostgreSQL 脚本一一对应。

脚本先由 `scripts/pg_to_dm.py` 从 PostgreSQL 脚本转换，再在达梦实例上校验。

## 启用

```yaml
spring:
  datasource:
    url: jdbc:dm://<host>:5236?schema=MUGSUN
    username: MUGSUN
    password: <密码>
  flyway:
    enabled: true
    locations: classpath:db/migration-dm
```

打包时加上 `dameng` 这个 Profile：

```bash
mvn -Pfull,dameng package -DskipTests
```

它会带上达梦的 JDBC 驱动和 `db-migration-dameng-flyway`。社区版的 Flyway 不认识达梦，要靠后者才能执行迁移。

有两件事不要做：

- JDBC 地址不要加 `compatibleMode=oracle`。
- 不要用 disql 手工执行这些脚本。

## 规则

- `V1__baseline_0_1.sql` 是 0.1.0 的发布基线。它已经发布，不能修改。
- 之后每次改库都新增 `Vn__*.sql`，版本号和 PostgreSQL 的相同。
- 手工改过的脚本要保留 `-- pg2dm: manual` 这一行。带这个标记的文件，`pg_to_dm.py` 再运行时不会覆盖。

现在目录里的脚本都带 `pg2dm: manual` 标记。

完整的规则见 [`../migration/README.md`](../migration/README.md)，已发布的清单是仓库根目录的 `db/RELEASED.sha256`。

### 版本号

新脚本的版本号取合入时 `main` 上最大的号加 1。

版本号只增不补，所以中间会跳号，例如没有 V4 到 V6，也没有 V12。这是正常的，不要回填空号。

库里已经执行过更高的版本时，再遇到低版本的新脚本，Flyway 的默认校验会拒绝启动。

### 占位的脚本

V16 和 V17 在 PostgreSQL 上给 `gis_feature` 加列和索引，背景见 [#76](https://github.com/mugsun/mugsun-boot/issues/76)。

达梦没有 PostGIS，也不建 `gis_feature` 表，所以这里的 V16 和 V17 只是同号的占位文件。

## 为什么目录和 migration 并列

目录是 `db/migration-dm`，没有放在 `db/migration/dm` 下面。

Flyway 默认会递归扫描 `db/migration/**`，放在里面会和 PostgreSQL 的脚本冲突。

## 编写约定

| 内容 | 这里的写法 |
| --- | --- |
| 主键和整型 | `BIGINT`、`INT`、`SMALLINT`。达梦都支持，不需要改成 `NUMBER` |
| 大文本 | `CLOB` |
| 时间戳 | `TIMESTAMP` |
| `now()` | `SYSDATE` |
| 保留字做列名，例如 `type`、`domain` | 加双引号，用小写，和 MyBatis-Flex 的 `DmDialect` 一致 |

下面几种 PostgreSQL 写法在这里已经改写或者跳过：

- `ON CONFLICT`
- 带 `WHERE` 的部分索引
- `VALUES (...) AS v()`
- `DO $$`

### 唯一索引

从 V7 起，PostgreSQL 里 `WHERE is_deleted = 0` 的部分唯一索引，在这里写成函数唯一索引。每个键列都要包一层：

```sql
CASE WHEN is_deleted = 0 THEN 列 END
```

达梦按 Oracle 的规则判断唯一：整组键全是 NULL 的行互不冲突，只有一部分列是 NULL 时仍然会冲突。所以不能只包一列。

也不要写成 `(列, is_deleted)`。那样同一个键删除两次就会冲突。

### DDL 不会回滚

达梦的 DDL 是自动提交的，迁移失败时不会回滚。需要中止迁移的检查要放在所有 DDL 之前，例如 V7 里对重复手机号的检查。

迁移失败后，`flyway_schema_history` 里会留下一条 `success = 0` 的记录。

处理完问题后，要先删掉这条记录或者执行 `flyway repair`，再重新运行。
