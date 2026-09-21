# 达梦迁移脚本说明（重定向）

**请勿在本子目录放置 `V*__*.sql`。**

Flyway 默认 `classpath:db/migration` 会**递归**扫描子目录，与 PG 同版本号脚本冲突（`Found more than one migration with version 1`）。

达梦 Oracle 语法脚本与启用方式见并列目录：

→ [`../migration-dm/README.md`](../migration-dm/README.md)（`spring.flyway.locations=classpath:db/migration-dm`）
