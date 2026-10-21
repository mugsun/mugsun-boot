# 更新日志

本文件记录 [mugsun-boot](https://github.com/mugsun/mugsun-boot) 的重要变更，格式参考
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### 修复

- 超限上传稳定返回 400，不再在请求体未读完时断开连接
  - 新增 `server.tomcat.max-swallow-size: 32MB`（Tomcat 默认 2MB）
  - 容器拒绝超限上传时，剩余请求体超过 Tomcat 默认 2MB 吞吐上限会断连
    （spring-boot#41581），提到 32MB 覆盖 21MB 请求上限
  - 超过 32MB 的请求仍会断连，属有意的带宽保护

- openGauss V8 改为兼容 openGauss-lite 5.1 的写法
  - lite 5.1 不支持 `ADD COLUMN IF NOT EXISTS`，原 V8 在 lite 上会中止
  - 改为 DO 块加 information_schema 判断，和 V2、V9、V10 写法一致
  - 企业版上跑过旧 V8 的库校验和会变，升级前执行一次 flyway repair
  - lite 上 V8 失败过的库直接升级；若 history 留有失败记录，先 repair

### 安全

- 自助注册改为可配置，生产默认关闭（不兼容变更）
  - 主配置 `mugsun.auth.register.mode`：OFF、OPEN、APPROVAL
  - 简写 `mugsun.auth.register.enabled=true/false`，等于 OPEN/OFF
  - 两者都设且含义冲突，或设成 APPROVAL（尚未实现），拒绝启动
  - 默认：生产 OFF；显式 active 含 dev/test/local 且不含 prod 才是 OPEN
  - 关闭时 `POST /auth/register` 返回 404
  - 生产要开放注册，请设 `mugsun.auth.register.enabled=true`
  - `GET /auth/captcha/config` 新增 `registerMode`，前端据此隐藏注册入口
  - 注册按 IP（默认 10 次/时）、账号和手机号（各 5 次/时）限流，超限 429
  - 反代后面须设 `MUGSUN_OPENAPI_TRUSTED_PROXIES`，否则全站算一个 IP
  - 新参数 `security.register.register-role-code`（V14 播种为 user）
- V14 播种内置 user 角色，并给注册角色补授 `sys:file:upload`
- 直传签名有效期由 60 分钟降到 15 分钟
- 直传登记前回查对象：大小、类型或文件头不符就删对象并拒绝
- 直传只签到私有暂存前缀 tmp/，登记时先复制到新 key 再对新 key 回验
  - 不通过则新旧 key 都删；登记失败新 key 进待清理队列
  - 暂存 key 复制后立即删，签名过期后再删一次
  - 部署须保证 tmp/ 不公开、不走 CDN，并配 1 天过期的 lifecycle
- 删不掉的存储对象进 Redis 待清理队列，每 10 分钟重删，失败 20 次后报错
  - 删前再校验：只删 tmp/ 下或本次新生成未登记的随机 key
  - 已登记的对象一律不删，打告警并丢弃该项
  - 升级前已在队列、不在 tmp/ 下的旧项会被丢弃，需人工清理对应对象
  - 登记表查不了时原样重排，不计失败；登记异常的新 key 延迟 60 秒复查再删
  - 登记表连续查不了超过 144 轮（约 1 天）时，当轮越线的条目汇总打一条 error，条目仍保留
- 上传上限 20MB 改由容器在接收阶段截断，超限返回 400
- 文本附件跳过开头空白后再查 HTML 标签；mp3 帧头判断收紧
- 操作日志脱敏补上 api_key、secretKey、appSecret 及字符串里嵌的 JSON

- 导出导入改用独立权限码，只有列表读权不能导出
  - `GET /system/user/export` 由 `sys:user:list` 改为 `sys:user:export`
  - 用户导入及导入模板由 `sys:user:add` 改为 `sys:user:import`
  - `GET /system/region/export` 改为 `sys:region:export`
  - V13 新增两个用户按钮权限，按旧码给已有角色补授，升级不掉权限
  - 导出导入接口写操作日志；用户导出仍按数据权限过滤，手机号按字段权限脱敏
  - 前端按钮需改用新码（pc 用户页导出、导入按钮）

- 附件上传需要 `sys:file:upload`，并收紧文件类型
  - `upload`、`presigned-put`、`create` 三个上传入口都校验新码
  - V13 把 `sys:file:upload` 补授给全部现有角色；之后新建的角色要手动勾选
  - 扩展名白名单 + 文件头校验 + 20MB 上限，拒绝 html、svg、js 等
  - 存储文件名、Content-Type 由服务端定，不用客户端上报值
  - 直传签发须带 `size`，签名绑定大小、类型和 `attachment`
  - 本地直出与授权下载一律 `attachment` + `nosniff`

- 表单、流程、规则、租户写接口加方法级权限校验
  - 表单 `sys:form:save/remove`，流程 `sys:flow:deploy/design/design-graph`
  - 流程发布、下线 `sys:flow:definition`；V13 补授给各租户 admin 角色
  - `/system/rule/tenant-init` 只允许平台超管（`sys:rule:tenant-init`）
  - 租户、套餐、租户数据源写接口加注解，并在方法内再校验平台超管

- 启动守卫改为「非 dev/test/local 一律按生产」，生产误开联调账号时拒绝启动
  - **行为变更**：没配 profile 也按生产处理，自测请显式加 `--spring.profiles.active=dev`
  - 只看显式 active：非空、含 dev/test/local 之一且不含生产名才放行
  - profile 名先 trim 再转小写；含 prod、production、prd、staging 即按生产，
    即使同时带 dev
  - 不看 `spring.profiles.default`：只配 `default=dev`、active 为空也按生产
  - 新增 `DeployProfiles`，`InitPasswordHardeningGuard` 与新守卫共用这套判定
  - 新增 `LabSeedProdGuard`：生产开启 `mugsun.lab.seed-fronttest` 即拒绝启动
  - 该开关会播种弱口令联调账号，仅供开发和 e2e
  - 集成测试统一激活 `test` profile，`spring-boot:run` 默认激活 `dev`
- 生产关闭开放接口签名或国密时拒绝启动，各留一个显眼的豁免开关
  - `mugsun.open-api.sign-required=false` 需同时设
    `mugsun.open-api.i-understand-unsigned-requests-are-insecure=true`
  - `mugsun.crypto.gm-enabled=false` 需同时设
    `mugsun.crypto.i-understand-plaintext-passwords-rely-on-tls=true`
  - 豁免时启动打 WARN；关闭国密后密码明文提交，完全靠 TLS 保护
  - 国密关闭时生产的登录、注册、改密请求必须 `isSecure()`，否则拒绝
  - 反向代理后需配 `server.forward-headers-strategy=native`

### 不兼容变更

- 去掉接口加密和字段加密的内置回落密钥；非 dev 环境必须显式配置密钥
  - 依赖 mugsun-core `0.2.0-SNAPSHOT`（Central 没有，须先装 core；core 先合）
  - `MUGSUN_API_KEY` 须为 32 位 hex 或带填充的 16 字节 base64
  - 接口加密改 SM4-GCM，只是传输混淆，不能代替 TLS；前端须同步升级
  - `Sm4CryptoConfig` 不再回落固定密钥；缺 `MUGSUN_SM4_KEY` 拒绝启动
  - 新增 dev profile：接口用临时密钥，字段用 `~/.mugsun/dev-sm4.key`
  - dev 密钥文件创建即 600、目录 700，权限过宽打 WARN
  - `strict-keys` 保留，只作否决：为 true 时不认临时密钥和本地文件
  - `gen-keys.sh` 的 `MUGSUN_API_KEY` 改为 `openssl rand -hex 16`
  - 已部署环境的旧 16 字符 `MUGSUN_API_KEY` 要先换成新格式再升级
  - `MUGSUN_SM4_KEY` 只认 32 位 hex、16 字节标准 base64、16 字符旧写法
  - 16 字符旧写法本版仍兼容并打 WARN，下一版只认 32 位 hex
  - 其他长度拒绝启动，不再补零、截断，没有 legacy 开关
  - **以前配 32 位 hex 或 24 位 base64 的部署也必须迁移**：旧版只取前 16 个字符，
    本版按编码解码，等于换了一把钥；设 `..._LEGACY_PADDED=true` 跑迁移
  - 启动自检：每字段最多抽 3 行密文试解，解不开拒绝启动；空库放行
  - 跳过开关 sm4-self-check=false 只在非生产生效，生产判定同 DeployProfiles
  - 新增离线迁移工具 `Sm4KeyMigrationTool`，覆盖 15 个加密字段
    - 新旧密钥只走环境变量，命令行只认 `--dry-run`、`--apply`
    - 默认 dry-run，按表和字段输出计数和解不开的主键，不输出值
    - `--apply` 单事务写库，有一条解不开就整体回滚；新旧钥相同拒绝执行
    - 旧短密钥、旧回落值加密的数据设 `..._LEGACY_PADDED=true` 还原
  - 用旧回落值、短密钥或 32 位 hex / base64 跑过的部署，升级前必须迁移：
    1. 备份数据库，停应用
    2. 先 dry-run，确认「解不开=0」，再 `--apply`
    3. 换新密钥启动，核对 error 日志「SM4 解密失败」为 0 条
    4. 这些字段里存的第三方凭据全部轮换
  - 解密失败返回 null，不会被写回库里（实体更新跳过 null 列）
  - `gen-keys.sh`、`gen-secrets.sh` 的 `MUGSUN_SM4_KEY` 改为 32 位 hex
  - `docker/full/boot.Dockerfile` 在镜像里先构建 core，不再拉 0.1.0
  - 开发者本地以前用旧回落值加密的数据，升级后会解不开
  - 接口加密不防重放、不绑定接口（暂无 AAD）

### CI

- CI 改为推送任意分支即运行，分支先绿再快进 main
  - `ci.yml` 触发改为 push `**`，新增 `workflow_dispatch`
  - 加 concurrency：同一分支新推送取消旧运行
  - main 上不取消正在跑的运行
  - main 按 SHA 单独分组，排队中的运行不被后来的推送顶掉
  - `'**'` 只匹配分支，不匹配 tag
  - compose-full 只在 main 按路径跑，另加手动触发和每周定时
- 已发布迁移守卫按事件取基线，拿不到基线即失败
  - 其他分支和手动触发用 merge-base(origin/main, HEAD)
  - main 用 event.before，全零或不可达即失败；PR 用 base.sha
  - 新增 `scripts/ci/test_migration_guard.py` 反例测试

- 收紧 workflow 权限并把 Actions 钉到 commit SHA
  - 每个 workflow 顶层 `permissions: contents: read`，禁用 `pull_request_target`
  - Actions 钉 40 位 SHA，行尾注释版本号
  - 新增 `scripts/ci/check-workflows.py` 和 actionlint（钉版本、校验 sha256）

- 接入 gitleaks 全历史扫描
  - gitleaks 8.30.1 钉版本、校验 sha256，`--redact` 不输出密钥值
  - 历史上的测试假值和开发默认值写进 `.gitleaks.toml` allowlist
  - allowlist 按 commit + 文件匹配，改动须安全官审

### 说明

- 许可证由 Apache-2.0 改为 MIT（见 `LICENSE`）
- 当前仓库版本号为 `0.1.0`（见根 `pom.xml`），尚未打 GitHub Release tag
- 依赖 `com.mugsun` 已可从 Maven Central 拉取（`0.1.0`）

### 升级须知

- **只读演示环境（demo profile）：操作日志能写入、连接池调小、登录页不再弹「演示环境只读」**
  - 操作日志哈希链（V8）在 demo 下改走日志账号，只读账号保持纯 SELECT：
    - 锁链头（`lock_timeout = '3s'` 后 `FOR UPDATE`）、读哈希、INSERT、刷新链头
    - 以上在日志数据源的同一独立事务（`REQUIRES_NEW`）、同一连接里完成
    - 记录的是操作尝试：写请求已被只读过滤器拒绝，与业务事务无关
    - 此前链头锁走只读账号，每条都报 `permission denied`；非 demo 不变
  - 已部署的演示库要给日志账号补授权（`docs/demo/sql/demo-accounts.sql` 已更新）：
    `GRANT SELECT ON sys_oper_log, sys_oper_log_chain TO mugsun_demo_log;`
    `GRANT UPDATE (update_time) ON sys_oper_log_chain TO mugsun_demo_log;`
  - 日志数据源必须连主库，否则 demo 启动失败：
    - `MUGSUN_DEMO_LOG_DB_URL` 与 `primary.url` 比较协议、主机（不分大小写）、端口、库名
    - 查询参数可以不同；多主机写法须完全一致；报错里两边 URL 脱敏
  - 连接池调小，给 `max_connections=30` 的演示库留出迁移和运维余量：
    - 业务只读池 5（`MUGSUN_DEMO_DB_POOL_MAX`）、日志池 4（`MUGSUN_DEMO_LOG_POOL_MAX`）
    - 埋点池 4（`MUGSUN_DEMO_TRACK_POOL_MAX`），合计 13
    - 此前三个池都是 Hikari 默认 10，会占满全部非 superuser 连接
  - 只读白名单：精确放行 `GET /auth/social/sources`，登录页不再被拒：
    - 只返回已配置的社交登录源名称
    - 只认 GET/HEAD 和原样路径；尾斜杠、矩阵参数、编码或大小写变体仍拒绝
    - 社交登录本身仍关闭
  - 锁超时：等链头锁超过 3 秒的日志被丢弃，链不受影响：
    - 打 WARN 并计数（`demoLockTimeoutDroppedCount`）
    - 链头 UPDATE 影响行数不是 1 时报错

- **租户编号永不复用**（V11，boot#100 复核 B1）。`uk_tenant_code` 在 PostgreSQL / 金仓 /
  openGauss / 达梦上都改回全量唯一（含已删除行），建租户时查重也包含已删除租户；`uk_tenant_ds_code` 仍只约束未删除行，
  租户可以删掉数据源配置后用同一编号重建。新建租户数据源时，租户编号必须对应一个未删除的租户，否则接口拒绝并提示编号不存在或已删除。
  库里已有同一编号被复用的多行时，V11 会中止并提示查重 SQL，此时还没有改任何索引；核对数据归属并处理后再重新迁移

- **破坏性变更：旧的数字图形验证码已移除，改为行为验证码**（行为验证码由 mugsun-boot#17、#35 引入，本次移除旧数字码；验证码契约
  v0.6.3）。登录、注册、忘记密码发码、发送短信验证码改用行为验证码：`GET /auth/captcha/config` 提示是否需要 →
  `POST /auth/captcha/challenge` 取题 → `POST /auth/captcha/verify` 换一次性
  `captchaToken` → 业务接口请求体带 `captchaToken`。题型 `SLIDER`（滑块拼图）、`ROTATE`（旋转）、
  `WORD_CLICK`（词语点选）、`ICON_CLICK`（图标点选）、`JIGSAW`（拼图还原），由服务端在启用集合里随机选一种。
  - 移除的接口与字段：`GET /auth/captcha`（返回 `captchaUuid`、`captchaImage`、`expireSeconds`
    、`captchaCode`）不再存在；`POST /auth/login`、`/auth/register`、`/auth/forget-code`、
    `/auth/sms-login` 不再读请求体里的 `captchaUuid`、`captchaCode`（传了也忽略）。
    `/auth/sms-login` 不再校验验证码，验证码前移到 `/auth/sms-code`（带 `captchaToken`）
  - 移除的配置：`mugsun.captcha.type=IMAGE`（`SLIDER` / `IMAGE` 切换）、
    `mugsun.captcha.expire-seconds`（旧数字码有效期，已无作用）。`mugsun.captcha.type` 只认
    `SLIDER`（不起作用，启动打 WARN，请删掉）；写 `IMAGE` 或其它值启动失败，报错写明「IMAGE 已移除」
  - 新配置 `mugsun.captcha.default-types`，平台默认
    `SLIDER,WORD_CLICK,ROTATE,ICON_CLICK,JIGSAW`（五种全开；`application-demo.yml` 相同）
    ：默认就是多题型随机，每次取题从这五种里随机出一种，`GET /auth/captcha/config` 返回 `type: RANDOM`；
    键盘模式只出 `SLIDER` / `ROTATE`。只接受上面五个题型名（不区分大小写），空值、未知值、`IMAGE` 一律启动失败，报错写出配置项、
    环境变量名（`MUGSUN_CAPTCHA_DEFAULT_TYPES`）和转义后的原值。租户可单独设置题型（
    `POST /system/tenant/captcha` 的 `types`，仅平台超管）
  - 取舍：五种题型随机时，攻击者可以反复刷新、等到最弱的题型再解，整体强度接近最弱的那一种。后续计划：
    - 同一会话或 IP 在一段时间内固定题型
    - 取了题没答或过期，也算一次失败
    - 拼图改成 3×3，并加接缝扰动
  - 总开关不变：`mugsun.captcha.enabled=false` 时所有场景都不要验证码。租户开关
    `sys_tenant.captcha_enabled` 照旧；客户端开关 `sys_client.captcha_enabled`
    不再参与判定（列与字段暂留）。`mugsun.captcha.show-code` 现在只控制双因子登录验证码回显
  - 演示环境只读白名单放行 `POST /auth/captcha/challenge`、`POST /auth/captcha/verify`（只写
    Redis 挑战记录与日志库），否则演示账号无法登录
  - 数据库：本次没有新迁移。租户开关与题型列来自已发布的 `V9__tenant_captcha.sql`、
    `V10__tenant_captcha_types.sql`（`captcha_types` 为 NULL 时用平台默认
    `mugsun.captcha.default-types`，所以默认题型改成五种随机不用改库）；旧数字码没有菜单或系统参数，不需要清理。Redis
    里旧数字码的 `mugsun:captcha:<uuid>` 键最多 5 分钟后自然过期

  迁移步骤：
  1. 前端（管理端、App、自建登录页）改走上面的行为验证码流程，请求体带 `captchaToken`，不要再调用 `GET /auth/captcha`
     。旧前端连上新后端时登录会返回 `40013`（缺少有效令牌）
  2. 在 yml、compose、`.env`、K8s 配置里删掉 `mugsun.captcha.type` /
     `MUGSUN_CAPTCHA_TYPE` 和 `mugsun.captcha.expire-seconds`；原来写 `IMAGE`
     的部署必须删掉，否则启动失败
  3. 要改启用的题型，设 `mugsun.captcha.default-types`（或 `MUGSUN_CAPTCHA_DEFAULT_TYPES`），
     例如 `SLIDER` 只留滑块
  4. 暂时不能升级前端、又必须先升级后端的，可临时设 `mugsun.captcha.enabled=false` 关闭验证码（登录失败锁定仍然生效），
     前端改完再打开
  5. 生产请把 `captcha/bg/` 等内置素材换成私有图库（见 `captcha/SOURCES.md`）

- **破坏性变更：模块开关取值收紧**（mugsun-boot#96）。GIS / 埋点 / AI 三个模块开关共用一个解析器，yml /
  环境变量与系统参数两层规则如下：
  - yml / 环境变量（`MUGSUN_GIS_ENABLED`、`MUGSUN_TRACK_ENABLED`、`MUGSUN_AI_ENABLED`，
    即 `mugsun.<模块>.enabled`）：不写按开启；只接受恰好 `true` 或 `false`（不区分大小写，前后不能有空格）；
    其它值一律启动失败，报错里写出配置项、环境变量名和原值
  - 系统参数 `gis.module.enabled`、`ai.module.enabled`：判定规则不变（去首尾空格，`true` / `false`
    不区分大小写，`1` / `0` 仍有效，空值按开启）。新增保存校验：其它值保存时拒收，`1` / `0` 能保存但提示已弃用。
    运行时读到升级前留下的非法值按关闭处理，同一键同一值每 10 分钟最多警告一次

  yml / 环境变量升级对照（其它值以前都按关闭处理，现在启动失败）：

  | 以前的写法 | 以前 | 现在 | 请改成 |
  | --- | --- | --- | --- |
  | 不设置 | 开启 | 开启 | 不用改 |
  | `true`、`TRUE` | 开启 | 开启 | 不用改 |
  | `false`、`FALSE` | 关闭 | 关闭 | 不用改 |
  | 空值（如 `MUGSUN_AI_ENABLED=`） | 关闭 | 启动失败 | 关写 `false`，开写 `true` 或删掉 |
  | `1` | 关闭 | 启动失败 | 要开启写 `true`；要关闭写 `false` |
  | `0` | 关闭 | 启动失败 | `false` |
  | 带首尾空格（如 `" true"`、`"false "`） | 关闭 | 启动失败 | 去掉空格 |
  | `yes`、`on` | 关闭 | 启动失败 | 要开启写 `true`；要关闭写 `false` |
  | `no`、`off` 及其它值 | 关闭 | 启动失败 | `false` |

  空值包括环境变量写成 `MUGSUN_AI_ENABLED=`，以及 yml 里只写 `enabled:` 不给值。

  迁移步骤：
  1. 在 compose 文件、`.env`、systemd / k8s 配置和 yml 里找出 `MUGSUN_GIS_ENABLED`、
     `MUGSUN_TRACK_ENABLED`、`MUGSUN_AI_ENABLED`（以及 `mugsun.<模块>.enabled`）
  2. 值为空、`1`、`0`、带首尾空格，或是 `yes`、`on`、`no`、`off` 的，改成 `true` 或 `false`。
     以前这些值都按关闭处理，想保持原状就写 `false`
  3. 或者删掉这个变量，按默认开启
  4. 启动时若报「配置项 … 不合法」，报错里写有变量名和原值，按第 2、3 步改完再启动

- **破坏性变更：内置 MCP Server 暂时下线，正在加鉴权后恢复**（mugsun-boot#89、#91）。原因：Spring AI 的 MCP
  Server 自动装配不经过本项目鉴权，打开即是匿名可调的端点。下线期间 Spring AI 与 MCP SDK 暂时移出依赖，`/sse`、`/mcp`、
  `/mcp/message` 在任何配置下都返回 404。AI 模块调用外部 MCP 工具（「MCP 工具箱」、工作流 MCP 节点）不受影响，
  连接配置仍在 `ai_mcp_tool` 表。
  - 下线期间不起作用的 MCP Server 配置：环境变量 `MUGSUN_AI_MCP_SERVER`，以及
    `spring.ai.mcp.server.*`（含 `enabled`、`name`、`version`、`sse-endpoint`、
    `sse-message-endpoint`、`streamable-http.mcp-endpoint`）。恢复后用什么开关、怎么配置凭据，
    以届时的更新日志为准
  - 随未使用的 Spring AI 组件一起失效的配置（与 MCP Server 恢复无关）：`MUGSUN_AI_MCP_CLIENT`、
    `MUGSUN_AI_OPENAI_API_KEY`、`MUGSUN_AI_OPENAI_BASE_URL`，以及
    `spring.ai.openai.*`、`spring.ai.vectorstore.pgvector.*`、
    `spring.ai.mcp.client.*`。模型地址与密钥一直以 `ai_model` 表为准，不受影响
  - 下线期间的处理：转发 `/sse`、`/mcp`、`/mcp/message` 的反向代理路由会收到 404，健康检查 / 探活不要指向这些路径
  - 旧版本曾设置 `MUGSUN_AI_MCP_SERVER=true`（或 `spring.ai.mcp.server.enabled=true`）
    运行过的部署：这些端点当时无需登录即可调用，请检查反向代理与应用访问日志里对 `/sse`、`/mcp`、`/mcp/message` 的请求，
    确认没有异常调用
  - 依赖检查：下线期间根 pom 的 maven-enforcer `bannedDependencies` 拦下
    `org.springframework.ai`、`io.modelcontextprotocol*`（含传递依赖），恢复时按鉴权方案调整这条规则；
    `scripts/check-banned-dependencies.sh` 在 CI 中证明规则会触发；
    `NoEnforcerSkipGuardTest` 禁止在 pom、`.mvn` 配置、CI 工作流与脚本里跳过 enforcer
- openGauss / 达梦：迁移 `V7__unique_index_delete_flag` 把 6 个唯一索引（`uk_tenant_code`、
  `uk_user_tenant_username`、`uk_user_role`、`uk_role_menu`、`uk_role_dept`、
  `uk_tenant_ds_code`）改为只约束未删除的行，逻辑删除后的行不再占着键：删除租户后可以再建同编号租户，撤销菜单后可以再授权，
  同一个键可以反复删除（#95）。做法是表达式 / 函数唯一索引，每个键列写成 `CASE WHEN is_deleted = 0 THEN 列 END`，
  与 PG 的部分唯一索引同口径。PostgreSQL / 金仓本来就是部分唯一索引，V7 在 PG 上不改动
  - **手机号改为同租户唯一（openGauss / 达梦）**：`uk_user_tenant_phone` 原来在这两种库上只是普通索引，V7 改成与
    PG 相同的唯一索引（未删除、手机号非空非空串的用户，同一租户内手机号不能重复；手机号为空的用户不受影响）。库里已有同租户重复手机号时 V7
    会报「V7 升级中止」并停下，此时还没有改任何索引。查重 SQL 与处理步骤见
    [升级指南 · 同租户重复手机号](https://docs.mugsun.com/boot/upgrade)。达梦上失败的 V7 会在
    `flyway_schema_history` 留一条 `success = 0` 的记录，处理完重复数据后要先删掉这条记录再启动
  - 版本号用 V7，当时跳过了未合并 PR 占用的 V4–V6。V7 已在 main 上，且未开启 Flyway `outOfOrder`。
    审计链加固因此用 V8（不再占用 V6），已升到 V7 的库会按顺序执行 V8。验证码迁移合并时同样后移到 V8 之后
- 删除租户：除用户、用户-角色、部门、岗位、角色外，现在也会逻辑删除该租户角色的角色-菜单、角色-部门关联和租户独立数据源配置；整个删除在同一个事务里，
  任何一步失败整体回滚。已注册的独立数据源在事务提交后注销。用户的岗位是 `sys_user.post_id` 列，没有单独的关联表，随用户一起删
- 埋点错误堆栈：顶层 `props.stack` 的保留长度从 1024 放宽到 4096 字符，与 SDK error 插件一致；其它字符串属性仍是
  1024，单事件 props 总量仍是 16KB。需配合 `@mugsun/track-web` 的同名修复，旧版 SDK 在客户端已截到 1024。
  props 超过 16KB 时先缩短 `stack`（按 UTF-8 字节、不拆字符），再从尾部摘键；堆栈帧 URL 的 query / hash
  去掉（防 token 落库）；所有截断不再拆开代理对。错误指纹（服务端计算的 `error_fingerprint`）改为先去掉帧 URL 的 query
  / hash 再计算：升级后错误指纹会变化，已有的错误分组会断开一次（升级前后的同一错误分属新旧两组），之后不再因 URL 参数不同而拆组
- 埋点来源域名：Web SDK 只上报完整 `referrer` URL，此前服务端只读 `referrer_domain`，导致来源维度恒为空。现在没有
  `referrer_domain` 时从 `referrer` 取主机名（小写，不剥 `www.`）；会话的来源域名和 `utm_source`
  跟随最早的事件，同一会话的事件分批到达时也能回填，只对升级后接收的新事件生效，历史数据不回填。来源域名统一小写（含自定义上报端直接给的
  `referrer_domain`）；`referrer` 解析失败（如 query 里有空格、`|`）时用宽松规则取主机名；**同站来源不再计入**：
  与页面主机（上报请求的 `Origin`，缺省取 `Referer` 头）相同的 referrer 记为空，站内跳转不会让来源维度全是自己的域名，
  子域不算同站

- 埋点日表历史 UV 回填：新增迁移 `T2`（备份表 `track_stats_day_uv_backup`）和可重复执行的回填任务，把
  `track_stats_day` 历史 UV 改成 `distinct_id` 口径，默认最近 90 天。单个「应用 × 天」超时只跳过该天、
  不卡住其它应用，可按应用放宽超时；PowerJob 结果仅在全部完成时为成功。步骤见
  [docs/upgrade/track-uv-backfill.md](docs/upgrade/track-uv-backfill.md)
- 埋点看板按租户时区切日（默认 `Asia/Shanghai`，配置 `mugsun.track.timezone.default-zone` /
  `MUGSUN_TRACK_TIMEZONE`，按租户编号覆盖 `mugsun.track.timezone.tenants`）：趋势图、错误列表、
  概览卡片、页面、性能、漏斗、留存、事件分析事件列表、地域地图的「今天」都从当地 0 点开始，之前按 UTC 切日。行为变化：
  - 漏斗时间窗从「最近 days×24 小时」滚动窗口改为从首日当地 0 点算起，`days=1` 即今天 0 点至今
  - UV 口径：日趋势里每天的 UV 是该当地日内去重设备数（`distinct_id`）；维度趋势未指定维度值时不再把各维度值的 UV 相加
  - 概览「会话数」= 每个当地日的活跃会话数之和
  - 时区不是 UTC 时，日趋势/概览/性能直接按明细统计（日表按 UTC 日汇总），大库上会比读日表慢；长期方案见 PR 说明里的 15 分钟预聚合设计

- GIS 空间分析 `/gis/analyze` 的 `engine=java` 语义变更（mugsun-boot#48）：
  不再转交图层查询（tryOffload），整份入参——包括 `layerId` 指向的整层 `data_json`——全部载入 JVM 内存用 JTS
  分析。大图层会明显占内存、变慢，只建议用于诊断和对比。不带 `engine` 时行为不变（有 PostGIS 时缓冲 / 包含仍下沉数据库）
- GIS 米制计算的 Java 回落路径（mugsun-boot#48）：
  - 缓冲 / 距离 / 面积 / 长度改为按要素局部方位等距投影计算，不再在 Web Mercator 上直接按米算（旧版北京 500 m 缓冲实际约
    383 m）。升级后同一请求的结果会变化
  - 图层查询（半径、最近邻、缓冲）遇到算不出的要素不再让整次查询失败：半径 / 最近邻回落求几何最近点，缓冲跳过。（#48 时极区指 |纬度| > 85°；
    mugsun-boot#57 起极区照常计算，只有「缓冲包住极点」和跨 180° 经线的要素会跳过）响应新增 `skipped`（Java 分支总带，
    PostGIS 分支不带），并按「操作 + 图层」每分钟最多记一条 WARN
  - 空间分析转交图层查询时同样带回 `skipped`；命中结果里的极区 / 跨 180° 要素不计入面积 / 长度汇总，数量记在
    `metrics.metricsSkipped`
  - 分析单个几何（`/gis/analyze` 入参，或带 `engine=java`）遇算不出的几何报业务异常（mugsun-boot#57
    起只剩「缓冲包住极点」和跨 180° 经线两种）；半径 / 最近邻的查询点在极区不再报错（#48 时 |纬度| > 85° 报错，
    mugsun-boot#57 起照常计算）
  - 空几何：距离视为 +∞，缓冲为空，面积 / 长度为 0
- GIS PostGIS 路径图层缓冲（mugsun-boot#63）：横跨全经度、经度走向无法确定的面（如在 ±180 切开的极冠）
  不再静默返回丢了极冠的结果，改为跳过并计入 `skipped`，与 Java 路径一致
- GIS 库里存的跨 180° 要素（mugsun-boot#64）：bbox / intersects / contains 不再在 0° 附近误命中、
  也不再在 180° 附近（含西>东 bbox）漏命中；半径预筛越过 ±180° 时拆框；Java 路径半径对 180° 附近的面不再误判为 0 米。
  查询时识别（平面包络宽于 180° 且 `ST_ShiftLongitude` 后不宽于 180°），换到 [0, 360) 经度判断，不改存储、无迁移
- GIS 回落路径跨 180° 经线按 RFC 7946 §3.1.9 处理：要素逐部件展开成连续经度后计算距离 / 面积 / 长度，在 180°
  拆开的多部件要素按一个整体算；缓冲结果越过 180° 时用 JTS 与 [-180,180] 两侧框求交切成 MultiPolygon，不再报错或跳过。
  只有单个部件展开后仍宽于 180°（走向无法确定）时才报错 / 计入 `skipped`。视野查询 `/gis/spatial/bbox` 支持
  minLon > maxLon（RFC 7946 §5.2，跨 180° 的范围，拆成两个框查询）
- GIS 回落路径的投影与面积（mugsun-boot#57）：缓冲 / 量距改用由 GeographicLib 正反算定义的椭球方位等距投影（|纬度| ≥
  88° 以极点为原点），去掉 85° 极区限制；面积改用 GeographicLib `PolygonArea`，长度逐段测地距离求和，边都按测地线（与
  PostGIS `geography` 一致）。高纬长边要素的面积、长度、距离会与旧版（按经纬度直边）明显不同
- GIS 测地距离改用 GeographicLib-Java 2.1（MIT，Karney 算法，与 PostGIS 2.2+ `geography` 同源）
  ，替换自写的 Vincenty 和大圆回落；近对蹠点也收敛。新增第三方依赖
  `net.sf.geographiclib:GeographicLib-Java`，见 NOTICE
- 只读演示模式（`demo` profile）数据库分账号：业务库用只读账号、登录/访问/操作日志用只插入的日志账号、埋点库账号只有
  INSERT/SELECT；demo 下关闭 Flyway 与写库启动任务/定时任务，启动时校验迁移版本一致，迁移改由 owner 账号以
  `--mugsun.demo.migrate-only=true` 执行（建账号脚本见 `docs/demo/sql/demo-accounts.sql`）
  。demo 下埋点 upsert 降级为 `ON CONFLICT DO NOTHING`，会话统计只记首批，普通模式不变。demo
  下登录按「账号+IP」锁定、登录与埋点按 IP 限流（Bucket4j + Redis）、导出/SSE 并发上限（Resilience4j
  Bulkhead），关闭两步验证、短信/邮件发送、社交登录与 AI 通道，Redis 用独立 db。新增第三方依赖 Bucket4j、
  Resilience4j，见 NOTICE
- 一键全栈（`docker-compose.full.yml`）：
  `docker compose -f docker-compose.full.yml run --rm init show` 在 admin
  改过口令后只提示「初始密码已失效」，不再打印初始口令。判断方式是用初始口令校验库里 admin 当前的口令哈希；连不上库时照常显示并提示无法确认。
  secrets 卷里的 `MUGSUN_INIT_PASSWORD` 保留（后端启动守卫与重置口令仍要用）。init 镜像新增
  `postgresql16-client`、`py3-bcrypt`

### 计划

- （暂无已定条目）

## [0.1.0] - 开发中

### 新增

- 只读演示模式访问日志（B4）：`AccessLogFilter` 每个请求一行（IP、方法、路径、状态、耗时、用户、traceId；不记 query 和
  body），demo profile 下单独写 `*-access.log`，按天滚动 gzip，默认保留 200 天、
  不设总量上限（`mugsun.demo.access-log.path/max-history`），日志盘剩余空间低于
  `mugsun.demo.access-log.min-free-space`（默认 10GB，单位写 `GB` 或 `G`（也认 `GiB`、`K/KB`
  、无单位即字节，1024 进位），可带小数并向下取整到整字节，如 `10GB`/`10 G`/`1.5GB`/`512M`；只去首尾空白，`1 0GB`
  无效；超出 long 视为无法解析，解析失败回落 10GB 并 WARN，≤0 关闭告警并 WARN）时 WARN，每小时检查、只在状态变化时记录；
  `max-history` 低于 180 或无法解析时启动改用 180 并 WARN，≥180 的小数（如 `200.5`）向下取整、不会改为 180，
  超过 int 上限（含 `1E+999999999`）取 Integer.MAX_VALUE，解析或取整出错回落 180 并 WARN；
  读磁盘余量抛运行时异常（如 SecurityException）时同样进入「读不到」状态、只 WARN 一次（WARN 带上异常信息），不受
  LogCleanJob 影响；demo 下日志库清理保留期不低于 180 天。普通模式不装配、不改变
- 模块化 Spring Boot 后端：`mugsun-boot-core` / `gis` / `track` / `ai` / `server`
- Docker Compose 仅启动 PostgreSQL、Redis 等依赖；应用由 Maven 启动
- 与 `mugsun-pc`、`mugsun-track` 联调路径见 README 与文档站

[Unreleased]: https://github.com/mugsun/mugsun-boot/commits/main
[0.1.0]: https://github.com/mugsun/mugsun-boot/tree/main
