# 这个目录不放迁移脚本

不要在这个子目录里放 `V*__*.sql`。

Flyway 默认扫描 `classpath:db/migration`，而且会递归扫描子目录。

脚本放在这里，会和上一级目录里同版本号的 PostgreSQL 脚本冲突，启动时报错：

```text
Found more than one migration with version 1
```

达梦的脚本在和 `migration` 并列的 `db/migration-dm` 目录里，说明见[那里的 README](../../migration-dm/README.md)。

启用时把 `spring.flyway.locations` 设为 `classpath:db/migration-dm`。
