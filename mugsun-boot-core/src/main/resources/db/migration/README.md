# 主库 Flyway（PostgreSQL / 金仓兼容）

**0.1.0 基线**：仅 `V1__baseline_0_1.sql`（合并原增量 V1–V79，含表结构、种子菜单/字典/管理员等）。

- 全新安装：Flyway 只跑这一份。
- 后续升级（如 0.1.1）：新增 `V2__*.sql`，不要再拆回历史增量。
- 达梦：见并列目录 `../migration-dm/`（`scripts/pg_to_dm.py` 从本目录转换）。
- 埋点库：独立序列 `../track/migration/T1__baseline_0_1.sql`。
