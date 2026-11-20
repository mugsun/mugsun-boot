# openGauss 的迁移脚本

这个目录是主库迁移脚本的 openGauss-lite 5.1 版本，版本号和 [`../migration/`](../migration/README.md) 里的 PostgreSQL 脚本相同。

目录里现在只有一个基线 `V1__baseline_0_2.sql`，全新安装只执行这一个文件。

基线由 0.1.0 的基线和它之后的脚本按版本号顺序拼成，语句没有改动。每一段前面有一行 `-- 原 <文件名>`。

各段最初由 `scripts/pg_to_opengauss.py` 按 5.1 的 SQL 参考从 PostgreSQL 脚本改写而来。

不要为了适配 openGauss 去改 PostgreSQL 的原脚本。

> [!WARNING]
> openGauss 不能用 Flyway 迁移，只能用 gsql 手工执行这里的脚本。

## 为什么不能用 Flyway

社区版 Flyway 的 PostgreSQL 插件在恢复连接时会发送 `SET ROLE`。openGauss 要求这条语句带密码，于是报错：

```text
Invalid username/password, set role denied
```

结果是 `flyway_schema_history` 建不出来，一个迁移都不会执行。详情见 [#102](https://github.com/mugsun/mugsun-boot/issues/102)。

所以要这样做：

- 在应用里关掉 `spring.flyway`，或者不要把 `locations` 指向 `classpath:db/migration-og`。
- 不用给 openGauss 补 `flyway_schema_history` 的记录。金仓社区版手工执行脚本时也是这样处理的。
- 用镜像自带的 gsql 执行这个目录里的基线。以后新增的脚本，在基线之后按版本号从小到大执行。

## 执行脚本

镜像用 `enmotech/opengauss-lite:5.1.0`，启动容器时要加 `--privileged`。

不要用企业版的 `enmotech/opengauss:5.0.0`。

先建一个 PG 兼容的库。默认建出来的库是 Oracle 兼容的，脚本在里面会失败：

```sql
CREATE DATABASE mugsun DBCOMPATIBILITY 'PG';
```

再执行基线。下面的例子里，容器的端口映射到了宿主机的 15432，端口和账号请按你的环境替换：

```bash
OG=mugsun-boot-core/src/main/resources/db/migration-og

gsql -d mugsun -U gaussdb -h 127.0.0.1 -p 15432 -f $OG/V1__baseline_0_2.sql -1 -v ON_ERROR_STOP=1
```

`-1 -v ON_ERROR_STOP=1` 两个参数都要带。

`-1` 让整个文件在一个事务里执行，`ON_ERROR_STOP=1` 让 gsql 遇到错误就停下。

以后新增的脚本也用这两个参数，每个文件执行一次。

> [!NOTE]
> 合并之前各个脚本是分开执行的，每个文件一个事务。合并以后整个基线在一个事务里执行，这种执行方式还没有在 openGauss 上实测过。目前只核对过各段和原来的脚本逐字相同。

### 已经执行过旧脚本的库

openGauss 不记迁移历史。合并之前按版本号执行完全部脚本的库，执行过的语句和基线相同，不用重建，也不要再执行基线。

没有执行完的库，重建以后执行基线。

### 原 V8 那一段

openGauss-lite 5.1 不支持 `ADD COLUMN IF NOT EXISTS`。

基线里原 V8 那一段已经改用 DO 块，加列之前先查 `information_schema.columns`，写法和原 V9、V10 两段一样。

仓库根目录下的 `scripts/og/V8_oper_log_chain_guard_lite.sql` 和这一段含义相同。执行了基线，就不用再执行它。

## 规则

- 0.2 发布之前，改库直接改基线，和 PostgreSQL、达梦的基线一起改。
- 0.2 发布之后，基线不能修改，每次改库都新增 `Vn__*.sql`，版本号和 PostgreSQL 的相同。
- 脚本里不能出现 `ADD COLUMN IF NOT EXISTS`。

PostgreSQL 的基线改了以后，这里要手工同步。

新增的内容可以先在 PostgreSQL 目录写成 `V18__说明.sql`，用转换器转出这里的版本，人工检查以后再并进基线：

```bash
DB=mugsun-boot-core/src/main/resources/db

python3 scripts/pg_to_opengauss.py $DB/migration/V18__说明.sql $DB/migration-og/V18__说明.sql
```

完整的规则和合并的命令见 [`../migration/README.md`](../migration/README.md)。

第三条由下面几处保证：

- 从 PostgreSQL 脚本重新转换时，转换器会把这种语法改成 DO 块。
- `scripts/ci/test_og_migration_ban.py` 会扫描这个目录里的脚本。
- `OpenGaussMigrationBanTest` 做同样的检查。

### 版本号

openGauss 不走 Flyway，版本号仍然和 PostgreSQL、达梦的保持一致。

基线之后的第一个新脚本是 `V18`，原因见 PostgreSQL 目录的说明。

版本号只增不补。中间跳号是正常的，不要回填空号。

### 占位的段

原 V16 和 V17 在 PostgreSQL 上给 `gis_feature` 加列和索引，背景见 [#76](https://github.com/mugsun/mugsun-boot/issues/76)。

这里没有 PostGIS，也不建 `gis_feature` 表，所以基线里这两段只有一句 `SELECT 1`，是占位用的。

## 为什么目录和 migration 并列

目录是 `db/migration-og`，没有放在 `db/migration` 下面。

Flyway 默认会递归扫描 `db/migration/**`，放在里面会被当成 PostgreSQL 的迁移扫到。

## 和 PostgreSQL 脚本的差别

| PostgreSQL 的写法 | 这里的写法 |
| --- | --- |
| `ON CONFLICT DO NOTHING` | `ON DUPLICATE KEY UPDATE NOTHING` |
| `ADD COLUMN IF NOT EXISTS` | 列已经在 `CREATE TABLE` 里时，删掉整句。否则改成查 `information_schema` 的 DO 块 |
| 带 `WHERE` 的部分索引 | 去掉 `WHERE`。带 `IS NOT NULL` 条件的唯一索引降为普通索引 |
| `WHERE is_deleted = 0` 的部分唯一索引 | 写成表达式唯一索引（基线里原 V7 那一段），见下文 |
| PostGIS 和 pgvector | lite 版没有这两个扩展。不建 `gis_feature` 表，向量表也没有 embedding 列 |

### 唯一索引

每个键列都包一层：

```sql
CASE WHEN is_deleted = 0 THEN 列 END
```

已删除的行整组键都是 NULL，不参与唯一约束。

不要写成 `(列, is_deleted)`。那样同一个键删除两次就会冲突。

2026 年 10 月 18 日实测过，openGauss-lite 5.1 也支持部分唯一索引。这里仍然用表达式索引，是为了和达梦的写法一致。
