# OpenCode Loopper 开发公约

本文件约束开发本仓库的 Agent。当前用户明确要求优先；目录专属规则只能细化适用范围，不能隐式放宽安全边界。首次进入任务时完整阅读本文件；后续仅在文件变化、任务范围变化或上下文丢失时重读。修改 `src/` 或 `frontend/` 前分别读取其 `AGENTS.md`，即使工具未自动加载。

当前交付版本：`0.4.0`。实际版本由 `pom.xml` 持有，发布引用用脚本同步。

## 1. 作用域与授权

区分仓库开发 Agent 与产品内部的 Designer、Implementation、Judge。产品角色的只读权限、工单提交格式、任务分支和人工操作要求，是对应功能必须保持的产品合同，不直接替代本仓库开发授权。

- 纯调查、解释、评审默认只读；明确要求修复、实现或按建议优化后，持续完成已授权范围。
- 可以自主完成常规实现选择、必要的局部职责调整、测试修复和文档同步。不得顺手重构无关功能。
- 只有选择会改变未确定的用户可见行为、数据兼容、外部副作用或授权范围时，才请求用户决策。先完成不依赖该决策的工作，给出具体方案、推荐和影响。
- 已明确授权的动作不重复确认。既有指令或技能导致停顿时，说明具体规则及其适用原因，不把建议擅自提升为审批要求。
- 完成验证后默认创建只包含本任务内容的本地提交；用户明确要求暂不提交时例外。
- 推送分支、创建 PR/MR、发布标签/Release 和部署分别需要对应授权。普通代码交付不包含这些动作；“发版/推送新版本”包含标准提交、标签与 Release 流程，不包含服务部署或重启。不得强推、移动或复用正式标签。
- 不停止、替换或覆盖既有服务/JAR。需要隔离运行时验收时使用独立数据、端口和进程，确认其所有权并清理本次创建的进程；真实 Provider 调用遵守任务授权和预算。

## 2. 必须保持的核心边界

以下是跨任务摘要；各主题的精确定义与例外由第 4 节对应合同持有。

1. **服务端权威**：生命周期、许可、路径/命令编译、冻结版本与最终接受由服务端决定。模型候选、MCP 成功、前端投影和 Todo 不产生业务成功事实。
2. **独立状态域**：Task、Stage、Attempt、Session、Judge、Queue、Lease 与候选 run 分开。状态变化经过既有生命周期入口并审计，普通 mutation 不伪造状态转换。
3. **确认与执行分开**：新任务确认后为 `PENDING_START`；只有正式 Start 才申请 Queue/Lease 和执行目录。全自动范围按设计合同区分普通任务与滚动包，执行期人工边界不得被设计授权覆盖。
4. **停止必须有证明**：abort true、精确不存在或独立终态才能证明停止。未知状态保留阻断与租约，不得启动重叠 writer。外部投递未知不得盲重发。
5. **事务与副作用分开**：数据库短事务负责状态/审计及 CAS；网络、模型、Git、进程、浏览器和长文件 I/O 在事务外。返回后复核原身份与版本，迟到结果不得覆盖新状态。
6. **冻结与恢复**：历史任务按冻结合同恢复；功能开关只控制新 run，不关闭恢复读写。已执行迁移只追加、不修改。已冻结 Task/Stage/Recovery 不随项目重析变化。
7. **验收证据分开**：构建、差异和文件存在不能替代行为验证。生产 Java 变化保持聚焦测试门禁。自动评审通过要求同批双 Judge PASS；独立人工认定不修改原 verdict，不绕过确定性验收或停止证明。
8. **路径与网络**：canonical containment、符号链接和敏感文件保护保持；产品验证器网络仅 loopback。Stage 路径提示、执行权限与 GIT_DIFF 验收是不同边界，范围外新增/修改行为按验证器合同处理。
9. **现有工作保护**：开始执行 `git status --short`，区分既有与本次改动。不得恢复、覆盖、格式化或提交他人修改；重叠时优先避开，无法安全分离才请求决策。不得擅自切换当前分支。
10. **证据与秘密**：不手改生成物、SQLite 或历史迁移修复源码问题；不把凭证写入日志、文档、测试快照或产物。不自动删除用户文件、worktree、分支、运行数据或历史证据。

## 3. 工作方式与完成条件

1. 识别本次目标、变更前状态及影响范围。阅读对应合同、目标源码、相邻测试与至少一个相似实现；没有相似实现时记录事实，不虚构先例。
2. 对相关问题集中定位根因和受影响路径，再形成一致的修改批次；实施期间可运行必要的聚焦测试取得反馈。
3. 按需补充行为测试，复用既有夹具。纯文案、排版或无行为变化的低影响修改不要求镜像实现的测试；说明实际采用的检查。
4. 先完成聚焦与受影响集成回归，最终交付内容稳定后执行一次完整门禁。检查通过后，仅在输入变化、出现新失败或存在未解决风险时重复；不得为“更放心”无限扩测。
5. 用户中途补充要求时，重新判断影响范围，只使受影响的实现和验证失效；保留其他已完成工作。长任务保留目标、决定、文件归属、测试结果与下一步，避免恢复后重做。
6. 并行读取和互不依赖检查可以批量执行。子 Agent 仅用于确能独立分工且有收益的任务，须有清楚的文件所有权；主 Agent 负责集成、版本、完整门禁和提交。小任务使用单 Agent，同一 checkout 的构建输出不得并发写入。
7. 文档只同步受影响的权威主题。开发规则、入口或完成定义变化时才修改根公约；日常交付记录写入 `docs/deliveries/`，不继续增长本文件。
8. 完成后检查 diff、`git diff --check`、工作区及暂存区，只提交本任务文件。

验证级别与实际命令：

| 任务 | 开发反馈 | 最终交付 |
| --- | --- | --- |
| 只读评审 | 源码/文档/必要只读证据 | 报告，不修改、不打包 |
| 纯说明文档 | `node scripts/check-project.mjs` | 文档检查、本地提交，不升版本或生成 JAR |
| Java 行为/结构 | `./scripts/dev-check.sh backend <TestClass>` | 聚焦及受影响集成回归后，完整门禁 |
| 前端行为 | `./scripts/dev-check.sh frontend <src/...spec.ts>` | 必要浏览器验收后，完整门禁 |
| 生命周期/权限/迁移/协议 | 对应失败、并发、恢复、升级测试 | 完整门禁与受影响隔离运行时验收 |
| 构建/运行提示/可执行内容 | 工具或合同聚焦测试 | 新版本完整 JAR 交付 |

代码与构建变更默认以完整 JAR 为交付；内置 JDK 21 的平台包仅在用户明确要求时手动生成，流程见开发文档。文档若进入运行提示、生成规则或打包内容，按实际行为影响分类。快速测试使用 `target/backend-dev`，没有正式静态资源，不是完整门禁或成品。

完整交付：同步一个未使用且递增的版本 → `./scripts/verify.sh` → 检查正式 JAR、静态资源和 SHA-256 → 更新交付记录 → 本地提交。只回填测试数、哈希和结果的记录不改变产物，不触发递归打包。聚焦失败先修复；完整候选构建后若可执行交付内容再变，重新分配版本。失败输出必须保留，禁止伪造成功。

最终回复只报告本次适用的证据：改动、验证及未覆盖项、本地提交；生成 JAR 时报告路径和哈希；收到发布授权时报告标签、Actions 与远端资产；明确尚未部署/未执行的运行时验证。源码、测试、JAR、活动 JVM、浏览器与 Provider 是独立证据。

## 4. 按任务加载的权威文档

先检索标题，再读取相关章节，不要求一次加载所有合同。每个主题只有一个规范定义；其他文档是摘要或引用。源码/测试提供当前实现证据，不自动覆盖产品合同；冲突时查明是文档陈旧、实现缺陷还是历史兼容。本次已授权范围内可修正文档陈旧；需要改变未明确产品行为时提出具体决策。

| 任务/主题 | 规范定义 | 主要代码入口 |
| --- | --- | --- |
| 生命周期、错误、事务、租约、结果与发布 | [架构合同](docs/architecture.md) | `lifecycle/`、`TaskService`、`TaskJudgeApprovalService`、`TaskPublicationService` |
| Designer、人工/自动动作、冻结设计、UI | [设计合同](docs/design-contract.md) | `DesignerSessionService`、`DesignerAutoModeService`、`WorkPackageRoleService`、`DesignerView.vue` |
| 角色语义、候选、默认合同与来源 | [AI 角色合同](docs/ai-role-contracts.md) | `MachineRoleContractCatalog`、`PackageDesignCompilation` |
| OpenCode、MCP 通道、权限、Session、预算轴 | [OpenCode 合同](docs/opencode-contract.md) | `runtime/`、`MachineCandidateSubmission` |
| 验证器、Recovery、Interaction、自动化、洞察 | [功能合同](docs/seven-feature-contract.md) | `verification/`、`RecoveryService`、`InteractionService` |
| 职责划分、依赖方向、规模门禁 | [代码设计合同](docs/code-design-contract.md) | 目标协作者、相邻测试、`CodeStructureContractTest` |
| 构建、版本、交付、流程评测 | [开发与交付](docs/development.md) | `pom.xml`、`scripts/`、CI/Release |
| 安装、配置、运维 | [README](README.md) | `application.yml`、三平台启动脚本 |
| 工作包 V2 与已撤回实验 | [V2 启用](docs/package-design-v2-enablement.md)、[回退边界](docs/package-behavior-rollback.md) | V2 编译内核、V74/V75（不可修改） |
| 统计绑定、附件、模型资格 | [统计](docs/story-binding.md)、[角色合同](docs/ai-role-contracts.md)、[Luna 评测](docs/package-design-luna-optimization.md) | 对应 coordinator 与独立资格脚本 |

技术版本从 `pom.xml`、`frontend/package-lock.json` 读取；源码入口位于 `src/main/java/io/opencode/loopper/`，前端位于 `frontend/src/`，迁移位于 `src/main/resources/db/migration/`。`target/`、`frontend/dist/`、`frontend/node_modules/`、`data/` 都不是手工维护源码。

根公约控制在 16 KiB 内；`src/AGENTS.md` 与 `frontend/AGENTS.md` 各不超过 12 KiB。检查入口验证体积、有效文档链接和发布版本引用。历史全文及迁移去向见 [历史索引](docs/history/README.md)；历史不得重新作为当前默认指令加载。Astra 的协作优化不改变产品角色权限、模型配置或用户批准边界，评测状态见开发文档。
