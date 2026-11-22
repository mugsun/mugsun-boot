# 主库的迁移脚本

这个目录是主库的 Flyway 迁移脚本，用 PostgreSQL 语法编写。PostgreSQL 和人大金仓都用这一套。

金仓的部署步骤见[信创国产库适配指南](../../../../../../docs/信创国产库适配指南.md)。

## 目录里有什么

| 迁移 | 内容 |
| --- | --- |
| `V1__baseline_0_2.sql` | 0.2 的基线。全新安装只执行这一个 SQL 文件 |
| `V17__gis_feature_wrap_index` | Java 迁移，在 `db.migration` 包里。建部分 GiST 索引 `idx_gis_feature_wrap`。没有 PostGIS 时跳过 |

PostgreSQL 上全新的库迁移完，`flyway_schema_history` 里只有这两条记录。

基线由 0.1.0 的基线和它之后的 16 个脚本按版本号顺序拼成，语句没有改动。

每一段前面有一行 `-- 原 <文件名>`，标出这一段原来是哪个脚本。各段的内容见[基线里的各段](#基线里的各段)。

V17 用 `CREATE INDEX CONCURRENTLY` 建索引。这条语句不能在事务里执行，所以它是单独的 Java 迁移，没有并进基线。

## 规则

0.2 还没有发布。发布前和发布后，改库的做法不同。

### 0.2 发布之前

- 改库直接改基线，目录里不留增量脚本。
- PostgreSQL、达梦和 openGauss 三份基线要一起改，含义相同。
- 改完要重建库。

改已有的表或数据，直接改基线里对应的语句。

新增的内容可以先写成增量脚本，再并进基线：

1. 在本目录写 `V18__说明.sql`。
2. 转换出达梦和 openGauss 的版本，写法见下面的命令。转换结果要人工检查。
3. 运行合并脚本。它把三个文件各自接到基线的末尾，加上段头，再删掉这三个文件。

```bash
DB=mugsun-boot-core/src/main/resources/db

python3 scripts/pg_to_dm.py
python3 scripts/pg_to_opengauss.py $DB/migration/V18__说明.sql $DB/migration-og/V18__说明.sql

python3 scripts/squash_flyway_baseline.py 0_2 --dry-run   # 只列出会合并哪些文件
python3 scripts/squash_flyway_baseline.py 0_2             # 合并
```

#### 为什么要重建库

Flyway 会比对已执行脚本的校验和。基线改过以后，旧库启动时校验失败，应用不会启动。

重建的做法：

- 用 `docker compose` 起的库：`docker compose down -v` 删掉数据卷，再 `docker compose up -d`。
- 自己装的 PostgreSQL：删掉 `mugsun` 和 `mugsun_track` 两个库，再执行 `scripts/init-db.sql` 建空库。

Redis 里的缓存也一起清掉。然后启动应用，Flyway 会从零迁移。

#### 删掉或改名脚本以后要清 target

Maven 不会删掉 `target/classes` 里已经没有源文件的资源。

迁移脚本删掉或改名以后，旧的那一份还留在 `target` 里，启动时 Flyway 报错：

```text
Found more than one migration with version 1
```

先执行一次 `mvn clean`，再编译。拉取到合并基线的这次改动以后，也要这样做一次。

### 0.2 发布之后

- 已发布的迁移不能修改，一个字节都不行。
- 每次改库都新增一个 `Vn__说明.sql`。
- PostgreSQL、达梦和 openGauss 三套脚本要一起新增，版本号相同，含义相同。
- 脚本尽量写成幂等的。

### 发布清单

已发布的迁移记在仓库根目录的 `db/RELEASED-0.2.sha256` 里。

CI 的 `Guard released migrations` 步骤会比对哈希，登记过的文件被改过就失败。

清单里现在没有登记项，所以基线还能改。

发布 0.2.0 时要把四个基线登记进去：主库三套各一个，埋点库一个。之后每次发版，把这一版新增的迁移追加进去。

清单只能追加。清单里的行用脚本生成：

```bash
scripts/check-released-migrations.sh --print <文件...> >> db/RELEASED-0.2.sha256
```

0.1.0 用的清单是 `db/RELEASED.sha256`。0.1.0 的脚本并进 0.2 基线以后，这份清单已经删除。

## 版本号怎么取

新脚本的版本号取合入时 `main` 上最大的号加 1。Java 迁移也占版本号。

现在最大的号是 V17，所以基线之后的第一个新脚本是 `V18__说明.sql`，不是 `V2`。

库里已经执行过更高的版本时，再遇到低版本的新脚本，Flyway 的默认校验会拒绝启动。

版本号只增不补。中间跳号是正常的，不要回填空号。

## 已有的库要重建

用合并之前的脚本建的库不能原地升级到 0.2，0.1.0 建的库也一样。

这些库的迁移历史是按原来的脚本逐条记的，和现在的脚本对不上。启动时 Flyway 校验失败，应用不会启动，日志里有这样的内容：

```text
Migration checksum mismatch for migration version 1
Detected applied migration not resolved locally: 2.
```

开发库直接重建。

这次合并没有提供保留数据的升级办法。库里有要保留的数据时，要自己导出，重建以后再导入。

只读演示环境也要重建。它启动时由 `DemoSchemaGuard` 做同样的校验。

## 其他数据库的脚本

| 数据库 | 目录 | 基线 |
| --- | --- | --- |
| 达梦 | [`../migration-dm/`](../migration-dm/README.md) | `V1__baseline_0_2.sql` |
| openGauss-lite 5.1 | [`../migration-og/`](../migration-og/README.md) | `V1__baseline_0_2.sql` |
| 埋点库 | `mugsun-boot-track/src/main/resources/db/track/migration/` | `T1__baseline_0_2.sql` |

达梦和 openGauss 的脚本最初由 `scripts/pg_to_dm.py` 和 `scripts/pg_to_opengauss.py` 从本目录转换而来。

这两个转换器都不会改动已有的基线。PostgreSQL 的基线改了以后，另外两份基线要手工同步。

openGauss 不能用 Flyway，只能用 gsql 手工执行脚本，库也要建成 `DBCOMPATIBILITY 'PG'`。

原因和步骤见那个目录的说明。

埋点库用的是另一个数据源，脚本单独编号，版本号以 `T` 开头。它的说明在那个目录的 `README.md` 里。

## 基线里的各段

| 原脚本 | 内容 |
| --- | --- |
| `V1__baseline_0_1.sql` | 0.1.0 的发布基线 |
| `V2__file_perm.sql` | 附件的归属列 `sys_attach.create_user`，以及 `sys:file:manage`、`sys:file:public`、`sys:file:remove` 三个权限 |
| `V3__clean_fake_changelog.sql` | 清掉演示用的更新日志，换成真实的 0.1.0 |
| `V7__unique_index_delete_flag.sql` | 唯一索引只约束未删除的行。PostgreSQL 在原 V1 里已经是这样，这一段没有改动，只为和另外两套脚本对齐 |
| `V8__oper_log_chain_guard.sql` | 操作日志哈希链的链头锁表 `sys_oper_log_chain`，以及 `chain_prev` 的唯一索引 |
| `V9__tenant_captcha.sql` | 租户的验证码开关 |
| `V10__tenant_captcha_types.sql` | 租户可以用的验证码题型 |
| `V11__tenant_code_full_unique.sql` | `uk_tenant_code` 改为全量唯一，已删除的行也算在内，租户编号不能复用。`uk_tenant_ds_code` 不变，仍只约束未删除的行 |
| `V13__export_import_upload_flow_perm.sql` | 用户导出、导入和附件上传各有了单独的权限码，表单和流程的按钮权限改为方法级注解 |
| `V14__register_role_upload.sql` | 内置的「普通用户」角色改由迁移创建。新增参数 `security.register.register-role-code`。注册用户可以上传附件 |
| `V15__user_must_change_password.sql` | `sys_user.must_change_password` 列，标记首次登录必须改密码 |
| `V16__gis_feature_wraps_antimeridian.sql` | `gis_feature.wraps_antimeridian` 生成列，标记要素是否跨 180° 经线。没有 PostGIS 时跳过 |
| `V18__track_error_issue_perm.sql` | 错误问题单的写权限 `sys:track-error-issue:edit`，读权限沿用 `sys:track-error:list` |
| `V19__track_ask_perm.sql` | 「问数据」菜单和它的权限 `sys:track-ask:list` |
| `V20__track_spatial_perm.sql` | 空间行为分析的读权限 `sys:track-spatial:list` |
| `V21__form_tenant.sql` | 低代码表单的 `sys_form` 和 `sys_form_data` 补上 `tenant_id`。表单标识改为租户内唯一，索引是 `uk_form_tenant_key` |
| `V22__track_storage_quota_params.sql` | 埋点回放和响应体的三个存储容量参数 |
| `V23__track_journey_perm.sql` | 「访问路径」菜单和它的权限 `sys:track-journey:list` |

原脚本的版本号有跳号，没有 V4 到 V6，也没有 V12。段的顺序就是原来的执行顺序。

V17 不在基线里，它是上面说的 Java 迁移，在基线之后执行。

测试按段头读取各段的内容（`MigrationBaseline.section`），所以段头不要删，也不要改名。

### 几段的补充说明

原 V16 和 V17 的背景见 [#76](https://github.com/mugsun/mugsun-boot/issues/76)。

V17 不在事务里执行，所以主库的 Flyway 要用会话级的锁。这项配置在 `FlywayPgCompatConfig` 里。

原 V22 的三个参数是：

- `track.storage.daily-max-bytes`
- `track.storage.app-daily-max-bytes`
- `track.storage.min-free-bytes`
