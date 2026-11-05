# AiFlow foreach / parallel 契约（P0）

> 实现对齐维护官拍板（Q1–Q10 默认）。pc 设计器 palette 另刀。

## 节点

| type | 说明 |
|------|------|
| `foreach` | 数组迭代，串行跑 `bodyNodeIds`，汇合后出主边 |
| `parallel` | 同层多分支 waitAll；失败 continue 不取消其余 |

二者**不是** warm-flow 审批图网关；AiFlow 自有执行器调度。

## foreach 配置（`data`）

- `inputPath`（必填）：vars 键或点路径，值须为数组
- `itemVar`/`indexVar`：默认 `item` / `index`
- `maxIterations`：默认 20，硬顶 100（clamp）
- `timeoutMs`：默认 120000，硬顶 300000
- `aggregate`：`array`（默认）| `last` | `none`
- `outputVar`：默认 `foreachResults`
- `onItemFailure`：`continue`（默认）| `abort`
- `bodyNodeIds`：同图线性节点 id；**禁**嵌套 foreach/parallel
- 截断（数组更长）：节点仍 **success**，`truncated=true`
- 外层主路径 steps 计 1；单轮 body 步硬顶 50
- 轮间 vars **不深隔离**（item/index 覆盖，其它累积）

## parallel 配置（`data`）

- `branches[]`：至少 2，至多 maxBranches（默认 8 / 硬顶 16）
- `branches[].id` / `bodyNodeIds` / `outputVar`（默认 `branch_{id}`）
- `timeoutMs`：同上
- P0 唯一汇合：`waitAll`
- 分支失败 → 节点 **success** + `partial=true`；写 `parallelResults` 与各 `outputVar`
- 分支线程：有界池 + `TenantContext.execute(租户)`

## 错误码

`AI_FOREACH_INPUT_NOT_ARRAY` / `AI_FOREACH_MAX_ITERATIONS` /
`AI_FOREACH_TIMEOUT` / `AI_FOREACH_BODY_INVALID` /
`AI_FOREACH_ITEM_ERROR` / `AI_PARALLEL_BRANCH_INVALID` /
`AI_PARALLEL_TIMEOUT` / `AI_PARALLEL_BRANCH_FAILED`；
未分类仍 `EXECUTE_ERROR`。

## 可观测

- 控制节点 `outputJson`：`iterations[]` 或 `branches[]`（含 status/error/耗时/steps）
- body 步另插 `ai_app_node_run`，`node_id`=`{parent}#{index|branch}#{child}`（≤64）

## 审批兼容

- **不改** `FORBIDDEN_NODE_TYPES`；整图扫描，body 在 `nodes[]` 即覆盖
- foreach/parallel 本身可内嵌；body 勿含 db/http/mcp/code/notice
- 演示流 `ai-foreach-preaudit`（lab：`mugsun.lab.seed-fronttest`）

## Lab 种子应用

| 名称 | 用途 |
|------|------|
| 数组迭代演示 | 直跑 foreach+text |
| 两路并行演示 | 直跑 parallel 两路 |
| 迭代预审演示 | 审批 foreach→ai_summary/pass/reason |

## 本刀不做

元素级并行、租户并发 Redis 闸、pc palette、子流程可视化嵌套大重构。
