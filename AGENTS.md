# OpenCode Loopper Agent 开发公约

本文件适用于仓库根目录及其全部子目录，是所有在本项目中工作的 Agent 的强制规则文件。若子目录以后增加更具体的 `AGENTS.md`，应同时遵守本文件和距离目标文件最近的规则；冲突时以用户的当前明确要求和更具体的子目录规则为准。

## 0. 最高优先级：每次任务必须执行

### 开始开发前

任何 Agent 在分析、开发、修改、优化、重构、修复或评审本项目代码前，必须：

1. **从头到尾阅读本文件**，不得依赖之前会话中的旧摘要代替当前文件。
2. 执行 `git status --short`，识别并保护用户或其他 Agent 已有的未提交修改。
3. 阅读与任务直接相关的源码、测试、类型、迁移和本文件指向的契约文档。
4. 找到至少一个现有的相似实现和相似测试，优先延续项目已有模式。
5. 明确本次任务的事实边界：源码、测试、打包 JAR、正在运行的 JVM、浏览器静态资源、OpenCode/模型状态是不同证据，禁止混为一谈。

未完成以上步骤，不得开始写代码。

### 开发结束前

任何仓库更新完成前，Agent 必须：

1. 为行为变化补充或更新自动化测试；不能测试时说明具体原因和未覆盖风险。
2. 更新受影响的用户文档、架构契约或运维说明。
3. **每次代码开发、修改、优化结束时都必须更新本 `AGENTS.md`**：
   - 项目结构、命令、约束或契约有变化时，直接更新对应章节；
   - 即使规则没有变化，也要更新“维护记录”，写明日期、范围、验证命令和 JAR 结果；
   - 不得只更新维护记录而遗漏已经变化的正文。
4. **每次形成新的可交付 JAR 前必须先更新版本号**：
   - 任何开发、修改或优化后的重新打包都视为一次新交付，必须先递增版本号；
   - 版本采用递增且从未发布过的 `MAJOR.MINOR.PATCH`，禁止复用 Maven 版本、Git 标签或 GitHub Release；`MINOR` 和 `PATCH` 均只允许 `0–99`；
   - 常规交付递增 `PATCH`；当前 `PATCH=99` 时向 `MINOR` 进一并把 `PATCH` 归零，例如 `0.1.99` 的下一版本必须是 `0.2.0`，不得使用 `0.1.100`；当前 `MINOR=99` 且再次进位时向 `MAJOR` 进一并把 `MINOR/PATCH` 归零，例如 `0.99.99` 的下一版本是 `1.0.0`；
   - 同步更新 `pom.xml`、`frontend/package.json`、`frontend/package-lock.json`、`application.yml`、Java MCP server info、README、本文件、`scripts/start-linux.sh` 和 `scripts/start-windows.bat`；
   - 使用 `rg` 检查旧版本是否仍残留在应同步的发布路径中；
   - 同一版本下仅允许对失败的同一次构建做诊断重试；源码或交付内容再次变化后必须使用下一个版本。
5. 运行与改动直接相关的聚焦测试，再运行完整验证和打包：

   ```bash
   ./scripts/verify.sh
   ```

6. 确认生成新的可执行 JAR：

   ```bash
   test -s target/opencode-loopper-0.2.14.jar
   jar tf target/opencode-loopper-0.2.14.jar \
     | rg 'BOOT-INF/classes/static/(index.html|assets/)'
   shasum -a 256 target/opencode-loopper-0.2.14.jar
   ```

7. 执行 `git diff --check` 和 `git status --short`，确认没有误改、生成物污染或用户改动被覆盖。
8. 完成本地验证后，为本次范围创建本地提交；除非用户明确要求暂不提交，否则不得把已完成交付长期留在未提交状态。提交前必须确认暂存区只包含本任务文件，不得顺带纳入用户或其他 Agent 的既有修改。
9. 默认不得推送提交、创建或推送标签、创建 GitHub Release。只有用户明确要求“发版”“推送新版本”或同等含义时，才统一推送已经核验的本地提交，创建并推送指向最新交付提交且不可移动的 `v<version>` 标签；标签触发 `.github/workflows/release.yml` 后，必须等待工作流结束并回读 Release 资产状态与 digest。
10. 最终交付必须明确报告：修改文件、验证命令及结果、本地 JAR 路径与校验值、本地提交；未收到发版要求时明确报告尚未推送、未打标签、未创建 Release，收到发版要求时再报告 Git 标签、GitHub Release URL、Actions 结果；同时说明尚未执行的运行时验证和剩余限制。

**“源码已改”“测试通过”“JAR 已生成”“端口 8080 正在运行新 JAR”“浏览器已加载新静态资源”是五个不同结论。** 未实际核验时不得宣称后一个结论。

### 例外处理

- 用户明确要求不运行测试/不打包时，遵从用户要求，但必须在最终交付中醒目标注未生成新 JAR。
- 因环境、网络、依赖或已有用户改动导致完整验证失败时，不得伪造成功；先保留失败输出，尽可能运行安全的聚焦验证，并报告阻塞点。
- 纯调查、解释或代码评审不授权修改文件，也不要求为只读任务打包；一旦实际修改仓库文件，就按上述交付流程执行。
- 完整打包后仅回填本文件“维护记录”中的测试数、JAR 哈希和结果，不需要递归再次打包；该回填不改变可执行产物内容。
- 本地提交是默认代码交付流程，但不等于发版授权。只有用户明确要求发版后，才允许统一推送已核验提交和对应的新版本标签；除此之外，不要擅自推送任何分支或标签、创建 Release、部署、重启服务或覆盖运行中的 JAR。

## 1. 项目目标与产品边界

OpenCode Loopper 是一个本机 AI 编程控制平面：将自然语言需求转换为经逐步人工确认或明确会话授权确认的分阶段 `LoopSpec`，在受控工作区中驱动 OpenCode 实施，并通过确定性验证、独立 Requirement/Risk 双 Judge、恢复和发布流程形成可审计闭环。

必须保持的产品边界：

- Loopper、受管 OpenCode、MCP 和验证器网络访问默认只绑定或允许 loopback。
- 服务端持久化状态是权威事实；前端不能制造队列、进度、用量、成本或模型输出。
- Designer 只读；确认 LoopSpec 之前不得写业务源码、创建执行任务或假装交付完成。
- 明确授权是不可跳过的边界：LoopSpec 默认逐步人工确认；用户可按单个 Designer 会话明确授权全自动设计直至 Task Start。危险权限、执行期决策、成功任务发布、本地冲突写回仍必须由人工逐次处理。
- Loopper 不自动强推、不自动合并托管平台请求或删除旧版 worktree；只有用户确认任务提交后，才自动恢复该任务开始前记录的源分支。
- Direct 模式直接写登记目录，但有独立租约、队列和私有基线；它不是较弱的“随便写”模式。
- 本项目不是多租户远程执行平台，也不把模型推理内容或外部 Provider 状态伪造成 Loopper 生命周期。

## 2. 技术栈和固定版本

### 后端

- Java 21；编译目标由 `pom.xml` 的 `maven.compiler.release=21` 固定。
- Spring Boot 4.1.0、Spring WebMVC、Actuator、Bean Validation。
- MyBatis 4.0.0、Flyway 12.0.0、SQLite JDBC 3.47.1.0。
- Spring AI 2.0.0 Streamable HTTP MCP Server。
- Playwright Java 1.62.0；产品 `BROWSER` 验证器只使用操作系统已安装的 Chrome/Chromium。

### 前端

- Vue 3.5、TypeScript 5.7、Vite 6、Pinia、Vue Router、Element Plus。
- CodeMirror 用于代码/合并编辑，ECharts 用于洞察，Markdown-it + Mermaid + DOMPurify 用于安全文档渲染。
- Vitest + Vue Test Utils；浏览器验收使用 Playwright。

### 构建产物

- Maven 项目版本：`0.2.14`。
- 正式产物：`target/opencode-loopper-0.2.14.jar`。
- Maven 固定准备 Node.js `v22.14.0` 和 npm `10.9.2`，执行 `npm ci`、类型检查、Vitest 和 Vite build，再将 `frontend/dist` 复制到 `target/classes/static` 后构建 JAR。
- `target/`、`frontend/dist/`、`frontend/node_modules/` 和运行时 `data/` 都是生成或运行目录，不作为手工编辑的源码来源。

版本升级时必须同步检查并更新：

- `pom.xml`；
- `frontend/package.json`；
- `README.md` 中的版本和命令；
- `scripts/start-linux.sh` 中的 JAR 文件名；
- `scripts/start-windows.bat` 中的 JAR 文件名；
- `src/main/resources/application.yml` 中 MCP server version；
- 本文件中的版本和产物路径。

## 3. 项目结构地图

```text
.
├── AGENTS.md                         # Agent 强制公约；每次代码任务结束更新
├── README.md                         # 面向最终用户的安装、使用和运维说明
├── pom.xml                           # Java/Maven、固定前端工具链和单 JAR 打包
├── scripts/
│   ├── dev.sh / dev.ps1              # 后端 + Vite 热开发
│   ├── verify.sh                     # JDK 21 下的 clean verify 与正式打包
│   ├── start-linux.sh                # Linux/内网成品 JAR 启动
│   └── start-windows.bat             # Windows 成品 JAR/OpenCode 启动
├── .github/workflows/
│   ├── ci.yml                        # main/PR 的三平台完整验证
│   └── release.yml                   # v<version> 标签验证并发布 JAR/脚本/校验值
├── docs/
│   ├── architecture.md               # 权威架构、生命周期、错误和工作区边界
│   ├── code-design-contract.md        # 单一职责、依赖方向、规模门禁和重构准则
│   ├── ai-role-contracts.md           # 机器角色轻量语义合同与服务端编译边界
│   ├── design-contract.md            # UI、Designer 和 Review Gate 合同
│   ├── opencode-contract.md          # OpenCode HTTP、Session、MCP、权限契约
│   └── seven-feature-contract.md     # Recovery、交互、验证器、洞察和自动化合同
├── src/main/java/io/opencode/loopper/
│   ├── api/                          # REST/MCP 接口、DTO、异常映射和认证过滤器
│   ├── config/                       # 配置属性、数据目录、验证执行器
│   ├── domain/                       # 持久化状态枚举、LoopSpec、错误语义
│   ├── lifecycle/                    # 轻量状态机、转换策略和审计入口
│   ├── persistence/                  # MyBatis Mapper 与数据库 Row
│   ├── runtime/                      # OpenCode、进程、Git 任务分支/Direct 基线
│   ├── service/                      # 编排、Designer、Recovery、发布、自动化
│   ├── verification/                 # 确定性验证器与二进制证据
│   └── web/                          # SPA fallback
├── src/main/resources/
│   ├── application.yml               # 运行配置、SQLite、OpenCode、MCP
│   └── db/migration/                 # 只追加的 Flyway 迁移
├── src/test/java/io/opencode/loopper/ # 后端单元/集成/契约测试
└── frontend/
    ├── src/api/                      # 类型化 API client
    ├── src/components/               # 可复用状态、证据、冲突、评审组件
    ├── src/views/                    # 路由页面
    ├── src/stores/                   # Pinia 服务端状态投影
    ├── src/types/                    # 前后端领域 DTO 类型
    ├── src/utils/                    # 显示标签、时间、合并和输出工具
    ├── src/styles/                   # 全局 token 与布局
    ├── e2e/                          # Playwright 端到端测试
    └── package-lock.json             # 必须保持可复现的 npm 依赖锁
```

## 4. 权威契约和阅读路由

不要一次性加载所有文件。按任务类型先读本文件，再读下列最相关的契约与实现：

| 任务类型 | 先读文档 | 重点源码 |
| --- | --- | --- |
| Task/Stage/Attempt/Session 状态 | `docs/architecture.md` | `domain/*State.java`、`domain/LifecycleEvent.java`、`lifecycle/`、`TaskService.java` |
| Designer / LoopSpec / Review Gate | `docs/design-contract.md`、`docs/opencode-contract.md`、`docs/ai-role-contracts.md` | `DesignerSessionService.java`、`MachineRoleContractCatalog.java`、`LoopDraftService.java`、`LoopSpec.java`、`DesignerView.vue` |
| OpenCode Runtime / Session | `docs/opencode-contract.md` | `runtime/OpenCode*.java`、`TaskSessionMonitorService.java`、`RuntimeView.vue` |
| 验证器 / Judge / 证据 | `docs/architecture.md`、`docs/seven-feature-contract.md` | `verification/`、`TaskVerificationDispatcher.java`、`TaskService.java` |
| Git 任务分支 / Direct / Recovery | `docs/architecture.md`、`docs/seven-feature-contract.md` | `GitWorktreeManager.java`、`DirectWorkspace*`、`RecoveryService.java` |
| 发布 / 本地同步冲突 | `docs/architecture.md` | `TaskPublicationService.java`、`LocalSyncConflictService.java`、`TaskPublicationActions.vue`、`CodeMergeEditor.vue` |
| Pending Center / 权限 | `docs/seven-feature-contract.md` | `InteractionService.java`、`InteractionController.java`、`InboxView.vue` |
| 自动化 / 模板 | `docs/seven-feature-contract.md` | `AutomationService.java`、`LoopSpecTemplateService.java`、`AutomationsView.vue` |
| 数据库变化 | 所有受影响契约 | `db/migration/`、`LoopperMapper.java`、对应集成测试 |
| UI 视觉/状态 | `docs/design-contract.md` | 相似 `views/`、`components/`、`styles/tokens.css` 和 `.spec.ts` |
| 打包/部署 | `README.md` | `pom.xml`、`application.yml`、`scripts/verify.sh`、`scripts/start-linux.sh`、`scripts/start-windows.bat` |
| 代码结构/重构 | `docs/code-design-contract.md` | 目标类、相似职责组件、相邻测试、`CodeStructureContractTest` |

文档与源码冲突时：

1. 不要静默猜测。
2. 先确认是否为文档陈旧、实现缺陷或未完成迁移。
3. 涉及产品语义时向用户说明冲突和推荐选择。
4. 完成决定后同时更新实现、测试和权威文档。

## 5. 核心领域契约

### 5.1 轻量状态机

- Task、Stage、Attempt、Session、Designer Session、Judge、Interaction、Lease、Queue、Automation 等必须保持独立状态域，不得合并成一个巨型枚举或工作流。
- 合法 `from + event -> to` 由项目内轻量状态机集中定义；不要引入 Spring Statemachine 或另一套工作流框架来绕过现有转换服务。
- 新增内部状态或事件枚举时必须实现中文 `description()`，用于界面和诊断；数据库、审计和协议继续持久化稳定的 `Enum.name()`，不要把中文说明写入协议码。
- 业务状态转换必须经过 `LifecycleTransitionService`/已有转换入口并产生审计记录。
- 仅更新投影、内容、心跳或外部 Session 状态时使用无状态转换的 mutation 路径，不得伪造业务转换事件。
- 使用现有 optimistic locking/version 规则；冲突应返回明确 409，不要最后写入覆盖并发变化。
- Flyway V15 之前的数据没有伪造的创建事件；缺少早期 transition 不能被解释为实体从未变化。
- 新确认任务必须停在 `PENDING_START`：确认事务只持久化 Task、Stage、冻结设计上下文和草稿确认，不得创建 Queue/Lease、fetch、创建/切换分支或分配执行目录。只有显式 `REQUEST_START` 才允许进入 `QUEUED` 并申请执行资源；准入后的准备、脏文件恢复和队列转移必须沿同一执行请求自动经过短暂 `READY` 继续到 `RUNNING`，不得要求第二次点击开始。
- 服务端拥有的文档生成和一次性表格转换在显式 Start 后创建正常 Attempt，但不创建可写 OpenCode Session，也不得捕获或执行生产 Java focused-test 基线门禁；其阻断性业务证据分别由 `DOCUMENT_STRUCTURE` 和 `TABULAR_DATA` 提供。
- OpenCode Todo 只允许作为实施 Session 的非权威进度投影：先探测 `todowrite`，可用时才注入提示；外部读取在 SQLite transaction 外，每两秒至多一次，只在内容变化时持久化。最多 64 项、单项 1 KiB、总计 64 KiB，稳定 ID 基于规范内容和重复序号；Todo 的成功、失败或完成状态都不得改变 Task/Stage/Attempt/Verifier/Judge 生命周期，Designer/Judge 不展示 Todo。

### 5.2 错误层级

`ErrorLayer` 是公开持久化契约：

- `FIELD`：输入或草稿校验，不改变运行状态。
- `VERIFICATION`：当前 Attempt 验收失败，保留证据并在预算内继续循环。
- `SESSION`：OpenCode Session 失败，关闭当前 Session/Attempt，安全确认后创建新 Session。
- `TASK`：当前执行轮次无法安全继续或预算耗尽，关闭所有子运行，记录失败轮次并进入 `AWAITING_DECISION`；不得未经用户处置直接形成新任务终态。

Session adapter 不得直接把 Task 写成 `FAILED`；重试耗尽后的升级由编排器负责。终止 Task 不能伪造远端 Session 已停止：无法确认的写入者保留为 `DISCONNECTED`，并阻止重叠写入。

`RETRY_WAIT` 必须由 V31 持久化计划驱动，同一 Task 只允许一个 `SCHEDULED`/`PAUSED` 活动计划。限流、普通 Session、验证失败默认分别按 `60→120→240→300`、`10→20→40→60`、`5→10→20→30` 秒退避并保持上限，不加随机抖动；计划创建后到期时间不可被后续设置追溯修改。只有确认旧 writer 已停止后才能建计划，到期由 Monitor 原子领取并创建唯一新 Attempt/Session；重启保留计划，历史无计划等待按普通 Session 默认值补建。暂停冻结剩余时间，恢复继续等待，Task 成功/失败/取消关闭活动计划。OpenCode `RETRY` 是 Provider 在原远端 Session 内的自恢复状态，不是 Session 错误、Loopper `RETRY_WAIT` 或 writer 终态证明；所有调用方必须保留原 Session 继续轮询，既有角色/操作超时仍为硬边界。

`PENDING_START`、`QUEUED`、`READY` 与 `WAITING_INPUT` 任务必须在本地任务详情中保留直接取消入口；取消需二次确认并保留已有执行目录、分支和证据，不得伪装成回滚。`PENDING_START` 取消只改变自身 Task，必须保持无 Queue/Lease/分支/执行目录；`READY` 取消不得伪称正在停止尚未创建的 Session/验证器，并按既有终态安全检查恢复源分支与释放自身租约。取消排队任务只能取消自身队列项并进入 `CANCELLED`，不得释放或转移当前 holder 的写租约；进入终态后再按既有规则归档或删除。

验证失败后的 Attempt 必须固化有界 `ATTEMPT_HANDOFF`，下一轮只能使用新 Attempt 和新可写 Session；不得复用旧实施对话。只有可靠且相同的失败签名与工作区内容指纹才累计停滞次数，达到 `stagnationLimit` 后必须进入 `WAITING_INPUT`，由本地 UI 明确确认继续。

新任务的执行结果与用户确认终态必须分离：每次开始/继续对应独立持久化 Execution Cycle；确定性验证和双 Judge 成功或 Task 级失败均进入 `AWAITING_DECISION`。继续当前任务必须创建新 Cycle、Attempt 和 Session，并从失败/用户选择的 Stage 起重跑后续阶段与最终双 Judge；轮次预算重新计算，历史用量和证据不得改写。成功结果经本地提交或确认推送进入 `COMPLETED`，继承/重做派生后父任务进入 `SUPERSEDED`，取消进入 `CANCELLED`；旧 `SUCCEEDED`/`FAILED` 只作历史终态兼容，不得静默重开。

### 5.3 Designer 和 LoopSpec

- V35 在设计流程前冻结 `TaskIntent / WorkflowTemplate / MutationMode / ArtifactKind / TestPolicy / ExecutionStrategy` 任务画像和版本化 Role Pack；V36 引入独立 `ROUTER_NO_TOOLS` 和 Reviewer 运行态；V37 把工作包 Role Pack、版本、技术栈和测试策略复制到每个确认 Stage，Implementation/Recovery 必须复用；V38 持久化每次 Router 的完整需求快照、外部 Session、响应模式、标签和错误，重启继续同一 Session，新讨论必须 abort 并废弃旧运行；V39 把 Reviewer 升级为固定 `REVIEWER_REPORT_V1` findings 合同并持久化合同版本。服务端结合有界仓库事实决定最终流程，格式/Session 失败降级为通用画像提问而不终止 Designer。置信度低于 80 或证据冲突必须人工确认；历史缺失画像投影为 `LEGACY_SOFTWARE`，Recovery 复用冻结画像。Role Pack `2026-08-dynamic-v5` 必须先把技术别名归并为 Java/Python/Node/Other 软件族：JavaScript/TypeScript 不得命中 Java，JUnit/Jupiter/Surefire 仍属于 Java，同族别名不得生成混合栈，真实跨族使用 `software-mixed`，显式未知单栈使用 `software-generic`；工作包技术信号使用词边界，业务符号中的 `Node`/`node` 子串不得误判为 Node 技术栈；每个可编译角色使用栈原生规划示例，文档、表格和只读报告明确走服务端或 Reviewer 绕过。新软件任务默认使用 `DIRECT_SOFTWARE_DESIGN` 和单一 `WP-1`，只有画像冻结前由用户显式打开“大型任务”才使用 `FULL_PACKAGE_DESIGN`；两种软件流程都冻结工作包自己的技术栈、Role Pack、执行和测试策略，其中默认单包必须继承已确认的软件任务画像，需求正文中的否定性“依赖/配置”措辞不得把它降级为维护角色，已冻结的冲突快照在下次权威使用时修复；只有大型任务的显式分包允许按包内容专门化角色。简单文档/表格/维护继续使用既有专属流程；大型文档要求 2–6 个二级章节包并由服务端确定性聚合结构化片段；只读 Reviewer 只开放 `read/glob/grep`，服务端逐条校验 finding 的受管路径、精确行号与源哈希且不创建 Task、Attempt、租约、分支或可写 Session；转换入口只创建关联 Designer。
- 任务画像对外决策态固定为 `ROUTING / NEEDS_CONFIRMATION / CONFIRMED / FROZEN`，所有设计入口统一依赖服务端 `confirmationReady`，不得从 `!decisionRequired` 推导。人工推荐确认记录 `USER_CONFIRMED`，编辑/沿用记录 `USER_OVERRIDE`；完整需求稿重算只有在任务意图、主要制品、单包/大型流程和读写模式不变且无新增安全冲突时，才以 `USER_CONFIRMED_CARRIED_FORWARD` 继承确认，技术栈、Role Pack 和测试策略仍取最新结果。对用户统一称为“任务设置”：首次歧义显示“确认并继续 / 修改设置”，实质变化返回 `previousConfirmedChoice` 并显示“原设置 / 本次识别结果”与“继续使用原设置 / 使用本次识别结果 / 修改设置”；编辑控件必须由用户主动打开。保存前调用只读影响预览，已确认且完全相同的选择由服务端无操作；只有流程切换才显示“停止当前设计并重新开始”的明确确认，取消不得废弃当前 Session，确认前不得启动设计。
- 请求线程和 Monitor 必须互斥领取同一 Router run；丢失乐观更新的启动方必须停止其刚创建的孤儿远端。新讨论、重算或画像换流程必须在旧远端 Session 确认停止后才能废弃旧运行并创建替代 Session，停止失败时保留原画像和 Session。新建设计提交后必须留在当前设计页持续刷新并在 Router 完成后自动继续，不得要求用户从历史设计恢复。
- 简单本地维护必须从确认稿提取明确的反引号相对路径，并生成精确 `allowedPaths`、`requireChanges=true`、`forbidDeletes=true` 的 `GIT_DIFF`；草稿确认和实施权限双重拒绝删除、通配路径、服务启停、Git 提交推送发布、外部应用与外部系统写入。
- Java 生产代码仍强制聚焦 Maven/Gradle TEST；统一 `TestFrameworkPolicy` 注册 Maven/Gradle/npm/pytest/unittest，解析显式目标并拒绝跳过参数。Python/Node 按仓库测试框架与用户要求选择 REQUIRED/OPTIONAL。无测试体系的独立 Python 脚本可用 SELF_CHECK 加原生输出验证；文档、一次性表格转换和只读报告为 NOT_APPLICABLE，不得生成 PROCESS TEST。
- `SERVER_DOCUMENT_MATERIALIZATION` 和 `SERVER_TABULAR_CONVERSION` 只能在显式 Task Start 后执行冻结 `artifact_plan`，创建正常 Attempt 但不伪造 OpenCode Session。`DOCUMENT_STRUCTURE`/`TABULAR_DATA` 是行为验证器；BUILD、GIT_DIFF 和报告证据仍不能冒充业务验收。

- Designer 新建会话先进入 `DISCUSSING_REQUIREMENT`。普通软件需求的每个讨论修订只调用一次 `question` 提出 1–3 个选择题；回答后允许 AI 空正文，服务端按时间原样拼装原始需求、需求作用域补充和持久化最终回答，后写优先，禁止把 AI 自由文本、仓库推断或任务画像混入需求语义。快照以 `SERVER_REQUIREMENT_SNAPSHOT` 系统来源消息保存并作为冻结需求的精确 `source_message_id`，页面通过独立只读卡片展示且不重复进入系统消息折叠组；历史冻结 AI 快照作为兼容基线。超过 24 KiB UTF-8 必须以 `REQUIREMENT_SNAPSHOT_TOO_LARGE` 阻断，不得截断或调用 AI 压缩。大型任务继续在需求讨论和每个工作包初稿/人工修订中提问，并由 AI 返回不超过 24 KiB UTF-8 的完整 Markdown 替代快照。遗漏必需问题仍只允许在全新 Session 补问一次，再次遗漏以 `DESIGN_QUESTION_REQUIRED` 进入 `WAITING_INPUT`。正常讨论/评审使用 Designer Session `REVIEWING`，不得滥用 `WAITING_INPUT`。
- 已回答的 Designer 问题不得从讨论记录中消失：服务端从持久化决策日志权威投影原问题、标题、全部选项说明和规范化最终回答，页面默认折叠为“需求讨论”，展开后完整展示；刷新、进程重启和历史旧格式恢复不得依赖浏览器本地状态。新决策日志必须保存完整问题结构，旧版仅含问题文本与答案的日志继续兼容读取。
- 拆包前的需求消息只更新需求讨论，不调用 Decomposer；只有显式确认需求才冻结下一编号需求版本并拆包。拆包后旧 `/messages` 缺少作用域时必须返回 `DISCUSSION_SCOPE_REQUIRED`；修改整体需求要显式重开并废弃当前拆包/批准，包级消息只能修改当前包且不得创建新需求版本或重跑 Decomposer。全部作用域写请求和批准都携带期望讨论/设计修订，过期操作返回 409。
- Designer 写接口允许用空响应体返回成功的 `202 Accepted` 或 `204 No Content`；前端公共 API transport 必须把任意成功空响应解释为已完成的 void 操作，再刷新服务端权威快照，不得对空内容调用 JSON 解析器并误报失败。
- 只有冻结为 `FULL_PACKAGE_DESIGN` 的完整需求才交给独立只读 Task Decomposer；只允许 `read/glob/grep`，不得写文件、执行命令、提问或创建 Task。服务端按非空段落/列表编号并校验每段被全局约束或至少一个工作包引用。`DIRECT_SOFTWARE_DESIGN` 不创建或调用 Decomposer Session，由服务端直接生成覆盖全部 RQ 的 `DIRECT_DESIGN / WP-1`。
- `DIRECT_DESIGN` 恰好一个包且允许 1–6 个 Stage；大型任务拆成 2–6 个依赖有序的纵向业务包，每包 1–3 个 Stage、总计不超过 18 个。禁止把数据库、后端、前端、测试机械分层拆包。普通模式需要第 7 个 Stage 时必须以 `LARGE_TASK_MODE_REQUIRED` 立即停止，不重设计、不自动切换；只有用户显式点击“改用大型任务”才重开当前需求。多项目根、超过六包或多个独立发布边界仍必须返回 `MULTI_TASK_REQUIRED` 并等待人工，不自动创建子 Task。
- 工作包严格串行执行。大型任务每包在健康时复用自己的只读交互 Designer Session；普通 WP-1 使用不开放 `question` 的通用只读 Session，创建后直接进入 `DESIGNING`，初稿、人工反馈和重新设计都直接输出完整替代设计，不得创建包级 Pending Question 或进入 `QUESTIONING_PACKAGE`。每个候选使用当前配置的同一模型。Designer 只输出不超过 24 KiB UTF-8 的完整 Markdown；当前软件设计固定使用“目标与范围 / 影响与交付 / 验收场景 / 可选人工评审项 / 验收约束 / 阶段与依赖”，场景列固定为“场景 / 前置或触发 / 操作 / 可观察结果 / 保持不变”。Designer 不得输出 WP/AC/DS-L ID、LoopSpec JSON 或可执行 argv，只能写相对路径/符号、测试类或原生测试路径和独立性约束。
- V41 为每次新软件包编译冻结一条 `design_acceptance_planning`：CommonMark AST 与 GFM 表格提取 `SCENARIO/REVIEW/SCOPE/DELIVERABLE/POLICY/DEPENDENCY` DesignFact，保存精确原文、稳定引用和 SHA-256；设计上限 24 KiB UTF-8、64 个场景、128 个事实。服务端从冻结 Role Pack、技术栈和测试策略生成闭集验证能力，Java/Node/Python/混合栈只允许仓库原生聚焦目标；用户要求“独立/分别/各自通过”时相应能力为强制项。AI 不得生成命令、路径、测试目标或验证器。规划持久化状态固定为 `EXTRACTED / BOUND / COMPILED / FAILED`：闭集求解得到 `DESIGN_INCOMPLETE` 时写入 `BOUND` 并由编排器进入重设计/人工输入流程，不得把编译结果码直接写入状态列。
- 新软件 Compiler 使用 `COMPILER_BINDING_NO_TOOLS` 和 `PACKAGE_ACCEPTANCE_BINDING_V5`，只返回事实分组、仅向前的分组依赖、事实到能力的软偏好和交接摘要，不得返回 `outcome/designGaps`。提示必须给出唯一的小型对象形状；该建议无法解析、字段形状漂移或超过分组上限时，服务端审计 `OPTIONAL_ACCEPTANCE_ADVICE_DROPPED` 并使用空建议继续确定性编译，不得消耗格式/语义修复后把 Designer 停在 `WAITING_INPUT`。服务端只从正向交付物、验收约束和阶段关系发现测试能力，负向/不变文本不得生成测试目标；测试限定名按主体符号归一并通过标识符、CamelCase、标题优先和中文语义唯一胜者竞争映射，每个业务条件至多绑定一个 focused test，独立必跑但无专属场景的目标只生成一次带原始来源的独立机器条件；仅测试路径生成 `JAVA_TEST_ONLY`，不得伪装生产变更。服务端对每个有界 Stage 分组运行上限 100000 节点的精确 branch-and-bound 集合覆盖，超限后确定性贪心复核，以零未覆盖为硬条件，再优先少 Judge-only、强确定性证据和少能力；随后唯一派生 `COMPILED` 或具体 `DESIGN_INCOMPLETE`，生成 EARS 验收文字、`<workPackageId>-AC-n`、精确来源、直接 argv、测试目标和验证器，并继续通过 `DesignerPackagePlanCompiler` 与 LoopSpec v2 全量校验。冻结 v4 工作包允许沿同一算法恢复；无闭集能力覆盖时必须失败关闭，不得因模型猜测放行；UI 只展示有界中文场景/能力投影，不泄露内部索引或原始 JSON。
- Decomposer 与历史 v3 Compiler 每个候选仍使用既有紧凑语义合同；只有没有 V41 快照的历史活动或明确 v3 信封才使用最多 16 个 `add/replace/remove` 的 `AI_SEMANTIC_PATCH_V1`，并继续保留各两次格式/语义修复。V5 验收绑定是可选建议，形状错误直接丢弃后由服务端闭集编译，不创建修复 Session；真实闭集设计缺口最多只让当前包完整重设计一次。每个只读角色已确认的传输失败允许一个全新 Session 重试。整个需求版本最多 96 次模型调用；各包内容次数互不挤占但受全局上限约束。
- 新 Decomposer 紧凑规划、Compiler 紧凑规划和 Judge 必须优先使用服务端固定 ID 的 OpenCode JSON Schema；旧 final Schema 只保留给缺少语义快照的历史活动记录。provider 内建 schema 重试固定为 0。只有格式接口拒绝、明确 `StructuredOutputError` 或完成后缺失 structured payload 才能在全新只读角色 Session 中回退到 marker，并计入当前步骤原有模型调用与格式修复预算；不得在失败 Session 内继续、增加隐藏重试池或绕过确定性语义校验。历史活动记录按 `TEXT_MARKER` 兼容。
- OpenCode `RETRY` 是 Provider 自恢复中的瞬态 Session 状态，统一适用于交互式 Designer、Decomposer、Compiler、Implementation、Judge、项目公约、提交建议和本地同步：必须保留原远端 Session 继续轮询，不得新建 Attempt/Judge、消耗 Loopper 重试预算、写入 Session 错误或把它当作旧 writer 已停止；Designer 流程保持 `RUNNING`，不得阻断已授权的全自动模式，各调用方既有超时继续生效。Loopper 管理的 Decomposer、Compiler 和 Judge 必须选择最多 24 个 agentic steps 的私有 `loopper-structured` agent。若最近一次用户提示后的同一规范化工具名和参数连续出现 3 次，必须立即尽力 abort，并且每个角色步骤最多启动一次禁用全部内置工具的 finalizer Session；恢复资格和纠正类别持久化在 V28，finalizer 计入全局模型调用预算但不占格式修复次数，24 步仍是最终保险。若 structured prompt 已接受但消息读取接口随后以格式/Schema 400 拒绝，必须按结构化格式不支持进入既有全新 marker Session 回退。结构化角色最终进入 `WAITING_INPUT` 或 `SESSION_ERROR` 前必须尽力 abort 当前远端 Session，UI 的“已停止”不得与仍在读仓库的远端执行并存。
- Decomposer、Compiler 和最终 Judge 只有在当前持久化步骤实际使用 `JSON_SCHEMA` 时才显式使用 `thinking=false`；Loopper 管理的 DeepSeek Runtime 为当前配置模型注入 `loopper-no-thinking` variant（`thinking.type=disabled`），且 HTTP 适配器只允许 Schema Prompt 选择它，避免 Thinking 与 JSON Schema 强制工具选择冲突。`TEXT_MARKER` 初始、重试、Schema 回退和 finalizer Session 必须保留配置的 thinking 或 Provider 默认值，并继续通过同一 JSON 提取、确定性校验和修复预算。机器角色仍使用零温度和禁止重复/虚构工具调用的固定指令。OpenCode 1.18.12–1.18.18 已确认会在读取自身持久化 Schema 时返回 400，必须直接使用 marker 兼容模式；后续版本恢复能力探测。交互式 Markdown Designer 和可写 Implementation 继续保留配置/LoopSpec 的 thinking 选择；复用外部 DeepSeek Runtime 时由操作者提供同名 variant，缺失时不得绕过既有全新 Session marker 回退。
- OpenCode Session 使用角色权限模板：每次创建前必须按项目目录读取 `/mcp`，为所有角色追加每个已配置 Server 的 `<server>_*` allow；发现失败必须在提示发送前明确停止，不得修改用户配置。Decomposer、初始 Compiler、Judge 和通用只读的内置工具只开放 `read`/`glob`/`grep`，Designer 额外开放 `question`，`COMPILER_REPAIR_NO_TOOLS`、Router 和 finalizer 只禁止内置工具；只读角色仍拒绝 `.env`/`.env.*`、外部目录和全部其他内置工具。MCP allow 不得解除写文件、Bash、Git、外部目录或 Loopper 人工授权边界。Runtime 可展示 agent、原生 `plan` 与 structured-output 能力，但当前不得让 Designer 接管原生 plan；Designer Markdown、Compiler JSON 和 Validator 权威边界保持不变。
- Decomposer、Compiler、Judge 与项目公约共用有界包容性提取器：原生 structured payload、角色 marker、`json`/无语言代码块、说明文字中括号完整的 object 和整段 object 按优先级提取。仅接受标准 JSON object；字符串花括号、转义、BOM 和空白必须正确处理；等价候选去重，不等价且都合格的候选按歧义拒绝。字段名、可选集合、枚举和安全命令分词只做唯一可逆的确定性规范化，成功提取/规范化直接进入同一权威业务合同校验并记录 `AI_OUTPUT_NORMALIZED`，不得消耗格式修复次数；数组根、残缺/非标准 JSON、不可唯一推导的缺口以及安全或执行合同违规则继续阻断。
- `MachineRoleContractCatalog`、紧凑 JSON Schema、语义编译器和 `docs/ai-role-contracts.md` 必须使用同一合同版本。当前 v5 Compiler 提示只要求索引分组与能力偏好；历史 v3 提示才允许语义 Stage、`DS-Lxxx` 来源和闭集证据意图。任何版本都不得让模型填写服务端派生的验收 ID、精确原文、`criterionIds`、`testTargets` 或重复命令。服务端编译完整 `VerifierSpec` 和可选 `verificationRuntime` 后，必须使用权威 LoopSpec v2 合同校验直接命令、行为覆盖、Java 聚焦测试及运行时绑定；短提示不能替代服务端确定性校验。
- Compiler 的 `criteria` 只承载可观察业务结果；未被聚焦测试显式覆盖的代码风格、源码/注解/装配形态、构建/测试结果和交付卫生属于工程元数据，服务端可确定性降级并重排 `covers`，不得因此消耗语义修复。一个 Java Stage 只有一个聚焦测试候选时可补齐剩余业务条件映射；每个 `JAVA_PRODUCTION` Stage 即使只有 Judge 条件也必须保留 `covers:[]` 的聚焦 Maven/Gradle TEST，`FULL_TEST`/`BUILD` 不能替代，不得生成只有全量测试/构建的 Java 接线或演示 Stage。多个候选、缺少真实聚焦测试或不可唯一推导仍必须阻断。语义预检应一次汇总全部问题并返回精确 JSON Pointer；源码搜索不得作为行为 `SELF_CHECK`。
- 分包 Designer/Compiler 读取的仓库是不可变的执行前基线；前置包 `APPROVED` 表示其设计、编译和校验合同已通过且已经人工接受，不表示生产文件已写入基线仓库。服务端必须注入前置包的冻结目标、Compiler 摘要和交接合同；严格串行执行保证前置 Stage 先落地，因此当前 `read/glob/grep` 找不到前置交付物不得返回 `MISSING_SCOPE`。Compiler 负责 Stage/业务验收/证据语义；服务端在权威 v2 校验前确定性生成 `<workPackageId>-AC-n`、仅在唯一归一化匹配时恢复 Designer 精确原文，并把 Designer 明确列出的聚焦单测行作为强制证据注入规划/修复提示。服务端只允许从统一 `TestFrameworkPolicy` 提取安全显式目标：Java 使用 Maven/Gradle `-Dtest`、`-Dit.test`、`--tests`，Python/Node 使用 pytest/unittest/npm 注册规则，不得让非 Java 命令经过 Java 解析器；同一 Java Stage 唯一匹配时可补齐重复的 `testCommand`/`testTargets`/`criterionIds` 或等价 TEST 验证器，不得从普通描述、全量测试或多个候选中猜测。歧义匹配或缺少语义证据仍必须阻断。
- 全部包完成后只能由服务端按包顺序确定性聚合，不允许模型二次合并；聚合保留草稿模型、Session 策略、预算和重试模板，并只在最初冻结的 draft version 上原子同步。草稿并发变化必须在启动下一包前停止，不再消耗模型调用。
- 聚合 Stage 的 `workPackageId` 映射进入 Review Gate 后不可删除、改写或重排；前端读取、结构化编辑、保存和确认必须无损往返。草稿更新边界拒绝映射漂移，确认边界还要校验每个已批准工作包均存在且保持依赖顺序，禁止静默降级成无包 Stage 任务。
- 工作包 Designer 在健康时复用该包自己的交互 Session；远端丢失时用持久化需求、当前完整包设计、决策和作用域消息重建，不得重跑已完成 Decomposer。每个候选仍使用独立只读 Compiler 并经过唯一权威 Validator；大型任务通过后进入 `REVIEWING`，只有人工接受或当前会话的全自动授权接受当前已验证修订才启动下一包；普通单包在 Compiler/Validator 通过后自动批准 `WP-1`、确定性聚合并直接进入总体确认，不显示包级接受步骤。初稿后每包最多 5 轮人工修改；失败候选不得覆盖上一版有效候选。重开已接受包时先展示影响，只把它的传递依赖标记 `STALE`，无关 `APPROVED` 包保持有效。
- 全部工作包 `APPROVED` 后才允许服务端确定性聚合并进入 `FINAL_REVIEW`；包级阶段右侧候选只读，最终聚合阶段才开放结构化编辑。V27 持久化讨论与批准；历史未确认 `COMPLETED` 包迁移为 `REVIEWING`，已确认草稿和已创建 Task 不变。
- 只有完成、项目匹配、版本匹配且经服务端确定性验证通过的聚合 LoopSpec 才能同步到绑定草稿；模型不得自报校验成功。逐步人工确认或当前会话全自动授权确认前仍不得写业务源码、创建 Task 或制造执行状态。
- 确认时冻结完整 `REQUIREMENT_CONTEXT`、`DECOMPOSITION_CONTEXT`、每包 `WORK_PACKAGE_DESIGN`/`WORK_PACKAGE_COMPILATION_SUMMARY`，并保留组合 `DESIGN_CONTEXT` 兼容历史。执行提示按当前 Stage 的包只注入当前包设计、全局约束和前置包交接。
- 执行期若已冻结的 `DECOMPOSITION_CONTEXT` 无法解析、字段形状错误、缺少当前包或依赖 ID 无效，必须在创建可写 OpenCode Session 前以 `DECOMPOSITION_CONTEXT_INVALID` 失败关闭；不得静默丢弃全局约束或前置包交接。
- Designer 合并在单个数组项中的 Maven 参数若能无歧义解析，应在同步时直接规范化并保存为独立 argv，不消耗自动纠正次数；只有引号未闭合等无法安全解析的输入才按无效 LoopSpec 回送纠正。
- 草稿确认必须是幂等边界；确认后创建唯一 `PENDING_START` Task，且确认事务不申请执行资源。默认由用户显式请求开始；当前 Designer 会话处于全自动授权时，确认完成后再复用正式 Task Start 边界请求执行。
- Designer 可按单个会话启用默认关闭的全自动模式；每次启用或从普通 `BLOCKED` 重新授权都必须由本地 UI 显示风险确认。V34 独立持久化 `DISABLED / ACTIVE / BLOCKED / COMPLETED`、最近动作、错误、Task 和乐观锁版本；750ms Monitor 每轮每会话最多推进一个动作，且只能复用已有画像推荐、问题回答、需求确认、工作包批准、草稿确认与 Task Start 权威入口。全自动模式只能遵循当前已持久化的大型任务选择，不得自动打开、关闭或在 `LARGE_TASK_MODE_REQUIRED` 后自动切换。低置信或冲突任务画像在普通模式仍必须人工覆盖；全自动模式把 Router 当前意图/主要制品单独持久化为 `AUTO_RECOMMENDED`，保留原置信度且不得绕过危险操作证据，需求确认前仍允许人工纠偏。历史 `BLOCKED + TASK_PROFILE_DECISION_REQUIRED` 允许一次专用 `RESUME` 后在下一轮采用同一推荐，不要求关闭再授权。自动答案按中英文推荐标记选择，无标记时兼容首项，多选只取推荐项；全部动作写入 System 消息和生命周期审计。其他异常进入 `BLOCKED` 后不得高频自动重试；关闭只停止后续动作，不撤销已完成动作或终止正在运行的模型调用。自动授权在 Task Start 成功请求后终止，执行期问题、危险权限、恢复、结果确认、提交、推送、合并和发布始终保持人工处理。
- Designer 页面在消息时间线现有的当前角色卡片内每 1.2 秒读取有界活动投影，只替换展示一条最新活动，不得另建顶部活动面板或累积历史。交互设计师使用消息一致的 Markdown 展示思考、增量文字和普通/MCP 工具调用，Router/Decomposer/Compiler/Reviewer/repair/finalizer 等结构化角色只展示最新工具活动和权威步骤，禁止泄露原始规划 JSON。断线只保留最后一条活动并重连，活动投影不得推进生命周期。V40 的 `model_token_usage` 只按 Designer 或 Task 范围累计 Provider 报告的远端 Session Token；活动消息与 Token 必须复用同一次读取，单次轮询最多额外补齐一个历史远端。Designer 当前角色卡和 Task 模型输出头部只显示紧凑数字与正增量 `+xxx`，不得显示额度、成本、无限 Token 或解释文案，也不得用时间/文本长度伪造 Token。
- Designer “清理并重新开始”必须先把会话转为 `STOPPING` 并停止 Router、需求/包设计师、Decomposer、Compiler、repair/finalizer 和 Reviewer 等全部活动远端 Session；`STOPPING` 期间 Monitor/全自动不得继续派发。失败时保留工作区、保持未归档并允许幂等重试；全部确认停止后才进入 `CANCELLED` 并归档。确认设计取得 Task ID 后，前端必须清除工作区指针和未发送内容、跳过本次离页警告并打开任务详情；以后从左侧进入 Designer 必须是新建设计，旧稿仅从历史只读查看。
- 项目 `taskCount` 只统计已创建的 Task，不得把确认前 Designer 会话伪装成任务；服务端必须按项目另行投影每个未确认草稿的最新 Designer Session 和 `openDesignerSessionCount`。浏览器工作区 ID 只是恢复提示，服务重启或短暂 API 失败不得清除。Designer 起始页只负责新建，不得平铺历史会话；独立“历史设计”页负责项目/状态/归档筛选与时间排序，未确认设计可继续、修改、归档和恢复，已确认设计必须关联 Task 只读展示且不得提供继续、修改或归档。V29 归档只增加可恢复投影，不删除草稿、消息、问题、候选或批准，且归档项不计入 `openDesignerSessionCount`。
- Designer 双栏和 PageHeader 操作区必须以 `min-width: 0`、换行和响应式单列保持在视口边界内；总体确认按钮除页头外还必须在 Review Gate 内提供同一权威动作，不能因窄视口变得不可点击。
- 聚合后仍只创建一个 Task、一个任务分支和一次发布；Stage/包严格串行。每包尝试池为 `min(stageCount × maxStageAttempts, stageCount + 2)`，必须为未启动 Stage 保留首次尝试，剩余额度不跨包转移；全部 Stage 通过后只运行一次 Requirement/Risk 双 Judge。
- 新建、导入和模板新版本使用 LoopSpec v2：每阶段必须显式声明 `implementationKind`，并至少有一个可观察 `acceptanceCriteria`。条件通过 `verificationMode` 选择 `MACHINE`、`JUDGE` 或 `BOTH`；机器模式必须由服务端分类为 `BEHAVIOR` 的验证器通过 `criterionIds` 覆盖，Judge 模式必须提供 `judgeRubric`，仅 Judge 还必须提供 `judgeOnlyReason` 且不能已有机器行为映射。每阶段无论模式都至少有一个阻断性确定性验证器。旧 v2 缺少 `implementationKind` 时只允许查看，再次保存、发布模板或确认前必须补齐；已持久化且未写模式的 v2 条件默认 `MACHINE`；v1 继续兼容且不得原地改版，只能复制为新 v2 草稿后补齐计划。

### 5.4 验证器与 Judge

- `PROCESS.command` 是 argv 数组，直接调用程序；禁止 `sh -c`、`bash -c`、`cmd /c`、管道、重定向和 shell 插值。
- Windows PROCESS 必须在启动前按任务根目录解析 `mvnw`/`gradlew` 包装器，并按 Loopper 进程 `PATH`/`PATHEXT` 解析裸程序的 `.com`/`.exe`/`.bat`/`.cmd` 入口；证据保存实际绝对 argv 与解析原因。Linux/macOS 保留原生 PATH 与可执行位语义。该适配不得放开用户 shell 启动器或 shell 片段，Windows 批处理启动必须启用 JDK 严格命令引用模式。
- Maven 参数兼容规范化只能进行确定性 token 拆分，不得启动 shell；新草稿保存规范化 argv，执行器还需兼容规范化历史草稿，并在证据中记录发生过拆分。
- v2 `PROCESS TEST` 只按精确 basename 识别 Maven、Gradle 和 npm 测试入口；必须同时拒绝拆分/合并形式的测试排除参数、npm 可选脚本和相似前缀伪装入口。草稿分析与实际进程启动前共用同一策略，并在 Maven argv 规范化后再次检查，禁止持久化历史合同绕过当前执行边界。
- Stage 的 `allowedPaths` / `forbiddenPaths` 只是 Agent 提示；只有显式 `GIT_DIFF` 才是路径/删除的强验收门槛。
- v2 Compiler 规划、草稿保存和人工确认必须复用运行期规范化且有界的路径策略校验：非法 glob，或被单条 `forbiddenPaths` 完整覆盖的 `allowedPaths` 规则，必须在 Task/Attempt/可写 Session 创建前退回规划修复或拒绝；宽允许范围配合更窄的禁止子树仍然有效。
- 普通可写 Stage 的显式 `GIT_DIFF` 与 Attempt handoff 必须使用该 Stage 首次 Attempt/Session 前捕获的私有基线；同一 Task 共用对象库、Stage 使用独立索引，重试和重启复用 `stage:<taskId>:<stageId>:<treeSha>`。前置 Stage 文件不得进入后续 Stage 差异或满足其 `requireChanges`，但后续 Stage 再次修改、删除或重命名前置文件必须可观测。`VERIFY_ONLY` 与最终自动差异继续使用任务基线，证据必须写明 `baselineScope`，Stage 范围还要包含 `stageId`。
- Stage 基线的文件扫描和 Git I/O 必须在 SQLite transaction 外执行；捕获后检查稳定性并只允许一次重试。已有 Attempt 的旧活动 Stage 缺失基线时以 `STAGE_WORKSPACE_BASELINE_MISSING` fail closed，不得按当前工作区补建或启动新 Session；启动清理只能删除 containment 校验后已无存活 Task 的私有目录，并在逐文件删除前清除 Windows Git 对象的只读属性。
- 最终成功 Attempt 无论是否配置 `GIT_DIFF` 都必须持久化非门禁性的任务基线差异快照，供详情页列出真实变更；提交后切回源分支或进入下一任务分支时，预览必须比较基线与显式任务分支引用，不能读取当时 checkout 猜测旧任务差异。
- `GIT_DIFF` 只证明改动范围，不能作为一个阶段唯一的功能验证。
- `FILE_EXISTS` 是兼容旧草稿的非阻断审计提示；不要为 Designer 新生成它。需要证明产物时，用会在缺失时非零退出的 `PROCESS` 自检，并可要求明确的 `outputContains` 标记。
- `FILE_NOT_EXISTS` 只用于明确的安全不变量。
- `FILE_CONTENT` 的 `expectedContent` 是精确文本合同；除纯空白输入仍按缺失拒绝外，不得裁剪首尾空白或尾随换行，`EXACT` 必须比较原始持久化文本。
- v2 `PROCESS` 必须声明 `processPurpose`。compile/package/build/typecheck/lint/install 属于 `BUILD`；映射业务条件的 `TEST` 必须是未跳过测试的 Maven/Gradle/npm 测试命令并列出 `testTargets`；未映射任何条件且不声明目标的安全全量测试可作为阻断性 `REPORT` 补充证据，但不能覆盖行为条件；`SELF_CHECK` 必须有明确 `outputContains`。`GIT_DIFF`、`FILE_NOT_EXISTS`、`JUNIT_XML` 和 `FILE_EXISTS` 分别只属于范围、安全、报告和提示证据，不能覆盖行为条件。
- `implementationKind` 为 `JAVA_PRODUCTION` 时，生产实现和未跳过的聚焦 Maven/Gradle 单元测试必须放在同一阶段，测试必须填写 `testTargets` 并通过 `criterionIds` 覆盖全部 `MACHINE`/`BOTH` 业务条件；不得创建“测试全部通过”元验收项。计划测试目标可由该阶段新增，设计时不要求已存在，但不得使用缺失目标忽略参数制造成功。
- 每个 v2 Stage 首次启动前必须持久化相对任务基线的生产 Java 路径和内容哈希。验证时新增、修改或重命名目标 `.java` 触发门禁；标准测试目录和 `target/`、`build/` 不属于生产 Java，删除单独由原有范围和风险规则处理。真实生产 Java 变化与声明不一致时返回 `JAVA_CHANGE_CLASSIFICATION_MISMATCH`，缺少该阶段成功的聚焦 Maven/Gradle 测试时返回 `JAVA_UNIT_TEST_ACCEPTANCE_REQUIRED`，并进入正常 Attempt 重试。
- v2 HTTP/JSON/BROWSER 条件只有绑定本阶段 `verificationRuntime` 和 `http://127.0.0.1:{{LOOPPER_PORT}}` 时才算行为覆盖。启动命令仍是无 shell argv，只允许 `{{LOOPPER_PORT}}`、`{{LOOPPER_TEMP}}`；固定 loopback 可作补充但不能证明本次代码已启动。
- 托管验证运行时的分配、启动、readiness、停止、工作区扫描和重启恢复都在 SQLite transaction 外。V19 用 PID 加启动身份防止误杀；终止不确定必须保留租约并以 `VERIFIER_RUNTIME_TERMINATION_UNCONFIRMED` 阻断重叠写入。
- HTTP/JSON/BROWSER 只访问 loopback；BROWSER 不允许任意 JavaScript，必须保留截图和 trace 证据；数据库中的二进制 artifact 相对路径统一使用 `/`，不能持久化平台相关的 Windows 分隔符。
- BROWSER 可执行文件发现顺序固定为显式 `LOOPPER_CHROME_EXECUTABLE`、进程 `PATH`、操作系统标准位置；显式路径无效时必须 fail closed。
- `DATABASE_QUERY` 只接受本地 SQLite 的只读单条 `SELECT`/`WITH`。
- 外部进程、HTTP、浏览器和模型调用不能在 SQLite transaction 内执行。
- Task 总 `maxDurationSeconds` 在 `VERIFYING` 期间继续生效；每个验证器和失败交接使用“验证器配置超时与剩余 Task 时限中的较小值”，Monitor 可让已在事务外运行的验证工作失败，迟到结果不得覆盖终态。
- 确定性验证成功与 Judge 成功是两套证据。Requirement 和 Risk Judge 都是独立只读 Session，必须明确 `PASS`。
- 两个最终 Judge 都接收所有阶段的 `JUDGE`/`BOTH` 条件与 rubric，以及已持久化的确定性摘要和差异；不为每个 Stage 额外启动 Judge，也不得把尚未执行的 Judge 计划显示成覆盖或通过。
- 最终 `VERIFICATION_SUMMARY` 必须按阶段顺序聚合每个成功 Stage 的最终成功 Attempt 与全部验证结果；单项证据摘录限制为 4 KiB UTF-8 并保留完整证据 SHA-256。确认目标、上下文和全部 Judge 合同总计不得超过 96 KiB UTF-8，完整 Judge 提示不得超过 128 KiB；每轮必须先批量构造并校验全部待启动角色的提示，任一超限时整批都不得创建 Judge row、只读 Session 或模型调用，直接进入 `WAITING_INPUT`，错误码为 `JUDGE_PROMPT_BUDGET_EXCEEDED`。
- `REVISE`、`BLOCKED`、Judge 冲突或 JSON 无法解析时进入人工处理/重新评审，不得丢弃已有确定性证据或伪造成功。
- Attempt 交接的差异扫描、文件读取、内容哈希和新 Session 创建都在 SQLite transaction 外执行；按实际读取字节限制 16 MiB，并在读取前后核对文件大小、修改时间和 file key；不可完整读取或读取期间变化的快照标记为不可比较，不得据此触发停滞。

### 5.5 工作区、租约与 Recovery

- 计划确认与执行资源申请必须分离：确认可做只读项目/重做基线校验，但不得识别并持久化 Queue/Lease、fetch 或切换 Git。首次显式开始才解析当前工作区身份，并在短事务中原子完成 `PENDING_START -> QUEUED` 与准入/排队；外部 Git/文件 I/O 随后执行。Recovery 子任务同样先停在 `PENDING_START`；自动化只有在已授权的自动开始或人工批准动作中才调用同一开始边界。
- 有可用 Git HEAD：登记目录有未提交或未跟踪文件时，任务持有租约进入 `WAITING_INPUT`，本地 UI 显示具体文件并要求逐文件选择提交、stash 或移除；处理决定必须绑定分支、HEAD、索引、状态和内容快照，取消弹窗直接把任务标记为失败且不改文件。重新检查干净后，在登记目录本身创建并切换 `loopper/<任务名>`；本地或远端跟踪分支已有同名时从第二次起追加 `(第2次)`、`(第3次)`，Git 禁止字符确定性替换为 `-`，分支叶名称按 UTF-8 字节安全截断，并在截断后重新修正 `.lock` 等非法结尾。
- 创建任务分支前以非交互方式 fetch 当前分支的 upstream/明确首选远端；远端线性领先时从远端最新提交创建任务。本地领先时保留本地提交；认证失败、fetch 失败或历史分叉必须 fail closed。未收到逐路径确认时禁止自动 stash、提交、覆盖或丢弃改动；移除操作必须二次确认。外部 Git 操作部分成功后不得伪造事务回滚，必须回读最新状态继续处理。
- 原项目分支 checkout 使用独立 10 分钟有界超时和命令局部 `core.longpaths=true`；短 Git 检查仍使用 30 秒边界，失败诊断保留输出尾部。
- OpenCode 的 canonical `directory` 查询值必须作为 URI 模板变量百分号编码，禁止让合法路径中的 `+` 被表单语义解码为空格；创建 Session 后必须回报与登记项目根一致的规范执行目录，缺失或不一致时不得发送实施提示；实施提示明确 AgentBridge、搜索、命令和验证器都使用该目录及当前任务分支。
- 无可用 Git HEAD：直接使用登记根目录，并在 `direct-baselines/<taskId>` 保存私有 Git-compatible 基线；不得在用户项目中隐式初始化或提交 Git。
- 所有路径 canonicalize 后进行 containment 和符号链接检查。
- 同一登记 root（Git 或 Direct）同时只能有一个未释放写租约；旧写入者状态未知时保持租约并阻断 Recovery/Automation。执行轮次结束后只有在旧 writer 已确认停止、全部 tracked/deleted/untracked 修改已通过临时 index 冻结到 `refs/loopper/checkpoints/<taskId>/<cycleId>`、工作区已清理且源分支可恢复时，才释放租约；私有 checkpoint ref 与 stash 永不推送。继续或派生写入前必须复核 root、分支、HEAD、ref、commit、tree 和清单，任一不一致都 fail closed。
- Checkpoint `CAPTURING`/`RESTORING` 必须可在应用重启后幂等续接：已落盘的私有 ref 不得被干净工作区覆盖，已恢复工作区必须重算 tree 精确匹配后才能推进；已终止 Execution Cycle 但尚未写入 Task 投影时只能恢复到 `AWAITING_DECISION`，不得凭空创建重试。成功等待任务发布时以 `PUBLICATION` 来源重新参与同一 FIFO 准入并恢复冻结点。
- Task、Queue 与 Lease 必须保持独立状态机，并由统一协调器维护跨状态不变量：`ADMITTED` 必须与非 `RELEASED` 租约的 holder 一致。终态 holder 只有在写入 Session/验证运行时已确认停止、项目指纹一致、工作区干净且源分支可安全恢复时，才允许完成队列项并严格按 FIFO 原子转移租约；Git/指纹检查在 SQLite 事务外，真正的完成/转移在短事务内复核。启动恢复、取消/Session 清理、归档前置、手动检查和仅扫描“终态 holder + QUEUED waiter”的 10 秒后台协调必须复用同一逻辑，且并发幂等。活动 holder 或 `ADMITTED` 任务不得归档/永久删除，删除路径不得清空 holder 绕过状态机；任何阻塞均 fail closed，不得自动 stash、提交、删除或强制切分支。
- `AWAITING_DECISION` 的失败轮次支持继续当前 Task、`INHERIT_CHANGES` 派生、`REWORK_ALL_STAGES`、`VERIFY_ONLY` 审计和取消；成功轮次还支持发布、选择 Stage 继续优化及空变更显式接受。派生子任务保持 `PENDING_START`，继承修改只把冻结 tree 作为未提交工作区种子，Task baseline 仍为父任务开始前 baseline；历史 `FAILED`/`CANCELLED` Recovery 保持只读兼容。
- `VERIFY_ONLY` 不创建可写 Session；Direct 模式不提供原地回滚。
- fingerprint、baseline 或旧 writer 不匹配时必须 fail closed。
- Direct root fingerprint 必须同时包含 canonical path、目录 file key 和创建时间，避免 Linux inode 立即复用；只有 `RELEASED` 且无写入者的租约可在新任务准入时刷新指纹。

### 5.6 发布与历史删除

- 自动发布面向最新执行结果成功且处于 `AWAITING_DECISION` 或 `COMPLETED` 的 Git 任务，也兼容历史 `SUCCEEDED`；确认完成不得隐藏尚未结束的推送或 MR/PR 创建入口。Direct 任务由用户在源仓库手工处理。耐久本地提交或确认推送是 Task 进入 `COMPLETED` 的用户确认边界。AI 提交说明只能从冻结 checkpoint 的 baseline-to-tree 差异只读生成，不得为了打开发布对话框提前恢复工作区、占用租约或切换任务分支；只有实际提交动作才以 `PUBLICATION` 来源重新准入并恢复 checkpoint。
- 用户必须提供四位数字工单号；提交格式为 `#dddd_subject`。AI 只能建议 subject，不能生成或替代工单号。
- 推送必须是普通非 force push；PR/MR 只打开预填创建页，最终创建和合并仍由平台/用户确认。
- HTTP/HTTPS remote 的 MR/PR Web 地址默认保留显式协议，SSH remote 默认使用 HTTPS；但 `loopper.publication.http-web-hosts` 中精确列出的主机必须强制使用 HTTP，即使 remote 显式写为 HTTPS。成品启动脚本必须默认加入 `gitlab.spdb.com`，且不得改写 remote 或改变推送协议。
- Execution Cycle 结果、Task 用户终态与远端交付是三条独立状态轴；`AWAITING_DECISION` 不得伪装终态，`COMPLETED`/`SUPERSEDED`/`CANCELLED` 才是新任务终态。`COMMITTED`、`PUSHED`、MR 打开/关闭和 `MERGED` 均为持久化交付事实；成功的 `COMPLETED` 任务在交付未结束时仍保留适用的推送和 MR/PR 动作，`MERGED` 无出向转换并拒绝重复交付动作。
- 只有配置主机完全匹配、并由 GitLab API 按源分支、目标分支和任务提交 SHA 唯一确认的 `merged` MR 才能推进 `MERGED`。删除源分支或引用、打开创建页、人工点击和本地 Git 推断都不能单独证明合并。Token 只从 `LOOPPER_GITLAB_PRIVATE_TOKEN` 注入，不写入持久化、日志、DTO 或 artifact；外部查询位于 SQLite transaction 外并在返回后复核 Task 与 Publication 版本。
- 新任务必须持久化任务开始前的源分支。提交任务分支后先恢复该源分支；有排队任务时再进入下一任务分支。推送、推送重试和 PR/MR 状态只使用明确的任务分支引用，不得为了发布旧任务而切换当前项目分支。
- 没有远端时在登记目录任务分支创建本地提交并记录证据，恢复后的源分支不快进、不覆盖；新任务不存在源目录与隐藏 worktree 的二次同步。
- 历史隐藏-worktree 任务仍保留旧版本地同步与冲突证据兼容能力，但不得用于新任务。
- 删除历史任务是终止操作：只允许已归档且终止的任务，需要二次确认，父任务仍有子 Recovery 时拒绝。
- 历史记录删除不得删除源文件、Git 分支或 worktree。

## 6. 后端开发约定

- 所有新增和修改必须遵守 `docs/code-design-contract.md`：编排、策略、持久化、传输、解析和展示职责分离，依赖从适配层指向稳定领域合同，优先组合而非为复用实现建立继承层级。
- 新生产 Java 文件默认不得超过 600 行；推荐类不超过 400 行、接口不超过 250 行、方法不超过 40 行、构造依赖不超过 8 个。存量超限文件由 `CodeStructureContractTest` 维护只降不升的债务上限，拆分时必须同步降低上限，不得为通过构建调高阈值。
- 设计模式只用于隔离真实变化轴：可替换算法用 Strategy，确定性判断用 Policy，构造与协议装配用 Factory/Assembler，有界用例用 Coordinator，外部系统用 Adapter。禁止用无行为的转发层掩盖原 God Class。

- API Controller 只做输入/输出边界、校验和 DTO 映射；业务编排留在 `service/`。
- 领域状态使用现有枚举和 typed failure；不要用散落字符串复制状态语义。
- `DesignerSessionService` 只协调 Designer 生命周期和远端角色步骤；紧凑包计划的规范化、语义校验与可执行证据生成归 `DesignerPackagePlanCompiler`，机器传输形状归 `DesignerSemanticContracts`，不得回流到会话编排器。
- `TaskService` 协调 OpenCode、验证器、Judge 等执行生命周期；不可变设计快照、验证汇总、Git diff 和 Judge 提示证据归 `TaskEvidenceService`。状态机只决定合法转换，不承载外部 I/O 或证据装配。
- 新 API 必须考虑：输入校验、local UI/MCP 授权、幂等、乐观锁、Problem Detail/明确错误码、终态重入。
- MyBatis Mapper 方法应明确行数预期。状态更新和普通字段 mutation 分开，不能用同一 SQL 偷改状态。
- 不得在持有数据库事务时等待模型、进程、网络、浏览器或长时间文件操作。
- 需要同时持久化多个聚合行时，先在事务外解析工作区身份或完成只读外部预检，再用短事务原子写入状态/审计，提交后才执行 Git、文件写入或 Provider 调用；可恢复的跨边界文件写入必须先持久化中间状态并按内容哈希恢复。
- 解析 Git 的 NUL 分隔输出时必须防止 stderr 警告混入数据；本地同步命令局部关闭 `core.safecrlf` 警告，但不得依赖或改写用户的全局 Git 配置。
- 所有外部命令使用参数数组；不要拼接未验证路径或用户内容到 shell。
- Git fetch/分支检查和 checkout 必须暂停调用方 SQLite transaction；远端 fetch 设置 `GIT_TERMINAL_PROMPT=0`，不得因凭据提示无限等待。
- 时间、超时、重试和最大输出必须有界，重启恢复必须能处理提交后的中间空档。
- 浏览器 SSE 只是权威状态的尽力投影：Task 事件提交后再发布，各订阅者必须隔离；断线、超时、`IOException` 或已关闭的 Servlet `AsyncContext` 只移除对应订阅，不得升级为 Designer、OpenCode Session、Attempt 或 Task 失败。
- Secret 只来自进程环境/内存，不写入 SQLite、日志、artifact 或测试快照。
- 长列表必须使用 `CursorPage<T>` 的时间加 ID 稳定游标，默认 50、最大 100；筛选改变时重置游标，不得用全量载入后在浏览器筛选代替服务端查询。
- Task 列表、详情核心、审计元数据和正文必须保持分层：摘要/overview/audit 禁止读取或返回 `spec_json`、完整 `evidence_json.output`、Judge `raw_output` 或 artifact `content`；正文接口必须同时校验记录属于路径中的 Task。
- 读模型使用独立只读 Service/Mapper、集合查询、聚合或窗口函数；固定查询上限由 MyBatis 统计器测试保护，禁止返回行数增加时产生 N+1。
- 页面只加载自身数据；Task 详情先显示 overview，再后台加载 audit，日志/证据/制品正文按 ID 首次展开时加载并缓存。Task SSE 的 overview/audit 失效分区后 180 ms 合并刷新，不得因任意事件下载全部正文。
- SQLite 保持 WAL、既有 busy timeout 与事务语义。没有经过当前规模和 SQL 证据证明，不得用迁移 H2、盲调同步级别/连接池/缓存替代查询和载荷优化。

### 数据库迁移

- 已有 V1–V39 Flyway 迁移不可修改；Schema 变化新增下一序号迁移，并同时验证全新数据库和至少一个受支持旧版本升级路径。
- SQLite 外键级联不能只靠假设；活动连接必须明确启用，终止删除路径仍要按依赖顺序显式清理并验证事务回滚。
- 数据库枚举码、artifact kind、错误码和 audit event 是兼容性契约；修改前先搜索所有 Java、SQL、前端 type/label 和测试消费者。

## 7. 前端开发约定

- TypeScript 类型以 `frontend/src/types/domain.ts` 为边界，API 变更必须同步 DTO、client、store、view 和测试；Designer 保存/确认必须无损往返 Stage `workPackageId` 以及全部 LoopSpec limits、model、sessionPolicy 和 nextAttemptPromptTemplate。
- 任务详情只为 `PENDING_START` 显示“开始执行”；该状态必须明确尚未入队、占用租约或切换分支。`READY` 是已请求执行后的短暂内部状态，只显示自动继续语义，不得再次显示开始按钮。
- Task 等待动作以服务端 `waitingReasonCode` / `loopRetryAvailable` 投影为准，前端不得从历史错误推断当前“继续一轮”入口。
- `SOURCE_BRANCH_WORKSPACE_DIRTY` 错误事件作为审计历史保留，但活动红色提示只在 Task 仍为 `WAITING_INPUT` 且当前 `waitingReasonCode` 与其一致时显示；进入 `READY`/执行阶段后不得残留为当前故障。
- `SOURCE_BRANCH_WORKSPACE_DIRTY` 必须打开不可静默关闭的文件处理弹窗，逐文件选择提交、stash 或移除；重新检查成功前不得制造任务分支已创建的状态，取消只能经确认后把任务标记为失败。
- 服务端是权威状态；不要用计时器伪造阶段进度、用量、成本、Session 完成或 Judge 结果。
- 动态 Token 窗口只消费服务端单调累计值；首次值静默建立基线，后续正增量短暂显示 `+xxx`，旧快照不得降低总量或显示负增量，切换 Designer/Task 作用域必须重置本地基线；动画只使用 `transform`/`opacity` 并尊重 `prefers-reduced-motion`。
- 所有等待、问题、权限、可恢复错误和终止错误都必须真实可见，并提供可执行的恢复动作；不要永久显示含糊的“待评审”。
- 使用 `displayLabels.ts` 和现有 `StatusBadge`/错误组件表达中文含义；不要在多个页面复制英文枚举到中文的映射。
- 前端遵循中文优先的极简文案：状态标签或操作已能表达含义时删除重复说明；全自动等模式只保留标签，只有阻断或待决策时显示原因和下一步。
- 普通页面不得直接展示内部枚举/错误码或 Task、Designer Session、Session、Attempt、Draft、Work Package、Criterion 等记录 ID；使用名称、顺序、时间和 `displayLabels.ts` 中文投影。原始值只保留在协议、URL、组件 key、服务端审计和用户主动展开的命令日志中。
- 所有页面错误、消息提示和工具提示必须用中文表达发生原因与下一步；未知英文码使用安全中文兜底，不得把 `XX_XX` 原样回显给用户。
- 项目登记卡片桌面端最多两列，名称、路径、说明、统计和操作均允许换行，窄屏降为单列，禁止 `nowrap` 造成文字和按钮互相挤压。
- Designer 的用户界面统一使用“任务设置”和 `displayLabels.ts` 中文标签，不得用“采用新画像”表达普通确认；任务类型、主要制品等选择控件默认隐藏到“修改设置”之后。REST、SQLite 与选择控件的 `value` 继续使用稳定英文枚举码。
- 界面角色称谓固定为需求分析师、任务规划师、设计师、规范工程师、评审员、验收工程师、开发工程师，以及需求评审员、风险评审员；协议与数据库英文角色码保持稳定。
- 遵循 `docs/design-contract.md` 的 dark-first token、错误层级和桌面优先结构；优先复用 `styles/tokens.css`，不要引入页面私有的另一套视觉系统。
- Markdown 必须经过 DOMPurify；Mermaid 错误必须抑制并清理渲染残留，不允许把原始不可信 HTML 插入 DOM。
- 冲突、代码、JSON 等编辑器优先复用 CodeMirror 组件和现有语言映射。
- 交互写操作要有 loading、错误、幂等/版本冲突处理；破坏性操作必须明确确认。
- Runtime 显式启动和重启都必须携带本地 UI 标识，服务端须在检查进程所有权或执行副作用前验证；LoopSpec 编辑器的数值上限必须与领域 Bean Validation 一致（启动 300 秒、停止 60 秒、单阶段尝试 20 次）。
- 运行环境页的 OpenCode Loopper 版本必须来自服务端 Runtime DTO，不能使用前端 package 版本硬编码，也不能与 OpenCode CLI 版本混为一个字段。
- 运行环境页只展示服务摘要、进程边界和恢复操作；原生能力发现与执行授权仍由服务端持有，但不再渲染独立的能力或授权说明卡片。
- 设置页多列数值表单必须按控件底部对齐，避免一行/两行标签混排时输入框错位；窄屏降列时仍保持自然文档流。
- 每个行为变化都在相邻 `.spec.ts` 中增加回归测试；路由级关键流程再考虑 `frontend/e2e/`。
- Designer 的已回答问题必须按作用域和讨论修订分卡，并固定在对应设计稿之前；确定性校验消息只渲染一个默认收起的汇总卡，展开后保留逐条状态和时间；真正连续的 System 消息必须跨需求版本和需求/工作包作用域元数据合并为一张默认收起、与“需求讨论”同结构的整行折叠条，展开后按持久化顺序展示完整内容；用户/设计师/讨论/校验时间线项仍是分组边界，活动错误横幅不得因此隐藏。
- UI 图标必须使用项目已打包的 Iconify/Lucide 资源，不依赖外网 CDN。
- Spring SPA fallback 必须接住无扩展名的深层前端 history 路由；`/api`、`/actuator`、`/assets` 和带文件扩展名的静态资源路径不得被改写为 `index.html`。

## 8. 测试与验证策略

### 聚焦验证

先运行最接近变更的测试，以快速定位错误。示例：

```bash
# 单个后端测试类
./mvnw -Dtest=TaskServiceIntegrationTest test

# 单个前端测试文件
npm --prefix frontend run test -- src/views/TaskDetailView.spec.ts

# 前端类型与构建
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

测试名称和命令必须来自当前仓库，不能凭记忆杜撰。遇到失败只摘取与根因相关的错误继续诊断，不用大段无关日志淹没上下文。

### 完整验证和 JAR

每次实际仓库更新的最终门槛：

```bash
./scripts/verify.sh
```

该命令执行 `./mvnw clean verify`。不能用以下结果替代：

- 只运行 Java 单测；
- 只运行 Vitest；
- 只执行 `npm run build`；
- 复用之前生成的 JAR；
- 看到 `target/` 已存在就推断当前源码已打包。

完整命令成功后必须检查：

```bash
JAR=target/opencode-loopper-0.2.14.jar
test -s "$JAR"
jar tf "$JAR" | rg 'BOOT-INF/classes/static/index.html'
jar tf "$JAR" | rg 'BOOT-INF/classes/static/assets/'
shasum -a 256 "$JAR"
```

若变更涉及 Flyway、静态资源、Linux、Chrome 或 OpenCode 兼容性，应增加对应的启动/运行时验收，不能仅依赖 Maven 成功。

集成测试通过 `loopper.scheduling.enabled=false` 关闭自动轮询，并通过 `loopper.startup-recovery.enabled=false` 关闭 ApplicationReady 自动恢复，但保留 Monitor/Recovery Bean 供测试显式调用；不得让后台查询、启动恢复与共享 SQLite 的 Flyway `clean/migrate` 或其他测试准备数据并发。

### 运行时验收

仅在任务授权启动或重启服务时执行。启动前先识别端口所有者，避免停止另一个 worktree/项目：

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
ps -p <PID> -o pid=,ppid=,cwd=,command=
```

启动/替换后至少核对：

```bash
curl --fail http://127.0.0.1:8080/actuator/health
```

前端变更还要证明实际 JVM 中的 JAR/静态资源是新版本；必要时检查 PID、cwd、JAR 时间/哈希、包内 asset 名和浏览器网络资源。浏览器看起来更新不能反推新 JAR 已部署。

Linux/Windows 成品启动脚本不得写死 OpenCode 端口。显式
`OPENCODE_BASE_URL` 优先；否则从当前 OpenCode 进程的显式 `--port`
提取候选。Linux 还必须按已确认的 OpenCode PID 读取实际监听端口，
覆盖 TUI 和 `opencode web` 的动态端口；若非特权进程看不到 socket
归属，可扫描本机 TCP 监听端口作为有界候选，但所有候选都必须用
loopback `/global/health` 的 `healthy=true` 验真。通配监听地址必须转换
为 loopback 连接地址，并兼容 OpenCode 官方 `OPENCODE_SERVER_USERNAME`/
`OPENCODE_SERVER_PASSWORD`。没有可复用实例时使用 `auto` 模式，由
Linux 启动器先把 OpenCode CLI 解析为确定的可执行文件路径，再由
Loopper 在动态 loopback 端口启动受管进程；不得把任意监听端口直接
推断为 OpenCode。受管启动失败必须公开安全的失败原因和实际尝试地址，
不得把默认探测地址 4096 伪装成正在监听的地址。启动阶段单次健康请求
必须短于总启动预算并持续重试，不得让通用请求超时吞掉整个启动窗口。
一次受管启动失败后，普通状态读取和内部 client 获取不得反复拉起进程；
Runtime 页只通过要求本地 UI 标识的显式动作重新启动，并且必须在受认证的
`/global/health` 返回 `healthy=true` 后才能显示连接成功。

设置页保存的非敏感启动配置镜像固定为 `${LOOPPER_DATA_DIR}/config/startup-overrides.properties`。两平台脚本只能逐项解析白名单，禁止 `source`、`eval`、`call` 或执行文件内容；优先级固定为显式环境变量、页面文件、脚本默认值。未知键告警忽略，已知键格式非法必须终止。数据目录、Java Home、JAR 路径、MCP Token、OpenCode 密码和 GitLab Token 不得进入页面、数据库设置 JSON 或启动镜像，服务监听继续固定 loopback。

## 9. 文档同步规则

| 变化 | 必须同步 |
| --- | --- |
| 面向用户的功能、安装、配置、页面或故障排查 | `README.md` |
| 架构、生命周期、错误、事务、工作区或发布不变量 | `docs/architecture.md` |
| Designer、Review Gate、视觉状态、交互语义 | `docs/design-contract.md` |
| OpenCode API、Session、权限或 MCP | `docs/opencode-contract.md` |
| Recovery、Interaction、Verifier、Insight、Automation | `docs/seven-feature-contract.md` |
| Agent 命令、目录、开发规则、关键陷阱、完成定义 | `AGENTS.md` |
| 代码职责、依赖方向、类/方法规模或结构例外 | `docs/code-design-contract.md`、`AGENTS.md`、`CodeStructureContractTest` |
| 版本/JAR 名称 | README、AGENTS、POM、前端 package、Linux 脚本、application.yml |

更新文档时只写已经实现并验证的事实。计划、建议和未验证运行时结果必须清楚标注，不得写成现有能力。

## 10. Git、文件和协作安全

- 默认只修改用户明确要求的范围；不要顺手重构无关代码。
- 工作区可能不干净。现有修改属于用户，除非有明确证据，否则不得恢复、覆盖、格式化或纳入本任务。
- 禁止使用 `git reset --hard`、`git checkout -- <file>`、递归删除工作区或其他不可恢复操作。
- 不要手工编辑 `target/`、`frontend/dist/`、`frontend/node_modules/`、SQLite 数据库或 Flyway 已执行迁移来“修复”源码问题。
- 完成已授权的代码或文档修改并通过相应验证后，默认创建只包含本任务范围的本地提交；用户明确要求暂不提交时例外。不得为了提交而纳入、覆盖或拆散既有用户修改，也不得擅自切换分支。
- 本地提交不授权任何远端写入。只有用户明确要求发版或推送新版本后，才允许统一推送已核验的本地提交和全新 `v<version>` 标签，以触发标准 Release 工作流；禁止强推、移动或复用标签，未获该授权时也不得创建 PR/MR。
- 不自动删除 worktree、分支、运行数据或历史证据。
- 修改前阅读文件，修改后检查 diff；批量格式化只能覆盖本任务文件。
- 若用户修改与当前文件重叠，先停下说明冲突；能避开时保留用户修改继续。

## 11. Agent 完成定义

一次代码任务只有满足以下条件才算完成：

- [ ] 开始前完整阅读当前 `AGENTS.md`。
- [ ] 读取 `git status --short` 并保护已有修改。
- [ ] 阅读相关契约、源码、测试和相似实现。
- [ ] 实现范围与用户目标一致，没有无关重构。
- [ ] 行为变化有自动化测试或明确的不可测边界。
- [ ] 相关 README/docs 已同步。
- [ ] 本 `AGENTS.md` 正文和维护记录已同步。
- [ ] 已在最终重新打包前把全部发布版本引用更新为一个未使用的新 SemVer。
- [ ] 聚焦测试通过。
- [ ] `./scripts/verify.sh` 完成并生成新的可执行 JAR。
- [ ] JAR 包含当前 Vue 静态资源，并记录新的 SHA-256。
- [ ] `git diff --check` 通过，`git status` 中没有意外文件。
- [ ] 已创建只包含本任务范围的本地提交；若用户要求暂不提交，已在最终回复中明确说明。
- [ ] 仅当用户明确要求发版时：发布提交已推送，新 `v<version>` 标签指向最新交付提交且与 Maven 版本一致。
- [ ] 仅当用户明确要求发版时：Release 工作流成功，GitHub 资产包含 JAR、`start-linux.sh`、`start-windows.bat` 和 `SHA256SUMS`，远端 digest 已回读。
- [ ] 如声称运行时有效，已核对真实 PID/cwd/JAR/health/浏览器证据。
- [ ] 最终回复列出文件、验证、JAR、运行时边界和剩余风险。

## 12. 维护记录

本表必须由每次实际修改代码的 Agent 在结束前追加或更新。保持简短；详细证据放在任务回复或对应契约文档中。

| 日期 | 范围 | 文档/契约变化 | 验证与 JAR |
| --- | --- | --- | --- |
| 2026-08-24 | Designer v5 不完整结果持久化状态修复，交付 0.2.14 | `DesignerAcceptanceWorkflow` 不再把确定性结果码 `DESIGN_INCOMPLETE` 直接写入 V41 的闭集状态列，而是持久化为 `BOUND` 并保留既有重设计/人工输入编排；新增真实 SQLite 迁移与 Designer 全链路回归，架构与本公约明确 `EXTRACTED / BOUND / COMPILED / FAILED` 状态语义；同步全部发布版本路径 | 聚焦不完整规划状态与发布脚本合同通过；`./scripts/verify.sh` 完整通过，Java 536 项（0 失败、0 错误、1 跳过），前端 Vitest 196/196；JAR `target/opencode-loopper-0.2.14.jar` 为 283477302 字节，含 110 个 SPA 静态条目，SHA-256 `61a10b31c3446417adfdb2b0d30260714574ced6d092e12abfd518d40c04c7f6`；隔离 18014 使用 OpenCode 1.18.18 对 CupXml2Java 普通任务“新增责任链单元测试”真实 Loop，Session `26fe8697-4c4e-4d62-8dc9-268a3e37f981` 的 WP-1 以 v5、0 次格式/语义修复、`server_compiled=1` 进入 `FINAL_REVIEW`，新增 Task 0；未重启当前 8080 实例，未推送、未打标签、未创建 Release |
| 2026-08-21 | Designer 单包软件角色继承与 Compiler 路由自愈，交付 0.2.12 | 普通 `DIRECT_SOFTWARE_DESIGN / WP-1` 固定继承已确认任务画像的技术栈、软件 Role Pack 与测试策略，设计稿中的“依赖”等非变更语句不再把软件包误降级为本地维护；历史或异常的非软件工作包快照在 Compiler 权威读取前自动重算并持久化，从而稳定进入 v5 验收绑定；只有大型多包流程允许基于包级交付语义专门化文档/维护角色；同步 README、架构、Designer/AI 角色合同与本公约正文 | 聚焦角色信号、历史快照自愈、v5 Compiler 选择与发布合同通过；GitNexus 变更影响为 medium，仅命中工作包编译规划链路；`./scripts/verify.sh` 完整通过，Java 535 项（0 失败、0 错误、1 跳过），前端 Vitest 196/196；JAR `target/opencode-loopper-0.2.12.jar` 为 283476978 字节，含 110 个 SPA 静态条目，SHA-256 `4f2deca787383d6f3e7c16885a3dc13a8fd2468b740ec8378f3ccbfac0bfd2c8`；未重启当前 8080 实例，等待推送、标签与 Release 核验 |
| 2026-08-21 | Designer v5 可选建议降级与关系竞争修复，交付 0.2.11 | 真实弱模型输出证明 v5 已进入运行链路，但模型返回 `groupIndex/capabilityIndex/preference` 形状后仍耗尽格式修复；现将分组/偏好明确为可丢弃建议，提示给出唯一对象形状，兼容可逆的软偏好缺失，其他无效建议审计后由服务端空建议确定性编译；共享领域词按标题优先和唯一胜者竞争，独立必跑目标只绑定一次；工作包技术信号使用词边界，`ChainNodeInvoker` 不再误判 Node；Router 兼容 `TEST_SOURCE_CODE`，纯测试 Stage 生成 `JAVA_TEST_ONLY`；同步 README、架构、Designer/OpenCode/AI 角色合同与本公约正文 | 聚焦 v5 算法/角色/Router/结构/发布合同和真实编译链路通过；`./scripts/verify.sh` 成功，Java 533 项（0 失败、0 错误、1 跳过），前端 Vitest 196/196；JAR `target/opencode-loopper-0.2.11.jar` 为 283476127 字节，含 110 个 SPA 静态条目，SHA-256 `d6dde83f5df392c2216eb977e6fc5e6fc16a388783f600901e4fd81e0a0f809c`；未重启当前 8080 实例，未推送、未打标签、未创建 Release |
| 2026-08-21 | Designer 验收绑定所有权与通用意图映射修复，交付 0.2.10 | 验收建议合同升级为 v5，AI 只提供分组/偏好，服务端唯一派生结果与具体缺口；测试能力只读取正向交付关系，排除负向/不变文本，并用限定名归一、符号与语义竞争绑定场景；每个业务条件只绑定一个 focused test，独立必跑目标生成可追溯机器条件；冻结 v4 工作包继续兼容；JUnit/Jupiter/Surefire 归入 Java，Router 兼容测试制品别名；同步 README、架构、Designer/OpenCode/AI 角色合同与本公约正文 | 聚焦后端 36/36、前端 Vitest 196/196；`./scripts/verify.sh` 的前端类型检查/生产构建及前 93 个 Java 测试通过（0 失败、0 错误、1 跳过），随后既有 `VerifierEngineTest` 在系统 Chrome/Playwright `BrowserImpl.newContext` 环境挂起约 8 分钟，按用户时限中止，故全量 Java 未完成；`mvn -q -DskipTests package` 成功；JAR `target/opencode-loopper-0.2.10.jar` 为 283473498 字节，含 110 个 SPA 静态条目，SHA-256 `3181ea6c251f9076371b61cb91381f9f3cd3f1cac8a3565b66c3b4af91945814`；未重启当前 8080 实例，未推送、未打标签、未创建 Release |
| 2026-08-21 | Designer 验收意图确定性编译，交付 0.2.9 | 新增受控 Markdown 事实提取、EARS 验收句、Gherkin 场景投影、DMN 风格能力决策表和分支定界集合覆盖；AI v4 只绑定事实/能力索引与软偏好，服务端闭集生成命令、相对路径、验证器及 LoopSpec v2，零覆盖失败关闭 Review Gate；V41 持久化规划快照并在设计页显示业务化“验收意图识别”；同步 README、架构、Designer/OpenCode/AI 角色合同与本公约正文 | PinTrans 黄金用例、v3 兼容、v4 Schema/权限/持久化/结构/发布契约及 Designer UI 聚焦验证通过；`./scripts/verify.sh` 完整通过（Java 527 项，失败 0、错误 0、跳过 1；Vitest 196 项全通过），BUILD SUCCESS；生成 `target/opencode-loopper-0.2.9.jar`（283458046 bytes，内含 110 个 `static/index.html`/`static/assets` 条目及 V41 迁移），SHA-256 `fa08211cb94e1cb03c68c795059f10a7237602692cd86f20acc7c7c0b6225abc`；未执行真实 Provider A/B、未重启运行实例，未推送、未打标签、未创建 Release |
| 2026-08-21 | Designer/Task 动态 Token 窗口，交付 0.2.8 | V40 持久化按 Designer/Task 聚合的 Provider Token 投影并通过触发器保留被 repair/finalizer 替换的远端；活动接口复用同一次消息读取，当前远端即时累计、每次轮询最多补齐一个历史远端；Designer 当前角色卡和 Task 模型输出头部新增纯数字窗口，首次静默建立基线，正增量短暂显示 `+xxx`，旧快照不回退且 reduced-motion 关闭动效；不显示额度、成本、无限 Token 或说明文案；同步 README、架构、Designer/OpenCode/七特性合同与本公约正文 | 聚焦后端活动/投影/OpenCode/迁移/结构/发布契约通过，前端类型检查与 Vitest 195/195 通过；`./scripts/verify.sh` 完整通过（Java 522 项，失败 0、错误 0、跳过 1；Vitest 195 项全通过），BUILD SUCCESS；生成 `target/opencode-loopper-0.2.8.jar`（283356850 bytes，内含 110 个 `static/index.html`/`static/assets` 条目及 V40 迁移），SHA-256 `05d442c9380b19ab19750a9643478dbb7e7ed04655eb3497f183b21337f10e5c`；未重启运行实例，未推送、未打标签、未创建 Release |
| 2026-08-21 | Designer 任务设置确认语义与 Session 重启预警，交付 0.2.7 | 用户界面以“任务设置”替代“画像”，首次识别只显示“确认并继续/修改设置”，真实重识别显示“继续使用原设置/使用本次识别结果”，编辑入口延迟展示；新增服务端只读影响预览和完全相同选择的无变化保护，只有流程切换才在明确说明停止当前远端设计 Session、丢弃未保存上下文并保留快照/历史后允许重新开始；同步 README、Designer/OpenCode/AI 角色合同与本公约正文 | `0.2.4` 聚焦打包契约发现旧 `0.2.3` 启动脚本断言，`0.2.5` 完整验证发现 Designer 历史大类行数门禁，`0.2.6` 聚焦编译发现测试仍依赖已移除转发方法，修正后均按公约顺延；聚焦后端 73/73、前端 193/193；`./scripts/verify.sh` 完整通过（Java 520 项，失败 0、错误 0、跳过 1；Vitest 193 项全通过），BUILD SUCCESS；生成 `target/opencode-loopper-0.2.7.jar`（283341708 bytes，内含 108 个 `static/index.html`/`static/assets` 条目），SHA-256 `2991ffe474ebdd763503e960c2481dffff7eed783636738b69f860919b169dbf`；未重启运行实例，未推送、未打标签、未创建 Release |
| 2026-08-20 | Router 串行启动、画像 Session 交接与时间线实时活动，交付 0.2.3 | Router 请求线程与 Monitor 互斥领取运行，丢失乐观更新时停止孤儿远端；画像换流程严格先停旧 Session 再持久化和派发，停止失败不创建并行 Session；新需求保持在当前设计页并在画像完成后自动继续；实时活动收回消息时间线当前角色卡，只显示最新 Markdown/工具片段；同步 README、架构、Designer、OpenCode 合同与本公约正文 | `0.2.1` 首次完整验证暴露旧发布契约与结构门禁，`0.2.2` 聚焦验证暴露停止服务依赖环，修正源码后均按公约顺延；聚焦 Router/画像交接/实时活动/结构/发布契约通过；`./scripts/verify.sh` 完整通过（Java 519 项，失败 0、错误 0、跳过 1；Vitest 192 项全通过），BUILD SUCCESS；生成 `target/opencode-loopper-0.2.3.jar`（283338579 bytes，内含 108 个 `static/index.html`/`static/assets` 条目），SHA-256 `7b1629f9177b6ab141ec08f04e2b528debdf43e5079a4a377480101cfc81e898`；未重启运行实例，等待推送标签和 Release 核验 |
| 2026-08-20 | Designer/Task 兼容门面继续瘦身，交付 0.2.0 | Designer 抽离机器语义合同及单包计划规范化、语义校验、证据映射与验证器合成，`DesignerSessionService` 从 6004 行降至 5406 行；Task 抽离冻结设计快照、验证摘要、最终差异和 Judge 证据投影，`TaskService` 从 3000 行降至 2841 行；结构门禁同步下调并在代码设计契约中固定兼容门面和新协作者的依赖边界；同步 README、架构与本公约正文 | 聚焦 `DesignerPackagePlanCompilerTest`、Designer/Task 集成、结构和打包契约通过；`./scripts/verify.sh` 完整通过（Java 513 项，失败 0、错误 0、跳过 1；Vitest 193 项全通过），BUILD SUCCESS；生成 `target/opencode-loopper-0.2.0.jar`（283335380 bytes，内含 108 个 `static/index.html`/`static/assets` 条目），SHA-256 `75bdfbf677c24ed8e03726d13356c2141f06e21362bac08c993cf9cc25d94c1c`；未重启运行实例，未推送、未打标签、未创建 Release |
| 2026-08-20 | Designer 画像确认、实时活动、MCP 与会话清理，交付 0.1.99 | 画像增加决策态、服务端确认就绪和安全继承；重算持续刷新并展示新旧选择；确认后清除工作区并直达任务；清理操作停止全部远端角色后才归档；所有角色开放已配置 MCP 且保持内置危险工具边界；新增实时活动面板和同事化角色称谓；同步 README、架构、Designer、OpenCode、AI 角色合同与本公约正文 | 聚焦后端、前端类型检查及相关 44 项 Designer/UI 测试通过；隔离 OpenCode 1.18.18 MCP 冒烟验证 `/mcp` 发现与 `<server>_*` 权限写入成功且未修改用户配置；`./scripts/verify.sh` 通过（Java 511 项，失败 0、错误 0、跳过 1；Vitest 193 项全通过）；生成 `target/opencode-loopper-0.1.99.jar`（283322812 bytes，内含 108 个 `static/index.html`/`static/assets` 条目），SHA-256 `afc9f802f2e01a04f6dde59735a8aca986134e2b5ab79df7d23e7b71228d0b51`；未重启运行实例，未推送、未打标签、未创建 Release |
| 2026-08-20 | God Class 职责拆分与代码结构门禁，交付 0.1.98 | 新增代码设计契约和 600 行默认门禁/遗留债务只降不升规则；Designer/Task 提取提示、上下文、重试、Judge、状态持久化职责；OpenCode HTTP、Git、发布、本地同步和 Mapper 按策略、解析、协议及聚合边界拆分；同步 README、架构、OpenCode 合同与本公约正文 | 聚焦结构、HTTP、Git、Designer、Task、发布和本地同步回归通过；`./scripts/verify.sh` 诊断重试完整通过：Java 504 项（0 失败、0 错误、1 项平台条件跳过），Vitest 186/186，BUILD SUCCESS；首次 0.1.98 全量运行仅并发队列测试出现一次时序失败，单测重跑及同源码全量重跑均通过；本地 JAR `target/opencode-loopper-0.1.98.jar` 大小 283292181 字节、含 108 个 SPA 静态入口/资产，SHA-256 `e58c391e00f75f07db907673ccbdf8ab563811ddb416ed073b1f1e4fff0ac738`；未重启运行实例，不推送、不打标签、不创建 Release |
| 2026-08-20 | 全量动态 Role Pack 与 Compiler 可靠性收口，交付 0.1.96 | Role Pack v3 按 Java/Python/Node/Other 软件族消除 JavaScript、同族别名和未知单栈误路由；全部可编译角色使用栈原生规划样例与测试目标解析；当前紧凑合同不再误入历史 `status` 解析，格式/语义修复使用全新无工具 Session 与匹配 Schema，非法补丁不覆盖有效快照；Java/混合包禁止仅 FULL_TEST/BUILD 的生产 Stage，补丁中可唯一反推的 `verifiers` 和缺失字段 `replace` 分别规范化为 `evidence` 与 `add` 后重跑全校验；同步 README、架构、Designer、OpenCode、AI 角色合同与本公约正文 | 聚焦补丁/Role Prompt/Compiler 集成 9/9；`./scripts/verify.sh` 完整通过：Java 497 项（0 失败、0 错误、1 项平台条件跳过），Vitest 186/186，BUILD SUCCESS；本地 JAR `target/opencode-loopper-0.1.96.jar` 大小 283226768 字节、含 108 个 SPA 静态入口/资产，SHA-256 `385d302b73bfe1d44f1b4bbf2a170c40a5997a8c0c1ad7cbde76a13a738c08d1`；复制当前 SQLite 到隔离 18096，复用 OpenCode 1.18.18 + `opencode-go/deepseek-v4-flash` 对失败会话 `857a0148-186e-4c65-ade0-8d0c23ad4744` 的 WP-1 真实重编译，0 次格式/语义修复即 `server_compiled=1`、3 个 Java Stage 均含聚焦测试并进入 `FINAL_REVIEW`，新增 Task 0，旧 `status`/`/status` 冲突 0；隔离端口已释放，当前 8080 仍为 0.1.94/PID 59428，未重启、不推送、不打标签、不创建 Release |
| 2026-08-20 | 普通任务服务端需求快照、单次提问流程与 0.1.94 | 普通需求只在讨论阶段提问，服务端从用户输入和最终回答原样组装权威快照；WP-1 初稿/修订/重设计不再提问；大型任务保留 AI 预设计和逐包问题；双向模式切换终止旧 Session 并重建目标合同；同步 README、架构、Designer、OpenCode、AI 角色契约与本公约正文 | Designer 后端聚焦测试 52/52、Designer 前端聚焦测试 30/30、前端类型检查通过；`./scripts/verify.sh` 完整通过：Java 485 项（0 失败、0 错误、1 项平台条件跳过），Vitest 186/186，BUILD SUCCESS；本地 JAR `target/opencode-loopper-0.1.94.jar` 大小 283220431 字节、含 108 个 SPA 静态入口/资产，SHA-256 `b846dc89dff89a0c6e4a5d3b1a9fdaaea2e723a1a720716a16504671f23fe084`；未重启运行实例，不推送、不打标签、不创建 Release |
| 2026-08-20 | Designer 连续系统消息折叠条与 0.1.93 | 连续 System 通知跨需求修订和需求/工作包作用域元数据合并，默认以与“需求讨论”同结构的整行“系统消息”折叠条显示，用户/设计师/讨论/校验仍保持时间线边界；同步 README、设计合同与本公约正文 | Designer 聚焦测试 28/28、前端类型检查、发布打包契约通过；`./scripts/verify.sh` 使用 JDK 21.0.12 完整通过：Java 483 项（0 失败、0 错误、1 平台条件跳过），Vitest 184/184，BUILD SUCCESS；本地 JAR `target/opencode-loopper-0.1.93.jar` 大小 283212527 字节、含 108 个 SPA 静态入口/资产，SHA-256 `230db80101fb888fa354b3afef4fc8245186c6ed090dbf33ad7830fb159693b6`；未重启运行实例，不推送、不打标签、不创建 Release |
| 2026-08-20 | 默认单包软件设计、大型任务开关与 0.1.92 | 软件任务默认 `DIRECT_SOFTWARE_DESIGN / WP-1`，允许 1–6 个 Stage，不创建 Decomposer Session 且校验后自动批准；画像冻结前可显式开启大型任务，保留既有多包/每包 1–3 Stage 流程；第 7 个 Stage 起以 `LARGE_TASK_MODE_REQUIRED` 停止并等待用户显式切换；同步 README、架构、Designer、OpenCode、AI 角色契约与本公约正文 | 聚焦后端 Router/Designer/打包契约及前端 Designer 28/28、类型检查通过；`./scripts/verify.sh` 使用 JDK 21.0.12 完整通过：Java 483 项（0 失败、0 错误、1 平台条件跳过），Vitest 184/184，BUILD SUCCESS；本地 JAR `target/opencode-loopper-0.1.92.jar` 大小 283212702 字节、含 109 个 SPA 静态条目，SHA-256 `724dbaed600cdc4d0083e50c18ec32cf27e5180151e8c6875c8c98dad2d7a59c`；未重启运行实例，不推送、不打标签、不创建 Release |
| 2026-08-19 | Designer 同位置系统消息折叠，0.1.91 | 同一时间线位置、需求版本和需求/工作包作用域内的连续 System 消息合并为默认收起的单图标控件，展开后按持久化顺序查看完整记录；用户/设计师/讨论/校验和作用域边界保持分组隔离，错误组使用警示图标且不隐藏活动错误横幅；同步 README、设计合同与本公约正文 | Designer 聚焦测试 26/26、前端类型检查及 `./scripts/verify.sh` 使用 JDK 21.0.12 完整通过：Java 480 项（0 失败、0 错误、1 平台条件跳过），Vitest 182/182，BUILD SUCCESS；本地 JAR `target/opencode-loopper-0.1.91.jar` 大小 283204888 字节、含 108 个 SPA 静态条目，SHA-256 `a7d2908d26005af52e9c11f7f8b8ebd19ed978e9c95ff257f3557955e9e27ccd`；未重启当前 8080，不推送、不打标签、不创建 Release |
| 2026-08-19 | 全前端中文极简化与可读性优化，0.1.90 | 所有主要页面删除冗余说明，状态、角色、验证器、错误码和内部名称统一走中文显示；普通页面隐藏任务、设计、工作包、尝试、会话、证据等记录 ID；项目登记卡片扩大为双列可换行布局；同步 README、设计合同与本公约正文 | 前端类型检查、聚焦发布契约及 `./scripts/verify.sh` 使用 JDK 21.0.12 完整通过：Java 480 项（0 失败、0 错误、1 平台条件跳过），Vitest 181/181，BUILD SUCCESS；本地 JAR `target/opencode-loopper-0.1.90.jar` 大小 283204274 字节、含 108 个 SPA 静态条目，SHA-256 `8e545c7bd03c50947533e1f8a0fdecf08cd7bcf2837191ac3414a9644142b1be`；未重启当前 8080，不推送、不打标签、不创建 Release |
| 2026-08-19 | 修复任务画像确认与全自动模式冲突及 IDEA 暴露的读模型异常，0.1.89（等待发版窗口） | 全自动把 Router 当前意图/主要制品持久化为 `AUTO_RECOMMENDED` 后继续，普通模式仍保留人工覆盖；危险操作证据不能自动确认；历史 `TASK_PROFILE_DECISION_REQUIRED` 阻断走一次专用 `RESUME`；不完整工作包角色快照不再解析空枚举并可在权威使用时修复；Task 概览从重叠重试计划中只选一条；同步 README、架构、设计合同与本公约正文 | 聚焦后端 4/4、Designer 前端 25/25、前端类型检查通过；`./scripts/verify.sh` 使用 JDK 21.0.12 完整通过：Java 480 项（0 失败、0 错误、1 平台条件跳过），Vitest 178/178，BUILD SUCCESS；本地 JAR `target/opencode-loopper-0.1.89.jar` 大小 283209400 字节、含 108 个 SPA 静态条目，SHA-256 `204b6c9eb1481c2669e862bf97722d7bd5c359dcaf6291dfa9a3d2c3208311c8`；未重启当前 8080，不推送、不打标签、不创建 Release |
| 2026-08-19 | 调整版本百进位、本地提交与按需发版公约 | 版本的 `MINOR/PATCH` 限定为 `0–99`，明确 `0.1.99 → 0.2.0`；完成交付默认创建范围内本地提交，但只有用户明确要求发版后才统一推送提交与最新版本标签并核验 Release | 仅修改开发公约，执行契约文本检查与 `git diff --check`；不改变源码或交付内容，不重新打包 JAR；本地提交，不推送、不打标签、不创建 Release |
| 2026-08-19 | Designer 任务画像中文显示，准备 0.1.88（等待发版窗口） | 画像摘要、流程/执行/测试策略及任务类型、主要制品覆盖选项统一通过 `displayLabels.ts` 显示中文，REST/SQLite 稳定英文枚举码不变；同步 README、设计合同与本公约正文 | `npm --prefix frontend run test -- src/views/DesignerView.spec.ts` 24/24、`npm --prefix frontend run typecheck`、`./mvnw -q -Dtest=ReleasePackagingContractTest test` 及 `./scripts/verify.sh` 通过（Java 476，跳过 1；Vitest 177/177）；生成 `target/opencode-loopper-0.1.88.jar`（283207892 bytes，静态入口/资产 107 项，SHA-256 `ad1fa5bb7491dc2a57b468f5e896d6f3ef30901b96b6aa21e291f9d4488e1566`）；按用户要求不提交、不推送、不打标签、不发布，也不替换当前 8080 实例 |
| 2026-08-19 | 三包收口：Stage 执行 Role Pack、持久化异步 Router、结构化独立 Reviewer、章节文档与 OOXML 安全，0.1.87 | V37/V38/V39 分别冻结 Stage 角色/测试策略、持久化可恢复 Router 运行、固定 `REVIEWER_REPORT_V1`；统一 Maven/Gradle/npm/pytest/unittest 测试策略；章节文档和表格转换冻结确定性策略；同步 README、架构、Designer、AI 角色、OpenCode、验证器合同与本公约正文 | 三包合并聚焦测试 89/89；`./scripts/verify.sh` 使用 JDK 21.0.12 通过：Java 476 项（0 失败、0 错误、1 平台条件跳过），Vitest 176/176，BUILD SUCCESS；本地 JAR `target/opencode-loopper-0.1.87.jar` 大小 283207041 字节、含 107 个 SPA 静态条目，SHA-256 `765d20fc549cfaa7b4070d24299933b31d378e23e206b6f17f2c6003f9fddc78`；既有隔离 Python、XLSX→Markdown、DOCX、只读报告四链路回归全部通过，另以独立数据目录在 18087 启动成品 JAR，Flyway v39、health `UP`、Runtime 版本 0.1.87，随后正常停止并释放端口；未替换或启动 8080 实例，Release 证据待标签触发后回填 |
| 2026-08-19 | 无工具 AI Router、工作包级动态 Role Pack、真实只读 Reviewer、大型文档/安全维护专属流程与 0.1.86 交付 | V36 冻结每个工作包的 Role Pack、技术栈、执行策略和测试策略，并持久化 Reviewer 运行态；Router 只给语义标签，服务端合并受控仓库事实和权限边界；Reviewer 只开放 read/glob/grep 并校验证据位置；大型文档按 2–6 个章节片段确定性聚合；安全维护使用精确路径白名单、禁止删除和危险进程命令的双重硬门禁；同步 README、架构、Designer、AI 角色、OpenCode 合同与本公约正文 | 聚焦 Router/Reviewer/混合 Java-Python Role Pack/大型文档/安全维护/迁移/任务执行 100/100；首次完整验证发现 4 个旧 Session 计数和 3 个发布语义夹具兼容问题，修正事件发布与外部版本发布区分后失败集合通过；最终 `./scripts/verify.sh` 使用 JDK 21.0.12 通过：Java 464 项（0 失败、0 错误、1 平台条件跳过），Vitest 176/176，BUILD SUCCESS；本地 JAR `target/opencode-loopper-0.1.86.jar` 大小 283169461 字节、含 108 个 SPA 静态条目，SHA-256 `ebe8794e2ce8f72a3b8aeb60a6510be5bd3fc88b65c3f3feb227f91c1e2ced52`；未替换当前 8080 运行实例 |
| 2026-08-19 | 动态任务画像、Role Pack、非代码制品/报告流程、Python 测试策略与 0.1.85 交付 | V35 持久化画像、制品计划和只读报告；按软件、文档、表格、报告、维护任务选择流程、执行策略和验收策略；新增 DOCX/Markdown 与 XLSX/CSV/TSV 原生物化和结构/等价验证、Python pytest/unittest 识别、动态 Designer UI；服务端制品阶段不创建可写 Session 且不进入 Java 门禁；同步 README、架构、Designer、AI 角色、OpenCode、验证器合同与本公约正文 | 0.1.83/0.1.84 隔离验收分别发现服务端文档误入 Java 门禁、可复用 Python 转换脚本被 Markdown 输出字样误路由，修复后按版本规则顺延；最终 `./scripts/verify.sh` 使用 JDK 21.0.12 通过：Java 457 项（0 失败、0 错误、1 平台条件跳过），Vitest 176/176，BUILD SUCCESS；本地 JAR `target/opencode-loopper-0.1.85.jar` 大小 283124367 字节、含 108 个 SPA 静态条目，SHA-256 `79ee430788c21b57cb37756b89d26faafad766e7bc5cf607fa87a6ad252787bb`；隔离 18086 实测 DOCX/TABULAR_DATA 均为 1 Attempt、0 可写 Session、原生验证 PASS，报告为 0 Task/Session/Lease；隔离 18085 真实 Python OpenCode 实施后 SELF_CHECK/FILE_CONTENT/GIT_DIFF 全部 PASS，完整自动 Designer 到 Python Compiler 后因 OpenCode 1.18.18 修复输出缺失未完成；两个隔离实例均已停止，当前 8080 未替换 |
| 2026-08-19 | 全部 OpenCode Provider `RETRY` 同 Session 自恢复与 0.1.82 交付 | 将瞬态恢复语义扩展到 Designer、Decomposer、Compiler、Implementation、Judge、项目公约、提交建议和本地同步；不再创建新 Attempt/Judge、消耗 Loopper 重试预算、记录 Session 错误或将 `RETRY` 当成 writer 终态，原有超时保持不变；同步 README、架构、设计、OpenCode 合同与本公约正文 | 聚焦 RETRY/发布契约 Java 30/30、补充异步收束后退避与 Implementation 2/2，Vitest 176/176；首次完整验证的既有并发退避时序用例瞬时失败，单独复跑通过；第二次暴露新增用例完成状态触发的异步验证清库竞争，收束用例后最终 `./scripts/verify.sh` 使用 JDK 21.0.12 通过：Java 446 项（0 失败、0 错误、1 平台条件跳过），Vitest 176/176，BUILD SUCCESS；本地 JAR `target/opencode-loopper-0.1.82.jar` 大小 263372016 字节、含 108 个 SPA 静态条目，SHA-256 `be7c39f337e48bfe39546a4bb5322ddc1bf2fa75c17d91f597bd0bb8c4387b5d`；不替换当前运行实例 |
| 2026-08-18 | Designer Provider 瞬态过载恢复与 0.1.81 交付 | 整体需求、工作包和兼容 Designer 轮询不再把 OpenCode `RETRY` 当作终态；保留原远端 Session、流程 `RUNNING` 与全自动 `ACTIVE`，Provider 恢复后沿原上下文继续，真实失败合同不变；同步 README、架构、设计、OpenCode 合同与本公约正文 | 新增整体需求全自动推荐回答、工作包设计两条 `system cpu overloaded` 注入及恢复回归；聚焦 Designer/HTTP/发布契约 64/64 及新增工作包用例通过；首次 `./scripts/verify.sh` 的既有 FIFO 并发时序用例瞬时失败，单独复跑通过，源码不变的第二次完整验证使用 JDK 21.0.12 通过：Java 444 项（0 失败、0 错误、1 平台条件跳过），Vitest 176/176，BUILD SUCCESS；本地 JAR `target/opencode-loopper-0.1.81.jar` 大小 263371897 字节、含 108 个 SPA 静态条目，SHA-256 `2a7e111653d93194e48f1f297a948a010f5746d8f932a2437121fa8de38597f8`；发布目标为 `v0.1.81`，不替换当前运行实例 |
| 2026-08-18 | Designer 全自动模式与 0.1.80 累积交付 | V34 按会话持久化全自动状态；新建页和进行中页默认关闭并在每次启用时确认风险；Monitor 单步复用推荐回答、需求确认、逐包批准、最终确认和 Task Start 权威边界，阻断后停止重试，执行期及发布动作保持人工；同步 README、架构、设计合同与本公约正文 | 0.1.79 首次完整构建在新增 `AUTO_MODE` SSE 类型未同步时失败，修复后按版本规则顺延；聚焦迁移、Designer 自动全链路、前端类型/API/视图及 Playwright 2/2 通过；`./scripts/verify.sh` 使用 JDK 21.0.12 完整通过：Java 442 项（0 失败、0 错误、1 平台条件跳过），Vitest 176/176，BUILD SUCCESS；本地 JAR `target/opencode-loopper-0.1.80.jar` 大小 263371593 字节，SHA-256 `51103ed9e5287d50477e7a1344f0f452821c4cc42d75fd5f1446572997c211a3`，含 108 个 SPA 静态条目；发布目标为 `v0.1.80`，不替换当前运行实例 |
| 2026-08-18 | Designer 讨论与确定性校验卡片收束，准备 0.1.78（暂不发布） | 需求讨论按作用域和讨论修订独立分卡，通过持久化 `designMessageId` 固定在对应 Designer 设计稿之前且默认收起，历史无关联数据按同作用域修订顺序兼容回退；全部可见 Validator 记录合并为一个默认收起的确定性校验卡；同步 README、设计合同与本公约正文 | 前端类型检查通过，API/Designer 聚焦 56/56、后端 Designer/API 与发布契约聚焦测试通过；`./scripts/verify.sh` 使用 JDK 21.0.12 完整通过：Java 440 项（0 失败、0 错误、1 平台条件跳过），Vitest 173/173，BUILD SUCCESS；本地 JAR `target/opencode-loopper-0.1.78.jar` 大小 263353413 字节，SHA-256 `5f9fea2d655cef67787ece3ac9ad8d96453e28ccda655d465462d22bca3df27d`，含 107 个 SPA 静态条目；按用户要求不提交、不推送、不打标签、不发布，也未替换运行实例 |
| 2026-08-18 | 修复已确认完成任务仍无合并请求入口，准备 0.1.77（暂不发布） | 任务详情页与发布组件统一接受 `COMPLETED + executionResult=SUCCEEDED`，不再由外层条件提前卸载发布动作；GitLab 远端核对在乐观锁快照未变化且最新执行结果成功时继续接受 `COMPLETED`，保持执行终态与交付状态轴独立；同步 README 与本公约正文 | TaskDetail/Publication 前端聚焦 26/26、TaskPublication 后端集成通过；`./scripts/verify.sh`：Java 440 项中 439 通过、Windows 条件用例 1 项跳过，Vitest 172/172，BUILD SUCCESS；JAR 263352245 bytes、静态资源 107 项，SHA-256 `d5553bd77f288ce47eb59ea715b5c670a159866380d6159ff12e334f04c8476f`；按用户要求未提交、未推送、未打标签、未发布 |
| 2026-08-18 | SQLite 有界读模型、任务详情按需加载及三项历史/发布修复，准备 0.1.76（暂不发布） | 保持 SQLite + WAL；V33 增加任务、设计、Attempt、Session 和自动化热点索引；新增稳定游标分页和任务 summary/overview/audit、Designer 历史/消息、项目汇总、洞察、自动化工作区读模型，正文按 Task 归属延迟加载；项目 Git 检测使用 5 秒缓存与最多 4 路并发，路由取消全局任务/Runtime 初始化，详情分层及动态组件加载，SSE 分区合并刷新，响应压缩和 Inbox ETag 生效；同时恢复 `COMPLETED` 任务未结束的 MR 动作、精确 HTTP 白名单覆盖显式 HTTPS Web 地址、已确认设计只读历史；同步 README、架构、设计合同与本公约正文 | 聚焦读模型测试覆盖 1000 个任务、1000 个设计和重任务正文隔离，查询数固定为 summary 2、overview 4、audit 3、历史 1、项目 1、洞察 4、自动化 4，响应大小与索引计划通过；首次 `./scripts/verify.sh` 的既有本地同步并发断言发生时序失败，单独复跑通过；源码不变的第二次完整验证通过：Java 440 项（0 失败、0 错误、1 项平台条件跳过），Vitest 171/171；本地 JAR `target/opencode-loopper-0.1.76.jar` 为 263352343 bytes，含 107 个 SPA 静态条目，SHA-256 `fc0cbab6a3707b16cd740df73c68f1dfe510130109c5a86f4703c032385d3e48`；未执行代表性内网 Linux 墙钟 p95 验收，按用户要求未提交、未推送、未打标签、未发布，也未替换运行实例 |
| 2026-08-18 | Designer 已回答问题持久展示，交付 0.1.75 | Designer Session 新增服务端权威 `answeredQuestions` 投影；新决策日志保存问题、标题、选项说明和规范化最终回答，旧版文本问题日志兼容读取；Designer 页默认折叠为“需求讨论”，展开后展示完整问答；同步 README、设计合同与本公约正文 | 聚焦前端类型检查、API/Designer 55/55，后端 Designer 集成 36/36；首次 `./scripts/verify.sh` 的既有 FIFO 并发时序用例瞬时失败，单测复跑通过；源码不变的第二次完整验证通过：Java 434 项（0 失败、0 错误、1 平台条件跳过），Vitest 169/169；本地 JAR `target/opencode-loopper-0.1.75.jar` 大小 263248085 字节，SHA-256 `a08ecb01283327ca6b25d719fc8c790abf6485e1698ccf854115a3b62893e8cd`，含 102 个 SPA 静态条目；`v0.1.75` 指向 `e94a398fe43e32de9635218533ff9b56d63bc171`，Actions `32106781123` 首次因无关 `retryWaitBackoffIncreasesAndOnlyStartsWhenDue` 时序用例失败，同提交第二次构建全部成功；Release 四项资产齐全且独立下载校验均为 `OK`，远端 JAR 大小同为 263248085 字节、SHA-256 `e1436499090b26cd94227773c049cb954ecd883d3da67260a82d13de56537298`，含 102 个 SPA 静态条目及新问答历史类型；未替换当前 8080 运行实例 |
| 2026-08-18 | 设置、持久化退避、用户确认终态、Recovery、Runtime 精简与模式化 Thinking，交付 0.1.74 | V31 增加启动覆盖和持久化分类 `RETRY_WAIT`；V32 分离执行轮次结果与用户确认终态，支持同任务继续、继承修改派生、原始基线重做、只读审计和取消，并用私有 checkpoint 保留全部修改；Runtime 移除能力/权限说明卡，设置页超时控件对齐；Decomposer、Compiler 和 Judge 仅在 `JSON_SCHEMA` 步骤关闭 Thinking，`TEXT_MARKER` 初始、重试、回退和 finalizer 保留配置或 Provider 默认；同步 README、架构、设计、OpenCode、七特性合同与本公约正文 | 聚焦 Thinking/Designer/Judge 120/120、发布契约 9/9；`bash -n scripts/start-linux.sh` 通过；`./scripts/verify.sh` 通过：Java 434 项（0 失败、0 错误、1 项平台条件跳过），Vitest 169/169；本地 JAR `target/opencode-loopper-0.1.74.jar` 为 263241621 bytes，含 102 个静态入口/assets 及 V31/V32，SHA-256 `b2cc64ec31d747abc6c7dc50d8961e4b10b8190d42d7c28a34680afb84792982`；`v0.1.74` 指向 `66f9a34abb0efc961186b7071b691679d872a2f9`，Actions `32099938149` 的 Windows 脚本与 Release Job 均成功；Release 四项资产齐全，独立下载校验均为 `OK`，远端 JAR 同为 263241621 bytes、SHA-256 `d306013ddf873bd62bf37078bbcf2d9356084b24676734011336652f3cc65839`，可读取且含 102 个静态入口/assets 及 V31/V32；未在 macOS 实机执行 Windows 启动脚本，未替换当前 8080 运行实例 |
| 2026-08-17 | Compiler 工程元条件容错、批量语义诊断与 0.1.73 交付 | Compiler 合同升级为 `2026-08-semantic-v2`：未被聚焦测试显式覆盖的代码风格、源码/注解/装配形态、构建/测试结果和交付卫生确定性降为冻结设计中的工程元数据并重排证据；单一 Java 聚焦测试可补齐同 Stage 剩余业务条件映射；语义预检一次返回全部错误码与 JSON Pointer；源码搜索仍不得作为行为 `SELF_CHECK`；同步 README、AI 角色、架构、Designer、OpenCode 合同与本公约正文 | 聚焦 Java 64/64；`./scripts/verify.sh` 通过：Java 418 项（0 失败、0 错误、1 项平台条件跳过）、Vitest 163/163；JAR `target/opencode-loopper-0.1.73.jar` 为 263152436 bytes，含 102 个静态入口/assets，SHA-256 `0a9d6dedc1752f4c24869995f6f19450815c0cc6d7b57a1278c2e02b0a728c29`。复制现有 V30 数据后在隔离 18073 对真实失败会话 `7caeb31c-8497-4b84-a190-92ddb9bd424c` 的 WP-1 重编译：一次模型调用直接进入 `REVIEWING`，格式/语义修复均为 0、`server_compiled=1`，3 个 Java Stage 的 7 条业务条件分别由 `BaseEventTest`、`EventRegistryTest`、`EventBusTest` 聚焦覆盖；隔离 Loopper/OpenCode 均已关闭，18073 已释放，8080 保持 0.1.72/PID 64781、health `UP`；GitHub Release 结果待标签触发后回填 |
| 2026-08-17 | AI 角色轻量语义合同、服务端容错编译与 0.1.72 交付 | Decomposer 和 Compiler 只返回业务语义与证据意图，服务端派生状态、GC/WP/AC ID、需求引用、依赖、Designer 精确来源、测试目标和验证器关联并直接编译最终对象，不再请求 AI 抄写 final JSON；V30 分开持久化格式/语义修复计数和服务端编译标识，语义失败仅接受受限 JSON Patch 且补丁后重跑全合同；Judge 兼容唯一中英文判定/理由标签；新增 `docs/ai-role-contracts.md` 并同步 README、架构、Designer、OpenCode、验证器合同和本公约正文 | 聚焦修复及服务端编译测试通过；`./scripts/verify.sh` 通过：Java 414 项（0 失败、0 错误、1 项平台条件跳过）、Vitest 163/163；JAR `target/opencode-loopper-0.1.72.jar` 为 263143983 bytes，包含 Vue 静态资源，SHA-256 `b60f24271efb3a652d721ace6781a890218519accf08b9b5d95e2cf1c901bf5e`。隔离 18072 使用真实 DeepSeek/OpenCode 1.18.18 marker 模式完成两工作包到 `FINAL_REVIEW`：总模型调用 7 次，Decomposer 和 WP-2 Compiler 格式/语义修复均为 0，WP-1 的真实证据缺口经 1 次局部语义补丁后通过，三者均 `server_compiled=1`；指定的两个机械性错误码为 0，未创建 Task；隔离实例已关闭，8080 仍为 PID 93512 且 health `UP` |
| 2026-08-17 | 拆分历史设计页并修复 Designer 操作边界，交付 0.1.71 | 新建设计页移除历史列表；新增 `/designs` 项目/状态/归档筛选、时间排序、继续/修改/归档/恢复；V29 持久化可恢复归档且不删除快照，旧浏览器指针不得自动重开已归档设计；项目入口改到历史页；页头动作换行、双栏宽度归零并在 Review Gate 补充最终确认入口；同步 README、架构、设计合同与本公约正文 | 聚焦发布/迁移/Designer Java 41/41、前端相关 64/64；`./scripts/verify.sh` 通过：Java 403 项（0 失败、0 错误、1 项平台条件跳过）、Vitest 163/163；JAR `target/opencode-loopper-0.1.71.jar` 为 263101602 bytes，含 102 个静态入口/assets、V29 迁移、历史 DTO 和历史页资源，SHA-256 `8b6ac104ac1979318f26fe77ac046066779ae4dd9f8ad44c8a8be6dbe3c10713`；复制 V28 数据后用该 JAR 在隔离 18083 升级到 V29，health `UP`、`/designs` 200、历史接口返回 29 条且 Runtime 版本为 0.1.71，实例随后关闭且未替换 8080；历史页另在 1440×1000 与 1024×768 视觉检查无横向越界；`v0.1.71` 指向 `94582da1aaa82fc68c1618ffa87ef7320e94b100`，Actions `32001229598` 的 Windows 脚本与 Release Job 均成功；Release 已发布 JAR、两平台启动脚本及 `SHA256SUMS`，独立下载三项校验均为 `OK`，远端 JAR SHA-256 `f96710e379507facdc9f286efa2010c1703d2b51f7b34d72851ab8f5c30c42bb` |
| 2026-08-17 | AI 输出包容性解析、工具循环恢复与 0.1.70 交付 | Decomposer/Compiler/Judge 共用严格 JSON object 提取与可审计确定性规范化，项目公约兼容 marker/唯一 fence/整段 Markdown；V28 持久化纠正类别与每步骤一次 finalizer 资格；连续 3 次同工具同参数提前 abort 并无工具收口；纯验证元描述不生成业务验收项，全量测试仅可作补充报告；前端以普通信息样式展示规范化与恢复提示；同步 README、架构、设计、OpenCode、七特性合同与本公约正文 | 0.1.68 首次完整验证因发布脚本契约仍固定旧 JAR 名失败，0.1.69 再发现迁移测试仍断言 V27；均修正并按版本规则递增。聚焦验证：`mvn -Dtest=AiOutputExtractorTest,HttpOpenCodeClientTest,ProjectConventionServiceTest test` 后端 33/33、前端 159/159；`mvn -Dtest=DesignerSessionMcpIntegrationTest,TaskServiceIntegrationTest test` 后端 95/95、前端 159/159；`mvn -Dtest=FeatureMigrationTest,ReleasePackagingContractTest test` 后端 10/10、前端 159/159。完整 `./scripts/verify.sh` 通过：Java 402 项（跳过 1）、前端 159 项；JAR `target/opencode-loopper-0.1.70.jar` 为 263090915 bytes，SHA-256 `6fade8fe70745872ce7e4b4dcf1523337cbf41979ba94f0fd1802e7cd3d29484`，含 `BOOT-INF/classes/static/index.html` 与静态 assets。隔离实例在 18080 使用真实 DeepSeek/OpenCode 1.18.18 `TEXT_MARKER` 模式完成需求讨论、DIRECT_DESIGN 拆解、WP-1 设计/编译/批准与确定性聚合并进入 `FINAL_REVIEW`：模型调用 7/96，Decomposer 格式修复 0、最终修复 0、规划语义修复 1，Compiler 规划/最终修复均为 0、格式回退 0、工具循环 finalizer 0，`OUTPUT_MARKERS_MISSING` 消息 0，持久化 3 条 `NORMALIZED` 审计；未创建 Task，验证后已关闭隔离 Loopper/OpenCode，现有 8080 仍为 0.1.67/PID 38300。标签 `v0.1.70` 指向 `e13df26376b8565cf8ceb10a483bba09e1a3ca00`；GitHub Actions `31997058481` 的 Windows 脚本校验与 Release 两项 Job 均成功；Release `https://github.com/wangyufengsky/opencode-loopper/releases/tag/v0.1.70` 已发布 JAR、两平台启动脚本与 `SHA256SUMS`。独立下载后全量校验通过，远端 JAR 为 263090915 bytes、SHA-256 `cdf9913c821a41abd028647524e81b6928eee842f561d1792ec9c0956d8a28b6`，可读取且包含 `BOOT-INF/classes/static/index.html` 与 99 个静态 assets |
| 2026-08-17 | 修复 Designer 成功操作误报 JSON 截断 | 前端公共 API transport 兼容任意 2xx 空响应；需求确认/重开与工作包接受回归测试改用后端真实的空 `202`；同步设计合同与本公约正文 | 聚焦 API client 34/34、完整 Vitest 158/158、前端类型检查通过；按用户要求不打包、不推送、不发布，版本保持 0.1.67 |
| 2026-08-17 | 项目任务/设计恢复、Designer 多轮讨论与逐包确认，交付 0.1.67 | 项目页分别投影历史任务和待继续设计；V27 持久化需求/工作包完整讨论快照、问题答案、候选和批准修订；Designer 强制先提问，需求确认后才拆包，每包讨论、Compiler/Validator 同步、明确批准后才推进，重开只失效传递依赖；新增四步进度、作用域、工作包导航、推荐答案、同步状态和最终确认；同步 README、架构、设计、OpenCode 合同与本公约正文 | 聚焦 Java 41/41、Vitest 62/62、Playwright 完整两包路径 1/1；`./scripts/verify.sh` 通过：Java 390 项（0 失败、0 错误、1 项 Windows 条件用例在 macOS 跳过），Vitest 158/158；JAR `target/opencode-loopper-0.1.67.jar`（263057730 bytes）内含 100 个前端静态条目、V27 和讨论快照类，SHA-256 `2645200dec65d6e3815de000ebf6adc37ed4814f76180286c28a34fc60552928`；Java 21 隔离端口 18097 首次启动和同数据目录重启均 health `UP`/Flyway v27，未确认 Designer 会话重启后仍由服务端找回，项目投影保持 Task 0/待继续设计 1，端口随后释放且未替换 8080；发布目标：`v0.1.67` |
| 2026-08-14 | 修复异步 Schema busy 盲区并稳定 DeepSeek 机器输出，准备本地测试 0.1.66 | 异步 2xx 不再标记结构化能力成功；机器角色在 busy 期间读取消息并识别 Schema 400、StructuredOutput 工具错误及超过 24 步；OpenCode 1.18.12–1.18.18 直接 marker，后续版本恢复探测；受管 agent 使用零温度和禁止重复工具指令；同步 README、Designer/OpenCode 合同与本公约正文 | 聚焦 Http adapter、Runtime 能力与 Designer 回退 48/48、Vitest 151/151；`./scripts/verify.sh` 通过：Java 383 个（0 失败、0 错误、1 个 Windows 专属用例在 macOS 跳过），Vitest 151/151；JAR `target/opencode-loopper-0.1.66.jar`（263023144 bytes）内含 101 个前端静态条目及新 Runtime 类，SHA-256 `f7289cf67486e7dc47484b9e735d0d0b244aa31f6f3c78deab43dd6e03aa1a27`；OpenCode 1.18.18 + DeepSeek 最小 Schema 实测在消息读取时返回 `Expected OutputFormatJsonSchema` 400；替换前 SQLite `quick_check=ok` 并备份至 `data/backups/loopper-before-0.1.66-20260814T180000.db`；本机 Loopper PID 94044 监听 8080、health `UP`，Runtime 报告 0.1.66/OpenCode 1.18.18 `AVAILABLE`（受管 PID 94058）并默认 `TEXT_MARKER`，实际首页哈希与 JAR 一致；保留既有未提交工作，未提交、未推送、未打标签、未发布 |
| 2026-08-14 | 阻止 Compiler 冻结 allowed/forbidden 自相矛盾的路径合同，准备本地测试 0.1.65 | Stage 与显式 `GIT_DIFF` 共用运行期路径匹配语义；非法 glob 和被单条禁止规则完整覆盖的允许规则进入 Compiler 规划修复，并在草稿保存/确认时再次 fail closed；合法宽允许+窄排除保持兼容；同步 README、架构、设计、OpenCode、七特性合同与本公约正文 | 聚焦路径/验证器 45/45、Compiler 集成 10/10；`./scripts/verify.sh` 通过：Java 379 个（0 失败、0 错误、1 个 Windows 专属用例在 macOS 跳过），Vitest 151/151；JAR `target/opencode-loopper-0.1.65.jar`（263020636 bytes）内含 100 个前端静态资源及 `VerifierPathPolicy.class`，SHA-256 `4b9c3ac9aa2d626055ef55fd0aa6a2a4b044aaa2161d0f936b593383520f6da2`；本机 PID 60918 监听 8080、health `UP`，Runtime 报告 0.1.65/OpenCode 1.18.12 `AVAILABLE`（受管 PID 60972）；真实 validate API 拒绝原 event/bridge 冲突并接受宽 allow+窄 exclusion；保留既有未提交工作，未提交、未推送、未打标签、未发布 |
| 2026-08-14 | 修复结构化角色瞬态重试错判、格式回退与孤儿 Session，准备本地测试 0.1.64 | Decomposer/Compiler 不再把 OpenCode `RETRY` 当作设计传输失败，Implementation/Judge 保持既有升级合同；消息读取阶段的 Schema 拒绝进入全新 marker Session 回退；终态失败尽力 abort；受管机器响应角色使用 24 步私有 agent；同步 README、架构、设计、OpenCode 合同与本公约正文 | 聚焦 Http adapter、Runtime、Designer 和 Task/Judge 107/107、Vitest 151/151；`./scripts/verify.sh` 通过：Java 375 个（0 失败、0 错误、1 个 Windows 专属用例在 macOS 跳过），Vitest 151/151；JAR `target/opencode-loopper-0.1.64.jar`（263015527 bytes）内含 100 个前端静态资源，SHA-256 `8d1e06697e5dfb31719cfa03ab640017938d9ade7ab65c39432680df2dc33fa3`；本机 PID 38632 监听 8080、health `UP`，Runtime 报告 0.1.64/OpenCode 1.18.12 `AVAILABLE`（受管 PID 38720），`loopper-structured` agent、入口与主 JS 均匹配 JAR；按用户要求未提交、未推送、未打标签、未发布 |
| 2026-08-14 | 为结构化角色关闭 Thinking，准备本地测试 0.1.62 | Decomposer、Compiler 与最终双 Judge 使用 `thinking=false`；受管 DeepSeek 为当前模型注入并选择 `loopper-no-thinking` 私有 variant；Markdown Designer 与 Implementation 保留既有推理配置；同步 README、架构、设计、OpenCode 合同与本公约正文 | 聚焦 Http adapter、Runtime、Designer/Compiler 与 Task/Judge 103/103 通过；`./scripts/verify.sh` 通过：Java 371 个（0 失败、0 错误、1 个 Windows 专属用例在 macOS 跳过），Vitest 151/151；JAR `target/opencode-loopper-0.1.62.jar`（263014471 bytes）内含 100 个前端静态资源，SHA-256 `d449d795ab9a3fca96ec9355de940ebcb149bace1156036194e75d3ca9aa9e0c`；本机 PID 13215 监听 8080、health `UP`，Runtime 报告 0.1.62/OpenCode 1.18.12 `AVAILABLE`（受管 PID 13283），真实 Provider 投影含 `loopper-no-thinking -> thinking.type=disabled`，入口、Designer、Tasks 与主 JS 均匹配 JAR；按用户要求未提交、未推送、未打标签、未发布 |
| 2026-08-14 | 将计划确认与执行期工作区准备解耦，准备本地测试 0.1.61 | 确认计划只创建无队列、租约、分支和 Session 的 `PENDING_START` Task；首次执行请求才原子入队/竞争租约，并在接纳后 fetch、创建或切换任务分支及自动开始 Stage；待开始与排队任务均可直接取消；同步 README、架构、设计、七特性合同与本公约正文 | 聚焦 TaskService 65/65、发布契约 7/7、Runtime 3/3 通过；`./scripts/verify.sh` 通过：Java 371 个（0 失败、0 错误、1 个 Windows 专属用例在 macOS 跳过），Vitest 151/151；JAR `target/opencode-loopper-0.1.61.jar`（263013278 bytes）内含 100 个前端静态资源，SHA-256 `3be7f4063dac437f9b48981e78ef317d6642bf07d5ab9d79468cbf3949f8d533`；本机可见 Terminal 运行 PID 93432、health `UP`、Runtime 报告 0.1.61/OpenCode 1.18.12 `AVAILABLE`（受管 PID 93878），入口、`/tasks` 深链与主 JS 哈希均匹配 JAR；按用户要求未提交、未推送、未打标签、未发布 |
| 2026-08-14 | 修复 `READY` 待执行任务无法直接取消并准备本地测试 0.1.60 | 任务详情同时提供开始与确认取消；取消文案明确尚未启动 Session，并保留分支、执行目录和证据；同步 README、架构、设计合同与本公约正文 | 聚焦 TaskDetail Vitest 13/13、既有 READY 后端取消集成测试 1/1 通过；`./scripts/verify.sh` 通过：Java 370 个（0 失败、0 错误、1 个 Windows 专属用例在 macOS 跳过），Vitest 151/151；JAR `target/opencode-loopper-0.1.60.jar`（263011846 bytes）内含 100 个前端静态资源，SHA-256 `4cb5dabca0f331e9c2b504ba3795208136d846107552d508aeb01db7018f43cc`；本机 Terminal 运行 PID 66470、health `UP`、Runtime 报告 0.1.60/OpenCode `AVAILABLE`，入口与 TaskDetail 静态资源哈希均匹配 JAR；按用户要求未提交、未推送、未打标签、未发布 |
| 2026-08-14 | 复用 OpenCode 角色权限、结构化输出、能力发现和实施 Todo，交付 0.1.59 | V26 持久化机器响应模式/schema 与实施 Todo 能力；五类 JSON Schema 在原预算内安全回退 marker；Runtime 展示 agent/plan/structured 能力但 Designer 不接管原生 plan；实施 Todo 有界同步且保持非权威；同步 README、架构、设计、OpenCode、七特性合同与本公约正文 | 聚焦 Designer/Compiler/Judge/Runtime/Todo/迁移后端测试和 SessionMonitorPanel 4/4 通过；`./scripts/verify.sh` 通过：Java 370 个（0 失败、0 错误、1 个 Windows 专属用例在 macOS 跳过），Vitest 150/150；JAR `target/opencode-loopper-0.1.59.jar`（263011804 bytes）内含 100 个前端静态资源，SHA-256 `d92b1849703deb7fbabb99656fe88107a54aab117744aa9922b9ca0217b82191`；发布目标：`v0.1.59` |
| 2026-08-14 | 稳定 NTFS Direct 根身份验收并交付 0.1.58 | Direct 指纹合同仍为 canonical path、file key 与 creation time 且不写用户目录标记；测试在 NTFS 同名重建发生元数据隧道碰撞时显式推进临时目录 creation time；同步 README、架构与本公约正文 | 注入 `core.autocrlf=true`/`core.safecrlf=warn` 的聚焦 Java 63/63、Vitest 149/149；`./scripts/verify.sh` 通过：Java 367 个（0 失败、0 错误、1 个 Windows 专属用例在 macOS 跳过），Vitest 149/149；JAR `target/opencode-loopper-0.1.58.jar`（262947774 bytes）内含 100 个前端静态资源，SHA-256 `40b7dcc029240065219119d110b90e389bd1e20f39d0bd313bd5c74493033863`；发布目标：`v0.1.58` |
| 2026-08-14 | 修正 Windows 源文件 Git 模式识别并交付 0.1.57 | Windows 已跟踪文件模式从源仓库 Git index 读取，未跟踪普通文件固定为 `100644`，不再把 NTFS ACL 可执行性误判为 Git `100755`；POSIX 保留真实执行位；同步 README、架构与本公约正文 | 注入 `core.autocrlf=true`/`core.safecrlf=warn` 的聚焦 Java 57/57、Vitest 149/149；`./scripts/verify.sh` 通过：Java 367 个（0 失败、0 错误、1 个 Windows 专属用例在 macOS 跳过），Vitest 149/149；JAR `target/opencode-loopper-0.1.57.jar`（262947774 bytes）内含 100 个前端静态资源，SHA-256 `f5bd0005dd6e8ae0ae4b94e1cb65a48e96d80d316c518f1f023e878ea7f38ee1`；CI 中本地同步 Windows 回归通过，Ubuntu/macOS 全套通过，但 Windows 因 NTFS 同名重建测试的元数据隧道碰撞失败；Release 同步失败；标签 `v0.1.57` |
| 2026-08-14 | 扩大跨 Git 版本的独立文本合并夹具并交付 0.1.56 | 自动合并测试的源/任务修改之间保留充足未改动行以排除 xdiff hunk 边界差异；Windows CI 随后定位到独立的源文件模式误判，真实修复进入 0.1.57 | 注入 `core.autocrlf=true`/`core.safecrlf=warn` 的聚焦 Java 56/56、Vitest 149/149；`./scripts/verify.sh` 通过：Java 366 个（0 失败、0 错误、1 个 Windows 专属用例在 macOS 跳过），Vitest 149/149；JAR `target/opencode-loopper-0.1.56.jar`（262946385 bytes）内含 100 个前端静态资源，SHA-256 `fd370d2031ca10167f1bb9371f5aa9a58fe1340f355b66b8cd04249b09575cf0`；CI Ubuntu/macOS 通过，Windows 因 NTFS ACL 模式误判失败；Release 工作流成功；标签 `v0.1.56` |
| 2026-08-14 | 修正 Windows 首次 clone 与精确文本夹具并交付 0.1.55 | 远端夹具在 clone 命令生效前固定 `core.autocrlf=false`；测试仓库以 `.gitattributes` 固定 Markdown/TXT 或 README LF，避免后置配置制造脏工作树/伪冲突；同步 README、架构与本公约正文 | 注入 `core.autocrlf=true`/`core.safecrlf=warn` 的聚焦 Java 56/56、Vitest 149/149；`./scripts/verify.sh` 通过：Java 366 个（0 失败、0 错误、1 个 Windows 专属用例在 macOS 跳过），Vitest 149/149；JAR `target/opencode-loopper-0.1.55.jar`（262946382 bytes）内含 100 个前端静态资源，SHA-256 `66c03a394cf90aa6d586cf4a6c94f29c77bd3c6408d676bdc0b655af21ce860c`；发布目标：`v0.1.55` |
| 2026-08-14 | 完成首批审查交付的 Windows CI 夹具隔离并交付 0.1.54 | 临时 Git 仓库固定 `core.autocrlf=false`，避免 runner 全局配置改变精确文本；裸 `ProcessBuilder("mvn")` 的合并夹具限定 POSIX，Windows 产品解析由专门契约测试覆盖；同步 README、架构与本公约正文 | 聚焦 Java 56/56、Vitest 149/149；`./scripts/verify.sh` 通过：Java 366 个（0 失败、0 错误、1 个 Windows 专属用例在 macOS 跳过），Vitest 149/149；JAR `target/opencode-loopper-0.1.54.jar`（262946382 bytes）内含 100 个前端静态资源，SHA-256 `c5a14e54e635b05e21caf5c7b9651db01482b746b71bb90c61b440055f609c06`；发布目标：`v0.1.54` |
| 2026-08-14 | 修复首批审查交付的 Windows CI 可移植性并交付 0.1.53 | Git NUL 输出局部关闭 CRLF 安全警告；Stage 基线清理兼容 Windows 只读对象；artifact 路径稳定使用 `/`；平台专属测试与 Git fixture 明确操作系统和换行边界；同步 README、架构与本公约正文 | 聚焦 Java 80/80、Vitest 149/149；`./scripts/verify.sh` 通过：Java 366 个（0 失败、0 错误、1 个 Windows 专属用例在 macOS 跳过），Vitest 149/149；JAR `target/opencode-loopper-0.1.53.jar`（262946383 bytes）内含 100 个前端静态资源，SHA-256 `a03d502982a17358ce7eccd9847a9ef7e02387af6bbfa55b5b38d202ea83b0b0`；发布目标：`v0.1.53` |
| 2026-08-14 | 修复首批代码审查问题并交付 0.1.52 | 外部 Git/Provider/验证器/模型发现 I/O 与 SQLite 短事务分离；Task/Stage/队列/草稿确认原子持久化；项目公约增加可恢复 `APPLYING`；发布与本地同步使用固定条带锁；恢复 main/PR 三平台 CI 并固定 Actions SHA；`VERIFYING` 受 Task 总时限约束，损坏分包上下文在写 Session 前失败关闭；同步 README、架构与本公约正文 | 聚焦 Java 96/96、Vitest 149/149；`./scripts/verify.sh` 通过：Java 364 个（0 失败、0 错误、1 个 Windows 条件用例在 macOS 跳过），Vitest 149/149；JAR `target/opencode-loopper-0.1.52.jar`（262946017 bytes）内含 101 个前端静态资源，SHA-256 `0e4535be74efaf9b70e8771165bc1bb5639a17b5fa4b7aefee4a5cd4fef89128`；发布目标：`v0.1.52` |
| 2026-08-14 | 修复终态 holder 阻塞 FIFO 队列并交付 0.1.51 | 新增统一租约协调服务，复用于取消/Session 清理、启动恢复、10 秒后台检查、手动检查和归档前置；新增 holder 阻塞投影与本地 UI 重新检查入口；活动 holder 禁止归档/删除；同步 README、架构、设计、七特性合同与本公约正文 | 聚焦队列/租约并发测试通过；`./scripts/verify.sh`：Java 360 项中 359 通过、Windows 条件用例 1 项跳过，Vitest 149/149，BUILD SUCCESS；JAR `target/opencode-loopper-0.1.51.jar` 262939806 bytes，SHA-256 `5575c8ff79fae533493ef54ef07c4ebaeb4f56e3dbdc5a0fc1753bc582e138f8`，含 100 项前端静态资源；发布目标：`v0.1.51` |
| 2026-08-13 | 隔离多包 Stage 差异验收并交付 0.1.50 | V25 持久化 Stage 私有工作区基线；普通可写 Stage 的显式 `GIT_DIFF` 与 Attempt handoff 改按 Stage 首次执行前基线比较，重试/重启复用，旧活动 Stage 缺失时 fail closed；`VERIFY_ONLY` 与最终 Task 差异保留任务基线；同步 README、架构、七特性合同与本公约正文 | 聚焦 Stage 基线、Git/Direct、多包范围、重试、Recovery 与迁移 Java 17/17；`./scripts/verify.sh`：Java 353 项中 352 通过、Windows 条件用例 1 项跳过，Vitest 145/145，BUILD SUCCESS；JAR `target/opencode-loopper-0.1.50.jar` 262923926 bytes，SHA-256 `c3f7040cc9c1a9f3438f5f3126eae75b5021f25b13cfe1b4fe808a95042fd324`，含 101 项前端静态资源；隔离 PID 87990 在 18081 以 V25 完成两阶段验收，Stage 差异分别仅含 `first.txt`/`second.txt`，最终 Task 差异同时含二者，临时实例已停止且原 8080 PID 55908 未变；发布目标：`v0.1.50` |
| 2026-08-13 | 修复 Designer 分包映射往返并交付 0.1.49 | 前端 LoopSpec 解析、保存和确认无损保留每个 Stage 的 `workPackageId`；服务端冻结已聚合草稿的包映射，并在确认时校验所有完成包均按依赖顺序被 Stage 表示，阻止分包 Task 静默退化为扁平 Stage；同步 README、架构、Designer 合同与本公约正文 | 聚焦 Java 30/30、Vitest 145/145；`./scripts/verify.sh`：Java 342 项中 341 通过、Windows 条件用例 1 项跳过，Vitest 145/145，BUILD SUCCESS；JAR `target/opencode-loopper-0.1.49.jar`，262905553 bytes，SHA-256 `bcd177195f069d71e180e4e73bcb459a4926e1d6a0dcea4c61def622961f2689`，含版本 0.1.49 与 99 项前端静态资源；按用户要求未替换或重启当前 8080 本机实例 |
| 2026-08-13 | 强化 Compiler 聚焦 Java 单测强合同并交付 0.1.48 | Designer 明确列出的单测命令/测试类作为强制证据清单进入 Compiler 首次规划与修复提示；服务端只从安全的 `-Dtest`/`-Dit.test`/`--tests` 显式选择器提取目标，并在同 Stage 唯一匹配时补齐 `testCommand`、`testTargets`、`criterionIds` 或等价 TEST 验证器；歧义与真实缺失继续由权威校验阻断；同步 README、架构、Designer/OpenCode 合同与本公约正文 | 聚焦 Compiler/命令策略/版本/打包 Java 33/33；首次完整验证被系统 Chrome 151 卡在 Playwright `newContext`，终止后显式使用本机 Playwright Chromium 重跑；最终 `./scripts/verify.sh`：Java 342 项中 341 通过、Windows 条件用例 1 项跳过，Vitest 144/144，BUILD SUCCESS；JAR `target/opencode-loopper-0.1.48.jar`，262904191 bytes，SHA-256 `ae5f677af1d3b959298f65fde1e2eeac585acfd41dd00e02cc7e98075f3795c9`，含版本 0.1.48 与前端静态资源；按用户当前要求未替换或重启 8080 本机实例 |
| 2026-08-13 | 修正分包 Compiler 的前置包语义与机械字段失败，交付 0.1.47 | 后续包 Designer/Compiler 显式接收已完成前置包的冻结摘要/交接合同，不得把执行前基线缺少未落地交付物误判为 `MISSING_SCOPE`；服务端确定性规范验收 ID、唯一可恢复的原文片段和同命令 TEST 的重复映射字段，语义规划及权威 v2 校验不变；同步 README、架构、Designer/OpenCode 合同与本公约正文 | 聚焦 Designer/MCP Java 20/20、版本/运行环境/打包契约 Java 30/30 与 Vitest 144/144 已通过；完整 `./scripts/verify.sh` 通过（Java 339，失败 0、错误 0、按平台跳过 1；Vitest 144/144）；JAR `target/opencode-loopper-0.1.47.jar`，262898928 bytes，SHA-256 `f7fe92c1906304d24beef1565fda23ae55b76ab1fe3d760bdb0c0a848bce2e19`，含版本 0.1.47 与前端静态资源；GitHub Release 待标签触发；按用户要求不替换或重启当前本机实例 |
| 2026-08-13 | 加固 Decomposer marker 丢失兼容并发布 0.1.46 | Decomposer 规划与最终 JSON 在精确 marker 外只接受一个完整裸 JSON object 或单独 `json` 代码块，继续执行全部确定性校验；运行环境页展示服务端 Runtime DTO 返回的 Loopper 版本，区别于 OpenCode CLI 版本；同步 README、架构、设计、OpenCode、七特性合同与本公约正文 | 聚焦 Runtime/Designer Java 22/22、Vitest 144/144 与前端构建通过；`./scripts/verify.sh`：Java 338 项中 337 通过、Windows 条件用例 1 项跳过，Vitest 144/144，BUILD SUCCESS；JAR 262892861 bytes，SHA-256 `cdf52a8f2b5b075fb5bafd5b06a99c17dcfdb07ac39908c29fa5f02080539bed`；发布目标：`v0.1.46`；按用户要求不替换或重启当前本机 0.1.45 实例 |
| 2026-08-10 | 初始化根目录 Agent 公约 | 新增强制预读、结束更新、完整验证、单 JAR 打包和项目契约地图 | `./scripts/verify.sh`：Java 222/222、Vitest 114/114，BUILD SUCCESS；JAR 262557087 bytes，SHA-256 `84e40ee48a61a985877ec2d06cd49144e043327f9f4db805adc55065f0986dcf` |
| 2026-08-10 | Maven PROCESS 参数容错规范化 | 明确可解析的合并 Maven 参数直接规范化，只有无法安全解析时才触发 Designer 自动纠正；同步 README、Designer/OpenCode 合同 | `./scripts/verify.sh`：Java 225/225、Vitest 114/114，BUILD SUCCESS；JAR 262561953 bytes，SHA-256 `761515a69dc8792433e157ca15b04b05e26a2d359d55c4650a601db61372694c` |
| 2026-08-10 | 发布稳定版 0.1.1 | 规定每次形成新交付 JAR 必须递增版本号；新增 `v<version>` 标签校验、完整验证和 GitHub Release 自动发布合同 | `./scripts/verify.sh`：Java 225/225、Vitest 114/114，BUILD SUCCESS；JAR 262561925 bytes，SHA-256 `f3fc9611be5f4afaec48ccc5a035695c27f3c910c1dabd84ae16ca99963be10e`；发布目标：`v0.1.1` |
| 2026-08-11 | 隔离分支使用任务名并处理重名 | 工作区契约改为 `loopper/<任务名>`；同名任务追加中文次数，非法字符规范化并限制 UTF-8 长度；同步 README、架构与 0.1.2 发布路径 | `./scripts/verify.sh`：Java 226/226、Vitest 114/114，BUILD SUCCESS；JAR 262564155 bytes，SHA-256 `b827de6ad36c8327146d9ae5fe20a6eff18778a87ff2746305047dd2acdc540e`；发布目标：`v0.1.2` |
| 2026-08-11 | 修复 Linux Release CI（第一轮） | 固定 Chrome 发现优先级、强化 Direct 指纹并隔离测试调度；同步 README、架构、验证器与 Recovery 契约及 0.1.4 发布路径 | 本地 `./scripts/verify.sh`：Java 229/229、Vitest 114/114，BUILD SUCCESS；JAR 262564882 bytes，SHA-256 `1ee8e6b1697582070122f1a7f6c96e8c16036de21e7dd824f1c99d2e5550170d`；`v0.1.4` Release Action 因启动恢复读取共享测试数据失败 |
| 2026-08-11 | 隔离测试启动恢复并发布 0.1.5 | 将 Task、本地同步和自动化的 ApplicationReady 恢复集中到可配置 Coordinator；测试关闭自动恢复并保留显式恢复入口 | `./scripts/verify.sh`：Java 231/231、Vitest 114/114，BUILD SUCCESS；JAR 262565774 bytes，SHA-256 `6cc646f9764885fdd19782fadca4be4d3ac286bed44715ab6e89d0e9c66da778`；发布目标：`v0.1.5` |
| 2026-08-11 | 隔离 SSE 断线与运行生命周期并发布 0.1.6 | Task 事件提交后发布，逐订阅隔离浏览器断线与已关闭 `AsyncContext`；展示层异常不再触发 Session 清理；同步 README、架构和设计契约 | 聚焦验证：Java 43/43、Vitest 114/114；`./scripts/verify.sh`：Java 239/239、Vitest 114/114，BUILD SUCCESS；JAR 262570171 bytes，SHA-256 `7d7d50a9db090cfee2223b5c88719d61d26d19a7035fcb16b4ee8346dd8c5e7a`；发布目标：`v0.1.6` |
| 2026-08-11 | Attempt 交接、停滞检测与 0.1.7 发布 | 验证失败固化有界交接证据；可靠无进展达到阈值后等待人工“继续一轮”，且每轮使用全新 Session；同步 README、架构、设计、OpenCode 和七特性合同 | 聚焦验证：Java 60/60、Vitest 116/116；`./scripts/verify.sh`：Java 244/244、Vitest 116/116，BUILD SUCCESS；JAR 262586349 bytes，SHA-256 `c4f27c123574fd663901b9af0803681bd3304c2105090ae09bb31aafc292524e`；发布目标：`v0.1.7` |
| 2026-08-11 | 修复 LoopSpec 往返、Attempt 重试/指纹、等待动作和分支截断并发布 0.1.9 | 完整保留重试策略；人工继续 fail-closed 并复用模板；服务端投影当前等待动作；截断后重验 Git 结尾 | 聚焦验证：Java 52/52、MCP Java 14/14、Vitest 119/119；`0.1.8` 首次完整构建 249/250，因遗留 MCP 版本断言失败后按规则递增；`./scripts/verify.sh`：Java 250/250、Vitest 119/119，BUILD SUCCESS；JAR 262591570 bytes，SHA-256 `9a2e0c073794c9b9e4ff7a286c5ef795dfc3ba0c4c558e60f52d0b1493ac7e33`；18089 隔离运行 health、LoopSpec 和 Task DTO 验收通过；发布目标：`v0.1.9` |
| 2026-08-11 | 修复内网远端基线与 worktree 执行隔离并发布 0.1.10 | 创建任务前非交互 fetch 线性远端基线且不移动源分支；拒绝嵌套 worktree；核验 OpenCode Session 目录并阻断运行期 Git 提交/引用变更；同步 README、架构和 OpenCode 合同 | 聚焦验证：Java 21/21、Vitest 119/119；`./scripts/verify.sh`：Java 253/253、Vitest 119/119，BUILD SUCCESS；JAR 262595222 bytes，SHA-256 `562dc640aab9f129282963acb8b2a5a20b8fb2ece2f4263ea3983361232e1458`；发布目标：`v0.1.10` |
| 2026-08-11 | 修复 Windows 大仓库 worktree 检出失败并发布 0.1.11 | worktree checkout 改用独立 10 分钟边界、`--quiet` 和命令局部长路径支持；错误保留尾部 fatal；同步 README 与架构合同 | 聚焦验证：Java 6/6、Vitest 119/119；`./scripts/verify.sh`：Java 255/255、Vitest 119/119，BUILD SUCCESS；JAR 262595536 bytes，SHA-256 `465476c3e307ee5a290a24997da3c31993e764aa21c06c94cbdc1357686c0408`；发布目标：`v0.1.11` |
| 2026-08-11 | 新增 Windows BAT 启动器并发布 0.1.12 | Windows 脚本校验 Java 21、确认或启动 OpenCode loopback 服务后启动 Loopper；Release 同步发布 BAT 并纳入 SHA256SUMS | 聚焦验证：Java 16/16；`./scripts/verify.sh`：Java 257/257、Vitest 119/119，BUILD SUCCESS；JAR 262595537 bytes，SHA-256 `772b65fe4159f91ffc3e687e25f837485214b25b664aac54f9c8e53bfcef0e86`；发布目标：`v0.1.12` |
| 2026-08-11 | 修复 Windows OpenCode 启动成功误报与演示模式无法退出并发布 0.1.13 | BAT 以 `/global/health` 为启动权威结果；设置页支持退出演示数据并重新加载真实 API，启用提示不再写入 Runtime 错误 | 聚焦验证：Java 16/16、演示切换 Vitest 11/11；`./scripts/verify.sh`：Java 257/257、Vitest 121/121，BUILD SUCCESS；JAR 262595766 bytes，SHA-256 `cd686f1dec075f9034cb10ae20fa2bc302fe2586c54cfae0031b1681b38b0cfd`；发布目标：`v0.1.13` |
| 2026-08-11 | 原项目目录任务分支与 AgentBridge 同目录执行并发布 0.1.14 | 新任务串行切换登记项目的 `loopper/*` 分支；IDE AgentBridge、OpenCode 和验证器统一根目录；脏目录拒绝切换，发布后释放租约；同步 README、架构、OpenCode 与七特性合同 | 聚焦验证：Java 80/80、Vitest 121/121；`./scripts/verify.sh`：Java 260/260、Vitest 121/121，BUILD SUCCESS；18080 有远程/无远程真实 Git E2E 均成功，PROCESS 证据目录等于登记根目录，发布分别为 `PUSHED`/`SYNCED_LOCAL`；JAR 262597940 bytes，SHA-256 `719ba8def087a0f83c9c4ec765b5526c91f5947f3955f36847af341940c3023d`；发布目标：`v0.1.14` |
| 2026-08-12 | 等待输入任务直接取消并发布 0.1.15 | 任务详情为 `WAITING_INPUT` 保留带确认的取消入口；取消继续保留目录、分支与证据；同步 README 和设计契约 | 聚焦验证：TaskDetail Vitest 6/6、版本/MCP Java 16/16；`./scripts/verify.sh`：Java 260/260、Vitest 122/122，BUILD SUCCESS；JAR 262597915 bytes，SHA-256 `0ad3b1ba48e18de13170d18268845ad26cfd2848ae95bf7e46ad5b885612be42`；发布目标：`v0.1.15` |
| 2026-08-12 | 提交后恢复源分支并发布 0.1.16 | 持久化任务开始前分支；提交后恢复源分支并转交队列；推送、重试和 MR 只按任务分支引用，不切换项目分支；同步 README 与架构契约 | 聚焦验证：Git/发布 Java 13/13、Vitest 122/122；`./scripts/verify.sh`：Java 260/260、Vitest 122/122，BUILD SUCCESS；JAR 262600078 bytes，SHA-256 `6d4bafc99adb42dc3e95af9af61d61244ef319bdcee056d98b5e05fbbe7604f6`；发布目标：`v0.1.16` |
| 2026-08-12 | 启动脚本动态发现 OpenCode 端口第一轮 0.1.17 | Linux/Windows 启动器从当前 `opencode serve --port` 进程提取并健康验证真实端口；无可复用实例时切换 auto 动态端口；同步 README 与 OpenCode 合同 | 聚焦验证：Java 16/16、Vitest 122/122；`./scripts/verify.sh`：Java 260/260、Vitest 122/122，BUILD SUCCESS；Linux 隔离运行发现 64964 且 Runtime 为 `AVAILABLE`/`managed=false`；JAR 262600078 bytes，SHA-256 `9ea799cbe264f2449d4583cac12fde273004409096f05c5c26c7b8de363e250e`；`v0.1.17` Windows 校验因 BAT 正则中的未转义管道符失败，未生成 Release |
| 2026-08-12 | 修复 Windows 端口发现解析并发布 0.1.18 | 去除 PowerShell 正则中会被 CMD 当作管道的未转义 `|`；Windows `--validate` 真实执行端口发现语句 | 聚焦验证：Java 16/16、Vitest 122/122；`./scripts/verify.sh`：Java 260/260、Vitest 122/122，BUILD SUCCESS；Linux 隔离运行发现 49861 且 Runtime 为 `AVAILABLE`/`managed=false`；JAR 262600078 bytes，SHA-256 `3bf6b85cefd8fa2397df7cf773ad77e7ebc83e06c185b440ae7bb1f914b85e7d`；发布目标：`v0.1.18` |
| 2026-08-12 | 修复 Linux TUI/Web OpenCode 动态端口发现并发布 0.1.19 | Linux 启动器按已确认的 OpenCode PID 通过 `lsof`/`ss` 读取监听端口，再用 `/global/health` 验真；同步 README 与 OpenCode 合同 | 聚焦验证：Java 18/18、Vitest 122/122；`./scripts/verify.sh`：Java 262/262、Vitest 122/122，BUILD SUCCESS；真实 `opencode web` 无 `--port` 进程被发现为 4096，Runtime 为 `AVAILABLE`/`managed=false`；JAR 262600078 bytes，SHA-256 `84b6e079402180550d24cacc36df0f1a88502d6c8f8d415fba5dab4c8119c83d`；发布目标：`v0.1.19` |
| 2026-08-12 | 修复 Linux 隐藏 socket 归属时无法发现 OpenCode 并发布 0.1.20 | 修复 Bash 空数组在 `set -u` 下提前退出；无 PID 归属时严格健康检查本机监听端口；规范化通配监听地址并兼容官方 Basic Auth 环境变量；同步 README 与 OpenCode 合同 | 修复前真实回归复现 `opencode_pids[@]: unbound variable`；聚焦验证：Java 20/20、Vitest 122/122；`./scripts/verify.sh`：Java 264/264、Vitest 122/122，BUILD SUCCESS；最终 JAR 真实连接 OpenCode 1.18.12（`0.0.0.0:18082`、无 socket PID 视角、Basic Auth），health `UP`、Runtime `AVAILABLE`；JAR 262600086 bytes，SHA-256 `e0e1126a6bbe9f15df9ff50b65ff087cd1da20726f2d9a4d6517c647c833e25c`；发布目标：`v0.1.20` |
| 2026-08-12 | 修复 Linux auto 启动失败误显示 4096，准备 0.1.21（暂不发布） | Linux auto 启动前锁定真实 CLI 路径；受管启动失败公开安全原因和实际动态尝试地址，客户端落到不可用 loopback；同步 README、OpenCode 合同和 Runtime UI | 聚焦验证：Java 26/26、Vitest 123/123，Runtime/API Vitest 27/27；`./scripts/verify.sh`：Java 266/266、Vitest 123/123，BUILD SUCCESS；真实 OpenCode 1.18.12 由 PID 73760 启动为 PID 73819，监听 `127.0.0.1:52068`，Runtime `AVAILABLE`/`managed=true`，父进程停止后子进程同步停止；失败 CLI 返回 `OFFLINE`、实际尝试 `127.0.0.1:52117`、退出码 1，未出现 4096；JAR 262600597 bytes，SHA-256 `d67ef40eb1e827b8010ae94f0908fc4e03f3821e9cd5c4940e9b5674356dbda6`；按用户要求未提交、未推送、未打标签、未发布 |
| 2026-08-12 | 修复 Linux auto 启动健康探测耗尽总超时，准备 0.1.22（暂不发布） | 将受管启动期间单次健康请求限制为 1 秒及剩余总预算的较小值，允许在 15 秒启动窗口内持续重试；同步 README 与 OpenCode 合同 | 聚焦验证：Java 27/27、Vitest 123/123；延迟 1.2 秒的首次健康响应在 3 秒总预算内成功触发重试；`./scripts/verify.sh`：Java 267/267、Vitest 123/123，BUILD SUCCESS；最终 JAR 启动 Loopper PID 83403，并自动启动 OpenCode 1.18.12 PID 83600，监听动态端口 `127.0.0.1:55280`，Runtime `AVAILABLE`/`managed=true`，停止父进程后子进程同步停止；JAR 262600979 bytes，SHA-256 `eb5bf405cbaa2a9c56facb6e1cfe422b5b42d49b6f0a503c6dde0f56a884c955`；按用户要求未提交、未推送、未打标签、未发布 |
| 2026-08-12 | Runtime 失败后显式启动并检查连接，准备 0.1.23（暂不发布） | Auto 失败卡片新增本地 UI 专用启动动作；普通读取不再重复拉起；仅健康检查通过后提示连接成功；同步 README、OpenCode 与设计合同 | 聚焦验证：Java 29/29、Vitest 125/125；`./scripts/verify.sh`：Java 269/269、Vitest 125/125，BUILD SUCCESS；真实流程第一次子进程退出码 41，连续 GET 保持同一失败且不重启，无本地 UI 标识返回 400，显式 POST 随后启动 OpenCode 1.18.12 PID 90888，监听 `127.0.0.1:56741`，Runtime `AVAILABLE`/`managed=true`，父进程停止后子进程同步停止；JAR 262601831 bytes，SHA-256 `47b8ccebf6403e808c9db567a8419d1ad476da93e2485e1c0d441c351a692312`；按用户要求未提交、未推送、未打标签、未发布 |
| 2026-08-12 | 未提交文件逐项处理并发布 0.1.24（包含 0.1.21–0.1.23 Runtime 累积修复） | 脏登记目录改为 `WAITING_INPUT`，展示精确文件并按快照逐项提交、暂存或移除；重新检查后继续创建任务分支；取消直接使任务失败且不改文件；同步 README、架构、设计、OpenCode 与七特性合同 | 聚焦验证：TaskService 44/44、Git/Controller/前端相关回归通过；`./scripts/verify.sh`：Java 275/275、Vitest 128/128，BUILD SUCCESS；0.1.24 隔离真实 JAR 验证提交+暂存后进入 `READY`/任务分支，取消后保持源分支和脏文件；JAR 262625680 bytes，SHA-256 `6dea210cd2ee475cbcca6e3b4513039a7496f041bc2873e83dc1c63767192028`；发布目标：`v0.1.24` |
| 2026-08-12 | 清理人工工作区检查后的活动提示，准备 0.1.25（暂不发布） | `SOURCE_BRANCH_WORKSPACE_DIRTY` 继续保留为审计历史，但仅在服务端当前等待原因仍匹配时显示活动红色告警；进入 `READY`/执行阶段即隐藏；同步 README 与设计合同 | 聚焦 TaskDetail Vitest 8/8、前端类型检查及版本/MCP 测试通过；`./scripts/verify.sh`：Java 275/275、Vitest 130/130，BUILD SUCCESS；JAR 262625669 bytes，SHA-256 `ff60a2aae2b1d2c435188791fd649644f3555b230a984f40976bc4574e3554ee`；按用户要求未提交、未推送、未打标签、未发布 |
| 2026-08-12 | 修复 Windows PROCESS 找不到 Maven 并发布 0.1.26 | Windows 按任务根解析 Maven/Gradle Wrapper，按 Loopper 进程 `PATH`/`PATHEXT` 解析 `.com`/`.exe`/`.bat`/`.cmd` 并记录实际 argv；启用 JDK 严格 Windows 命令引用；Linux/macOS 保留原生 PATH 与可执行位语义；同步 README、架构、设计和验证器合同 | 聚焦 Java 58 项：57 通过、Windows 实机条件用例 1 项在 macOS 跳过；Vitest 130/130；`./scripts/verify.sh`：Java 283 项中 282 通过、Windows 条件用例 1 项跳过，Vitest 130/130，BUILD SUCCESS；Java 21 隔离启动 18086 health `UP` 且返回打包 SPA；JAR 262634284 bytes，SHA-256 `730063b9d1a0b011cff2b7429feaf2e73012b6fdaec837bba90ab21ddab05c3d`；发布目标：`v0.1.26` |
| 2026-08-12 | 修复任务分支差异快照与合并请求入口并发布 0.1.27 | 成功 Attempt 固化独立于显式 `GIT_DIFF` 验证器的任务基线差异；恢复源分支后按持久化任务分支引用预览；合并请求改为单击普通按钮直接打开确认对话框；同步 README、架构与设计合同 | 聚焦任务基线/分支恢复 Java 1/1、发布及差异面板 Vitest 11/11；首次 `./scripts/verify.sh` 因既有本地同步并发测试时序失败，单独复跑通过；最终 `./scripts/verify.sh`：Java 284 项中 283 通过、Windows 条件用例 1 项跳过，Vitest 131/131，BUILD SUCCESS；JAR 262634583 bytes，SHA-256 `6355b79510620d6cde5c510ec003b6e5735770cf52ee97bc5c643ea63c5f1f29`；发布目标：`v0.1.27` |
| 2026-08-12 | LoopSpec v2 行为覆盖与托管验证运行时并发布 0.1.28 | 新草稿/模板使用 criterion 覆盖合同；统一 REST/MCP/Designer/保存/确认分析；V19 持久化动态端口运行时与 PID 身份恢复；v1 保持兼容并可复制为新 v2；同步 README、架构、设计、OpenCode 与七特性合同 | 聚焦 Java 43/43、Vitest 134/134；`./scripts/verify.sh`：Java 298 项中 297 通过、Windows 条件用例 1 项跳过，Vitest 134/134，BUILD SUCCESS；隔离 JAR 实测 v2 REST、Review Gate 阻断/完整矩阵、HTTP/JSON/BROWSER、截图/trace、PID/端口清理和双 Judge 全部通过；JAR 262693773 bytes，SHA-256 `02b748e451ee16237089476d6e0e1e5856807083dc99868c9fa5bb531e25de0b`；发布目标：`v0.1.28` |
| 2026-08-12 | 配置化内网 GitLab HTTP MR 地址，准备 0.1.29（暂不发布） | 新增精确主机白名单；SSH remote 命中时只将 MR Web 地址改用 HTTP，显式 HTTP/HTTPS 和 SSH 推送协议不变；Linux/Windows 启动脚本默认加入 `gitlab.spdb.com`；同步 README 与架构合同 | 聚焦 Java 28/28、Vitest 134/134；`./scripts/verify.sh`：Java 298 项中 297 通过、Windows 条件用例 1 项跳过，Vitest 134/134，BUILD SUCCESS；隔离端口 64131 启动 JAR，health `UP` 且返回打包 SPA；JAR 262695237 bytes，SHA-256 `59cfd48d7111912f7fc7e95ab19d248f354766de8067523ad99bca409076453c`；按用户要求未提交、未推送、未打标签、未发布 |
| 2026-08-12 | GitLab 合并确认与不可逆交付终态，准备 0.1.30（暂不发布） | 新增独立 Publication 状态轴和 V20 持久化；GitLab API 精确匹配任务提交并确认 MR opened/closed/merged；任务页展示执行/交付双状态，`MERGED` 后阻断原任务发布写操作；同步 README、架构与设计合同 | 聚焦 Java 31/31、Publication Vitest 11/11；`./scripts/verify.sh`：Java 309 项中 308 通过、Windows 条件用例 1 项跳过，Vitest 138/138，BUILD SUCCESS；隔离 JAR 实测 `PUSHED → MERGE_REQUEST_OPENED → MERGED`，合并后写操作返回 409，临时进程无残留；JAR 262721662 bytes，SHA-256 `6d8584a0e6d50a1af1909c5a40d839a03f2c17d729ae999fdd3011953fbf0bc9`；按用户要求未提交、未推送、未打标签、未发布 |
| 2026-08-12 | Designer 机器验收与最终 AI Judge 双重计划并发布 0.1.32 | LoopSpec v2 条件支持 `MACHINE`、`JUDGE`、`BOTH`；Java 默认同阶段聚焦 Maven/Gradle 单元测试与最终 Judge；统一拒绝 shell、`java -e`、源码搜索和可跳过目标的伪行为验证，同时保留带空格的直接可执行路径；同步 README、架构、设计与验证器合同 | 聚焦 Java 51/51、Vitest 139/139；首次完整验证因既有本地同步并发用例时序失败，单独复跑通过；最终 `./scripts/verify.sh`：Java 315 项中 314 通过、Windows 条件用例 1 项跳过，Vitest 139/139，BUILD SUCCESS；隔离 JAR 在 18087 实测 health、打包 SPA、`BOTH`/`JUDGE` 评估与 `java -e` 拒绝，关闭后无监听残留；JAR 262726851 bytes，SHA-256 `fc0496c81d6c0207db968c7c6889a2c62cced40dc58a35f63be5ce1f53ae308d`；发布目标：`v0.1.32` |
| 2026-08-12 | 修复验收边界并发布 0.1.34 | PROCESS TEST 精确识别并在执行前复核；Judge 汇总全部阶段证据并增加双层 UTF-8 预算；LoopSpec 空字段分析返回错误而非异常；Runtime 重启要求本地 UI；前端数值上限与后端一致；同步 README、架构、设计与验证器合同 | 聚焦 Java 91/91、Vitest 37/37；`0.1.33` 首次完整验证因 2 处旧版本断言失败后按规则递增；最终 `./scripts/verify.sh`：Java 322 项中 321 通过、Windows 条件用例 1 项跳过，Vitest 140/140，BUILD SUCCESS；隔离 JAR PID 33181 监听 `127.0.0.1:62813`，health `UP`、返回打包 SPA、Runtime 重启本地 UI 边界生效，退出后端口释放；JAR 262733264 bytes，SHA-256 `7530f5d87293e871f4ad76cdd33b83117e52d6fdce1093d96dd3b1945b1fd9a9`；发布目标：`v0.1.34` |
| 2026-08-12 | 修复双 Judge 提示预算原子预检并发布 0.1.35 | 每轮先构造并校验全部待启动角色提示；任一角色超过 128 KiB 时，整批不创建 Judge 记录、只读 Session 或模型调用；显式双评审重试遵循同一边界；同步 README、架构、设计与七特性合同 | 聚焦 Java 71/71；`./scripts/verify.sh`：Java 323 项中 322 通过、Windows 条件用例 1 项跳过，Vitest 140/140，BUILD SUCCESS；JAR 262733719 bytes，SHA-256 `75c3e9759921c234324ab94954bacf378b3e39ffa56dde515c6eef027e22dee3`；未启动运行时；发布目标：`v0.1.35` |
| 2026-08-13 | 修复真实环境三项缺陷并发布 0.1.37 | OpenCode 目录查询编码保留 `+`；FILE_CONTENT 保留尾随换行；深层无扩展名路由进入 SPA 且后端/静态 404 不变；同步 README、架构、设计、OpenCode 与验证器合同 | 聚焦缺陷回归 Java 41/41、Vitest 140/140；首次 0.1.36 运行时复测发现 catch-all 抢占静态资源，补全真实 Spring MVC 集成覆盖后，`./scripts/verify.sh` 在 JDK 21 下 Java 325 项通过 324、跳过 1，Vitest 140/140，BUILD SUCCESS；`target/opencode-loopper-0.1.37.jar` 262734548 bytes，SHA-256 `19cf7f18aa6ba203bd835218cba6e27f7f68150685ad28b87fc2fc50685af25f`；真实 JAR 回归：含 `+` 项目路径的 AGENTS 只读生成进入 READY，15-byte 尾随 LF 的 FILE_CONTENT EXACT 任务 SUCCEEDED/PASS，SPA 根路由/深链/打包资产为 200 且 API、Actuator、缺失资产与文件式路径为 404；临时 JVM/OpenCode 已停止且端口释放；发布目标：`v0.1.37` |
| 2026-08-13 | Designer / LoopSpec Compiler 双角色与 Java 单元测试硬门禁并发布 0.1.39 | V21 持久化冻结设计、独立只读 Compiler、确定性 Validator、角色消息和阶段 Java 基线；Compiler 两次修复、Designer 一次自动完整重设计及人工恢复；v2 `implementationKind` 与 Git/Direct 运行期单元测试门禁；前端多角色卡片；Session 创建失败保留原始错误并进入可恢复终态；同步 README、架构、设计、OpenCode、验证器合同与本公约正文 | 0.1.38 候选完整构建后，隔离链路发现 Session 创建失败会被二次状态转换错误覆盖，修复并按公约递增；0.1.39 聚焦 Designer/MCP Java 19/19、前端 141/141；`./scripts/verify.sh`：Java 333 项中 332 通过、Windows 条件用例 1 项跳过，Vitest 141/141，BUILD SUCCESS；真实 JAR PID 34565 监听 `127.0.0.1:61629`，经两个独立只读 Session 完成 Designer → Compiler → Validator → Review Gate，角色卡与聚焦 Java 测试合同正确、原始 Compiler JSON 未进入消息、Task 数为 0，退出后端口释放；JAR 262777458 bytes，SHA-256 `a10e854f2dba4f353808c39e414a9018e994974ac89599697c8a947993b4bb4b`；发布目标：`v0.1.39` |
| 2026-08-13 | Task Decomposer、分包设计/执行与 0.1.40 | V22 冻结完整需求版本、独立只读 Decomposer、2–6 个纵向工作包、分包 Designer/Compiler/Validator 严格串行及确定性聚合；24 次全局模型调用预算、包内重试隔离、草稿并发停止；确认后单 Task/分支/发布、包级尝试池与当前包执行上下文；Recovery/MCP/模板兼容；前端角色卡、包轨道、Review Gate/历史/任务执行分组；同步 README、架构、设计、OpenCode、七特性合同与本公约正文 | 聚焦 Designer/MCP、包级尝试池、V22 迁移及前端角色/包视图通过；`./scripts/verify.sh`：Java 328 项中 327 通过、Windows 条件用例 1 项跳过，Vitest 143/143，BUILD SUCCESS；真实 JAR PID 54117 监听 `127.0.0.1:18140`，health `UP` 且返回打包 SPA；DIRECT 使用 3 个独立只读 Session，三包链路使用 7 个独立只读 Session 严格串行，聚合前项目 Task 数为 0、确认后为 1，三个包各 1 次 Attempt 后成功且最终仅 Requirement/Risk 两个 Judge PASS，执行提示只含当前包完整设计和前置包交接摘要；进程已停止；JAR 262857840 bytes，SHA-256 `5014e25588b833a5e222e17b0dab433fea4fecfe96151aa6b0514c22b8f4330d`；发布目标：`v0.1.40` |
| 2026-08-13 | 修复排队任务无法取消并准备 0.1.41 | `QUEUED` 任务详情新增二次确认取消入口；只取消自身队列项且不释放当前 holder 写租约，进入 `CANCELLED` 后可归档和删除；同步 README、架构、设计合同与本公约正文 | 聚焦 TaskDetail Vitest 10/10、TaskService 49/49；`./scripts/verify.sh`：Java 329 项中 328 通过、Windows 条件用例 1 项跳过，Vitest 144/144，BUILD SUCCESS；JAR 262857697 bytes，SHA-256 `f07c952d3221c67fab8a28a58878c0f36611267f7c9e2b99caa15e7c8c008047`；未启动运行时；发布目标：`v0.1.41` |
| 2026-08-13 | 修复弱模型 Compiler 连续输出错误 JSON 类型并发布 0.1.42 | 首次编译与两次修复共享完整 JSON 类型合同和规范生产 Java 信封；明确验证器对象、直接 argv、验收/测试数组、托管运行时 object/null 与设计缺口对象边界；去除输出标记旁会诱导空验证器的旧模板；同步 README、Designer/OpenCode 合同与本公约正文 | 聚焦 Designer/MCP 14/14、发布契约 7/7；`./scripts/verify.sh`：Java 330 项中 329 通过、Windows 条件用例 1 项跳过，Vitest 144/144，BUILD SUCCESS；真实 0.1.42 JAR PID 75683 监听 `127.0.0.1:8080`、health `UP`、受管 OpenCode 1.18.12 `AVAILABLE`；对原失败会话重编译后 JSON 首次即完成反序列化，仅一次验收映射修复即令 WP-1 的 12 条验收通过确定性校验并自动进入 WP-2；JAR 262861120 bytes，SHA-256 `e79d56eae92b68601e2e79d39d546bf666494b2448de4ef72a4fa75289b77f5c`；发布目标：`v0.1.42` |
| 2026-08-13 | Decomposer 与 Compiler 多步智能编译并发布 0.1.43 | 两个只读 AI 角色先完成语义规划与证据映射，由服务端校验并冻结规划，再在同一 Session 生成受规划约束的最终 JSON；V23 持久化步骤和规划证据，修复按当前步骤路由，旧 V22 活动记录按最终 JSON 兼容；前端展示规划、JSON 生成和修复步骤但不泄露原始结构化输出；六包模型调用预算由 24 调整为 32；同步 README、架构、设计、OpenCode、七特性合同与本公约正文 | 聚焦 Designer/MCP Java 15/15、后端集成/迁移/发布契约 24/24、Designer Vitest 16/16、前端类型检查通过；`./scripts/verify.sh`：Java 332 项中 331 通过、Windows 条件用例 1 项跳过，Vitest 144/144，BUILD SUCCESS；JAR 262888383 bytes，SHA-256 `7487f76d7e8d8969673ee57a837ccacef9cc0d0dd8d0d56db68d4f87bf927e62`；发布与真实运行验证发现两步共用修复池且耗尽终态不完整，后续由 0.1.44 修复；发布目标：`v0.1.43` |
| 2026-08-13 | 隔离多步智能编译修复预算并发布 0.1.44 | V24 为 Decomposer 与分包 Compiler 分别持久化规划修复和最终 JSON 修复计数，每步最多两次且互不挤占；补齐 Decomposer 从 `VALIDATING` 在预算耗尽时进入可人工恢复 `SESSION_ERROR` 的合法转换；API、前端状态条与工作包轨道分别展示两个计数；同步 README、架构、设计、OpenCode、七特性合同与本公约正文 | 真实 0.1.43 弱模型链路证明规划在两次格式修复后冻结 2222 字节证据，但最终 JSON 因共用预算无法修复且停在 `VALIDATING`；新增 Decomposer/Compiler 双步骤预算与终态回归，聚焦后端 34/34、Designer Vitest 16/16、前端类型检查通过；`./scripts/verify.sh`：Java 334 项中 333 通过、Windows 条件用例 1 项跳过，Vitest 144/144，BUILD SUCCESS；JAR 262889712 bytes，SHA-256 `9165f8ec879980032a84a3d847741ca99d6a58880ff0b15ffe33c653b513afc9`；GitHub Release 成功，本机 PID 5933 以 V24 启动；真实弱模型证明 Decomposer 规划/JSON 各修复 1 次后通过，但 Compiler 冻结了含 shell 的不可执行证据策略并耗尽最终修复，后续由 0.1.45 前移校验；发布标签：`v0.1.44` |
| 2026-08-13 | 前移 Compiler 可执行证据蓝图校验并发布 0.1.45 | 分包 Compiler 规划合同升级为 `contractVersion=2`，每个 Stage 在证据映射阶段即携带完整 `VerifierSpec` 和可选 `verificationRuntime`；服务端用权威 LoopSpec v2 合同校验直接 argv、BEHAVIOR 覆盖、Java 聚焦测试与运行时绑定后才冻结，最终 JSON 必须逐字段复制；同步 README、架构、设计、OpenCode、七特性合同与本公约正文 | 真实 0.1.44 弱模型链路证明独立修复池生效，但其规划把 bash/grep 作为证据并导致最终 JSON 两次修复耗尽；新增 shell 证据在规划阶段拒绝、直接 Python 自检与 GIT_DIFF 蓝图冻结、最终无漂移且无 Task 副作用的回归；聚焦后端 24/24，`./scripts/verify.sh`：Java 335 项中 334 通过、Windows 条件用例 1 项跳过，Vitest 144/144，BUILD SUCCESS；JAR 262891311 bytes，SHA-256 `6a243ffa9cedc91b82679ff2c5f1b7db69a4bbaeb0ce2df6c632fa710516a506`；发布目标：`v0.1.45`，真实运行与 Release 结果在标签发布后核验 |
