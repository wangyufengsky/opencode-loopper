# 角色配置与发布

本合同定义产品业务角色的配置、发布、绑定与运行冻结。它不改变仓库开发 Agent 的授权，也不替代 [AI 角色合同](ai-role-contracts.md)、[OpenCode 合同](opencode-contract.md) 中的生命周期、候选协议、预算和服务端验收。

## 数据与生效边界

内置角色清单位于 `src/main/resources/roles/builtin.yaml`，静态提示片段位于 `src/main/resources/role-prompts/`。`catalog.properties` 登记可读取的片段；`role-fragments.properties` 明确各业务角色使用的共享片段。Role Pack 的版本化定义仍描述技术栈、执行及测试策略，与业务角色的角色 ID 分开。

当前内置目录包含 26 个业务/辅助角色、52 个工作流职责位置，覆盖全部 39 个 SessionProfile，以及需求/风险评审的专用绑定、需求讨论、设计讨论、提交说明、合并建议和统计命令。阶段协议与候选编译器仍由服务端适配器提供。

| 对象 | 保存的信息 | 生效与修改方式 |
| --- | --- | --- |
| 角色定义 | 稳定 ID、中文名称、作用、分类、来源 | 导入发布更新目录元数据；旧修订保留当时定义 |
| 角色修订 | 完整清单、静态 Prompt 片段、权限声明、策略引用、内容 SHA-256 | 不可变；同角色相同语义内容复用已有修订 |
| 工作流绑定 | 职责位置、适配器、角色修订、绑定版本 | 发布事务按预期版本切换；并发冲突整批回滚 |
| 流程拥有者快照 | 所有职责位置的修订、父流程、数量及摘要 | 创建或冻结流程时一次保存；派生流程继承父快照 |
| 执行快照 | Session、拥有者、职责位置、角色修订、适配器版本、有效权限及摘要 | 在请求准备/会话创建边界保存，后续请求复核 |
| 请求身份 | 业务消息标识及发送摘要 | 不保存临时授权凭证；恢复沿用原精确投递协议 |

首次启动幂等初始化内置版本。初始化后的应用升级可以追加内置修订和新增职责位置，不覆盖已有激活绑定。恢复或重试不读取当前绑定重新解释旧流程。没有角色快照的旧拥有者保持历史路径；其派生流程继承显式的历史兼容标记，不擅自套用当前默认角色。

Designer 会话、直接创建的 Task、模板 Task、文档导入流程、知识会话、源码模板流程与 PPT Run 在各自创建边界冻结。Task 从 Designer、文档或源码模板流程派生时继承原绑定；恢复任务经新草稿继承父 Task 快照。新 Attempt 继续使用 Task 的角色修订，工具策略按原有工具授权合同编译，不能扩大 Task/Stage 已冻结的数据或路径授权。

## 配置包

系统 → 角色管理（`/roles`，原 `/settings/roles` 自动跳转）可查看、按版本导出配置 ZIP。导出的清单和 Markdown 文件可在外部编辑，再通过页面校验、检查差异并发布。首版没有在线创建/编辑表单；通过配置包可以新增使用现有适配器的角色。

ZIP 在内存中解析，不解压到项目目录。只接受 `manifest.yaml` 和清单引用的 `prompts/*.md`，拒绝路径穿越、重复条目、未知字段、YAML 对象标签/别名及超限内容。上限为压缩包 2 MiB、单条目 512 KiB、总展开文本 3 MiB、1024 个条目。

下面是只读助手的配置示例。实际使用时先导出目标角色，以保留它的完整 Prompt 片段引用。

```yaml
schemaVersion: 1
roles:
  - roleId: team.reader
    displayName: 项目只读助手
    description: 在现有只读流程中核对项目文件
    groupKey: general
    groupLabel: 通用助手
    allowedSlots: [GENERAL_READ_ONLY]
    permissionMode: INTERSECT
    nativeTools: [read, glob, grep]
    mcpTools: []
    requiredMcpTools: []
    modelPolicy: INHERIT_WORKFLOW
    runtimePolicy: WORKFLOW_ADAPTER
    prompts: {}
bindings:
  - slot: GENERAL_READ_ONLY
    roleId: team.reader
```

`allowedSlots` 声明兼容的既有职责位置；`bindings` 指定本次实际激活的绑定。显式空列表只发布修订。省略 `bindings` 的兼容包会激活各角色列出的职责位置，校验结果会完整展示目标。导出包只列出当前激活到该角色的绑定。角色 ID 不要求新增 Java 枚举；新增工作流、所有者、协议或编译器仍需代码。

修订号由服务端分配，不接受客户端覆盖既有修订。语义内容由完整清单与 Prompt 内容计算哈希；重复相同内容复用已有修订，导出历史修订再发布可重新激活该内容。发布请求同时携带原 ZIP 摘要、校验时的绑定版本与幂等键。发布前重新解析/校验相同内容；不同内容复用幂等键、绑定变化、未知插槽或越界能力均拒绝，不保留部分激活状态。

## 权限、MCP 与 Prompt

权限编译是角色声明、适配器上限、全局/项目策略、冻结任务授权与当前阶段的交集。`BASELINE` 保留现有授权规则和顺序；`INTERSECT` 在此基础上收窄，保留保护路径/Git/停止/候选校验。私有提交工具及合同查询由对应协议固定；角色配置不能替换成另一角色的提交工具。

MCP 使用精确工具名。内置服务通过 `@loopper-internal/工具名` 与 `@loopper-assist/工具名` 引用，派发时绑定当前运行时名称；第三方工具使用稳定服务名和精确工具名。配置不接受通配符授权或凭证。声明为必需的工具必须位于允许列表中，且创建会话时已发现并获得授权，否则派发失败并指出工具。可选工具不可用时不进入实际授权清单。

静态角色说明和 Role Pack 提示片段按冻结修订装配；动态工作流事实、StageSpec、路径、候选身份、验收结构和临时凭证由服务端生成。装配只在同步 Prompt 构造函数的明确作用域内使用配置，退出即恢复；不对最终消息做子字符串替换，不替换用户需求或代码中的相同文本。

首版静态片段没有可执行模板变量。动态值由原适配器在对应位置拼接；导入拒绝新增未知变量、表达式及 include 指令。内置示例中已有的字面占位符保持数据语义，不作为表达式执行。模型策略 `INHERIT_WORKFLOW` 保留用户显式模型选择的优先级；`WORKFLOW_ADAPTER` 引用当前阶段的独立预算与恢复策略，角色配置不能把步数、轮数、Attempt 次数或 MCP 提交次数混为一项。

统计使用独立 `ACCOUNTING_COMMAND` 适配器及 `loopper-accounting` 原生 Agent，工具权限、命令参数、回执和统计/业务消息隔离保持固定。唯一可配置片段是 `accounting.instructions`。新统计调用的持久化消息身份带角色标记，受管桥读取绑定到精确消息/会话的本地不可变描述文件并校验内容哈希；缺失或不符时拒绝执行。历史无标记调用沿用原路径；未知投递不重发。

## 查看、预估与会话证据

角色页以“描述”展示职责、所属工作流和流程位置。“MCP”统一展示原生工具、配置声明及适配器计算出的程序内置工具，工具名和权限名后附中文用途，并标记来源；BASELINE 的空声明不等于没有工具。角色页区分配置声明、项目条件下的预估和会话已冻结权限。预估共用适配器上限和权限交集编译，工具策略通过纯读取路径取得，不登记策略、不创建 Session、不调用 Provider。未取得实际 MCP 工具目录或 Task/Stage 授权时保留不完整标志，以简短状态展示，只有阻断或项目异常显示具体原因，不能把预估当成已获得运行授权。

版本页按需读取历史、比较字段及 Prompt 内容差异。角色模板是静态配置；首版不提供通过任意外部 Session ID 查询历史完整 Prompt 正文的接口。Task 会话的角色摘要先通过原 Task/Session 归属校验，再返回角色修订、适配器和冻结权限。

| 接口 | 用途 |
| --- | --- |
| `GET /api/roles` | 分页搜索角色摘要 |
| `GET /api/roles/{id}` | 角色详情和绑定概览 |
| `GET /api/roles/{id}/revisions` | 历史修订分页 |
| `GET /api/roles/{id}/revisions/{revisionId}` | 不可变修订及静态片段 |
| `GET /api/roles/{id}/compare` | 两个指定修订的内容差异 |
| `GET /api/roles/{id}/export` | 导出指定修订的 ZIP |
| `GET /api/role-bindings` | 现有职责位置、适配器和绑定版本 |
| `POST /api/roles/{id}/preview` | 确定性权限预估；保留 GET 兼容查询 |
| `POST /api/role-imports/validate` | ZIP 校验、诊断位置、变更与激活目标 |
| `POST /api/role-imports/publish` | 同 ZIP 摘要、绑定版本、幂等键原子发布 |
| `GET /api/tasks/{taskId}/sessions/{sessionKey}/role` | 归属校验后的冻结角色和权限摘要 |

导入写入口保持本地 UI 来源校验。失败时页面保留配置包与诊断，可修正后重校验；发布冲突必须重新读取当前绑定。发布成功只影响之后新建或冻结的流程。

## 迁移检查表

| 业务职责 | 运行/历史入口 | Prompt 与冻结来源 |
| --- | --- | --- |
| 开发 | TaskService / Attempt | Task 冻结角色、当前 StageSpec、版本化 Role Pack |
| 需求/设计讨论 | DesignerSessionService / DesignerConversationCoordinator | Designer owner 与会话角色、原讨论协议 |
| 规划/工作包设计/验收编译 | Designer、候选 launch、滚动规划 | 受管准备快照或显式 Session、各候选协议 |
| 需求/风险评审 | TaskService / JudgeDecisionCandidateWorkflow / LegacyJudgeTransport | Task 的分角色绑定、冻结评审证据、双 Judge 原合同 |
| 分析评审/项目规范 | AnalysisReportService / ProjectConventionService | Designer/规范草案快照、精确候选工具 |
| 知识库 | KnowledgePersistence / KnowledgeCoordinator | 知识会话冻结绑定及原数据授权；不新增知识访问权限 |
| PPT | PptAgentPersistence / PptAgentCoordinator / PptAgentTools | PPT Run 角色、会话权限、制作阶段检查 |
| 文档/模板/源码评审 | DocumentTemplateAdmission / DocumentModelExecution / TemplateBatchExecution | 导入流程或 Task 的冻结版本、原来源与验收合同 |
| 来源模板详细设计/独立复核 | SourceTemplateAdmission / SourceModelStore / SourceModelExecution | 来源流程、模型批次的继承快照、冻结原文及专属候选协议 |
| 提交说明/合并建议 | TaskPublicationService / LocalSyncAiAdvisor | Task 冻结绑定、实际 Git 或三方文件事实 |
| 故事统计 | StoryAccountingCoordinator / OpenCodeCommandTransport | 原持久化消息身份、独立 command 修订和受管 guard |

角色表通过 V139 追加，保留已执行的 V127–V138 来源模板迁移；从 0.4.70/V126 和 0.4.74/V138 升级均不为旧会话回填角色正文。

回归必须同时验证配置驱动、默认权限等价、候选隔离、发布原子性、历史恢复、只读无副作用与页面操作。Fake/隔离 HTTP/浏览器证据证明配置和协议行为，不替代真实 Provider 效果评价。
