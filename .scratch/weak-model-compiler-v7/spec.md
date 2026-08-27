# 弱模型友好的 Designer Compiler v7

状态：分票实施中；001 已完成，002 正在交付。本文同时记录后续 003/004 的目标边界。

## 1. 目标

提高新软件设计在弱模型下的端到端成功率，同时保持现有安全和验收强度：

- 降低因格式别名、唯一可推导归属和唯一最优能力选择产生的 Compiler 调用与整稿重设计；
- 阻止“设计编译成功，但正确修改了设计遗漏路径后在执行期失败”；
- 保持路径越界、删除、外部系统写入、闭集外能力、机器能力缺失和结构冲突失败关闭；
- 以端到端可执行率为主指标，设计编译通过率只作过程指标。

## 2. 术语

### 2.1 Stage 外路径

位于登记项目根内，但不在某个 Stage 当前 `allowedPaths` 中的路径。它可能是合法交付物，必须由设计事实证明并分配到 Stage，不能等执行期再发现。

### 2.2 项目根外路径

canonicalize 后不属于登记项目根的路径。它不是本设计可自动补全的“遗漏路径”，继续走现有人工权限或多项目边界，不能进入 `allowedPaths`。

### 2.3 Mutation Obligation

从冻结需求和受控设计中确定性提取的写入义务。最小字段：

```text
obligationId
pathRule
pathKind = EXACT_PATH | PATH_RULE
operation = WRITE | DELETE_REQUEST | MOVE_SOURCE | MOVE_DESTINATION
sourceKind = REQUIREMENT | DESIGN_DELIVERABLE | DESIGN_SCOPE
sourceRef
sourceExcerpt
sourceSha256
candidateStageIndexes
assignedStageIndexes
```

`WRITE` 覆盖新增或修改。删除和移动源端保持单独类型；v7 首版不自动放开删除，遇到 `DELETE_REQUEST / MOVE_SOURCE` 时保留现有删除保护并返回明确缺口。

### 2.4 路径守恒

对所有新 v7 软件计划，Compiler 返回 `COMPILED` 前必须同时满足：

```text
requiredMutationPaths <= union(stage.allowedPaths)
requiredMutationPaths intersect union(stage.forbiddenPaths) = empty
every required obligation has at least one justified Stage owner
every Stage GIT_DIFF uses the same normalized path contract as the Stage and focused tests
```

这里的 `<=` 表示每个路径规则都被相同规则或可证明覆盖它的允许规则包含；判断复用运行期路径策略，不能另写一套近似匹配。

## 3. 当前根因

当前 v6 已经把场景归属和能力覆盖做成服务端闭集，但 material facts 没有守恒合同：

1. `DesignerAcceptanceFastPathResolver` 只追踪未归属的 `SCENARIO / REVIEW`。
2. `DesignerAcceptancePlanCompiler.groups(...)` 只把 Stage 已引用的 `DELIVERABLE / SCOPE / POLICY / DEPENDENCY` 作为 material facts。
3. `allowedPaths(...)` 对非空局部路径不再补入包内其他必改路径。
4. 计划仍可生成显式 `GIT_DIFF` 并进入执行。
5. 实施正确修改遗漏文件后，运行期才报 `outside allowed paths`。

因此提高通过率的正确位置是设计到执行合同，而不是降低 `VerifierEngine` 的路径强度。

## 4. v7 数据合同

### 4.1 冻结输入

`DESIGN_ACCEPTANCE_V7` 在既有不可变 `facts_json` 中冻结：

- 现有 DesignFact Catalog；
- Mutation Obligation Catalog；
- 每条义务的精确来源和哈希；
- 提取问题，例如项目根外路径、否定/正向冲突、删除请求和无法判定的路径文本。

优先扩展 JSON 合同而不是新增可变数据库列。历史 v5/v6 快照缺少义务列表时按原合同读取，绝不从当时未冻结的需求重新推断或回写。

### 4.2 确定性来源

义务只来自以下正向证据：

- 冻结需求中的独立仓库相对路径、glob 或代码跨度路径，且处在新增、修改、实现、写入等正向任务作用域；
- 受控设计“影响与交付”中的正向 `DELIVERABLE / SCOPE` 路径；
- 已冻结工作包的精确 `scopeIn` 和 `deliverables` 路径。

否定句、保持不变、示例路径、项目根外路径和纯符号不生成自动写入义务。正向宽泛 glob 保留为
`PATH_RULE` 义务以防静默丢失，但不能证明精确 Stage 归属；无法唯一识别正负作用域时形成定点缺口，
不通过宽泛 glob 猜测或扩大权限。

### 4.3 服务端守恒输出

v7 不扩展弱模型 binding schema，继续使用 `PACKAGE_ACCEPTANCE_DISAMBIGUATION_V6` 的
`summary / factAssignments / capabilityPreferences / handoffSummary` 闭集。Mutation Obligation 不进入模型输入或
输出；服务端在 Stage 组装后，根据各 Stage 自己引用的受控正向 `DELIVERABLE / SCOPE` 路径来源证明归属并执行守恒门禁。
义务记录中的候选/分配索引只作历史兼容的空字段读取，不由模型或启发式回填。Stage 标题、目标、顺序、依赖、
路径文本、命令和测试目标仍由服务端锁定。

`diagnostics_json` 保存有界的人类可读结果：

- `mutationObligationCount`；
- `resolvedMutationObligationCount`；
- `unresolvedMutationObligationCount`；
- `pathConservation`；
- `fastPathDecision / routingReasons`；
- 未决事实、歧义能力索引和 Solver 的安全规范化代码。

API/UI 不展示内部索引、原始 JSON 或项目根外绝对路径。

## 5. 编译算法

### 5.1 冻结

`DesignerSessionService` 在现有 `startCompilation(...)` 边界把不可变 `requirementText`、工作包设计、`scopeIn`、`scopeOut` 和 `deliverables` 一并交给验收工作流。文件系统和模型调用不进入冻结事务。

完成条件：DesignFact、能力和 Mutation Obligation 使用同一设计修订与来源哈希持久化。

### 5.2 服务端路径归属证明

Stage 先按既有事实分配组装。每条 Mutation Obligation 只接受以下确定性归属证明：

1. `EXACT_FACT_REFERENCE`：Stage 自己引用了产生该义务的受控正向 `DELIVERABLE / SCOPE` 事实；
2. `UNIQUE_PATH_COVERAGE`：恰好一个 Stage 的现有精确正向路径规则按运行期语义覆盖义务；
3. `SINGLE_STAGE`：计划恰好一个 Stage，且义务为精确 `WRITE/MOVE_DESTINATION`，服务端把同一精确路径补入该 Stage、focused test 和显式 `GIT_DIFF`。

归属后仍须独立证明 Stage、focused test 和显式 `GIT_DIFF` 共用同一 allowed/forbidden 集合，且义务不与任何禁止规则相交。包级 `scopeIn`、全局事实和技术栈 fallback 不能单独证明归属；不得按最后一个 Stage、标题相似度、目录词相似度或宽泛默认值分配。多个 Stage 均可覆盖时保留为 `MUTATION_PATH_AMBIGUOUS_STAGE_BLOCKED`，不交给模型选择。

### 5.3 局部闭集消歧

只有闭集事实或能力候选仍无法唯一绑定时，才向一次沿用 V6 schema 的 Compiler 提供：

- 未决事实及其候选 Stage；
- 真正同分的能力候选。

Parser 可以接受唯一可逆的字段别名、单项集合和无害额外说明字段，但前提是所有必需选择完整、索引均在闭集内、没有重复或冲突。任何命令、路径、拓扑或安全字段仍不能由模型写入。被忽略字段必须记录 `AI_OUTPUT_NORMALIZED`；闭集缺口继续返回 `AMBIGUOUS_ACCEPTANCE_INTENT`。

### 5.4 唯一最优能力

当前 v6 只要一个事实有多个 covering capability 就请求 Compiler。v7 先运行现有确定性评分：

1. 零未覆盖；
2. 强制能力完整；
3. 更少 Judge-only；
4. 更高确定性和强度；
5. 更少能力；
6. 稳定索引顺序只用于输出稳定，不用于打破真实同分的业务选择。

只有多个候选在全部业务评分维度真实同分时才请求闭集偏好。唯一最优解直接编译。

### 5.5 守恒门禁

Stage 组装后、`DesignerPackagePlanCompiler` lowering 前执行 `MutationConservationPolicy`：

- 每条 `WRITE` 义务必须被至少一个 Stage 的 `allowedPaths` 覆盖；
- 同一路径不能同时被有效 `forbiddenPaths` 覆盖；
- Stage、聚焦测试和显式 `GIT_DIFF` 必须共享完全相同的规范化路径集合；
- 未决义务返回 `DESIGN_INCOMPLETE / REQUIRED_MUTATION_PATH_UNASSIGNED`；
- 冲突义务返回 `DESIGN_INCOMPLETE / REQUIRED_MUTATION_PATH_FORBIDDEN`；
- 当前 v7 普通包、大型任务包与滚动执行当前包在确定性绑定完整时统一服务端直编；路径缺口只进入定点人工输入门，不消耗整稿自动重设计次数；
- 项目根外路径返回现有权限/多项目边界，不得转换为相对路径；
- 删除或移动源端继续返回明确人工缺口，不能通过 `forbidDeletes=false` 整体放宽。

宽泛技术栈回退可以继续为没有显式路径的旧语义提供提示，但不能作为“显式义务已守恒”的证明。

## 6. 弱模型交互原则

- 模型输出平坦闭集选择，不输出 LoopSpec、命令、测试目标或路径。
- 服务端先完成所有唯一可推导工作，再决定是否调用模型。
- 一次响应只解决闭集事实和真实同分能力；路径义务完全由服务端证明或阻断，避免让弱模型重述整稿或选择权限。
- 机械格式偏差只在可逆且安全时规范化；语义、安全或执行缺口继续阻断。
- invalid response 不丢弃已冻结事实，也不生成空 binding、catch-all Stage 或宽泛路径。

## 7. 兼容与恢复

- 冻结 v5/v6 工作包继续按原合同恢复，不用 v7 重解释。
- v7 的 `facts_json` 必须能在重启后读回同一义务和来源哈希。
- 已持久化 v7 规划只能从冻结输入恢复，不重新读取浏览器正文或当前仓库推断义务。
- `binding_source` 继续使用 `SERVER_STAGE_HINTS / AI_DISAMBIGUATION_V6 / LEGACY_UNKNOWN` 兼容现有 UI；若需要区分 v7，优先由合同版本和诊断投影表达，只有产品确需稳定新来源码时才新增迁移。
- 运行期 `VerifierEngine` 保持权威，不因 v7 改为自动接纳越界路径。

## 8. 成功指标

必须同时报告：

| 指标 | 目标 |
| --- | --- |
| 显式必改路径守恒 | 100% |
| 已知“编译成功、执行期路径遗漏”逃逸 | 0 |
| 必须阻断硬缺口保持率 | 100% |
| 端到端可执行率 | 不低于 v6，且目标样本提升 |
| Compiler 模型调用/计划 | 不高于 v6 |
| 整稿重设计/计划 | 不高于 v6 |
| Judge-only 条件占比 | 不因优化上升 |
| 项目根外或删除自动授权 | 0 |

设计编译通过率可以上升，但不能单独作为上线依据。

## 9. 验证矩阵

至少覆盖：

1. 需求有 A、B，设计 Stage 只引用 A：不得 `COMPILED` 后遗漏 B。
2. 单 Stage 且遗漏 B：服务端唯一补入 B，不调用模型。
3. 多 Stage 且 B 只有一个现有路径规则可覆盖：唯一补入。
4. 多 Stage 均可承载 B：不调用模型，保留项目相对路径和候选 Stage 名称并定点阻断。
5. B 被 `forbiddenPaths` 覆盖：编译期阻断。
6. B 在项目根外：保持权限边界。
7. 删除或 rename source：保持删除保护。
8. 多能力但唯一最优：不调用模型。
9. 能力真实同分：调用一次闭集选择。
10. 弱模型夹带摘要字段但闭集选择完整：安全规范化后通过并审计。
11. 弱模型遗漏/越界/重复选择：阻断且保留冻结事实。
12. v5/v6 历史 JSON、重启恢复和滚动工作包兼容。

## 10. 发布策略

1. 先用原型 10 样本和新增 golden corpus 做离线双编译。
2. 再以只读 shadow 模式对冻结新设计同时运行 v6/v7，只记录有界差异，不改变权威计划或启动模型。
3. 只有路径守恒、硬阻断、端到端和 Judge-only 指标全部达标，才对新 `DIRECT_SOFTWARE_DESIGN / WP-1` 启用 v7。
4. 大型滚动包在单包路径稳定后启用；历史包不迁移。
5. 最终交付按仓库公约完成文档、版本、聚焦测试、全量验证、JAR、隔离运行和真实弱模型回放。

## 11. 非目标

- 不放宽登记项目根、外部系统写入、Git 推送发布或危险权限。
- 不用 Judge 替代本可确定性证明的业务验收。
- 不做模糊标题匹配或从源码搜索推断用户写入意图。
- 不在本轮重写整个 Designer/Compiler 生命周期。
- 不让 Compiler 修改 Stage 拓扑、命令、路径归属或测试目标。

## 12. 实施顺序

1. [001：Mutation Obligation 与路径守恒门禁](issues/001-mutation-obligation-conservation.md)
2. [002：唯一自动绑定与局部路径消歧](issues/002-unique-binding-and-targeted-ambiguity.md)
3. [003：弱模型闭集协议与唯一最优能力](issues/003-weak-model-closed-choice.md)
4. [004：Shadow 评估、上线门槛与完整交付](issues/004-shadow-evaluation-and-rollout.md)

阻塞关系：`001 -> 002 -> 003 -> 004`。`004` 的 corpus/观测脚手架可提前准备，但权威对比必须基于前三票的冻结合同。
