# AGENTS.md — mugsun-boot

本文件写给在本仓工作的开发者和 AI 助手。内容以本仓代码为准；与代码不一致时以代码为准，并请顺手修正本文件。

## 仓库是什么

后端参考实现，依赖 [mugsun-core](https://github.com/mugsun/mugsun-core)（当前依赖 `0.2.0-SNAPSHOT`，Maven Central 上没有，先在 `../mugsun-core` 执行 `mvn -B install`）。Maven 多模块：

| 模块 | 作用 |
| --- | --- |
| `mugsun-boot-core` | 系统业务（用户、租户、权限、验证码、工作流等） |
| `mugsun-boot-gis` | GIS 可选模块 |
| `mugsun-boot-track` | 埋点服务端可选模块 |
| `mugsun-boot-ai` | AI 可选模块 |
| `mugsun-boot-server` | 可运行应用入口与集成测试 |

## 分层与命名

包根：`com.mugsun.boot.<业务包>`。一个业务包内常见子包：

- `entity`：表实体，`@Table("表名")`，继承 `com.mugsun.core.mybatis.base.BaseEntity`
- `mapper`：接口，继承 MyBatis-Flex `BaseMapper<实体>`（如 `SysRoleMapper`）
- `service`：具体类（如 `DictService`），**没有** `XxxService` / `XxxServiceImpl` 成对接口
- `controller`：`@RequestMapping` + `@SaCheckPermission("模块:资源:动作")`，返回 `com.mugsun.core.tool.api.R`

示例路径：`mugsun-boot-core/src/main/java/com/mugsun/boot/system/{entity,mapper,service,controller}`。

insert / update 前调用实体的 `sanitizeForInsert()` / `sanitizeForUpdate()`，不要信任请求体里的审计字段。

## 实体基类

`com.mugsun.core.mybatis.base.BaseEntity`（定义在 mugsun-core）：

- `id`：雪花主键
- `createTime` / `updateTime`：数据库 `now()` 填充
- `isDeleted`：逻辑删除（0 正常 / 1 删除）

租户字段 `tenantId` **不在**基类里，需要隔离的表在实体上自行声明（列名 `tenant_id`）。

## 租户

入口与工具都在 `com.mugsun.boot.tenant`：

- `TenantConfig`：注册 `SaTokenTenantFactory` 给 MyBatis-Flex `TenantManager`
- `TenantContext`：当前租户解析与作用域切换
  - `current()` / `resolveTenantIds()`：读当前租户
  - `execute(tenantId, …)`：临时切到指定租户（可重入）
  - `ignore(…)`：**唯一**忽略租户隔离的入口（不要散落 `withoutTenantCondition`）
  - 哨兵：`IGNORE`（空串）、`ALL`（`"-1"` 查看全部）
- `TenantTaskDecorator`：`@Async` / 虚拟线程间透传租户，finally 清理
- `TenantGuardInterceptor`：鉴权后校验平台专属路径、租户可用性、请求头 `X-Tenant-Id` 与会话一致、套餐模块
- 平台租户编号：`TenantConstants.DEFAULT_TENANT_ID` = `"000000"`；请求头常量 `TenantConstants.TENANT_HEADER` = `"X-Tenant-Id"`
- 平台超管判定：`TenantContext.isPlatformSuperAdmin()`（平台租户 **且** 持内置 `admin` 角色）

跨租户查询只把最小范围包在 `TenantContext.ignore` / `execute` 里。

## 数据权限

包：`com.mugsun.boot.datascope`。

- 查询方法上加 `@DataScope`；`DataScopeAspect` 激活后，由 `MugsunDataScopeDialect` 对受控表注入条件
- 受控表登记：`DataScopeTables.register(表名, 部门列, 本人列)`；内置已登记 `sys_user`（`dept_id` / `id`）
- 规则实现：`rule/` 下的 `AllDataRule`、`SelfDataRule`、`DeptDataRule`、`DeptAndChildDataRule`、`CustomDeptDataRule`、`CustomSqlDataRule`

租户隔离与数据权限正交：忽略租户不会关掉数据权限。

## Flyway

三套方言同号同义：

- PostgreSQL / 金仓：`mugsun-boot-core/src/main/resources/db/migration/`
- 达梦：`…/db/migration-dm/`
- openGauss：`…/db/migration-og/`

规则（见同目录 `README.md` 与根 `CONTRIBUTING.md`）：

1. **已发布的迁移不可修改**（一个字节都不行）。清单：`db/RELEASED.sha256`；校验：`scripts/check-released-migrations.sh`（CI 步骤 `Guard released migrations`）。
2. 改库只新增 `Vn__说明.sql`，三套方言同号；尽量幂等。
3. 发版时把新文件追加进 `db/RELEASED.sha256`（`scripts/check-released-migrations.sh --print <文件…>` 生成行），清单只能追加。

## 组件速查

| 需求 | 位置 |
| --- | --- |
| 统一返回 | `com.mugsun.core.tool.api.R` |
| 业务 / 权限异常 | `ServiceException` / `ForbiddenException`（core） |
| 权限注解 | `@SaCheckPermission("sys:role:list")` 等 |
| 数据权限 | `@DataScope` + `DataScopeTables.register` |
| 租户切换 / 忽略 | `TenantContext.execute` / `ignore` |
| 代码生成 | `com.mugsun.boot.gen`（`GenController`、`GenNaming` 等） |
| 本地密钥示例 | `config/application-local.yml.example`（勿提交真实密钥） |
| 可选模块说明 | `docs/可选模块装配.md` |

## 测试与构建

```bash
# core 0.2.0-SNAPSHOT 不在 Central，先装到本地：
cd ../mugsun-core && mvn -B install -DskipTests && cd -
mvn -B install -DskipTests          # 建议在仓库根带 -am，避免只编 server 吃到 ~/.m2 旧 jar
mvn -B verify                       # CI 同款（含 surefire）
mvn -pl mugsun-boot-server -am spring-boot:run
scripts/check-released-migrations.sh
scripts/check-banned-dependencies.sh
```

集成测试在 `mugsun-boot-core/src/test` 和 `mugsun-boot-server/src/test` 两处；基类 `AbstractIntegrationTest` 在 `mugsun-boot-core`。
测试用 Testcontainers 自起数据库，不需要 `docker compose up`，也不要连共享的本地库或 Redis；`docker compose` 只给手动跑应用用。

- `java -jar` 启动时要显式传 `--spring.profiles.active=...`：没配 profile 按生产处理，缺密钥会拒绝启动。
- 多租户列名由 `mybatis-flex.global-config.tenant-column` 指定（`mugsun-boot-core/src/main/resources/application.yml`，当前为 `tenant_id`）。
- 接口加密的跨端向量在 mugsun-core 的 `mugsun-starter-web/src/test/resources/crypto/api-crypto-vectors.json`；`.gitleaks.toml` 的任何改动（新增放行规则、改 `paths`/`regexTarget`）都要安全官审查后再合。

> 请始终在仓库根目录、带 `-am` 构建。只编 `mugsun-boot-server` 且不带 `-am` 时，会从 `~/.m2` 取上次 install 的 `mugsun-boot-core`，可能是别的分支的旧代码。

## 本地构建

- 重型任务（构建、类型检查、e2e、测试容器、dev server 等）一律经仓库父目录 `mugsun/` 下的 `_locks/heavy.sh` 排队，同一时间只跑一个：
  ```bash
  ../_locks/heavy.sh <名字> <做什么> -- <命令...>
  # 例：../_locks/heavy.sh 平台后端工程师 "core 单测" -- mvn -q test
  ```
- 前面有任务时脚本每 30 秒重试并打印当前占用者；空闲内存低于 30% 时不开跑（退出码 75），先停掉闲置的 dev server 再试。
- 脚本会在未设置时自动设 `NODE_OPTIONS=--max-old-space-size=3072`，不要在命令里再调大。
- dev server 用完即停，不要挂着。

## 提交与推送

- Conventional Commits，主题中文，不超过 50 字，不用 emoji；详见 `.github/COMMIT_CONVENTION.md`。
- `CHANGELOG.md` 写在 `## [Unreleased]` 下，**每行不超过 80 字**。
- 不用 `git stash`；切换工作用 `git worktree`。
- 提交的作者和提交者一律用 `mugsun <bydongxin@gmail.com>`：只在仓库级配置（`git config --local`）里设，或用 `git -c user.name=… -c user.email=…` 传入，不改全局配置。已推送的历史不改；未推送的提交推送前用 `git rebase --exec 'git commit --amend --no-edit --reset-author'` 重做，并确认提交者也是 bydongxin@gmail.com。这条 rebase 只用于未推送的提交；已推送的提交改身份要从 origin/main 新开分支 cherry-pick 后 `commit --amend --reset-author`，旧分支不动。
- 合入 main 后，作者自己删掉远端分支（`git push origin --delete <分支>`）。
- **只有分支最新的 commit 已经在分支上跑过同名必需检查、并且全绿，才可以快进推入 main。推送前用 `gh run list --commit <sha>` 自己确认。不要 force push。**
- 鉴权、租户、密钥、新依赖、公开 API、CI workflow 属于敏感改动，需安全审查后再合。
