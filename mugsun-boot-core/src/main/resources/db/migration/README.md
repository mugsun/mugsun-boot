# 主库 Flyway（PostgreSQL / 金仓兼容）

**0.1.0 基线**：仅 `V1__baseline_0_1.sql`。0.1 还没发布，表结构、种子、AI 模块和菜单修正都在这一份里。

- 全新安装：Flyway 只跑这一份。
- 发布前不要再加 `V2__*.sql`。要改库，直接改这份基线。
- 正式发布之后的升级（如 0.1.1）：再新增 `V2__*.sql`，不要改已经发出去的 V1。
- 达梦：见并列目录 `../migration-dm/`（`scripts/pg_to_dm.py` 从本目录转换）。
- openGauss-lite 5.1：见并列目录 `../migration-og/`（`scripts/pg_to_opengauss.py` 从本目录转换，库须 `DBCOMPATIBILITY 'PG'`）。
- 埋点库：独立序列 `../track/migration/T1__baseline_0_1.sql`。
