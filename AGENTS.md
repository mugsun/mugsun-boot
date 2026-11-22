# AGENTS.md — mugsun-boot

本文件写给在本仓工作的开发者和 AI 助手。内容以本仓代码为准；与代码不一致时以代码为准，并请顺手修正本文件。

## 仓库是什么

后端参考实现，依赖 [mugsun-core](https://github.com/mugsun/mugsun-core)（当前依赖发布版 `0.3.0`，基于 Spring Boot 4，在 Maven Central 上，不用先装）。Maven 多模块：

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
- 条件只在查询时注入，按 id 改、删的写接口不会自动受约束，要自己校验目标行在操作者的范围内。用户管理用 `UserDataScopeGuard`：先取 `operatorScope()`，写之前 `assertCovers`，新建或改部门之后在同一事务里 `assertStillCovers`

租户隔离与数据权限正交：忽略租户不会关掉数据权限。

## Flyway

三套方言同号同义，每套一个基线 `V1__baseline_0_2.sql`：

- PostgreSQL / 金仓：`mugsun-boot-core/src/main/resources/db/migration/`
- 达梦：`…/db/migration-dm/`
- openGauss：`…/db/migration-og/`

埋点库单独一套：`mugsun-boot-track/src/main/resources/db/track/migration/T1__baseline_0_2.sql`。

规则（见同目录 `README.md` 与根 `CONTRIBUTING.md`）：

1. **0.2 发布前改库直接改基线**，三套方言一起改，目录里不留增量脚本；改完重建库（旧库校验和对不上，应用不会启动）。
2. **已发布的迁移不可修改**（一个字节都不行）。清单：`db/RELEASED-0.2.sha256`，现在还没有登记项；校验：`scripts/check-released-migrations.sh`（CI 步骤 `Guard released migrations`）。
3. 发布后改库只新增 `Vn__说明.sql`，三套方言同号；尽量幂等。版本号取 `main` 上最大的号加 1，Java 迁移也占号：主库下一个是 V18，埋点库下一个是 T5。
4. 发版时把新文件追加进清单（`scripts/check-released-migrations.sh --print <文件…>` 生成行），清单只能追加。0.2.0 发布时登记四个基线。
5. 删掉或改名迁移脚本后先 `mvn clean`：Maven 不清 `target` 里的旧资源，残留的脚本会让 Flyway 报 `Found more than one migration with version N`。
6. 测试按段头（`-- 原 <文件名>`）读基线里的各段，工具类是 `MigrationBaseline`；段头不要删、不要改名。

## 组件速查

| 需求 | 位置 |
| --- | --- |
| 统一返回 | `com.mugsun.core.tool.api.R` |
| 业务 / 权限异常 | `ServiceException` / `ForbiddenException`（core） |
| 权限注解 | `@SaCheckPermission("sys:role:list")` 等 |
| 数据权限 | `@DataScope` + `DataScopeTables.register` |
| 租户切换 / 忽略 | `TenantContext.execute` / `ignore` |
| 分布式锁 | `@DistributedLock` / `LockTemplate`（core 的 `mugsun-starter-lock`）。不要再手写 `setIfAbsent` 加脚本；锁名不带 `mugsun:`，前缀由 `mugsun.lock.key-prefix` 加 |
| 多语言 | 提示语照常写中文（`throw new ServiceException("用户不存在")`），返回前按请求头 `Accept-Language` 整句查翻译（core 的 `mugsun-starter-i18n`）。英文配在句子所在模块的 `src/main/resources/i18n/` 下，键就是中文原文：mugsun-boot-core 是 `mugsun-boot_en.properties`，ai、gis、track 各带各的 `mugsun-boot-<模块>_en.properties`（core 里配过的句子不用再配）；没配的句子照旧返回中文。带变量的用 `I18n.get("用户 {0} 不存在", name)`，不要用加号拼（拼出来的句子查不到翻译）；这种句子按 MessageFormat 解析，原文和译文里的单引号都写两个（`''`），字面的花括号写成 `'{'`、`'}'`，数字原样输出不加千分位。只有 `msg` 会自动翻译：放在返回数据里给人看的字用 `I18n.translate("…")`，存在库里的整句固定说明（连接状态、同步结果）读出来返回时再 `I18n.translate(存的值)`；替用户起的默认名字（流程的开始、结束节点）跟着创建人的语言走。几句话连成一段（多条提醒、多条修复说明）用 `Sentences.join(…)`，不要自己写分号：中文用分号连，英文每句以句号结尾、中间空一格。带数量的句子（「已删除 {0} 个图层」）英文按数量分单复数：`Deleted {0} {0,choice,0#layers|1#layer|1<layers}`，数量要传数字（`list.size()`），不要先转成字符串。名词不要当变量拼进句子（`label + "请选择开启或关闭"`），一种情况写一整句。要存起来、过后给别人看的句子（上报被拒收时留给管理员看的原话）不要存拼好的话：存 `StoredSentence.pack("…{0}…", 值)`，读出来返回时 `StoredSentence.render(存的值)`，谁看按谁的语言拼；存的地方有长度上限用 `StoredSentence.fit(存的值, 上限)`，不要自己截断（截在数字中间会读出另一个数）。靠关键词理解用户输入的地方（「问数据」的规则解析）中英文关键词都要认，英文提示里举的例子自己要能被识别。过滤器、拦截器里自己往 response 写 JSON 的地方用 `DirectResponse.msg(request, "…")`。改了中文原文要同步改翻译文件，各模块的 `*I18nBundleTest`（继承 core 测试代码里的 `AbstractI18nBundleTest`）会拦；写进日志、存进库的记录，短信、邮件和告警通知的正文，生成代码的模板，初始化数据不翻译 |
| 代码生成 | `com.mugsun.boot.gen`（`GenController`、`GenNaming` 等） |
| 本地密钥示例 | `config/application-local.yml.example`（勿提交真实密钥） |
| 可选模块说明 | `docs/可选模块装配.md` |

## 测试与构建

```bash
# core 用 Central 上的发布版，不用先装。要联调还没发布的 core 改动：在 ../mugsun-core 执行 mvn -B install -DskipTests，
# 再把根 pom 的 mugsun.version 改成它的版本（只用于本地联调，提交前改回发布版）
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
- 推 main 前的防误推（同步清单 3.20）：
  1. 先核对这个完整 SHA 已经审过（审查结论里写的就是它），并且 `gh run list --commit <完整SHA>` 显示同名检查全绿；短 SHA 查到空不算核对。
  2. 推送一律写全 refspec：`git push origin <sha>:refs/heads/<分支>`，不用裸 `git push`、`HEAD` 或依赖 upstream 跟踪。
  3. 还没审的本地提交，不放在跟踪 main（upstream 为 origin/main）的分支上。
  4. 改动 `.github/` 下任何文件的提交，必须先有安全官签字，才能进 main。
  5. 一个 worktree 只给一条工作线用，另开的工作线一律新建自己的 worktree；推送前用 `git rev-parse HEAD` 对照审过的 SHA。被打断的 push 先用 `git ls-remote` 和 `ps` 确认没有在后台跑完，再做下一步。
- 鉴权、租户、密钥、新依赖、公开 API、CI workflow 属于敏感改动，需安全审查后再合。
