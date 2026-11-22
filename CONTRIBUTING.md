# 贡献指南

本仓库的贡献流程以账号默认指南为准（中文）：

**[贡献指南](https://github.com/mugsun/.github/blob/main/CONTRIBUTING.md)** · [English](https://github.com/mugsun/.github/blob/main/CONTRIBUTING_EN.md)

安全漏洞请按 [SECURITY.md](https://github.com/mugsun/.github/blob/main/SECURITY.md) 私密报告，不要公开开 Issue。

## 本仓要点

- 默认分支：`main`
- 提交信息：Conventional Commits，主题中文；详见 [`.github/COMMIT_CONVENTION.md`](./.github/COMMIT_CONVENTION.md)（若存在）
- 变更记录：见 [CHANGELOG.md](./CHANGELOG.md)
- 文档：[docs.mugsun.com](https://docs.mugsun.com/)
- 后端参考实现；依赖 `mugsun-core` `0.3.0-SNAPSHOT`（Central 没有，须先在平级 core 仓 `mvn -B clean install -DskipTests`）
- 本地：Docker Compose 起依赖 → Maven 启动应用，详见 README

## 数据库迁移

每套库现在只有一个基线脚本（主库三套方言各一个 `V1__baseline_0_2.sql`，埋点库 `T1__baseline_0_2.sql`）。

- 0.2 发布前：改库直接改基线，三套方言一起改，改完重建库。
- 0.2 发布后：已发布的 Flyway 迁移（清单见 `db/RELEASED-0.2.sha256`）不可修改，CI 会校验哈希。改库请新增 `Vn__*.sql`（三套方言同号，主库从 V18 起），发版时把新迁移追加进清单。
- 0.1.0 和合并基线之前建的库不能原地升级，要重建。

详见 `mugsun-boot-core/src/main/resources/db/migration/README.md`。
