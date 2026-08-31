# AI 机器角色轻量合同

通用运行时合同版本：`2026-08-semantic-v6`；当前验收闭集 Compiler 合同版本：
`2026-08-semantic-v7`。

本文供维护者理解角色边界。真正可执行的合同以
`MachineRoleContractCatalog`、`OpenCodeStructuredSchemas`、服务端语义编译器和
LoopSpec v2 权威校验为准；文档和提示示例都不能替代这些运行时来源。

## 设计原则

AI 只决定业务语义：目标、范围、依赖、可观察条件和证据意图。服务端负责稳定
ID、状态、引用反向映射、精确来源摘录、测试目标、验证器关联和最终 DTO 编码。
只要唯一可解析、可规范化并通过业务与安全合同，就直接接受，不因包装或冗余字段
消耗修复预算。

Decomposer 新会话只需一次模型提示，但可在同一 Session 内通过内部 MCP 提交多个有界候选；当前 v7 Compiler 只在服务端无法唯一绑定闭集事实或能力时创建，
普通包、大型任务包和滚动执行当前包均不因包形态或交接摘要强制调用；冻结 v6 大型包保留历史一次调用兼容。
模型提示调用、OpenCode Session 和候选工具提交必须分别计数。规划通过后由服务端直接
生成最终 `Decomposition` 或 `CompiledPackage`，不再要求模型进行第二次逐字段抄写。
旧 final Schema 和字段仅用于历史读取与缺少规划快照的旧活动记录兼容。

## Task Router 与 Role Pack

需求首先形成任务画像。Router 只提供软件、文档、数据转换、只读评审、调研、配置或
本地维护等语义标签；服务端把标签与有界仓库事实合并，决定置信度、流程、权限、测试
策略和最终执行方式。格式不可用或结果冲突时退回通用画像并提问，不能让首次路由失败
终止 Designer。当前内置 Role Pack 以 `2026-08-dynamic-v7` 版本冻结，并继承 v6 的技术族归并。服务端先把同义技术标签
归并为 Java、Python、Node 和 Other 软件族，再选择 Role Pack：`java`、`java 8` 与
`spring boot` 仍是同一 Java 族，`javascript/typescript` 只属于 Node，只有跨族组合才是
`software-mixed`；显式但未知的单栈使用 `software-generic`，不再回退到 Java/Maven。
Java、Python、Node、混合栈、通用软件、Markdown/DOCX、表格转换、只读报告和本地维护
各自组合提示，不能把其他栈或非软件流程的示例注入当前 Compiler。

Router 使用独立 `ROUTER_NO_TOOLS` Session；它不执行 MCP 发现并拒绝全部内置与 MCP 工具，
只读取服务端提供的需求快照完成单次分类；仓库画像不进入模型提示。受管运行时为它选择单步、零温度、
非思考的 `loopper-router` Agent；提示明确禁止仓库搜索、方案设计、实现推演和解释。Router 固定使用 marker 信封承载
`TASK_PROFILE_ROUTER_V2` 闭集对象，不向 OpenCode Session 持久化 JSON Schema 响应格式，
以兼容会在加载该格式时崩溃的桌面版本；服务端解析与闭集校验没有放宽。它只返回
`intent / artifactKinds（恰好一个主要制品）/ complexity`；V1 的额外字段仅兼容读取，不能参与决策。
技术栈、组件和置信度由服务端仓库证据与三标签一致性推导，服务端仍独占
`WorkflowTemplate / MutationMode / ExecutionStrategy / TestPolicy`。每次 Router 的需求
快照、外部 Session、响应模式、时间戳、标签和失败原因都持久化；重启继续同一 Session，
新一轮需求讨论会终止并废弃旧快照的 Router。需求 Designer 和
Decomposer 使用父画像；每个软件工作包再按包标题、目标、范围和交付物检测技术栈，原子
冻结自己的 Role Pack、执行策略和测试策略，包 Designer 与 Compiler 只读取该冻结值；
Task 确认后每个 Stage 也保存同一快照，Implementation 与 Recovery 不重新猜测角色。

运行中的 Router 可由用户通过版本化接口主动取消。服务端只有在远端 abort 成功后才把该 run
收束为 `SUPERSEDED / ROUTER_USER_CANCELLED`，形成阻断全自动采用的人工待选画像；前端随后直接
打开任务类型、主要制品、大型任务和组件选择控件。过期 run ID、重复点击或与 Monitor 争用返回 409。

任务画像对客户端以“任务设置”投影 `ROUTING / NEEDS_CONFIRMATION / CONFIRMED / FROZEN` 与服务端计算的
`confirmationReady`。人工采用推荐记录 `USER_CONFIRMED`，编辑或沿用旧选择记录
`USER_OVERRIDE`；完整需求稿重算后，只有任务意图、主要制品、单包/大型流程和读写模式均一致
且无新增安全冲突时，才以 `USER_CONFIRMED_CARRIED_FORWARD` 继承确认。技术栈、Role Pack 和
测试策略仍使用重算结果。任一决策面变化都返回上次确认选项并阻断设计入口，直到用户明确继续使用
原设置、使用本次识别结果或进入“修改设置”；修改导致流程切换时必须先确认停止当前设计并重新开始。
危险操作证据按动作对象确定性判定：进程内领域事件、消息、通知、信号、指标或发布订阅不因自然语序中的
“发布”被误判为外部发版；“发布器/发布者/发布-订阅器”是组件名词而非一次发版动作，“进程内同步发布”、事件总线的“监听器注册、发布与按类型分发”、生命周期 `started/succeeded/failed/compensated` 示例和受控的 `CHAIN_STARTED/SUCCEEDED/FAILED/COMPENSATED` 事件常量作为事件投递上下文处理；同句“重复/再次/重新发布”只有在前一个发布对象已证明为业务事件时才继承该对象，普通第二次裸发布仍失败关闭；版本、制品、镜像、安装包、环境、GitHub Release、提交推送和无法限定对象的
裸发布继续失败关闭，且不能由 AI 标签、全自动模式或人工画像覆盖绕过。
否定作用域必须绑定到具体动作，只有写入、修改、创建、上传、同步或发送等动作以外部系统/应用为对象时
才形成外部写入冲突；“不伪造外部系统结果”等证据边界不是写请求。`LOCAL_MAINTENANCE` 也只由明确的
任务级配置/依赖维护指令触发，“可配置”能力、“不新增依赖”约束与源码职责中的“维护调用身份/MDC”等描述不得把软件任务降级。
只读 Reviewer 只由任务级评审、审查、检查或诊断动作选择；大型完整设计里的人工评审点、
只读 getter/投影和验收复核词汇属于交付内容，不能把已有软件变更改路由为只读报告或伪造
`mixed-mutation-conflict`。显式任务级“评审并修复”仍保留读写冲突并要求人工确认。

除真正零工具的 Router 外，机器角色在创建 Session 前执行有界 MCP 发现。普通角色只把已连接的
用户 MCP allow 叠加到既有权限模板；候选角色移除用户 MCP 通配权限，只开放本代随机内部
`submit_candidate` 精确工具。内部 MCP 不会进入 Judge、Router 或公共六工具 Provider，也不能解除写文件、
Bash、Git、外部目录或人工授权限制；发现或精确就绪失败时不发送模型提示。界面称谓统一为需求分析师、任务规划师、设计师、
规范工程师、评审员、验收工程师和开发工程师；需求与风险 Judge 分别显示为需求评审员和风险评审员，
数据库及协议角色码保持不变。

软件任务默认使用 `DIRECT_SOFTWARE_DESIGN`。Router 的 `SIMPLE` 或 `PACKAGED` 复杂度只形成
建议，不会自动打开大型任务；画像中的 `largeTaskMode` 由持久化的 `workflowTemplate` 派生，
用户显式开启后才切换为 `FULL_PACKAGE_DESIGN`。普通模式由服务端直接生成覆盖全部 RQ 的
`DIRECT_DESIGN / WP-1`，不创建 Decomposer Session；大型模式才调用 Decomposer 生成 2–6 包。

新建大型软件任务使用逐包闭环：Decomposer 只负责冻结高层包边界，包 1 详细设计确认后创建唯一
Task；后续包在前一包机器验收和事实冻结后才生成详细设计。大型文档和历史 Designer/Task 继续使用
聚合流程。后续包 Designer 接收原始冻结需求、最新真实只读快照和分层事实索引，其中“已证明”只来自
服务端 Checkpoint/验证证据，“已接受合同”只来自人工确认的设计与累计 TaskSpec，“AI 导航摘要”永远
不能作为通过条件。每包事实索引限制为 4 KiB、累计 24 KiB；超出部分只能通过只读事实/证据接口读取。
模型不能重写已冻结包；改变既有行为只能提出带 `correctionOf` 的后续修正包。
调整未执行后缀时，用户可以人工编辑，也可以显式启动独立的只读 AI 建议。建议角色只读取原始冻结需求、
当前未执行包、精确 Checkpoint 快照和有界事实索引，以 marker JSON 返回 `replaces/dependencies/requirementRefs`；
服务端验证来源、依赖、基线版本并计算影响。`GENERATING / PROPOSED / FAILED`、外部 Session 和错误均持久化，
Monitor 可在重启后继续；AI 完成只形成候选，不能确认计划、确认包设计或开始执行。

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

受管模式为每个 Decomposer 规划建立持久化 `DECOMPOSITION_PLAN_V2` 候选运行，绑定需求修订、
TaskDecomposition 版本、外部 Session、运行时代际与 `INTERNAL_MCP` 通道，最多五次唯一提交。
每次工具调用返回 `REJECTED / ACCEPTED / WAITING_INPUT`、剩余次数、下一提交修订和至多 16 个
带 JSON Pointer 的问题。`REJECTED` 只让同一 Session 修正；`ACCEPTED` 与规范计划原子冻结；
预算耗尽或不可修复边界进入人工输入。被拒原文不落库，只保存 SHA-256、问题和安全响应。
外部/兼容模式使用 `IN_PROCESS_LEGACY`，但复用完全相同的编译、策略和 accepted writer；两种通道不可互投。

## Compiler

新软件设计先使用固定受控 Markdown：`目标与范围`、`影响与交付`、`验收场景`、可选
`人工评审项`、`验收约束`、`阶段与依赖`。验收场景表固定为“场景 / 前置或触发 / 操作 /
可观察结果 / 保持不变”，对应 EARS 条件/触发/响应/不变量，也可直接表达 Given/When/Then/And。
当前 v7 的 `阶段与依赖` 表固定为“阶段 / 目标 / 负责路径 / 包含场景/评审/交付 / 前置阶段”，
冻结 v6 继续读取历史四列。`负责路径` 只列该阶段承担写入责任的仓库相对路径/规则，每条必改路径必须有且仅有
一个可证明阶段，不能把整包路径复制到每个阶段；“包含”只能原样引用
前文标题，多项使用中文或英文分号分隔，前置阶段只能原样引用更早阶段，空值或“无”表示无依赖。
原 Markdown 始终保留，不会被 AI 摘要替代。

服务端使用 CommonMark AST 与 GFM 表格扩展生成 `SCENARIO / REVIEW / SCOPE / DELIVERABLE /
POLICY / DEPENDENCY` DesignFact。每项事实保存精确 Designer 原文、稳定行引用和 SHA-256；工作包
设计没有固定字节上限，仍限制为 64 个场景和 128 个总事实。随后服务端根据冻结 Role Pack、技术栈、测试策略、
明确测试类/目标、范围与外部依赖限制生成闭集验证能力。AI 不再生成命令、路径、测试目标或验证器。

当前 Role Pack 的新软件包同时冻结 `DESIGN_ACCEPTANCE_V7` Mutation Obligation。服务端只从冻结需求
的正向新增/修改/实现/写入路径、受控 `DELIVERABLE / SCOPE` 正向路径以及冻结工作包中的显式路径规则生成
`WRITE / DELETE_REQUEST / MOVE_SOURCE / MOVE_DESTINATION`；每项区分精确路径与路径规则，并保存来源引用、有界原文和 SHA-256。
否定、不变、示例、纯符号和项目根外路径不生成义务。需求、受控设计或冻结包级的宽泛 glob 会保留为
可审计路径规则义务，但不会自行变成写权限；必须由唯一 Stage 的显式负责路径或另一条运行期可证明规则承接。冻结 v5/v6 JSON 缺少该列表时按空列表恢复，不重新
读取新需求推断；冻结 `dynamic-v6` 工作包升级后首次编译仍使用 V6 合同。

服务端先用 `DesignerAcceptanceFastPathResolver` 解析 v6 阶段表。符号只做 Unicode NFKC、首尾裁剪、
连续空白折叠和拉丁字符大小写归一；标点保留，禁止子串或模糊自动匹配。`SCENARIO / REVIEW`
必须恰好属于一个阶段，`DELIVERABLE` 可重复引用；V6 的 `SCOPE / POLICY` 保持包级，V7 只有正向
受控 `SCOPE` 可被阶段精确引用作路径 provenance，`POLICY` 仍保持包级；阶段名唯一、数量为
1–6，依赖只能指向更早阶段。完整的普通 `DIRECT_SOFTWARE_DESIGN / WP-1` 直接使用冻结拓扑，
不创建 Compiler Session，也不增加模型调用。对新 V7 单 Stage 设计，遗漏的 `SCENARIO / REVIEW`
事实由服务端归入唯一 Stage；Stage“包含”栏里不能对应任何冻结事实的附加说明标签会被审计丢弃，
但不会做标题模糊匹配。V5/V6 继续按原合同阻断同类附加标签。

只有闭集事实或能力仍无法唯一绑定时，才创建一次 `COMPILER_BINDING_NO_TOOLS` Session。当前 v7
使用 `PACKAGE_ACCEPTANCE_CLOSED_CHOICE_V7`；冻结 v6 继续使用
`PACKAGE_ACCEPTANCE_DISAMBIGUATION_V6`。二者都只承载 `summary / factAssignments /
capabilityPreferences / handoffSummary`，只填写服务端列出的 unresolved facts 和候选能力；阶段名称、
目标、顺序、依赖和已锁定事实均不可修改。V7 提示投影为每个候选 Stage 显式列出零基 `stageIndex`，
并为每个未决事实列出完整 `allowedStageIndexes`；人类可读的“阶段 1”只作标签，不能被解释为索引 1。
服务端把全部可覆盖事实与能力构成一个全局二部图：先处理
强制独立测试和单候选事实，再按锁定 Stage 运行精确 branch-and-bound 集合覆盖；超过 100,000 个节点时
确定性降级为贪心选择并复核。业务评分依次要求零未覆盖、强制能力完整、更少 Judge-only、更少
非确定性能力、更少能力和更高证据强度；稳定索引只用于输出顺序。只有全部业务维度同分的多个最优集合
才是真实 tie，并触发一次模型选择；唯一全局最优集合直接编译，不能因单个事实有多个 covering capability
制造一次 Compiler 调用。模型只看到这些等价最优集合之间真正改变成员关系的候选，并且必须返回恰好一个
完整最优集合的判别索引；共同成员、较弱候选和非最优组合不进入选择面。若有界搜索未穷举，结果只作为诊断
并失败关闭，不允许把贪心结果或模型选择冒充权威最优解。AI 偏好不参与服务端评分。结果仍须经过现有
`DesignerPackagePlanCompiler` 和 LoopSpec v2 全量校验。

验收闭集候选迁移由 `loopper.internal-candidate.acceptance-closed-choice-v7-enabled` 单独控制且默认关闭；只有隔离成品 JAR 证明当前真实模型会主动调用私有 MCP 并依据拒绝结果在同一 Session 自修正后才允许显式开启。关闭时真实同分继续走既有 `PACKAGE_ACCEPTANCE_CLOSED_CHOICE_V7` JSON 兼容路径。唯一最优始终由服务端直编；不可枚举、未穷举、路径归属或权限安全问题始终直接等待人工，不能为了启用候选协议扩大模型选择面。
开关开启只允许已持久化 `compilerRequired` 路由且经服务端再次证明为 2–32 个穷举等价最优集合的真实同分
建立 `ACCEPTANCE_CLOSED_CHOICE_V7` 候选运行。该运行固定 `INTERNAL_MCP` 通道、同一冻结 Compilation
拥有者/设计修订/运行时代际和最多两次唯一提交；只开放专用候选 Session 的精确内部
`submit_candidate`，不开放内置工具或用户 MCP。只有闭集选择遗漏、越界或组合错误
`ACCEPTANCE_CANDIDATE_SELECTION_INVALID` 可在同一 Session 再提交一次；路径/权限/执行/拓扑字段、
非对象合同和非枚举快照分别以 `ACCEPTANCE_CANDIDATE_SECURITY_BOUNDARY / CONTRACT_INVALID /
NOT_ENUMERABLE` 失败关闭并进入人工输入，不创建修复 Session。Accepted writer 只从数据库冻结事实生成
规范 binding，并通过通用 `MachineCandidateSubmission` 的短事务落库；模型提交不能写路径、命令、权限或安全策略。

候选开关的启用资格与既有 v7 JSON 资格分开。测试专用 registry 从同一次真实运行原子记录
`modelCalls / candidateSessions / candidateSubmissions` 三轴：唯一最优严格为 `0/0/0`；真实同分严格为
`1/1/1..2`；非枚举与路径安全阻断严格为 `0/0/0`。当前 4 条真实 Designer/OpenCode guard 分别实测
`0/0/0`、`1/1/2`、`0/0/0`、`0/0/0`，结构候选资格为 `complete=true / passed=true`；连同 22/22 精确 guard、
7/7 指标 guard 和 1/1 同输入测量，证明服务端候选管道与三轴计数符合边界。当前 MCP 请求由测试驱动器发起，
不构成真实模型工具采用或自修正证明，因此生产默认值保持关闭；这不移除恢复已有持久化 run 所需的 policy/writer/adapter。

Stage 组装完成后，服务端优先接受 `负责路径` 的唯一显式声明，随后兼容 Stage 精确引用产生义务的受控
交付/范围事实、恰好一个 Stage 的既有精确路径规则、旧四列表格中仅一个阶段目标出现的精确文件名/类名/
末尾路径符号，或计划恰好一个 Stage 时补入精确 `WRITE/MOVE_DESTINATION`。旧格式符号恢复只做 NFKC 后的
完整 token 匹配，不做包含、相似度或语义猜测。多个 Stage 声明、覆盖或出现同一符号时保留为定点阻断并
显示候选 Stage 中文名，不交给 Compiler 选择。
随后在 lowering 前执行路径守恒：义务不能命中禁止路径，包级范围、全局事实和技术栈 fallback 不能单独证明归属。
Stage、focused test 和显式 `GIT_DIFF` 必须复用同一
allowed/forbidden 集合。遗漏返回 `REQUIRED_MUTATION_PATH_UNASSIGNED`，冲突、删除和移动源端返回
`REQUIRED_MUTATION_PATH_FORBIDDEN`。该门禁完全由服务端和运行期同一匹配语义决定，不交给弱模型、
Judge 或 catch-all Stage 降级；路径义务不进入模型输入输出，v7 最小闭集输出形状不增加执行字段。
当前 v7 普通包、大型任务包和滚动执行当前包在服务端绑定完整时都走 `SERVER_DIRECT`，不因包形态强制创建
Compiler Session。路径归属缺口直接进入有界人工输入门，不消耗整份工作包的自动重设计次数；相同设计修订
禁止原样重编译，工作包输入框与“恢复当前包设计”会把全部未归属路径和候选阶段注入完整替代设计提示。

测试能力只从“新增/修改/测试代码”等正向交付物、正向验收约束和阶段交付关系中发现；“不变、禁止、
不修改、不引入、无 `@SpringBootTest`/无框架上下文”等负向子句只保留为约束，不能生成 focused-test
目标。目标名先去除路径、扩展名和
通用 `Test/Spec/Core/Unit/Integration` 限定词，再结合符号标识、CamelCase 词和中文语义片段做竞争式
匹配；场景直接点名的目标优先，模糊关系只分配给得分最高的能力。每个业务条件最终只绑定一个
focused test；设计明确要求“各自独立通过”而没有独立业务场景的测试目标，会生成带原始约束来源的
独立机器验收条件，既不丢失验收意图，也不制造多测试覆盖同一条件的歧义。若包内只有一个正向
交付物声明的聚焦测试，所有未显式点名其他测试的新增场景均绑定该唯一目标，阶段中的“同一/该/本聚焦
测试类”也可权威回指该目标；
存在多个交付测试时不得猜测回指对象。仅说明既有测试“保持通过/继续回归”或“测试风格一致”的
子句只生成独立必跑约束，不得借词汇相似度冒充新增业务场景的覆盖证据。

AI 分组中引用的 `DELIVERABLE / SCOPE` 事实只用于确定该 Stage 的交付与路径边界，不参与业务条件
集合覆盖。服务端仅接受完整、独立的仓库相对路径或 glob；含中文说明、连接词、标点或仅由中文分段
组成的斜杠短语的 scope 整句
不会下沉为 `allowedPaths` 或 `forbiddenPaths`，阶段与其 PROCESS/GIT_DIFF 证据必须复用同一份过滤后
路径边界。固定章节名称必须各出现且只出现一次，可选人工评审章节也不得重复；当前软件设计缺少或
重复章节时必须阻断重设计，不能把同一响应中的两套完整设计合并。具备固定章节的当前软件设计若没有
可解析的 GFM“验收场景”表，必须以 `MISSING_ACCEPTANCE_INTENT` 阻断并进入重设计，不得使用兼容旧稿
的段落降级把标题、范围或伪表格冒充业务场景。若一个 `JAVA_PRODUCTION` 分组只剩 Judge 条件，服务端必须从该组明确
引用的测试交付或包内唯一聚焦能力绑定一个 `covers:[]` 的阶段门禁；多个候选或没有真实聚焦能力时
确定性返回 `DESIGN_INCOMPLETE`，不得把硬校验失败转交给模型反复修复。最终 LoopSpec 验收只在该
`JAVA_PRODUCTION` Stage 的全部条件均为 `JUDGE` 时接受空 `criterionIds` 的真实聚焦测试门禁；一旦
存在 `MACHINE/BOTH` 条件仍须逐项覆盖，其他空行为验证器仍然非法。

普通模式若无法把完整设计安全编译成一个 1–6 Stage 工作包，Compiler 返回唯一专用缺口
`LARGE_TASK_MODE_REQUIRED`。服务端立即停止，不消耗自动重设计或修复预算，也不自动切换流程；
用户显式“改用大型任务”后，系统重开整体需求、终止普通讨论 Session 并立即启动大型需求预设计；
预设计完成并再次确认后才调用 Decomposer。大型切回普通时终止未冻结预设计，服务端从用户输入和
已回答决策重建快照，未确认 AI 推断不进入普通需求语义。
大型流程中的 Compiler 不得返回该缺口；多项目根、超过六包和独立发布边界仍使用
`MULTI_TASK_REQUIRED`。

滚动模式下，Compiler 仍只编译当前包的验收语义；服务端把确认结果追加为新的完整
`TaskSpecRevision` 和新 Stage，不回写原始 LoopDraft，也不修改或重排已执行 Stage。包内标为
`JUDGE/BOTH` 的条件在事实冻结时只是“计划评审”，不会启动包级 Judge。只有全部有效包
`FACT_FROZEN` 后，最终累计 TaskSpec 和完整事实链才进入唯一一批 Requirement/Risk Judge。

服务端从 DesignFact 的精确来源生成 `<WP>-AC-n`，并把求解结果编译成验证器关联。v3 历史流程
继续解析 `DS-L` 与 Maven/Gradle/npm/pytest/unittest 显式选择器，不会被 v6 快照重解释。
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
受控设计已声明合法的显式测试命令时，服务端直编必须原样保留其框架和目标；即使交付表或 Stage
同时提供了可推导的测试路径，也不得把显式 `unittest` 改写成猜测的 `pytest`。只有完全缺少合法
显式命令时，才允许按冻结技术栈和正向测试目标确定性派生候选。执行 Python TEST 时，验证器进程
固定注入 `PYTHONDONTWRITEBYTECODE=1`，防止自身生成的 `__pycache__`/`.pyc` 越过冻结 GIT_DIFF
路径；声明 argv、测试框架、目标和 Stage 路径集合均不因此改写。

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

v7 消歧提示给出唯一小型对象形状和全部闭集候选。服务端可机械接受可逆字段别名、单项对象/数组互转、
`null` 集合归一为空集合，并忽略不参与合同的说明字段；每个动作都写入 AI 输出 `NORMALIZED` 审计和
`safeNormalizations` 诊断，不消耗格式/语义修复预算。原始响应在规范化前扫描：路径、命令、测试目标、
Stage 拓扑、权限或安全字段即使 Schema transport 接受也必须失败关闭。缺少必选项、越界索引、重复或冲突
选择、修改锁定事实，以及多个不等价的有效 JSON 候选在显式关闭开关的兼容路径继续产生
`AMBIGUOUS_ACCEPTANCE_INTENT`，不创建格式或语义修复 Session；候选开关开启后只有纯机械闭集选择错误
可由同一候选 Session 在总提交上限内重送，安全、合同和非枚举问题仍不可重试。两条路径都不得以空 binding、
丢弃建议或 catch-all Stage 继续。无效结果保留冻结事实和
已完成的唯一绑定，只更新失败诊断。结构缺失、依赖冲突和确定性验证能力缺失直接
`DESIGN_INCOMPLETE`，不会为了取得一个无法改变结论的回答而调用 Compiler。

v3 历史活动仍按既有 `AI_SEMANTIC_PATCH_V1` 和最多 16 个补丁兼容；冻结 v4/v5 的在途工作包继续按
自己的快照恢复，不会被 v6/v7 合同重解释。冻结 v6 使用原阶段表和 V6 Schema；当前 v7 新请求使用
同一受控阶段表和 V7 最小闭集 Schema。

v7 上线证据由 `weak-model-compiler-v7-evaluation.md` 的版本化 corpus 和只读同输入 shadow 提供。corpus
逐样本固定独立的修改义务/硬缺口预期并精确执行引用的生产算法 guard，但期望汇总明确不是测量门禁；
生产编译链在同一冻结输入上实际产生权威实测，关键 guard 还通过测试专用 registry 发布实际调用、
重设计、安全与覆盖计数；单一样本不构成完整资格，只有这些实测与全部精确 guard
共同通过的 qualification 才是权威本地门禁。实测计数为负、覆盖数超过基数、
任一单样本路径/硬缺口/focused-test 退化都失败，不能用其他样本的超报抵消。单 Stage 只丢弃零匹配的
无害说明标签；同名多匹配事实仍作为真实歧义失败关闭。
shadow 不创建额外 Compiler Session，不保存原始模型输出或需求正文，也不能推进 Designer/Task；只比较
稳定状态、计数和缺口码。Compiler 表面通过率不能单独放行，必须同时满足端到端路径守恒、硬阻断、
模型调用、整稿重设计、Judge-only 与 focused-test 门槛。冻结 v5/v6 不参加迁移。
`DIRECT_SOFTWARE_DESIGN` 的隐式 `WP-1` 继承已确认的软件任务画像；需求正文中的否定性
“依赖/配置”措辞不得把它降级为 `local-maintenance`。若历史或中断记录出现这种与父画像
冲突的非软件 Role Pack，人工重新编译前的权威读取会重新冻结软件 Role Pack，再进入 v6
验收事实与能力求解器。只有大型任务的显式分包允许按包内容专门化为文档或配置维护角色。
历史 v3 补丁只能修改 AI 拥有的语义字段，不能覆盖服务端控制字段或任何派生字段；
应用后必须重新运行完整提取、规范化和权威校验。
紧凑 Compiler Stage 的证据字段固定为 `evidence`；弱模型若在补丁路径中使用最终 DTO 名
`verifiers`，服务端只在 `/stages/<n>/verifiers...` 可唯一反推时改写为 `evidence`，并把对
尚未出现的对象字段执行 `replace` 规范化为等价 `add`，两类纠正都写入 V28 审计。每个
`JAVA_PRODUCTION` Stage 即使只有 Judge 条件也必须保留 `covers:[]` 的聚焦 Maven/Gradle
TEST；`FULL_TEST`/`BUILD` 不能替代该门禁。只有完整补丁应用后再次通过全部语义、路径、
测试和验收校验，才允许冻结结果。

新 Compiler 记录按其冻结规划合同解释：当前 v7 使用闭集规范化边界，冻结 v6 保持严格原形状，只有冻结为旧 Role Pack 或明确含历史
`evidenceMappings` 的活动记录才走兼容解析。缺少 `outcome` 是 v6/v7 的合法形态；历史响应额外携带
`status/outcome/designGaps` 也不能夺回结果所有权。服务端在闭集覆盖后唯一派生 `COMPILED`，无覆盖能力时唯一派生带具体事实
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
