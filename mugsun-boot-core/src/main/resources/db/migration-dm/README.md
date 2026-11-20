# 达梦的迁移脚本

这个目录是主库迁移脚本的达梦版本，用达梦的 Oracle 语法编写。版本号和含义与 [`../migration/`](../migration/README.md) 里的 PostgreSQL 脚本一一对应。

目录里现在只有一个基线 `V1__baseline_0_2.sql`，全新安装只执行这一个文件。

基线由 0.1.0 的基线和它之后的脚本按版本号顺序拼成，语句没有改动。每一段前面有一行 `-- 原 <文件名>`。

各段最初由 `scripts/pg_to_dm.py` 从 PostgreSQL 脚本转换，再在达梦实例上校验。

> [!NOTE]
> 合并成一个文件以后，基线还没有在达梦实例上整体执行过。目前只核对过各段和原来的脚本逐字相同。

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

- 0.2 发布之前，改库直接改基线，和 PostgreSQL、openGauss 的基线一起改。改完要重建库。
- 0.2 发布之后，基线不能修改，每次改库都新增 `Vn__*.sql`，版本号和 PostgreSQL 的相同。
- 手工改过的脚本要保留 `-- pg2dm: manual` 这一行。带这个标记的文件，`pg_to_dm.py` 再运行时不会覆盖。

基线带 `pg2dm: manual` 标记，所以转换器不会改动它。PostgreSQL 的基线改了以后，这里要手工同步。

新增的内容可以先在 PostgreSQL 目录写成 `V18__说明.sql`，运行 `pg_to_dm.py` 转出达梦版本，人工检查以后再并进基线。

完整的规则和合并的命令见 [`../migration/README.md`](../migration/README.md)，发布清单是仓库根目录的 `db/RELEASED-0.2.sha256`。

### 版本号

版本号和 PostgreSQL 的相同。基线之后的第一个新脚本是 `V18`，原因见 PostgreSQL 目录的说明。

版本号只增不补。中间跳号是正常的，不要回填空号。

库里已经执行过更高的版本时，再遇到低版本的新脚本，Flyway 的默认校验会拒绝启动。

### 已有的库要重建

用合并之前的脚本建的库，迁移历史和现在的基线对不上，Flyway 校验会失败。这些库不能原地升级，要重建。

### 占位的段

原 V16 和 V17 在 PostgreSQL 上给 `gis_feature` 加列和索引，背景见 [#76](https://github.com/mugsun/mugsun-boot/issues/76)。

达梦没有 PostGIS，也不建 `gis_feature` 表，所以基线里这两段只有一句 `SELECT 1 FROM DUAL`，是占位用的。

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

PostgreSQL 里 `WHERE is_deleted = 0` 的部分唯一索引，在这里写成函数唯一索引，见基线里原 V7 那一段。每个键列都要包一层：

```sql
CASE WHEN is_deleted = 0 THEN 列 END
```

达梦按 Oracle 的规则判断唯一：整组键全是 NULL 的行互不冲突，只有一部分列是 NULL 时仍然会冲突。所以不能只包一列。

也不要写成 `(列, is_deleted)`。那样同一个键删除两次就会冲突。

### DDL 不会回滚

达梦的 DDL 是自动提交的，迁移失败时不会回滚。

发布之后新增的脚本里，需要中止迁移的检查要放在这个脚本的所有 DDL 之前。基线里原 V7 那一段对重复手机号的检查就是这样写的。

迁移失败后，`flyway_schema_history` 里会留下一条 `success = 0` 的记录。

处理完问题后，要先删掉这条记录或者执行 `flyway repair`，再重新运行。
