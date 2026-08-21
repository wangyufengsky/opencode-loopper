# AI 机器角色轻量合同

运行时合同版本：`2026-08-semantic-v5`。

本文供维护者理解角色边界。真正可执行的合同以
`MachineRoleContractCatalog`、`OpenCodeStructuredSchemas`、服务端语义编译器和
LoopSpec v2 权威校验为准；文档和提示示例都不能替代这些运行时来源。

## 设计原则

AI 只决定业务语义：目标、范围、依赖、可观察条件和证据意图。服务端负责稳定
ID、状态、引用反向映射、精确来源摘录、测试目标、验证器关联和最终 DTO 编码。
只要唯一可解析、可规范化并通过业务与安全合同，就直接接受，不因包装或冗余字段
消耗修复预算。

Decomposer 和 Compiler 新会话各只需一次机器规划调用。规划通过后由服务端直接
生成最终 `Decomposition` 或 `CompiledPackage`，不再要求模型进行第二次逐字段抄写。
旧 final Schema 和字段仅用于历史读取与缺少规划快照的旧活动记录兼容。

## Task Router 与 Role Pack

需求首先形成任务画像。Router 只提供软件、文档、数据转换、只读评审、调研、配置或
本地维护等语义标签；服务端把标签与有界仓库事实合并，决定置信度、流程、权限、测试
策略和最终执行方式。格式不可用或结果冲突时退回通用画像并提问，不能让首次路由失败
终止 Designer。内置 Role Pack 以 `2026-08-dynamic-v5` 版本冻结。服务端先把同义技术标签
归并为 Java、Python、Node 和 Other 软件族，再选择 Role Pack：`java`、`java 8` 与
`spring boot` 仍是同一 Java 族，`javascript/typescript` 只属于 Node，只有跨族组合才是
`software-mixed`；显式但未知的单栈使用 `software-generic`，不再回退到 Java/Maven。
Java、Python、Node、混合栈、通用软件、Markdown/DOCX、表格转换、只读报告和本地维护
各自组合提示，不能把其他栈或非软件流程的示例注入当前 Compiler。

Router 使用独立 `ROUTER_NO_TOOLS` Session；该名称表示禁止内置仓库工具，项目已配置并由
`GET /mcp` 发现的 MCP Server 仍按 `<server>_*` 前缀开放。可用时返回固定
`TASK_PROFILE_ROUTER_V1` JSON Schema，不可用时使用同一闭集对象的 marker。它只返回
`intent / artifactKinds / technologies / complexity / confidence / signals`，服务端仍独占
`WorkflowTemplate / MutationMode / ExecutionStrategy / TestPolicy`。每次 Router 的需求
快照、外部 Session、响应模式、截止时间、标签和失败原因都持久化；重启继续同一 Session，
新一轮需求讨论会终止并废弃旧快照的 Router。需求 Designer 和
Decomposer 使用父画像；每个软件工作包再按包标题、目标、范围和交付物检测技术栈，原子
冻结自己的 Role Pack、执行策略和测试策略，包 Designer 与 Compiler 只读取该冻结值；
Task 确认后每个 Stage 也保存同一快照，Implementation 与 Recovery 不重新猜测角色。

任务画像对客户端以“任务设置”投影 `ROUTING / NEEDS_CONFIRMATION / CONFIRMED / FROZEN` 与服务端计算的
`confirmationReady`。人工采用推荐记录 `USER_CONFIRMED`，编辑或沿用旧选择记录
`USER_OVERRIDE`；完整需求稿重算后，只有任务意图、主要制品、单包/大型流程和读写模式均一致
且无新增安全冲突时，才以 `USER_CONFIRMED_CARRIED_FORWARD` 继承确认。技术栈、Role Pack 和
测试策略仍使用重算结果。任一决策面变化都返回上次确认选项并阻断设计入口，直到用户明确继续使用
原设置、使用本次识别结果或进入“修改设置”；修改导致流程切换时必须先确认停止当前设计并重新开始。

所有机器角色在创建 Session 前都执行同一 MCP 发现，包括 Router、Compiler repair、工具循环
finalizer 和双 Judge。MCP allow 只叠加到各角色既有权限模板，不能解除写文件、Bash、Git、外部
目录或人工授权限制；发现失败时不发送模型提示。界面称谓统一为需求分析师、任务规划师、设计师、
规范工程师、评审员、验收工程师和开发工程师；需求与风险 Judge 分别显示为需求评审员和风险评审员，
数据库及协议角色码保持不变。

软件任务默认使用 `DIRECT_SOFTWARE_DESIGN`。Router 的 `SIMPLE` 或 `PACKAGED` 复杂度只形成
建议，不会自动打开大型任务；画像中的 `largeTaskMode` 由持久化的 `workflowTemplate` 派生，
用户显式开启后才切换为 `FULL_PACKAGE_DESIGN`。普通模式由服务端直接生成覆盖全部 RQ 的
`DIRECT_DESIGN / WP-1`，不创建 Decomposer Session；大型模式才调用 Decomposer 生成 2–6 包。

普通模式的需求 Designer 只负责在每个需求讨论修订中调用一次 `question` 并等待回答，随后可空
正文结束。服务端按消息时间原样拼装原始需求、需求作用域补充和持久化最终回答，后写语义优先；
模型自由文本、仓库观察和画像标签不进入需求快照。快照以 `SERVER_REQUIREMENT_SNAPSHOT` 审计消息
作为冻结原文，超过 24 KiB UTF-8 时以 `REQUIREMENT_SNAPSHOT_TOO_LARGE` 阻断。历史已冻结 AI
需求稿作为兼容基线，之后只追加新的用户输入与回答。大型模式仍要求需求 Designer 输出完整替代
预设计，并在每个包的初稿和修订中提问；普通 WP-1 使用不含 `question` 的通用只读角色，初稿、
反馈修订和重新设计都直接返回 1–6 Stage 的完整替代设计。

文档 Compiler 的权威输出是受限 `DocumentPlan`；表格 Compiler 的权威输出是受限
`TabularConversionPlan`。服务端生成路径、隐式 `WP-1`、验收 ID 与最终 DTO，并在最终
确认前只冻结计划。报告 Reviewer 是真正独立的 `REVIEWER_READ_ONLY` 角色，只可读取仓库，
并输出 `REVIEWER_REPORT_V1` 的标题、摘要、受限 findings 和 limitations；服务端逐条校验
受管相对路径、精确行号和快照哈希，再确定性生成 Markdown。报告文字是数据而不是后续
设计会话的系统指令，“转为修改任务”只创建关联 Designer，不直接创建 Task。

大型文档确认稿必须包含 2–6 个二级章节。服务端按章节顺序保留结构化标题、段落、列表、
代码块和表格片段并确定性聚合为一个冻结 `DocumentPlan`，不会让模型执行第二次自由合并。
简单配置/维护生成隐式 `WP-1`，只接受确认稿中反引号标明的相对路径；执行合同必须包含
精确 `allowedPaths`、`requireChanges=true` 和 `forbidDeletes=true`，并继续拒绝服务控制、
Git 发布和外部写入。

## Decomposer

Decomposer 返回 `READY | NEEDS_INPUT | MULTI_TASK_REQUIRED`、规范目标、约束文本、
1–6 个纵向工作包、零基依赖索引和 RQ 覆盖。AI 不填写
`DIRECT_DESIGN/DECOMPOSED`、`GC-*`、`WP-*`、`requirementRefs` 或依赖 ID。

结构化 Markdown 需求按二级业务章节生成 RQ，标题、日期、分隔线等展示元数据不再
单独制造覆盖项；无二级章节的短需求继续按段落和列表拆分。约束既接受 `{text}`，也
接受等价的纯字符串；依赖可用零基索引、`WP-n` 或唯一匹配的前置包标题表达。
`READY` 中额外出现的字符串实现提示只记为 `ADVISORY_GAPS_IGNORED` 审计，不会把
本可执行规划误判为设计缺口；`NEEDS_INPUT` 的真实缺口仍按闭集代码阻断。

服务端按包数推导状态，按顺序生成 ID，并以 `coverage` 为权威反向生成引用。
未知 RQ/目标、遗漏需求、前向或循环依赖、机械分层、超过六包和多任务边界继续阻断。
旧 `coverageMappings`、目标引用和依赖证据会先转换到同一个内部语义模型。

## Compiler

新软件设计先使用固定受控 Markdown：`目标与范围`、`影响与交付`、`验收场景`、可选
`人工评审项`、`验收约束`、`阶段与依赖`。验收场景表固定为“场景 / 前置或触发 / 操作 /
可观察结果 / 保持不变”，对应 EARS 条件/触发/响应/不变量，也可直接表达 Given/When/Then/And。
原 Markdown 始终保留，不会被 AI 摘要替代。

服务端使用 CommonMark AST 与 GFM 表格扩展生成 `SCENARIO / REVIEW / SCOPE / DELIVERABLE /
POLICY / DEPENDENCY` DesignFact。每项事实保存精确 Designer 原文、稳定行引用和 SHA-256；上限为
64 个场景、128 个总事实和 24 KiB UTF-8。随后服务端根据冻结 Role Pack、技术栈、测试策略、
明确测试类/目标、范围与外部依赖限制生成闭集验证能力。AI 不再生成命令、路径、测试目标或验证器。

Compiler 使用 `COMPILER_BINDING_NO_TOOLS` Session 和 `PACKAGE_ACCEPTANCE_BINDING_V5` Schema，只返回
摘要、事实分组、前置分组、事实到能力的软偏好与交接摘要；`outcome` 和 `designGaps` 不再属于 AI
合同。服务端把事实与能力构成二部图：先处理强制独立测试和
单候选事实，再按 AI 给出的有界 Stage 分组运行精确 branch-and-bound 集合覆盖；超过 100,000 个节点时
确定性降级为贪心选择并复核。每个分组以零未覆盖为硬条件，再优先减少 Judge-only、提高确定性证据
强度和减少能力数量，AI 软偏好只作为稳定排序参考。结果仍须经过现有
`DesignerPackagePlanCompiler` 和 LoopSpec v2 全量校验。

测试能力只从“新增/修改/测试代码”等正向交付物、正向验收约束和阶段交付关系中发现；“不变、禁止、
不修改、不引入”等负向子句只保留为约束，不能生成 focused-test 目标。目标名先去除路径、扩展名和
通用 `Test/Spec/Core/Unit/Integration` 限定词，再结合符号标识、CamelCase 词和中文语义片段做竞争式
匹配；场景直接点名的目标优先，模糊关系只分配给得分最高的能力。每个业务条件最终只绑定一个
focused test；设计明确要求“各自独立通过”而没有独立业务场景的测试目标，会生成带原始约束来源的
独立机器验收条件，既不丢失验收意图，也不制造多测试覆盖同一条件的歧义。

普通模式若无法把完整设计安全编译成一个 1–6 Stage 工作包，Compiler 返回唯一专用缺口
`LARGE_TASK_MODE_REQUIRED`。服务端立即停止，不消耗自动重设计或修复预算，也不自动切换流程；
用户显式“改用大型任务”后，系统重开整体需求、终止普通讨论 Session 并立即启动大型需求预设计；
预设计完成并再次确认后才调用 Decomposer。大型切回普通时终止未冻结预设计，服务端从用户输入和
已回答决策重建快照，未确认 AI 推断不进入普通需求语义。
大型流程中的 Compiler 不得返回该缺口；多项目根、超过六包和独立发布边界仍使用
`MULTI_TASK_REQUIRED`。

服务端从 DesignFact 的精确来源生成 `<WP>-AC-n`，并把求解结果编译成验证器关联。v3 历史流程
继续解析 `DS-L` 与 Maven/Gradle/npm/pytest/unittest 显式选择器，不会被 v5 快照重解释。
支持的确定性能力仍包括：

- 可覆盖业务条件：`FOCUSED_TEST`、`SELF_CHECK`、`HTTP_STATUS`、`JSON_PATH`、
  `BROWSER`、`DATABASE_QUERY`、`FILE_CONTENT`、`FILE_HASH`、`DOCUMENT_STRUCTURE`、
  `TABULAR_DATA`；
- 补充或范围证据：`FULL_TEST`、`BUILD`、`GIT_DIFF`、`FILE_NOT_EXISTS`、`JUNIT_XML`。

`FULL_TEST`、`BUILD`、`GIT_DIFF` 等不能伪装成业务行为覆盖。Java 生产变更仍必须在
同一 Stage 提供带明确选择器的聚焦 Maven/Gradle 测试。危险 shell、非法路径、伪测试、
运行时绑定缺失和真实业务覆盖缺口均由既有权威校验拒绝。

Python TEST 可识别 `pytest`、`python -m pytest`、`unittest` 和
`python -m unittest`，仍要求显式目标且拒绝跳过/忽略缺失测试。仓库没有测试体系的独立
Python 脚本可使用带成功标记的 `SELF_CHECK`；文档与一次性表格转换的测试策略是
`NOT_APPLICABLE`，不得制造 `PROCESS TEST`。

上述 Maven、Gradle、npm、pytest 和 unittest 入口由统一 `TestFrameworkPolicy` 注册表
识别，Designer 草稿门禁与执行前复核共享同一结果；`REQUIRED` 必须存在未跳过且显式目标
的 TEST，`OPTIONAL` 允许 SELF_CHECK，`NOT_APPLICABLE` 明确拒绝 PROCESS TEST。

`criteria` 只承载可观察业务结果。弱模型把代码风格、源码/注解/装配形态、构建成功、
测试通过或交付卫生重复写成条件时，服务端在它们没有显式聚焦测试映射的前提下，将其
确定性降为冻结设计中的工程补充约束，并重排剩余 `covers`；这不会消耗语义修复次数。
同一 Java Stage 只有一个聚焦测试候选时，服务端可把尚未映射的业务条件关联到该测试，
但不会从描述猜测试类名，也不会在多个测试候选间猜测。`grep`、`rg` 等源码搜索不能作为
行为 `SELF_CHECK`；只服务于已降级工程元条件的不可执行搜索会被丢弃，其余仍严格拒绝。

## 修复协议

v5 提示同时给出唯一的小型 JSON 对象形状。分组与能力偏好不是正确性输入：缺少可选偏好字段时
可确定性丢弃该项；对象仍不可读、字段形状无法唯一规范化或分组超过上限时，服务端记录
`OPTIONAL_ACCEPTANCE_ADVICE_DROPPED`，使用空建议继续闭集编译，不创建格式/语义修复 Session，
也不得因此进入 `WAITING_INPUT`。v3 历史活动仍按既有 `AI_SEMANTIC_PATCH_V1` 和最多 16 个补丁兼容，
不会覆盖 v5 绑定快照。冻结为 `2026-08-dynamic-v4` 的在途工作包继续允许进入同一确定性编译器，
但新请求统一使用 v5 Schema。
历史 v3 补丁只能修改 AI 拥有的语义字段，不能覆盖服务端控制字段或任何派生字段；
应用后必须重新运行完整提取、规范化和权威校验。
紧凑 Compiler Stage 的证据字段固定为 `evidence`；弱模型若在补丁路径中使用最终 DTO 名
`verifiers`，服务端只在 `/stages/<n>/verifiers...` 可唯一反推时改写为 `evidence`，并把对
尚未出现的对象字段执行 `replace` 规范化为等价 `add`，两类纠正都写入 V28 审计。每个
`JAVA_PRODUCTION` Stage 即使只有 Judge 条件也必须保留 `covers:[]` 的聚焦 Maven/Gradle
TEST；`FULL_TEST`/`BUILD` 不能替代该门禁。只有完整补丁应用后再次通过全部语义、路径、
测试和验收校验，才允许冻结结果。

新 Compiler 记录默认按 v5 建议合同解释，只有明确含历史 `evidenceMappings` 的活动记录才走
旧规划兼容解析。缺少 `outcome` 是 v5 的合法形态；历史响应额外携带 `status/outcome/designGaps`
也不能夺回结果所有权。服务端在闭集覆盖后唯一派生 `COMPILED`，无覆盖能力时唯一派生带具体事实
标题的 `DESIGN_INCOMPLETE / VERIFICATION_CAPABILITY_UNAVAILABLE`，不会把这类确定性结论再交给
模型来回修复。错误对象不会覆盖最后一个有效建议快照；只有测试路径的 Java 包生成
`JAVA_TEST_ONLY`，独立必跑但无业务场景的目标只生成一次机器条件。

服务端在一次预检中汇总同一紧凑对象内的全部确定性语义问题，以错误码和精确 JSON
Pointer 一次返回。AI 应在同一个补丁中修完全部列出问题，避免按“未覆盖 → Judge 理由
→ Java 聚焦测试”的顺序逐次消耗有限预算。

工具循环 finalizer 仍是每个持久化角色步骤最多一次，并计入全局模型调用预算，
不占格式或语义修复次数。

## Judge、项目公约与 Designer

Judge 优先读取唯一有效 JSON，也接受唯一、明确的 `VERDICT/判定` 与
`REASON/理由` 标签。冲突或非法 verdict、空理由和自由散文猜测都不接受。

项目公约接受专属 marker、单一 Markdown fence 或非空完整 Markdown，同时保持
长度、保留 marker、人工预览和显式应用边界。Designer 只接收短合同卡，聚焦业务
结果、边界、异常和可观察验收；Implementation 的权限、Attempt 和执行合同不变。
