# 主库 Flyway（PostgreSQL / 金仓兼容）

- `V1__baseline_0_1.sql`：0.1.0 发布基线（已发布，不可修改）。
- `V2__file_perm.sql`：附件归属列 `sys_attach.create_user` 与 `sys:file:manage/public/remove` 权限。
- `V3__clean_fake_changelog.sql`：清掉演示更新日志，写入真实 0.1.0。
- `V4`：保留给 #17（`V4__tenant_captcha.sql`）。
- `V5`：保留给 #35（多类型验证码）。
- `V6__oper_log_chain_guard.sql`（本 PR，审计链加固）：操作日志哈希链链头锁表 `sys_oper_log_chain` 与 `chain_prev` 唯一索引（多节点防分叉）。

## 迁移规则（0.1.0 已发布）

- **已发布的迁移不可修改**，一个字节都不行。已发布清单见仓库根 `db/RELEASED.sha256`，CI 的 `Guard released migrations` 步骤会比对哈希，改了就失败。
- 每次改库都新增 `Vn__说明.sql`（版本号递增），三套方言（`migration` / `migration-dm` / `migration-og`）同号同义。脚本尽量写成幂等。
- 发版时把本版新增的迁移追加进 `db/RELEASED.sha256`（`scripts/check-released-migrations.sh --print <文件...>` 生成行），清单只能追加。

其它目录：
- 达梦：见并列目录 `../migration-dm/`（`scripts/pg_to_dm.py` 从本目录转换）。
- openGauss-lite 5.1：见并列目录 `../migration-og/`（`scripts/pg_to_opengauss.py` 从本目录转换，库须 `DBCOMPATIBILITY 'PG'`）。
- 埋点库：独立序列 `../track/migration/T1__baseline_0_1.sql`。
