# 主库的迁移脚本

这个目录是主库的 Flyway 迁移脚本，用 PostgreSQL 语法编写。PostgreSQL 和人大金仓都用这一套。

金仓的部署步骤见[信创国产库适配指南](../../../../../../docs/信创国产库适配指南.md)。

## 规则

- 已发布的迁移不能修改，一个字节都不行。
- 每次改库都新增一个 `Vn__说明.sql`，版本号递增。
- PostgreSQL、达梦和 openGauss 三套脚本要一起新增，版本号相同，含义相同。
- 脚本尽量写成幂等的。

已发布的迁移记在仓库根目录的 `db/RELEASED.sha256` 里。

CI 的 `Guard released migrations` 步骤会比对哈希，文件被改过就失败。

发版时把这一版新增的迁移追加进清单，清单只能追加。清单里的行用脚本生成：

```bash
scripts/check-released-migrations.sh --print <文件...>
```

## 版本号怎么取

新脚本的版本号取合入时 `main` 上最大的号加 1。

版本号只增不补，所以中间会跳号，例如没有 V4 到 V6，也没有 V12。这是正常的，不要回填空号。

库里已经执行过更高的版本时，再遇到低版本的新脚本，Flyway 的默认校验会拒绝启动。

## 其他数据库的脚本

| 数据库 | 目录 | 来源 |
| --- | --- | --- |
| 达梦 | [`../migration-dm/`](../migration-dm/README.md) | `scripts/pg_to_dm.py` 从本目录转换 |
| openGauss-lite 5.1 | [`../migration-og/`](../migration-og/README.md) | `scripts/pg_to_opengauss.py` 从本目录转换 |
| 埋点库 | `mugsun-boot-track/src/main/resources/db/track/migration/` | 单独编写，版本号以 `T` 开头 |

openGauss 不能用 Flyway，只能用 gsql 手工执行脚本，库也要建成 `DBCOMPATIBILITY 'PG'`。

原因和步骤见那个目录的说明。

埋点库用的是另一个数据源，脚本从 `T1__baseline_0_1.sql` 开始单独编号。

## 脚本清单

| 脚本 | 内容 |
| --- | --- |
| `V1__baseline_0_1.sql` | 0.1.0 的发布基线。已发布，不能修改 |
| `V2__file_perm.sql` | 附件的归属列 `sys_attach.create_user`，以及 `sys:file:manage`、`sys:file:public`、`sys:file:remove` 三个权限 |
| `V3__clean_fake_changelog.sql` | 清掉演示用的更新日志，换成真实的 0.1.0 |
| `V7__unique_index_delete_flag.sql` | 唯一索引只约束未删除的行。PostgreSQL 在 V1 里已经是这样，这个文件没有改动，只为和另外两套脚本同号 |
| `V8__oper_log_chain_guard.sql` | 操作日志哈希链的链头锁表 `sys_oper_log_chain`，以及 `chain_prev` 的唯一索引 |
| `V9__tenant_captcha.sql` | 租户的验证码开关 |
| `V10__tenant_captcha_types.sql` | 租户可以用的验证码题型 |
| `V11__tenant_code_full_unique.sql` | `uk_tenant_code` 改为全量唯一，已删除的行也算在内，租户编号不能复用。库里已有编号被复用时迁移会中止。`uk_tenant_ds_code` 不变，仍只约束未删除的行 |
| `V13__export_import_upload_flow_perm.sql` | 用户导出、导入和附件上传各有了单独的权限码，表单和流程的按钮权限改为方法级注解 |
| `V14__register_role_upload.sql` | 内置的「普通用户」角色改由迁移创建。新增参数 `security.register.register-role-code`。注册用户可以上传附件 |
| `V15__user_must_change_password.sql` | `sys_user.must_change_password` 列，标记首次登录必须改密码 |
| `V16__gis_feature_wraps_antimeridian.sql` | `gis_feature.wraps_antimeridian` 生成列，标记要素是否跨 180° 经线。没有 PostGIS 时跳过 |
| `V17__gis_feature_wrap_index` | Java 迁移，在 `db.migration` 包里。建部分 GiST 索引 `idx_gis_feature_wrap`。没有 PostGIS 时跳过 |
| `V18__track_error_issue_perm.sql` | 错误问题单的写权限 `sys:track-error-issue:edit`，读权限沿用 `sys:track-error:list` |
| `V19__track_ask_perm.sql` | 「问数据」菜单和它的权限 `sys:track-ask:list` |
| `V20__track_spatial_perm.sql` | 空间行为分析的读权限 `sys:track-spatial:list` |
| `V21__form_tenant.sql` | 低代码表单的 `sys_form` 和 `sys_form_data` 补上 `tenant_id`，已有数据归平台租户 `000000`。表单标识改为租户内唯一，索引是 `uk_form_tenant_key` |
| `V22__track_storage_quota_params.sql` | 埋点回放和响应体的三个存储容量参数。库里已有同名参数时不覆盖 |

新增迁移时，请在这张表里加一行。

### 几个脚本的补充说明

V9 和 V10 原先预留的版本号是 V4 和 V5。

它们合入时 V7 和 V8 已经在 `main` 上，Flyway 也没有打开 `outOfOrder`，所以排到了后面。

V16 和 V17 的背景见 [#76](https://github.com/mugsun/mugsun-boot/issues/76)。

V17 用 `CREATE INDEX CONCURRENTLY` 建索引，这条语句不能在事务里执行，所以主库的 Flyway 要用会话级的锁。

这项配置在 `FlywayPgCompatConfig` 里。

V22 的三个参数是：

- `track.storage.daily-max-bytes`
- `track.storage.app-daily-max-bytes`
- `track.storage.min-free-bytes`
