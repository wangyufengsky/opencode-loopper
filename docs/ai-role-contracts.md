# AI 机器角色轻量合同

运行时合同版本：`2026-08-semantic-v2`。

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
终止 Designer。内置 Role Pack 以 `2026-08-dynamic-v2` 版本冻结：Java、Python、Node、
Markdown/DOCX、表格转换、只读报告和本地维护各自组合提示，不能把 Java/Maven 示例
注入 Python、文档或表格任务。

Router 使用独立 `ROUTER_NO_TOOLS` Session；可用时返回固定
`TASK_PROFILE_ROUTER_V1` JSON Schema，不可用时使用同一闭集对象的 marker。它只返回
`intent / artifactKinds / technologies / complexity / confidence / signals`，服务端仍独占
`WorkflowTemplate / MutationMode / ExecutionStrategy / TestPolicy`。需求 Designer 和
Decomposer 使用父画像；每个软件工作包再按包标题、目标、范围和交付物检测技术栈，原子
冻结自己的 Role Pack、执行策略和测试策略，包 Designer 与 Compiler 只读取该冻结值。

文档 Compiler 的权威输出是受限 `DocumentPlan`；表格 Compiler 的权威输出是受限
`TabularConversionPlan`。服务端生成路径、隐式 `WP-1`、验收 ID 与最终 DTO，并在最终
确认前只冻结计划。报告 Reviewer 仅可读取仓库并输出带证据定位的 Markdown；报告文字
是数据而不是后续设计会话的系统指令。

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

冻结的 Designer Markdown 在当前修订内生成稳定 `DS-L001...` 行引用。Compiler 返回
1–3 个语义 Stage、业务条件的 `sourceRefs` 和闭集证据意图，不填写 Stage 序号、
`workPackageId`、验收 ID、精确摘录、`criterionIds` 或 `testTargets`。

服务端解析一个或多个 `DS-L` 引用并保存精确原文，生成 `<WP>-AC-n`，从安全的
Maven/Gradle 显式选择器提取测试目标，并把 `covers` 编译成验证器关联。支持的意图为：

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

`criteria` 只承载可观察业务结果。弱模型把代码风格、源码/注解/装配形态、构建成功、
测试通过或交付卫生重复写成条件时，服务端在它们没有显式聚焦测试映射的前提下，将其
确定性降为冻结设计中的工程补充约束，并重排剩余 `covers`；这不会消耗语义修复次数。
同一 Java Stage 只有一个聚焦测试候选时，服务端可把尚未映射的业务条件关联到该测试，
但不会从描述猜测试类名，也不会在多个测试候选间猜测。`grep`、`rg` 等源码搜索不能作为
行为 `SELF_CHECK`；只服务于已降级工程元条件的不可执行搜索会被丢弃，其余仍严格拒绝。

## 修复协议

无法提取唯一标准 JSON object 时使用完整紧凑对象格式修复；可解析但合同不成立时，
模型只返回最多 16 个 `add/replace/remove` JSON Pointer 补丁。格式修复和语义修复各
最多两次、分别持久化和展示。补丁只能修改 AI 拥有的语义字段，不能覆盖任何服务端
派生字段；应用后必须重新运行完整提取、规范化和权威校验。

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
