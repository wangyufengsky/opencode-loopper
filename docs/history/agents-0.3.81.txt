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
   test -s target/opencode-loopper-0.3.81.jar
   jar tf target/opencode-loopper-0.3.81.jar \
     | rg 'BOOT-INF/classes/static/(index.html|assets/)'
   shasum -a 256 target/opencode-loopper-0.3.81.jar
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
- Loopper 不自动强推、不自动合并托管平台请求或删除旧版 worktree；用户确认任务提交后自动恢复该任务开始前记录的源分支；取消仍持有登记目录租约的 Git 任务时，确认写入者停止并保存修改快照后切回主分支。
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

- Maven 项目版本：`0.3.81`。
- 正式产物：`target/opencode-loopper-0.3.81.jar`。
- Maven 固定准备 Node.js `v22.14.0` 和 npm `10.9.2`，执行 `npm ci`、类型检查、Vitest 和 Vite build，再将 `frontend/dist` 复制到 `target/classes/static` 后构建 JAR。
- `target/`、`frontend/dist/`、`frontend/node_modules/` 和运行时 `data/` 都是生成或运行目录，不作为手工编辑的源码来源。

版本升级时必须同步检查并更新：

- `pom.xml`；
- `frontend/package.json`；
- `README.md` 中的版本和命令；
- `scripts/start-linux.sh` 中的 JAR 文件名；
- `scripts/start-windows.bat` 中的 JAR 文件名；
- `src/main/resources/application.yml` 中 MCP server version；
- 本文件中的版本和产物路径。历史记录中的版本和校验值保持原样。

## 3. 项目结构地图

```text
.
├── AGENTS.md                         # Agent 强制公约；每次代码任务结束更新
├── README.md                         # 面向最终用户的安装、使用和运维说明
├── pom.xml                           # Java/Maven、固定前端工具链和单 JAR 打包
├── scripts/
│   ├── dev.sh / dev.ps1              # 后端 + Vite 热开发
│   ├── evaluate-weak-model-v7.sh      # v7 脱敏 corpus、只读 shadow 与上线门槛
│   ├── verify.sh                     # JDK 21 下的 clean verify 与正式打包
│   ├── start-linux.sh                # Linux/内网成品 JAR 启动
│   └── start-windows.bat             # Windows 成品 JAR/OpenCode 启动
├── .github/workflows/
│   ├── ci.yml                        # main/PR 的三平台完整验证
│   └── release.yml                   # v<version> 标签验证并发布 JAR/脚本/校验值
├── docs/
│   ├── history/                      # 历史发布、维护记录与已完成工单，不作为当前合同
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

`docs/history/` 保存历史发布说明、维护记录及已完成工单；它们只作证据，不得覆盖当前合同。新增交付继续更新本文件，历史记录可按版本归档并保留索引。

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
- Pending Center 的交互只有在其本地拥有者仍属于可刷新 Session 时才可操作：Task 依赖 `CREATING/RUNNING` 执行 Session，Designer 依赖 `RUNNING` 且仍在交互设计阶段的会话；拥有者停止、丢失或离开交互阶段时，`PENDING/RESOLVING/HARD_DENIED` 必须经 Interaction 状态机收束为 `STALE` 并退出待处理列表。仍活动拥有者的 Provider 传输失败继续保留最后持久化状态，不能误删真实待处理请求。

### 5.2 错误层级

`ErrorLayer` 是公开持久化契约：

- `FIELD`：输入或草稿校验，不改变运行状态。
- `VERIFICATION`：当前 Attempt 验收失败，保留证据并在预算内继续循环。
- `SESSION`：OpenCode Session 失败，关闭当前 Session/Attempt，安全确认后创建新 Session。
- `TASK`：当前执行轮次无法安全继续或预算耗尽，关闭所有子运行，记录失败轮次并进入 `AWAITING_DECISION`；不得未经用户处置直接形成新任务终态。

Session adapter 不得直接把 Task 写成 `FAILED`；重试耗尽后的升级由编排器负责。终止 Task 不能伪造远端 Session 已停止：HTTP abort 必须解析 boolean，只有 `true`、精确 404 已不存在或独立终态状态可作为正向证明；`false`、空响应和传输失败均保持未确认。无法确认的写入者保留为 `DISCONNECTED`，并阻止重叠写入。

`RETRY_WAIT` 必须由 V31 持久化计划驱动，同一 Task 只允许一个 `SCHEDULED`/`PAUSED` 活动计划。限流、普通 Session、验证失败默认分别按 `60→120→240→300`、`10→20→40→60`、`5→10→20→30` 秒退避并保持上限，不加随机抖动；计划创建后到期时间不可被后续设置追溯修改。只有确认旧 writer 已停止后才能建计划，到期由 Monitor 原子领取并创建唯一新 Attempt/Session；重启保留计划，历史无计划等待按普通 Session 默认值补建。暂停冻结剩余时间，恢复继续等待，Task 成功/失败/取消关闭活动计划。OpenCode `RETRY` 是 Provider 在原远端 Session 内的自恢复状态，不是 Session 错误、Loopper `RETRY_WAIT` 或 writer 终态证明；所有调用方必须保留原 Session 继续轮询，既有角色/操作超时仍为硬边界。

`AWAITING_DECISION` 必须由带 Task/Cycle 乐观锁的独立结果处置接口取消，不能回接会拒绝该状态的普通运行期取消命令；该专用入口仍复用 `STOPPING` writer 终止确认，但保持已经结束的 Execution Cycle 与 Stage 成功/失败证据不变。其他所有非终态 Task 都必须在本地任务详情中保留取消入口；取消需二次确认并保留已有执行目录、分支和证据，不得伪装成回滚。服务端必须先把 Task 持久化为 `STOPPING`，停止并确认该 Task 的验证进程、Implementation/Judge 远端 Session 和其他 writer 已终止，再把仍活动的 Attempt/Stage/Execution Cycle 分别收束为 `CANCELLED / CANCELLED / INTERRUPTED` 并将 Task 转为 `CANCELLED`；停止无法确认时保持 `STOPPING` 和 `DISCONNECTED`，允许显式重试，禁止释放租约或启动重叠 writer。`PENDING_START` 取消只改变自身 Task，必须保持无 Queue/Lease/分支/执行目录；取消排队任务只取消自身 Queue，不得释放或转移当前 holder 的写租约。脏工作区对话框的“取消任务并保留文件”也必须走同一取消协议，不得用通用失败入口制造 `FAILED` Task 或遗留 `RUNNING` Cycle。
Task 详情 `overview` 必须投影 `loopRetryAvailable`、`cancellationAvailable`、`hasDesignHistory` 和 `archived` 四个布尔字段；前端不得把缺失字段静默解释为 `false`，而应拒绝不完整 overview 并回退完整详情接口。取消能力规则由 `TaskState.cancellationAvailable()` 统一持有，精简读模型与兼容 `TaskDto` 不得各自复制判断。

验证失败后的 Attempt 必须固化有界 `ATTEMPT_HANDOFF`，下一轮只能使用新 Attempt 和新可写 Session；不得复用旧实施对话。只有可靠且相同的失败签名与工作区内容指纹才累计停滞次数，达到 `stagnationLimit` 后必须进入 `WAITING_INPUT`，由本地 UI 明确确认继续。

新任务的执行结果与用户确认终态必须分离：每次开始/继续对应独立持久化 Execution Cycle；确定性验证和双 Judge 成功或 Task 级失败均进入 `AWAITING_DECISION`。继续当前任务必须创建新 Cycle、Attempt 和 Session，并从失败/用户选择的 Stage 起重跑后续阶段与最终双 Judge；轮次预算重新计算，历史用量和证据不得改写。成功结果经本地提交或确认推送进入 `COMPLETED`，继承/重做派生后父任务进入 `SUPERSEDED`，取消进入 `CANCELLED`；旧 `SUCCEEDED`/`FAILED` 只作历史终态兼容，不得静默重开。

所有新 Task 进入 `COMPLETED / SUPERSEDED / CANCELLED` 前必须经过统一聚合终态守卫：非终态 PackageRun、Attempt、Stage、Execution Cycle 和 Designer 子流程必须已收束或在同一短事务内收束，Queue/Lease 必须无活动占用。远端停止未确认、乐观锁冲突、活动 writer/Verifier/Judge 或 Queue/Lease 混合状态一律失败关闭并保持父 Task 非终态；历史 `SUCCEEDED/FAILED` 只能读取，不得作为新建状态或新转换目标。

### 5.3 Designer 和 LoopSpec

- 0.3.80 按用户要求撤回 0.3.76–0.3.79 未达标的有限域/SAT/独立来源复核实验，生产 Java、工作包 Schema 和 Prompt 恢复 0.3.75；V2 继续默认开启。V74 保留原文与校验和，V75 只允许无实验策略/义务/run/复核会话的数据库继续启动，不自动删除数据、修改合同或确认远端停止。含实验记录的库须继续使用 0.3.79 处理；禁止直接删表或修复迁移历史绕过。历史评测文档仅作证据，不能用于当前功能声明。详见 `docs/package-behavior-rollback.md`。

- 0.3.80 新建工作包会话默认 V2，显式 false 仅回滚新会话；V2 自动冻结证据，不依赖独立 V1 evidence 开关。接受前必须用纯 `PackageDesignScopeGuard` 从冻结范围证明 Stage/GIT_DIFF 允许规则包含关系和删除保护；候选路径不得反向成为授权。原文集合删除、指代文件移动和既有显式删除/移动均在来源预检阻断；候选 ANY 不能解除硬冲突。固定 Prompt R2 区分首次转换/重复请求及优先级例外；不得把词面规则或来源图宣称为自然语言完备检查。详见 `docs/package-design-v2-enablement.md`。

- 0.3.72 增加默认关闭的 `LOOPPER_PACKAGE_DESIGN_V2_ENABLED`，仅新建 V2 conversation profile 打开 `submit_package_design_v2`；内部 `PACKAGE_DESIGN_V1` kind 保持角色命名空间，真正合同由冻结的 contractVersion/workflowStep 区分，旧工具不能提交 V2，Legacy/V1 不升级。V2 校验来源绑定与 all/any/unless 有界 DAG（32 节点、4 层），所有适用分支保留；原文引用和关系不证明自然语言完整性。冻结精确 scope 仅在 V2 消除重复路径歧义，不覆盖否定、安全或冲突。原始需求、仓库快照及模型整理证据分开；显式来源定位的 USER 本地反馈可作为同一需求修订的补充决定，AI 消息和候选声明不能替代用户决定。V73 持久化每包讨论修订最多一次的 PACKAGE_SEMANTICS 回合，复用会话协调器与消息身份；简单/历史/已有问题回合不增加整理，未知投递不重发，停止未确认不推进。整理输出只是有界建议；失败保留材料并沿既有停止规则处理。最终来源/关系编译进交接及验收准则，由 Implementation/Recovery/Judge 消费完整冻结 StageSpec；不得引入模型执行权限。详见 `docs/package-design-luna-optimization.md`。
- 0.3.70 增加关闭默认的 `LOOPPER_PACKAGE_DESIGN_EVIDENCE_ENABLED`，仅新运行冻结 `PACKAGE_DESIGN_V1_EVIDENCE_V1`；历史 workflow step 及生产提交次数配置不变。V72 冻结需求来源与有界仓库证据，候选校验不得读取文件/网络/模型；未知或超限证据不得判为能力不存在。模型缺口码只作待核实声明，安全/范围证明保持阻断。Codex Luna 评测只走 ChatGPT 订阅、`gpt-5.6-luna`、medium，四次候选预算与实际模型请求预算分开；GEPA 未验证逐请求预留硬上限前不得启动。见 `docs/package-design-luna-optimization.md`。

- 0.3.67 将工具模型友好度同步到七类候选：schema 的 UTF-8 字节扩展必须与生产编译器的 strip/count 语义一致，字符长度按 Unicode code point；Reviewer 报告总字节与源证据约束仍由生产编译器最终检查。所有非工作包强类型候选先执行角色策略保留值内安全/冻结事实检查，再优先返回独立形状问题，不能宣称角色语义检查完全跳过；不可重试的安全/人工输入结果优先。
- V71 允许所有新 INTERNAL_MCP 角色冻结可选 2–16 次 `correction_limit`。工作包沿用专用配置，其余六类使用 `internal-candidate.correction-limits.<KIND>`，默认 0；配置仅在新 run 打开时读取，重开和重启沿用持久化值，Legacy 不继承新配置。公共修正提示读取同一冻结预算。非工作包内部回执追加 `CANDIDATE_REPAIR_V1`，工作包保留 `PACKAGE_REPAIR_V1`；实体 key、packageKey、factIndex、Reviewer path+line 用于问题身份，形状修复后仍可能揭示下一层语义错误。预算耗尽/已完整诊断的候选重复进入 WAITING_INPUT，接受优先，幂等重放不收费。Judge 的 ACCEPTED 仅表示判定合同有效，允许并保留 BLOCKED；不得引导改判 PASS。
- `scripts/qualify-all-role-model.py` 与 `AllRoleModelProbe` 使用隔离只读夹具和真实生产编译内核，夹具须先经 `AllRoleModelProbeTest` 校验；冻结业务需求与工具提示元数据分开。探针不证明完整生产角色提示、HTTP launch、拥有者原子结算或远端停止握手，真实模型小样本不得作为生产通过率。

- 0.3.66 的 `PACKAGE_DESIGN_V1` 直接从强类型候选组装 Fact/StageHint，候选 key 用于引用，标题仅作展示；Markdown 只提供可读投影和精确 DS-L 行证据，Legacy Markdown 入口保留原解析。共享上限为场景 64、总事实（requirements + scenarios + deliverables + reviews + stages）128、阶段最多 6（分包最多 3）。冻结输入在模型调度前预检；冻结需求/范围冲突属于人工输入，不能要求模型修改候选绕过。
- V70 为新工作包 MCP 运行增加可选且不可变 `correction_limit`；`LOOPPER_PACKAGE_DESIGN_CORRECTION_LIMIT=4` 表示首投加最多三次修正，允许 2–16，默认 0 继续无限语义。已有运行保持冻结值，历史 NULL 不受配置变更影响，`maxAttempts` 仍是旧合同身份。四次预算耗尽或有完整诊断证明最近三次内重复相同规范候选时进入 WAITING_INPUT，不强制 Markdown 兜底；ACCEPTED 优先，幂等重放不计数。角色超时、Provider 传输、权限和正向远端停止证明保持独立。
- 工作包强类型入口先检查权威边界，再检查形状，形状失败不执行依赖它的语义检查。`CANDIDATE_DIAGNOSTIC_V2` 保留，新增 `PACKAGE_REPAIR_V1`：以实体 key/指针、错误码和约束哈希生成 issue ID，返回 resolved/remaining/introduced 与 comparisonComplete。截断诊断不得宣称问题消失；拒绝候选仍只持久化哈希和有界诊断。真实模型探针只测 MCP 与生产编译内核，不能冒充完整 HTTP 工作流、owner 结算、活动 JVM 或浏览器验收。


- 当前角色提示必须按实际阶段、通道和冻结合同代际装配。需求讨论与 MCP Decomposer 均携带专属 Role Pack，但不得重复确认任务设置；工作包提示不混入编译器 argv/Verifier 字段。滚动设计以最新 checkpoint 为现状，APPROVED 仅是已接受合同，AI 摘要仅作导航。Router 枚举、闭集选择嵌套字段、Package reviews 与设计缺口码必须完整给出并经生产解析器回归；七类新 MCP 候选各使用一个角色专属强类型 Tool，在同一 run 内按返回的 revision 提交完整替换对象，权限、角色超时和停止条件保持。历史 v3 语义 Compiler 与 v5/v6/v7 建议合同分别维护。
- 实施与 Recovery 提示必须通过与验证入口一致的零基 ordinal 注入当前完整 StageSpec（含 acceptanceCriteria、Judge 准则和 verificationRuntime），不得只依赖设计摘要或重复其他阶段合同；当前结构化合同优先，服务启动和动态端口仍由 Loopper 管理。Reviewer、Judge、公约、提交、合并及统计提示分别保留证据边界，不得把建议、工具结果文本或历史回执当作当前成功事实。

- V35 在设计流程前冻结 `TaskIntent / WorkflowTemplate / MutationMode / ArtifactKind / TestPolicy / ExecutionStrategy` 任务画像和版本化 Role Pack；V36 引入独立 `ROUTER_NO_TOOLS` 和 Reviewer 运行态；V37 把工作包 Role Pack、版本、技术栈和测试策略复制到每个确认 Stage，Implementation/Recovery 必须复用；V38 持久化每次 Router 的完整需求快照、外部 Session、响应模式、标签和错误，重启继续同一 Session，新讨论必须 abort 并废弃旧运行；V39 把 Reviewer 升级为固定 `REVIEWER_REPORT_V1` findings 合同并持久化合同版本。服务端结合有界仓库事实决定最终流程，格式/Session 失败降级为通用画像提问而不终止 Designer。置信度低于 80 或证据冲突必须人工确认；历史缺失画像投影为 `LEGACY_SOFTWARE`，Recovery 复用冻结画像。当前 Role Pack `2026-08-dynamic-v7` 继承 v6 的 Java/Python/Node/Other 软件族归并：JavaScript/TypeScript 不得命中 Java，JUnit/Jupiter/Surefire 仍属于 Java，同族别名不得生成混合栈，真实跨族使用 `software-mixed`，显式未知单栈使用 `software-generic`；工作包技术信号使用词边界，业务符号中的 `Node`/`node` 子串不得误判为 Node 技术栈；每个可编译角色使用栈原生规划示例，文档、表格和只读报告明确走服务端或 Reviewer 绕过。新软件任务默认使用 `DIRECT_SOFTWARE_DESIGN` 和单一 `WP-1`，只有画像冻结前由用户显式打开“大型任务”才使用 `FULL_PACKAGE_DESIGN`；两种软件流程都冻结工作包自己的技术栈、Role Pack、执行和测试策略，其中默认单包必须继承已确认的软件任务画像，需求正文中的否定性“依赖/配置”措辞不得把它降级为维护角色，已冻结的冲突快照在下次权威使用时修复；只有大型任务的显式分包允许按包内容专门化角色。简单文档/表格/维护继续使用既有专属流程；大型文档要求 2–6 个二级章节包并由服务端确定性聚合结构化片段；只读 Reviewer 只开放 `read/glob/grep`，Legacy 与后续 MCP 候选必须共用单一 `ReviewerReportCompilation`，确认无问题时允许空 findings 但 summary 仍必填；每条实际 finding 都须逐条绑定受管路径、精确行号与源哈希，任一失败即整份拒绝且不得保留部分 finding；Reviewer 不创建 Task、Attempt、租约、分支或可写 Session，转换入口只创建关联 Designer。冻结 v4/v5/v6 工作包保持历史兼容，不得用 v7 改写既有设计快照。
- 任务画像对外决策态固定为 `ROUTING / NEEDS_CONFIRMATION / CONFIRMED / FROZEN`，所有设计入口统一依赖服务端 `confirmationReady`，不得从 `!decisionRequired` 推导。人工推荐确认记录 `USER_CONFIRMED`，编辑/沿用记录 `USER_OVERRIDE`；完整需求稿重算只有在任务意图、主要制品、单包/大型流程和读写模式不变且无新增安全冲突时，才以 `USER_CONFIRMED_CARRIED_FORWARD` 继承确认，技术栈、Role Pack 和测试策略仍取最新结果。对用户统一称为“任务设置”：首次歧义显示“确认并继续 / 修改设置”，实质变化返回 `previousConfirmedChoice` 并显示“原设置 / 本次识别结果”与“继续使用原设置 / 使用本次识别结果 / 修改设置”；编辑控件必须由用户主动打开。保存前调用只读影响预览，已确认且完全相同的选择由服务端无操作；只有流程切换才显示“停止当前设计并重新开始”的明确确认，取消不得废弃当前 Session，确认前不得启动设计。
- 危险操作判定必须识别动作对象与否定作用域，不能把孤立“发布”词面等同外部发版，也不能把“外部系统”引用等同写请求：进程内领域事件、消息、通知、信号、指标、执行轨迹可观测、发布订阅、事件总线的“监听器注册、发布与按类型分发”、生命周期 `started/succeeded/failed/compensated` 示例和受控的 `CHAIN_STARTED/SUCCEEDED/FAILED/COMPENSATED` 事件常量属于软件业务语义；“发布器/发布者/发布-订阅器”是组件名词而非发版动作，“进程内同步发布”是事件投递上下文；同句“重复/再次/重新发布”仅可继承前一个已证明的业务事件对象，普通第二次裸发布不得继承。只有写入、修改、创建、上传、同步或发送等动作以外部系统/应用为对象时形成外部写入冲突；版本、制品、构建产物、镜像、安装包、环境、GitHub Release、无法限定对象的裸发布以及提交推送继续失败关闭。`LOCAL_MAINTENANCE` 只由任务级明确配置/依赖维护指令触发，“可配置”能力、“不新增依赖”约束或“某类维护 tradeSeq/MDC”等源码职责描述不得把开发任务降级。只读 Reviewer 只由任务级评审、审查、检查或诊断动作选择；完整软件设计中的人工评审点、只读 getter/投影和验收复核措辞不得把写任务降级或制造读写冲突，显式任务级“评审并修复”仍必须人工确认。AI 标签、全自动模式和人工任务设置均不能覆盖真实危险操作证据。
- 请求线程和 Monitor 必须互斥领取同一 Router run；丢失乐观更新的启动方必须停止其刚创建的孤儿远端。Router 必须是真正的单次零工具业务分类器：不执行 MCP 发现，权限拒绝全部内置/MCP 工具，受管运行时选择 2 步 OpenCode 传输上限、零温度、非思考 `loopper-router` Agent；第 2 步仅用于避开 OpenCode 把配置的最后一步替换为收束提示，不得增加第二次业务分类。提示词只包含需求快照，禁止仓库搜索、技术栈推断、设计、实现推演和解释，只允许立即返回 `TASK_PROFILE_ROUTER_V2` 的任务意图、恰好一个主要制品及 SIMPLE/PACKAGED 三项标签；V1 额外字段仅兼容解析，不能影响决策。技术栈、组件与置信度必须由服务端仓库/需求证据和三标签一致性推导；失败或降级画像的 `confidenceAvailable=false`，客户端显示“未产生”而不是 `0%`。Router 的可配置 `task-profile-router-timeout` 默认 240 秒，只约束尚未持久化外部 Session ID 的连接等待；一旦连接成功必须持续轮询到远端真实终态，不得再按墙钟超时，读模型也不投影截止时间。未连接越界记录 `ROUTER_TIMEOUT` 和可重做降级结果。Router 新会话固定使用 `TEXT_MARKER` 与同一服务端闭集校验，不得向 OpenCode 持久化会触发桌面 Session 加载错误的 `json_schema` 响应格式。运行中取消必须携带期望 run ID 并领取同一并发边界，远端 abort 成功后才写入 `SUPERSEDED / ROUTER_USER_CANCELLED` 和阻断全自动的人工待选画像；失败时保留活动 run，过期、重复或争用点击返回 409。新讨论、重算或画像换流程必须在旧远端 Session 确认停止后才能废弃旧运行并创建替代 Session，停止失败时保留原画像和 Session。新建设计提交后必须留在当前设计页持续刷新；普通模式首次成功结果也必须停在可恢复的任务设置确认门，确认或手动覆盖后才创建需求 Designer Session；全自动只可采用成功且通过安全/组件校验的结果。未连接超时、运行失败、用户取消、危险证据或组件歧义不得自动越界。只有等价重算的人工设置可沿用 `USER_CONFIRMED_CARRIED_FORWARD`。重做接口必须校验最新终态 run ID、仍待决的任务设置及其版本，并仅使用该 run 的服务端持久化需求快照；已确认设置不得再重做，不得接受浏览器回传正文或创建并发 Router。
- 简单本地维护必须从确认稿提取明确的反引号相对路径，并生成精确 `allowedPaths`、`requireChanges=true`、`forbidDeletes=true` 的 `GIT_DIFF`；草稿确认和实施权限双重拒绝删除、通配路径、服务启停、Git 提交推送发布、外部应用与外部系统写入。
- Java 生产代码仍强制聚焦 Maven/Gradle TEST；统一 `TestFrameworkPolicy` 注册 Maven/Gradle/npm/pytest/unittest，解析显式目标并拒绝跳过参数。Python/Node 按仓库测试框架与用户要求选择 REQUIRED/OPTIONAL。无测试体系的独立 Python 脚本可用 SELF_CHECK 加原生输出验证；文档、一次性表格转换和只读报告为 NOT_APPLICABLE，不得生成 PROCESS TEST。
- `SERVER_DOCUMENT_MATERIALIZATION` 和 `SERVER_TABULAR_CONVERSION` 只能在显式 Task Start 后执行冻结 `artifact_plan`，创建正常 Attempt 但不伪造 OpenCode Session。`DOCUMENT_STRUCTURE`/`TABULAR_DATA` 是行为验证器；BUILD、GIT_DIFF 和报告证据仍不能冒充业务验收。

- Designer 新建会话先进入 `DISCUSSING_REQUIREMENT`。普通软件需求的每个讨论修订只提问一次 1–3 个选择题；服务端必须先探测项目作用域 OpenCode 工具，只有 `AVAILABLE` 且明确包含 `question` 才创建 `DESIGNER_INTERACTIVE_READ_ONLY` 并调用原生问题接口。`UNKNOWN`、`UNAVAILABLE` 或列表不含 `question` 时使用 `GENERAL_READ_ONLY`：AI 只输出普通文本问题，服务端以 `CHAT_QUESTION` 保存，页面显示“对话回答模式”并在 `RUNNING`/全自动模式下开放聊天输入框；用户回答必须先写入同一决策日志，再由服务端生成普通需求快照或让同一大型工作包 Session 继续生成完整设计。能力缺失不是 Session 错误，不进入 `DESIGN_QUESTION_REQUIRED` 修复循环。原生回答后允许 AI 空正文，服务端按时间原样拼装原始需求、需求作用域补充和持久化最终回答，后写优先，禁止把 AI 自由文本、仓库推断或任务画像混入需求语义。快照以 `SERVER_REQUIREMENT_SNAPSHOT` 系统来源消息保存并作为冻结需求的精确 `source_message_id`，页面通过独立只读卡片展示且不重复进入系统消息折叠组；历史冻结 AI 快照作为兼容基线。超过 24 KiB UTF-8 必须以 `REQUIREMENT_SNAPSHOT_TOO_LARGE` 阻断，不得截断或调用 AI 压缩。大型任务继续在需求讨论和每个工作包初稿/人工修订中提问；大型完整需求替代快照仍受 24 KiB UTF-8 防护，工作包设计稿不设固定字节上限。只有已证明支持原生工具但遗漏必需问题时，才允许在全新 Session 补问一次，再次遗漏以 `DESIGN_QUESTION_REQUIRED` 进入 `WAITING_INPUT`。正常讨论/评审使用 Designer Session `REVIEWING`，不得滥用 `WAITING_INPUT`。
- 最终工作包聚合只允许更新 Stage、上下文与执行限制，聚合 `goal` 必须来自 Designer 会话持久化的首条用户需求；服务端需求快照和 Decomposer `normalizedGoal` 只作冻结需求/规划证据，不得覆盖任务标题或后续 `loopper/<任务名>` 分支来源。
- 已回答的 Designer 问题不得从讨论记录中消失：服务端从持久化决策日志权威投影原问题、标题、全部选项说明和规范化最终回答，页面默认折叠为“需求讨论”，展开后完整展示；刷新、进程重启和历史旧格式恢复不得依赖浏览器本地状态。新决策日志必须保存完整问题结构，旧版仅含问题文本与答案的日志继续兼容读取。
- Recovery/重做生成的新 Task 必须持久化原始设计来源 Task、Loop Draft 和 Designer Session；任务历史设计使用子 Task 自己冻结的 LoopSpec，但需求、对话、问题、拆包和包设计从这条不可变来源读取。连续重做必须继续指向根设计来源，历史旧数据只允许沿 lineage 有界解析，不得复制对话或依赖当前子 Task 恰好存在 Designer Session。
- 拆包前的需求消息只更新需求讨论，不调用 Decomposer；只有显式确认需求才冻结下一编号需求版本并拆包。拆包后旧 `/messages` 缺少作用域时必须返回 `DISCUSSION_SCOPE_REQUIRED`；修改整体需求要显式重开并废弃当前拆包/批准，包级消息只能修改当前包且不得创建新需求版本或重跑 Decomposer。全部作用域写请求和批准都携带期望讨论/设计修订，过期操作返回 409。
- Designer 写接口允许用空响应体返回成功的 `202 Accepted` 或 `204 No Content`；前端公共 API transport 必须把任意成功空响应解释为已完成的 void 操作，再刷新服务端权威快照，不得对空内容调用 JSON 解析器并误报失败。
- 只有冻结为 `FULL_PACKAGE_DESIGN` 的完整需求才交给独立只读 Task Decomposer；只允许 `read/glob/grep`，不得写文件、执行命令、提问或创建 Task。服务端按非空段落/列表编号并校验每段被全局约束或至少一个工作包引用。`DIRECT_SOFTWARE_DESIGN` 不创建或调用 Decomposer Session，由服务端直接生成覆盖全部 RQ 的 `DIRECT_DESIGN / WP-1`。
- `DIRECT_DESIGN` 恰好一个包且允许 1–6 个 Stage；大型任务拆成 2–6 个依赖有序的纵向业务包，每包 1–3 个 Stage、总计不超过 18 个。禁止把数据库、后端、前端、测试机械分层拆包。普通模式需要第 7 个 Stage 时必须以 `LARGE_TASK_MODE_REQUIRED` 立即停止，不重设计、不自动切换；只有用户显式点击“改用大型任务”才重开当前需求。多项目根、超过六包或多个独立发布边界仍必须返回 `MULTI_TASK_REQUIRED` 并等待人工，不自动创建子 Task。
- 工作包严格串行执行。大型任务每包在健康时复用自己的只读交互 Designer Session；普通 WP-1 使用不开放 `question` 的通用只读 Session，创建后直接进入 `DESIGNING`，初稿、人工反馈和重新设计都直接输出完整替代设计，不得创建包级 Pending Question 或进入 `QUESTIONING_PACKAGE`。每个候选使用当前配置的同一模型。工作包 Designer 输出不设固定字节上限并按原文持久化；当前软件设计固定使用“目标与范围 / 影响与交付 / 验收场景 / 可选人工评审项 / 验收约束 / 阶段与依赖”，每个必需章节恰好出现一次、可选人工评审章节至多一次，同一响应重复整套设计或缺失任一固定章节必须重设计，不得合并抽取；场景列固定为“场景 / 前置或触发 / 操作 / 可观察结果 / 保持不变”，当前 v7 阶段列固定为“阶段 / 目标 / 负责路径 / 包含场景/评审/交付 / 前置阶段”，冻结 v6 四列表格继续兼容。“负责路径”只列该 Stage 承担写入责任的仓库相对路径/规则，每条必改路径必须有且仅有一个可证明阶段，不得把整包路径复制到所有 Stage；“包含”必须原样引用前文标题并只用中英文分号分隔，前置阶段只能原样引用更早阶段，空值或“无”表示无依赖。Designer 不得输出 WP/AC/DS-L ID、LoopSpec JSON 或可执行 argv，只能写相对路径/符号、测试类或原生测试路径和独立性约束。
- V41 为每次新软件包编译冻结一条 `design_acceptance_planning`：CommonMark AST 与 GFM 表格提取 `SCENARIO/REVIEW/SCOPE/DELIVERABLE/POLICY/DEPENDENCY` DesignFact，保存精确原文、稳定引用和 SHA-256；工作包设计没有固定字节上限，仍限制为 64 个场景和 128 个事实。服务端从冻结 Role Pack、技术栈和测试策略生成闭集验证能力，Java/Node/Python/混合栈只允许仓库原生聚焦目标；用户要求“独立/分别/各自通过”时相应能力为强制项，“无 `@SpringBootTest`/无框架上下文”等否定标记只作为约束而不能创建测试能力。AI 不得生成命令、路径、测试目标或验证器。规划持久化状态固定为 `EXTRACTED / BOUND / COMPILED / FAILED`：闭集求解得到 `DESIGN_INCOMPLETE` 时写入 `BOUND` 并由编排器进入重设计/人工输入流程，不得把编译结果码直接写入状态列。
- 当前 Role Pack 的新软件包使用 `DESIGN_ACCEPTANCE_V7`：同一不可变 V41 `facts_json` 额外冻结需求正向新增/修改/实现/写入路径、受控正向 `DELIVERABLE/SCOPE` 路径和工作包显式 `scopeIn/deliverables` 规则形成的 `WRITE/DELETE_REQUEST/MOVE_SOURCE/MOVE_DESTINATION` Mutation Obligation，每项必须区分 `EXACT_PATH/PATH_RULE` 并保留来源引用、有界原文和 SHA-256；显式目录必须作为子树 `PATH_RULE`，API 路由和业务符号不得当作仓库文件，同句多个路径的写/删/移动动作无法逐路径唯一绑定时必须失败关闭。无动作语义时，裸的无扩展名斜杠标识符不得只因 `/` 被提升为未分类路径；只有已知仓库根、扩展名、glob 或明确路径/目录/文件上下文才形成未分类阻断，明确写入、删除或移动动作仍按完整标识符提取义务。否定、不变、示例、纯符号和项目根外路径不得转成写权限，需求、受控设计或冻结包字段中的宽泛 glob 必须保留为可审计路径规则义务，只有 Stage 的唯一显式负责路径或其他运行期可证明规则才能形成归属；v5/v6 缺失列表时按原语义空列表恢复且不得重推断，冻结 `dynamic-v6` 工作包升级后首次编译仍生成 V6 快照并沿用 v7 极性分类前的历史 Stage 路径选择。项目根外正向路径必须在创建 Compilation 前以原权限错误收束且不得按模型传输失败重试；正负或强路径证据下的未分类作用域必须定点阻断。Stage 组装后，服务端按顺序只接受以下义务归属证明：唯一 `负责路径` 声明、Stage 精确引用产生义务的受控交付/范围事实、恰好一个现有 Stage 路径规则按运行期语义覆盖义务、旧四列表格中仅一个 Stage 标题/目标包含的完整文件名/类名/末尾路径 token，或单 Stage 计划把精确 `WRITE/MOVE_DESTINATION` 补入该 Stage；旧格式兼容只做 NFKC 和完整 token 匹配，不做模糊或语义猜测。多个 Stage 声明、覆盖或命中同一符号时必须以候选 Stage 中文名定点阻断，路径义务不得进入 Compiler 模型输入输出，也不得触发整份工作包自动重设计，只进入定点人工输入门；`DesignerMutationOwnershipRecovery` 独占未归属路径恢复投影和提示，相同设计修订不得原样重编译，包级反馈和恢复提示必须携带全部未归属路径与候选阶段并要求完整替代设计，`DesignerSessionService` 只负责门禁与路由且不得提高遗留行数上限。当前 v7 普通包、大型任务包和滚动执行当前包在事实与能力均可确定时统一服务端直编，不因包形态强制创建 Compiler Session。随后 lowering 前必须复用运行期 `VerifierPathPolicy` 的有界规则集合包含/交集语义，证明义务不命中禁止规则；包级 `scopeIn`、全局事实和技术栈 fallback 只能形成执行路径，不能单独证明义务归属。Stage、focused test 与显式 `GIT_DIFF` 必须共用同一 allowed/forbidden 集合；遗漏返回 `REQUIRED_MUTATION_PATH_UNASSIGNED`，禁区、删除和移动源端返回 `REQUIRED_MUTATION_PATH_FORBIDDEN`。不得用 Judge-only、catch-all Stage 或放宽 `VerifierEngine` 消除该缺口；诊断从初始路由起只公开义务总数、已解决/未解决数、`NOT_EVALUATED/CONSERVED/BLOCKED`、项目相对路径、绑定理由和 Stage 中文名称，不公开内部索引或原始 JSON。
- V44 为 `design_acceptance_planning` 增加稳定 `binding_source`：`UNDECIDED / SERVER_STAGE_HINTS / AI_DISAMBIGUATION_V6 / LEGACY_UNKNOWN`。`diagnostics_json` 保存快速路径决策、未解析引用和调用 Compiler 的原因；API 只投影业务化 `bindingSource/routingReasons`。服务端直接路径按 `PENDING_HANDOFF -> RUNNING(SERVER_DIRECT) -> COMPLETED/DESIGN_INCOMPLETE` 审计并可由 Monitor 幂等恢复，但不伪造远程 Session；前端显示“服务端直接编译 / 规范工程师辅助消歧 / 历史编译”，不轮询不存在的活动，`serverCompiled` 仅兼容旧客户端且不得推断来源。
- V42 用不可变 `project_stack_profile / project_stack_component` 保存项目技术栈与模块基线。新登记或取消管理后重新登记的项目在登记事务提交后自动分析；既有受管项目不批量回填，项目列表只连接最新持久化摘要并投影 `UNANALYZED`，不得产生文件系统 N+1。第一次创建新 Designer 或点击“AI 更新 Loopper 公约”时按需分析，后者必须强制重析。分析最多读取 2000 个非符号链接普通文件、深度 5，并跳过生成目录；Maven/Gradle、`package.json`、Python 配置/测试、Go/Rust 分别形成 Java、Node、Python、Other 组件，同一组件根真实跨软件族才是 mixed。指纹必须基于排序后的相对 Manifest 路径与内容 SHA-256；失败持久化 `FAILED`，超限或证据不完整持久化 `PARTIAL`，任何状态都不得隐式兜底为 Java。
- V42 Router 只能用需求相对路径、模块名和明确技术词在当前项目画像证据内选择组件；AI 三标签不能选择或发明技术栈，也不能覆盖证据、权限、测试或执行策略。单栈可自动选，明确跨组件使用 `software-mixed`，多栈歧义、`PARTIAL/FAILED` 或空仓库无明确技术必须进入确认，空仓库使用 `software-generic`。任务画像、Router run、工作包和 Stage 冻结项目画像 ID、Manifest 指纹与组件键；普通 WP-1 继承已确认组件，只有大型任务允许包级专门化。冻结 Task/Stage/Recovery 永不被后续重析改写；未冻结 Designer 仅在需求重算时使用新画像，且只有指纹、组件、意图和流程均一致才能继承原确认。
- “AI 更新 Loopper 公约”必须在数据库事务外先强制重析，再创建只读 AI Session；`FAILED` 不启动 AI，`PARTIAL` 必须显示复核警告。AI 必须根据结构化画像和当前文件重新生成 `技术栈与模块 / 构建与测试 / 目录与边界`，服务端拒绝缺失章节、超限内容和画像中不存在的已知技术。`project_convention_draft` 冻结画像 ID 与指纹；预览不写文件，确认写入前同时复核原 `AGENTS.md` 哈希和实时 Manifest 指纹。没有 marker 时首次追加，已有 marker 时仅替换管理区块，区块外人工内容必须原样保留；`AGENTS.md` 只服务于 Agent 上下文，不是机器路由权威来源。生成中的最新思考、工具/输出片段与 Provider 权威 Token 必须由服务端活动接口投影并在弹窗持续刷新；V43 持久化进度指纹和显式停止意图用于重启恢复，但连接成功后不得因无进展或总时长自动停止，必须持续轮询到远端真实终态，弹窗不得展示超时上限。用户显式停止必须先持久化 `STOPPING`，确认远端终止后进入 `CANCELLED`；浏览器关闭弹窗不得冒充远端取消。
- v6/v7 软件验收先由 `DesignerAcceptanceFastPathResolver` 执行服务端符号解析：只允许 Unicode NFKC、首尾裁剪、连续空白折叠和拉丁字符大小写归一，不删除标点，不做子串或模糊匹配；`SCENARIO/REVIEW` 恰好属于一个 Stage，`DELIVERABLE` 可共享；V6 的 `SCOPE/POLICY` 为包级约束，V7 仅允许正向受控 `SCOPE` 被 Stage 精确引用作路径 provenance，`POLICY` 仍为包级约束；阶段名唯一、数量 1–6 且依赖只能指向前序。当前 v7 普通 `DIRECT_SOFTWARE_DESIGN / WP-1`、大型任务包与滚动执行当前包在闭集事实和能力已确定时均不创建候选 Session、不消费模型调用，按 Designer 阶段数直接进入能力求解和 LoopSpec v2 lowering；冻结 v6 大型包继续保持历史的一次 Compiler 交接摘要语义。当前 v7 对全部可覆盖事实运行一次全局能力集合求解：完整覆盖和强制能力是硬约束，再依次偏好少 Judge-only、少非确定性能力、少能力和高证据强度；稳定索引只能稳定输出，不能打破业务真实同分。唯一最优记录 `compilerAvoidedReason=UNIQUE_OPTIMUM` 并直接编译；非枚举、未穷举、路径归属或权限安全问题以 0 次模型调用失败关闭；只有全部业务维度同分且服务端证明 2–32 个穷举等价最优集合时，才创建一次 `ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS` Session；`ACCEPTANCE_CLOSED_CHOICE_V7` 内部 MCP 候选默认不限提交次数，新运行可按 V71 冻结 2–16 次总提交上限。模型只看到稳定索引和 `closed-choice-n` 不透明标签，不得看到命令、测试目标、路径、Stage 拓扑、权限或安全策略；闭集选择的遗漏、越界、组合错误以及安全的字段形状错误返回有界诊断，按返回的 revision 在同一 Session 提交完整修正对象；简写候选本身永不接受，候选修正不能改变冻结事实。权限、安全、身份或非枚举的真实业务缺口仍等待人工；有冻结次数上限时按该策略收束，不能重新套用历史“两投一次修正”的限制。v7 内部 MCP 候选在 0.3.5 隔离成品 JAR 证明真实同 Session 拒绝后自修正后默认开启；显式设为 `false` 只回滚新运行并保留持久化恢复。关闭时，新真实同分使用全新 `COMPILER_BINDING_NO_TOOLS / PACKAGE_ACCEPTANCE_CLOSED_CHOICE_V7` JSON Session，不得复用或升级旧 Session。冻结 v6 继续使用 `PACKAGE_ACCEPTANCE_DISAMBIGUATION_V6` 严格原合同。服务端只从正向交付物、验收约束和阶段关系发现测试能力，负向/不变文本不得生成测试目标；测试限定名按主体符号归一并通过标识符、CamelCase、标题优先和中文语义唯一胜者竞争映射，每个业务条件至多绑定一个 focused test，独立必跑但无专属场景的目标只生成一次带原始来源的独立机器条件；仅测试路径生成 `JAVA_TEST_ONLY`，不得伪装生产变更。分组引用的非验收事实必须保留为该 Stage 的交付与路径边界；只有独立完整的仓库相对路径或 glob 才可进入路径合同，含说明、连接词或中文标点的 scope 整句不得因包含 `/` 被当成路径。求解超过 100000 节点时只允许确定性贪心复核并按非穷举诊断阻断模型替代权威评分；随后唯一派生 `COMPILED` 或具体 `DESIGN_INCOMPLETE`，生成 EARS 验收文字、`<workPackageId>-AC-n`、精确来源、直接 argv、测试目标和验证器，并继续通过 `DesignerPackagePlanCompiler` 与 LoopSpec v2 全量校验。不得用 Judge-only 替代 focused-test 门禁。冻结 v4/v5/v6 工作包按自身合同恢复；无闭集能力覆盖时必须失败关闭，不得因模型猜测放行；UI 只展示有界中文场景/能力投影，不泄露内部索引或原始 JSON。
- 当前 v7 若冻结拓扑恰好一个 Stage，遗漏的 `SCENARIO/REVIEW` 事实必须由服务端归入该唯一 Stage，不创建 Compiler Session；Stage“包含”栏里无法对应任何冻结事实的附加说明标签只记录 `UNLISTED_STAGE_REFERENCE_DROPPED` 并丢弃，不得做模糊匹配，冻结 v5/v6 继续保持历史阻断语义。多 Stage 确需闭集选择时，提示投影必须为每个候选显式列出零基 `stageIndex`，并为每个未决事实列出完整 `allowedStageIndexes`；“阶段 1”等人类标签不得替代索引，输出越界仍失败关闭。
- 当前 v7 真实同分时只向候选角色投影穷举等价最优集合间成员关系不同的能力索引和不透明标签；模型返回的完整判别索引并集必须恰好等于其中一个最优集合，共同成员、较弱候选、局部选择或跨最优组合均不得进入有效选择。非穷举结果只作阻断性诊断，不能创建候选 Session 或编译计划。
- v7 上线必须通过 `scripts/evaluate-weak-model-v7.sh` 的版本化脱敏 corpus、同冻结输入只读 shadow 和完整 qualification：corpus 逐样本固定独立修改义务/硬缺口预期并精确执行引用的生产算法 guard，但手填预期只能形成 `authoritativeGate=false` 的期望报告，禁止作为测量值直接送入门禁；生产 guard 必须通过测试专用有界 registry 发布同输入端到端/Judge/focused、路径守恒与歧义硬缺口、唯一最优/真实同分路由、闭集选择工作流实际 prompt/Session 数、外部写阻断及配对 v6/v7 Compiler 调用和重设计的实际计数。单一样本必须标记为非完整资格，无适用分母的比率为未产生而不是虚构 100%；只有全部 22 个精确生产 guard、7 个补充指标 guard 与 1 个同输入实测共同通过且实际计数达标的 qualification 可标记 `authoritativeGate=true`。其中 4 条候选工作流必须从同一次实际运行原子记录 `modelCalls / candidateSessions / candidateSubmissions`：唯一最优、非枚举和路径安全阻断均为 `0/0/0`，真实同分为 `1/1/1..2`（仅为该固定夹具的测量预期，不是生产 MCP 提交次数上限）；不得从任一轴推导另一轴。任一实测计数越界、基数消失、路径/硬缺口/focused-test 单样本退化均失败，禁止跨样本超报抵消；显式写入/移动目标路径守恒和硬缺口保持均为 100%，已知路径逃逸和危险自动授权均为 0，端到端可执行率、Compiler 模型调用、整稿重设计、Judge-only 占比及 focused-test 覆盖不得退化，fresh/upgrade SQLite、重启、普通 WP-1、滚动包和冻结 v5/v6 兼容必须通过。registry 的 evidence ID、指标名和标记必须为闭集，未知字段、负值、冲突值及从固定常量伪造硬缺口或危险授权都必须失败；registry 和评估器只能携带有界计数、稳定缺口码与布尔标记，不保存路径、需求或 Session ID，不写权威规划；synthetic 指标不得冒充真实弱模型通过率，发布仍需以隔离数据目录和成品 JAR 完成 Designer 到 Review Gate 回放，且不得替换 8080 实例。
- Decomposer 紧凑语义合同默认经 `DECOMPOSITION_PLAN_V2` CandidateSubmission 传输：受管模式一个只读候选 Session 不设 MCP 唯一提交次数上限，拒绝响应只返回有界机械/语义问题供同一 Session 修正，`ACCEPTED/WAITING_INPUT` 而非 assistant final text 才是权威；关闭开关或外部 `auto/http` 必须创建全新 `IN_PROCESS_LEGACY` Session 读取旧 marker JSON，不能把内部 MCP Session 的终文静默改当兼容候选。历史 v3 Compiler 仍使用既有紧凑合同；只有没有 V41 快照的历史活动或明确 v3 信封才使用最多 16 个 `add/replace/remove` 的 `AI_SEMANTIC_PATCH_V1`，并继续保留各两次格式/语义修复。冻结 v5 验收绑定继续按历史建议合同恢复；新 v6 消歧形状错误不得丢弃为空建议继续，而应直接定点重设计。每个只读角色已确认的传输失败允许一个全新 Session 重试。整个需求版本最多 96 次模型调用；各包内容次数互不挤占但受全局上限约束。
- 新 Decomposer 紧凑规划、Compiler 紧凑规划和 Judge 必须优先使用服务端固定 ID 的 OpenCode JSON Schema；旧 final Schema 只保留给缺少语义快照的历史活动记录。provider 内建 schema 重试固定为 0。只有格式接口拒绝、明确 `StructuredOutputError` 或完成后缺失 structured payload 才能在全新只读角色 Session 中回退到 marker，并计入当前步骤原有模型调用与格式修复预算；不得在失败 Session 内继续、增加隐藏重试池或绕过确定性语义校验。历史活动记录按 `TEXT_MARKER` 兼容。
- OpenCode `RETRY` 是 Provider 自恢复中的瞬态 Session 状态，统一适用于交互式 Designer、Decomposer、Compiler、Implementation、Judge、项目公约、提交建议和本地同步：必须保留原远端 Session 继续轮询，不得新建 Attempt/Judge、消耗 Loopper 重试预算、写入 Session 错误或把它当作旧 writer 已停止；Designer 流程保持 `RUNNING`，不得阻断已授权的全自动模式。除已明确采用连接后无限等待的 Router 和项目公约外，各调用方自身保留的角色/操作超时继续生效。固定 agentic 步数由 `OpenCodeAgentPolicy` 统一控制受管 Agent 和本地消息计步：Designer（需求、工作包及 MCP 候选）、Implementation、Reviewer（Legacy/MCP）和 Requirement/Risk Judge（Legacy/MCP/工具循环收尾）不设固定上限；其中机器响应角色选择不含 `steps` 的 `loopper-structured-unbounded`。Decomposer、Compiler（含绑定、修复、验收选择）、滚动规划、项目公约和非 Judge 通用 finalizer 保留 `loopper-structured` 的 24 步；Router 保留单次业务分类并使用 2 步 OpenCode 传输上限，独立工作量统计命令保留 2 步。旧调用方显式指定有界 Agent 不能重新限制豁免角色；外部 OpenCode 不发送新版私有 Agent 名称。若最近一次用户提示后的同一规范化工具名和参数连续出现 3 次，必须立即尽力 abort，并且每个角色步骤最多启动一次禁用全部内置工具的 finalizer Session；恢复资格和纠正类别持久化在 V28，finalizer 计入全局模型调用预算但不占格式修复次数；Judge 使用独立 `JUDGE_FINALIZER_NO_TOOLS` 延续步数豁免，保持原 finalizer 的权限。豁免角色仍执行结构化格式与重复工具检查，角色超时与 Task 预算独立保留；MCP 候选提交次数自 V69 起默认不设上限，V70/V71 的新运行可冻结独立次数策略。OpenCode 最大步数控制提示经 `OpenCodeStepLimitNotice` 在 HTTP 结果与工作包 Markdown 兜底入口以 `OPENCODE_STEP_LIMIT_REACHED` 阻断，Router 轮询必须保留该具体错误码，活动投影必须隐藏控制原文，不得保存为业务设计或进入验收编译；引用该提示的正常参考内容不受影响。新 Agent 配置随新版受管进程启动加载，不热改已有进程或历史设计。若 structured prompt 已接受但消息读取接口随后以格式/Schema 400 拒绝，必须按结构化格式不支持进入既有全新 marker Session 回退。结构化角色最终进入 `WAITING_INPUT` 或 `SESSION_ERROR` 前必须尽力 abort 当前远端 Session，UI 的“已停止”不得与仍在读仓库的远端执行并存。
- Decomposer、Compiler 和最终 Judge 只有在当前持久化步骤实际使用 `JSON_SCHEMA` 时才显式使用 `thinking=false`；Loopper 管理的 DeepSeek Runtime 为当前配置模型注入 `loopper-no-thinking` variant（`thinking.type=disabled`），且 HTTP 适配器只允许 Schema Prompt 选择它，避免 Thinking 与 JSON Schema 强制工具选择冲突。`TEXT_MARKER` 初始、重试、Schema 回退和 finalizer Session 必须保留配置的 thinking 或 Provider 默认值，并继续通过同一 JSON 提取、确定性校验和修复预算。机器角色仍使用零温度和禁止重复/虚构工具调用的固定指令。OpenCode 1.18.12–1.18.18 已确认会在读取自身持久化 Schema 时返回 400，必须直接使用 marker 兼容模式；后续版本恢复能力探测。交互式 Markdown Designer 和可写 Implementation 继续保留配置/LoopSpec 的 thinking 选择；复用外部 DeepSeek Runtime 时由操作者提供同名 variant，缺失时不得绕过既有全新 Session marker 回退。
- OpenCode Session 使用角色权限模板。普通角色在创建前按项目目录读取 `/mcp`，把已连接用户 Server 的 `<server>_*` allow 叠加到既有模板；发现失败必须在提示发送前明确停止，不得修改用户配置。Router 固定零工具且跳过 MCP 发现；Legacy Requirement/Risk Judge 保留原只读内置工具和用户 MCP，但不获得私有候选工具权限（冻结附件可由 OpenCode 原生 Resource 输入读取）；每个 Candidate profile 只保留该角色合同允许的只读工具和本代随机 Server 下唯一角色专属提交 Tool，不得获得恢复专用 `submit_candidate`、其他角色 Tool、shell、写入或用户 MCP；验收闭集候选不开放任何内置工具或用户 MCP。七个角色 Tool 共用一个私有 Server、统一生命周期/持久化内核和服务端权威，不拆成七套 MCP Server。私有名称即使与继承配置碰撞，也只允许覆盖受管子进程的环境叠加层，不得写用户/项目 OpenCode 文件；公共六工具 Provider 与私有内部 MCP 必须保持分离。只读角色仍拒绝 `.env`/`.env.*`、外部目录和全部其他内置工具，MCP allow 不得解除写文件、Bash、Git、外部目录或 Loopper 人工授权边界。Runtime 可展示 agent、原生 `plan`、代际短标识和内部 MCP 就绪，但不得返回私有 Server 全名、Bearer 或让 Designer 接管原生 plan。
- V69 取消全部七种 `INTERNAL_MCP` 候选的默认提交次数上限；以下无限制语义适用于未冻结 V70/V71 次数策略的运行：可修正拒绝持续为 `OPEN / REJECTED`，不因次数进入 `WAITING_INPUT / FALLBACK_REQUIRED`。响应以 `submissionCountLimited=false` 和 `remainingAttempts=null` 明确无限制，`attemptOrdinal / attempts_used` 与提交修订继续真实递增；相同幂等键与摘要仍回放原安全响应。`max_attempts` 保留为 `IN_PROCESS_LEGACY` 修复预算及既有 launch/run 不可变身份字段，不能再解释为 MCP 配额。升级以独立 savepoint 原子重建 run 表，仅放宽 MCP 计数 CHECK，保留全部外键、索引、触发器和历史响应；旧 `OPEN` 运行适用新规则，已结束运行不得自动重开。非可修复错误、成功后关闭、用户停止、代次/来源/权限、超时与重复工具检测仍按各自合同处理。
- `INTERNAL_MCP` 的可修正性不得等同于“纯格式机械错误”：安全 JSON/字段形状、候选拥有的语义遗漏、覆盖不全、验收归属/验证能力歧义及闭集引用错误都必须通过 `CANDIDATE_DIAGNOSTIC_V2` 返回有界 `parameter / JSON Pointer / category / expected / actual / detail / allowedValues / repairHint`，并用 `diagnosticsComplete / problemCount / returnedProblemCount / truncated / action / submissionRevision` 明示本轮是否完整及下一步；达到上限必须按 UTF-8 字节确定性安全截断并声明不完整，不得用 Java 字符数近似或退化为通用内部错误。`actual` 必须解析该 Pointer 对应的候选局部类型和值；根级 `/candidate` 只返回类型、UTF-8 大小和顶层字段摘要，不复制完整候选。包设计语义错误还要绑定 `SC-* / REV-*`、阶段/证据项、全部冲突位置及为消歧所需的候选原句，禁止使用 `value satisfying ...`、`does not satisfy the declared contract` 或 `Replace candidate` 等占位诊断。可修正拒绝保持同一 run/Session 为 `OPEN`，模型必须重新提交完整对象，不能通过逐字段 mutation 留下半成品。只有真实用户缺失语义、大型任务模式选择、路径/命令/权限/身份/执行权威、安全边界、非枚举或服务端运行冲突才可进入人工/终止边界；模型将 `AMBIGUOUS_ACCEPTANCE_INTENT / VERIFICATION_CAPABILITY_UNAVAILABLE / REQUIRED_MUTATION_PATH_UNASSIGNED` 包装为 `NEEDS_INPUT` 仍必须拒绝并继续修正。普通未知字段是可修正形状错误，只有服务端权威字段名才失败关闭。
- V47 的 `MachineCandidateSubmission` 是独立权威状态域：run 冻结角色 kind、唯一 owner、来源/owner 版本、通道、外部 Session、运行时代际和冻结的 Legacy 修复预算元数据；精确幂等重放返回同一安全响应，复用 key 但 payload 不同、乐观锁冲突、跨 owner/kind/channel/generation 均失败关闭。拒绝原始候选正文不得落库，只保存 SHA-256；角色专属 `ROLE_SPECIFIC_V2` 诊断可持久化同一模型已经提交的有界局部实际值和候选原句，但不得附带未出现在候选中的冻结秘密、凭证或外部敏感值，`LEGACY_COMPATIBLE` 继续只保存静态脱敏诊断。accepted writer 必须在同一短事务中从数据库冻结事实生成规范结果并推进 owner，MCP/模型成功本身不具权威性。V69 起七种角色的 MCP 提交默认无次数上限，V70/V71 可为新运行冻结 2–16 次总提交上限；原 max_attempts 仅保留为 Legacy 修复预算及不可变合同元数据。v7 验收闭集的 `ACCEPTED/WAITING_INPUT/CLOSED` 不是远端停止证明：每次恢复仍须复核 binding/owner/source/external Session 和精确已知 owner 版本步数，只有 `REMOTE_COMPLETED / ABORT_ACKNOWLEDGED / ALREADY_ABSENT` 持久化后才可编译、进入人工输入、失败收束或切 legacy；停止/传输/代次未确认保持同一 run 的 `DISCONNECTED` 投影并由 Monitor 重试，不得编译、建 Task、重复 prompt/submission、关闭 run 或放开新 writer。OPEN run 只允许一个精确 `DISCONNECTED` owner checkpoint 后继续同 Session 提交，额外漂移拒绝。外部运行时在 internal run 创建前被绑定守卫拒绝时也必须先确认旧 remote abort；未确认保持原 compilation/Session `RUNNING + DISCONNECTED` 且不建 run/prompt/Task，由 Monitor 重试，只有 ACK/ALREADY_ABSENT 后才创建全新 Legacy run。V50 持久化 close reason，只有 `NORMAL_COMPLETION_ZERO_SUBMISSION` 配合真实远端完成可切 legacy；timeout/provider/interaction/owner close 和历史 `CLOSED + NULL` 失败关闭。外部 I/O 后必须由短事务重新核验原 run/version、Designer 非 `STOPPING/CANCELLED`、owner/source/external Session/binding 后 CAS proof，拒绝不得进入通用失败收束。proof 已持久化后允许 JVM/受管代次轮换及精确 `SERVER_COMPILING/serverCompiled` 两步幂等恢复，额外 owner 漂移仍拒绝；proof 前仍必须校验原活动代次。终止证明闭集由共享 `CandidateSessionTerminationProof` 持有，通用 runtime guard 不得依赖具体角色 orchestrator，workflow 只可通过窄 `Port` 调用 Designer facade。
- V51-V55 把 Acceptance Legacy handoff、internal launch、prompt dispatch 与 termination 分成四个持久化协议：所有远端创建参数、权限/请求摘要、运行代次和一次性本地 credential 必须在 I/O 前冻结，回读 remote 未通过 attestation 只能 cleanup，run open 与 settlement certificate 必须同事务；提示的模型调用消耗和可能派发必须在 HTTP 前不可逆记录，结果不确定不得盲重发。取消、需求替换和首次提示 `BUDGET_EXHAUSTED / LOOKUP_UNSUPPORTED / RESULT_UNKNOWN` 只能先创建唯一 typed termination intent，取得 prompt/run/remote 安静及正向停止证明后才可原子推进父状态。首次失败后到达的取消或替换只提升同一 ready intent，不得并存第二条终止权威。新内部 launch、prompt、submission 和父终态均受数据库 gate 约束，应用层检查不能替代迁移级不变量。
- V48 的 `PACKAGE_DESIGN_V1` 为工作包设计增加 MCP 主路与 Markdown 兜底：每个包修订使用一个独立候选 run，在所属设计师 Session 的当前回合内提交完整替换对象，MCP 不设次数上限，候选不得携带命令、可写路径清单、测试命令、Verifier、权限、安全结论或稳定 ID；MCP/Markdown 两入口必须共用 `PackageDesignCompilation` 确定性内核。`READY` 候选的内容遗漏、覆盖不全、验收归属或验证能力歧义必须返回可修正问题，不能误转 `WAITING_INPUT` 或 abort；模型明确提交的真实 `NEEDS_INPUT` 和路径/安全/权限边界仍失败关闭。只有远端 `COMPLETED` 且最终 Markdown 非空的零提交，或恢复历史已终结为 `FALLBACK_REQUIRED` 的候选，才允许 `MARKDOWN_FALLBACK`；修订/运行代次冲突、超时、传输失败和停止未确认全部失败关闭。接受结果原子保存规范候选、服务端 Markdown、编译结果、SHA 和 `settledCompilationId`，重启幂等推进。页面只读取候选状态、Session/提交计数、来源和兜底原因，不得反推；所有提交计数显示为“候选提交”，不得误称全部都是修正。大型任务在工作包轨道展示，默认单包继续隐藏审批轨道但必须用独立摘要展示同一组服务端事实。隔离成品 JAR 已真实证明同 Session 拒绝后修正接受与 Markdown-only 零提交兜底，因此生产默认开启；`LOOPPER_PACKAGE_DESIGN_CANDIDATE_V1_ENABLED=false` 只回滚新工作包，已有候选与接受结果仍须恢复。
- V49 将 CandidateSubmission run 的 `designerSessionId + nullable owner` 升级为 `CandidateScope(DESIGNER_SESSION/TASK/PROJECT,type+id)` 与 `CandidateOwnerRef(type+id)`：数据库必须保持恰一作用域外键，owner 必须真实属于该作用域，作用域/owner/kind 创建后不可变；V47/V48 的 run、attempt、accepted result、外部 Session/代际绑定、乐观版本、唯一 open run 与级联删除必须无损保留。`ROLLING_PACKAGE_PLAN_V1 / REVIEWER_REPORT_V1 / PROJECT_CONVENTION_V1 / JUDGE_DECISION_V1` 均不限制 MCP 提交次数；原 3/2 次仅保留为 Legacy 预算及冻结合同元数据；V56 激活 Rolling，0.3.10 增加 Reviewer 单一确定性编译器，V57 为 Reviewer/Convention/Judge 增加共用 `GENERIC_V1` 持久化 launch/prompt/termination 底座，V58–V61 接通 Reviewer，V62 接通 Convention，V63 接通 Judge。V57 的 `candidate_launch_id` 与 Acceptance `ACCEPTANCE_V55` 的 `internal_launch_id` 必须互斥，Java 只可使用强类型 `CandidateLaunchRef`；三个角色只有 `INTERNAL_MCP` run 必须绑定精确 Generic launch，`IN_PROCESS_LEGACY` 仍无 launch。Reviewer 必须在任何 OpenCode I/O 前冻结精确需求修订与源码 manifest，只允许 `REVIEWER_CANDIDATE_READ_ONLY` 的 read/glob/grep 和精确私有工具；Candidate 只含标题、摘要、findings 与 limitations，Legacy/MCP 均进入同一 `ReviewerReportCompilation`，模型 final text 和结束后实时文件不得成为权威输入。Convention 必须在任何远端 I/O 前冻结源 `AGENTS.md`、技术栈指纹以及组件/安全 argv 命令/受管相对路径证据目录，只允许 `PROJECT_CONVENTION_CANDIDATE_READ_ONLY` 的 read/glob/grep 和精确私有工具；Candidate 只选择闭集证据 ID，Legacy/MCP 均进入同一 `ProjectConventionCompilation`，原始命令、路径、Markdown、权限、生命周期、稳定 ID 与模型 final text 均不得成为权威输入。派发前仅受管运行时/精确 lookup 能力缺失可建立全新 Legacy Session；一旦可能派发，零提交、超时、交互、传输/停止不确定、安全失败均失败关闭。accepted-result 必须在候选接受事务冻结，只有正向停止证明后才与 owner READY/COMPLETED 原子结算；Reviewer 的 Designer 后到取消以 V61 单调位优先于已接受结果且复用同一 termination intent。0.3.13 隔离成品 JAR 已证明真实 `opencode/gpt-5.4` 在同一 Reviewer Session 主动提交第 99 行、接收冻结源码机械拒绝后修正为第 1 行并接受，0.3.14 起 Reviewer 新 run 默认开启；显式关闭只回滚新报告，已持久化恢复不依赖当前开关。Convention 0.3.17 的两个隔离运行只证明首投接受；0.3.20 的独立受控故障资格已证明真实模型重复合法组件 ID 后，读取机械拒绝并在同一 Session 修正接受，因此 0.3.22 起默认开启。V63 Judge 每轮 Requirement/Risk 必须绑定同一 `judge_review_batch`、活动最终评审 Execution Cycle、最终成功 Attempt 和 source revision；滚动工作包的最终评审 Cycle 可锚定最后一个已冻结事实的成功 Attempt，二者不必伪造为同一执行 Cycle。人工重试只能新建代次，聚合不得跨批次拼接；MCP/Legacy 双入口共用 `JudgeDecisionCompilation`，候选只含 role/verdict/reason/闭集 evidence IDs，最终 assistant text、证据内容、稳定 ID 和生命周期均不由模型决定。`JUDGE_CANDIDATE_READ_ONLY` 只开放 read/glob/grep 与精确私有工具；派发后零提交、超时、交互、传输/停止不确定、安全和代次冲突失败关闭。0.3.20 隔离成品 JAR 的 Requirement 与 Risk 已分别真实提交带换行的 reason，收到机械拒绝后在原 Session 第二投接受，并完成同批次 PASS 和正向停止结算，因此 0.3.22 起 Judge 默认开启；显式 false 仅回滚新运行，既有候选恢复不受影响。受控故障只由测试启动器向模型 system prompt 添加一次性格式指令，不修改产品提示、权限、工具参数或服务端响应，也不代表自然错误率或统计可靠性；证据见 `docs/mcp-default-enablement-qualification.md`。
- 0.3.19 的隔离真实 Judge 资格证明 Requirement 会主动调用精确私有工具，但模型提交了多行 `reason`；旧实现把 CR/LF/TAB 与 NUL/BEL/C1 一并判为不可纠错安全控制字符，导致本可机械修正的候选过早进入 `WAITING_INPUT`。0.3.20 必须先在原始 `reason` 上检测危险控制字符，再把 CR/LF/TAB 单独作为 `JUDGE_DECISION_REASON_LINE_BREAK_INVALID` 机械错误；NUL/BEL/C1、混合或超长危险字符继续使用 `JUDGE_DECISION_REASON_CONTROL_INVALID` 失败关闭。普通额外说明字段返回 `JUDGE_DECISION_FIELD_INVALID` 和精确允许字段，权限、代次、生命周期、路径、命令、测试及带通用语义前缀的服务端权威字段仍是安全错误。INITIAL 提示必须同时公布 `reason` 的 1–4000 UTF-8 字节、单行和无控制字符约束。不可纠正 Candidate 在正向停止后必须让当前批次进入人工输入，不能作为 Session 错误自动重试；Legacy Judge 也必须在远端创建前持久化不可变 prompt/evidence/SHA，完成和 finalizer 只读取该快照。
- V49 历史复制前必须安装 owner/scope 守卫，跨 scope 的 V48 脏行必须中止整个迁移且不破坏旧数据。现有七种 owner 删除均要验证同事务 run/attempt 级联和 rollback；`PACKAGE_DESIGN_V1` 与 V56 `ROLLING_PACKAGE_PLAN_V1` 分别拥有独立不可变 accepted result，均须精确级联，尚未接入的 kind 不得虚构结果表。
- 默认 `managed` 在 Loopper HTTP 监听就绪后的 `ApplicationReadyEvent` 立即启动一个新的独占 OpenCode 进程和随机 loopback 端口、Basic Auth、私有 Server/Bearer/代际；`auto/http/fake` 保持首次实际使用惰性连接。成功必须同时证明认证 `/global/health` 与 `/mcp` 中本代精确私有 Server 为 `connected`，启动失败不得因读取 Runtime 状态反复拉起。每个新建或 fork 的远程 Session ID 必须先写入 V47 `open_code_session_runtime_binding` 才能暴露；后续 HTTP 使用先核对代际和不可逆 endpoint fingerprint，外部 `auto/http` 候选必须复用 Session 创建时已持久化的 `EXTERNAL` binding，不得按工作目录重算身份；只有 fake 适配器可显式生成 fake binding。旧 Session 回填为 `LEGACY_UNKNOWN` 并失败关闭；数据库不得保存 endpoint URL 或凭证。
- Decomposer、Compiler、Judge 与项目公约共用有界包容性提取器：原生 structured payload、角色 marker、`json`/无语言代码块、说明文字中括号完整的 object 和整段 object 按优先级提取。仅接受标准 JSON object；字符串花括号、转义、BOM 和空白必须正确处理；等价候选去重，不等价且都合格的候选按歧义拒绝。字段名、可选集合、枚举和安全命令分词只做唯一可逆的确定性规范化，成功提取/规范化直接进入同一权威业务合同校验并记录 `AI_OUTPUT_NORMALIZED`，不得消耗格式修复次数；数组根、残缺/非标准 JSON、不可唯一推导的缺口以及安全或执行合同违规则继续阻断。
- `MachineRoleContractCatalog`、紧凑 JSON Schema、服务端快速路径/语义编译器和 `docs/ai-role-contracts.md` 必须使用同一合同版本。当前 v6 Compiler 提示只允许未解析事实分配与闭集能力偏好；历史 v3 提示才允许语义 Stage、`DS-Lxxx` 来源和闭集证据意图。任何版本都不得让模型填写服务端派生的验收 ID、精确原文、`criterionIds`、`testTargets` 或重复命令。服务端编译完整 `VerifierSpec` 和可选 `verificationRuntime` 后，必须使用权威 LoopSpec v2 合同校验直接命令、行为覆盖、Java 聚焦测试及运行时绑定；短提示不能替代服务端确定性校验。
- Compiler 的 `criteria` 只承载可观察业务结果；未被聚焦测试显式覆盖的代码风格、源码/注解/装配形态、构建/测试结果和交付卫生属于工程元数据，服务端可确定性降级并重排 `covers`，不得因此消耗语义修复。一个 Java Stage 只有一个聚焦测试候选时可补齐剩余业务条件映射；每个 `JAVA_PRODUCTION` Stage 即使只有 Judge 条件也必须保留 `covers:[]` 的聚焦 Maven/Gradle TEST，`FULL_TEST`/`BUILD` 不能替代，不得生成只有全量测试/构建的 Java 接线或演示 Stage。v5 服务端须先从该 Stage 引用的测试交付唯一匹配门禁；没有组内匹配时仅允许使用包内唯一聚焦能力。多个候选、缺少真实聚焦测试或不可唯一推导必须直接形成 `DESIGN_INCOMPLETE`，不得落入已知不可修复的模型 JSON 修复循环。语义预检应一次汇总全部问题并返回精确 JSON Pointer；源码搜索不得作为行为 `SELF_CHECK`。
- 历史 Designer/Task、大型文档和关闭滚动兼容开关时继续使用聚合流程：分包 Designer/Compiler 读取不可变执行前基线，前置包 `APPROVED` 只表示设计合同已接受，不表示生产文件已写入；全部包完成后由服务端确定性聚合 LoopDraft，禁止模型二次合并或普通草稿更新绕过映射保护。
- 新建 `FULL_PACKAGE_DESIGN` 软件任务默认使用 `ROLLING_PACKAGES`：包 1 详细设计确认才创建唯一 `PENDING_START` Task，且不得申请 Queue/Lease、创建执行目录或可写 Session；后续包只在前一包确定性验收、Checkpoint 和 `PackageFactSnapshot` 成功冻结后设计。Git 每包释放租约并从精确 checkpoint tree 构造只读快照，Direct 全程持有租约并在每次设计前后复核目录 tree/manifest；两者都不得回退初始基线冒充当前事实。LoopDraft 保持不可变，每次包设计批准只追加完整 `TaskSpecRevision` 与新 Stage，不修改或重排已执行 Stage。
- `TaskPackageRun` 是独立状态轴，状态变化必须经过生命周期服务；事实严格分为机器证明、人工接受合同和非证据 AI 导航摘要，提示每包最多 4 KiB、总计 24 KiB。失败候选 checkpoint 不得形成已证明事实；重规划只替换未执行后缀，来源映射必须能预览新增、删除、拆分、合并、排序和依赖变化；依赖只允许指向已冻结包或提案中更早的包，不得形成前向依赖或环。人工编辑、Legacy JSON 或 `ROLLING_PACKAGE_PLAN_V1` MCP 都必须进入同一 `RollingPackagePlanCompilation`，只形成待确认计划；模型不得决定稳定 run ID、checkpoint、路径、命令、Verifier、权限、impact 或生命周期。V56 在候选接受事务中保存不可变 canonical candidate/plan/impact，远端 `REMOTE_COMPLETED / ABORT_ACKNOWLEDGED / ALREADY_ABSENT` 前不得把拥有者推进为 `PROPOSED`；停止未确认保持 `GENERATING + DISCONNECTED`，候选 run 建立后零提交、超时、传输、安全或代次失败均不得读取 marker 或切 Legacy。0.3.9 的 `LOOPPER_ROLLING_PACKAGE_PLAN_V1_ENABLED` 默认 `true`，显式 `false` 仅控制新派发，policy/writer/恢复 reader 常驻；默认切换不放宽机械纠错或失败关闭边界。已冻结行为只能通过 `correctionOf` 修正包单调追加。包级 `JUDGE/BOTH` 只标记计划评审，最后一个有效包冻结后才创建唯一 Requirement/Risk Judge 批次。
- 滚动包 Run 处于 `DESIGNING` 且关联设计包仍为 `PENDING / QUESTIONING / DESIGNING` 时，服务端必须投影 `canResumeDesign`，工作台显示版本化的“继续当前包设计”；显式继续与启动恢复共用同一幂等路径，必须复用已持久化的活动远程 Session，只在远程缺失或终态时按冻结事实重建，禁止并发派发第二个 Designer。所有 `package.*` SSE 都必须使 Task/工作台权威快照失效，不得让候选已到达的包仍显示旧设计态。任务列表 `/summaries` 使用独立精简适配器，不要求详情专属的 `loopRetryAvailable / cancellationAvailable`，也不得用默认 `false` 伪造操作能力；详情 overview 的四个布尔字段仍严格失败关闭。
- 聚合 Stage 的 `workPackageId` 映射进入 Review Gate 后不可删除、改写或重排；前端读取、结构化编辑、保存和确认必须无损往返。普通草稿更新边界拒绝映射漂移，确认边界还要校验每个已批准工作包均存在且保持依赖顺序，禁止静默降级成无包 Stage 任务。
- 工作包 Designer 在健康时复用该包自己的交互 Session；远端丢失时用持久化需求、当前完整包设计、决策和作用域消息重建，不得重跑已完成 Decomposer。每个候选都经过唯一权威 Validator；当前 v7 普通包、大型任务包和滚动执行当前包在闭集已解析时统一服务端直编，只有真实事实/能力歧义才按需创建独立只读 Compiler；冻结 v6 大型包和其他历史合同继续保持原有一次 Compiler 兼容语义。大型任务通过后进入 `REVIEWING`，只有人工接受或当前会话的全自动授权接受当前已验证修订才启动下一包；普通单包在服务端直编或按需 Compiler/Validator 通过后自动批准 `WP-1`、确定性聚合并直接进入总体确认，不显示包级接受步骤。初稿后每包最多 5 轮人工修改；失败候选不得覆盖上一版有效候选。重开已接受包时先展示影响，只把它的传递依赖标记 `STALE`，无关 `APPROVED` 包保持有效；最终确定性聚合必须推进需求的草稿乐观锁检查点但保持冻结正文和来源消息不变，旧版未推进检查点的聚合只有在草稿恰比 Decomposer 基线多一个版本且仍精确覆盖全部冻结包时才允许一次兼容恢复，任何后续外部编辑继续以 `DESIGNER_DRAFT_CHANGED` 阻断；从 `FINAL_REVIEW` 重开时必须在同一事务内把同一不可变需求版本从 `COMPLETED` 以 `RETRY` 恢复为 `ACTIVE`，显式重编译再经 `REVIEWING + RETRY -> COMPILING`，确保后续讨论、重设计或重编译不会被旧聚合终态阻断。
- 全部工作包 `APPROVED` 后才允许服务端确定性聚合并进入 `FINAL_REVIEW`；重复聚合必须把冻结的 Decomposer 全局约束规范化为唯一一段可追踪 context，不得重复追加；包级阶段右侧候选只读，最终聚合阶段才开放结构化编辑。V27 持久化讨论与批准；历史未确认 `COMPLETED` 包迁移为 `REVIEWING`，已确认草稿和已创建 Task 不变。
- 只有完成、项目匹配、版本匹配且经服务端确定性验证通过的聚合 LoopSpec 才能同步到绑定草稿；模型不得自报校验成功。逐步人工确认或当前会话全自动授权确认前仍不得写业务源码、创建 Task 或制造执行状态。
- 确认时冻结完整 `REQUIREMENT_CONTEXT`、`DECOMPOSITION_CONTEXT`、每包 `WORK_PACKAGE_DESIGN`/`WORK_PACKAGE_COMPILATION_SUMMARY`，并保留组合 `DESIGN_CONTEXT` 兼容历史。执行提示按当前 Stage 的包只注入当前包设计、全局约束和前置包交接。
- 执行期若已冻结的 `DECOMPOSITION_CONTEXT` 无法解析、字段形状错误、缺少当前包或依赖 ID 无效，必须在创建可写 OpenCode Session 前以 `DECOMPOSITION_CONTEXT_INVALID` 失败关闭；不得静默丢弃全局约束或前置包交接。
- Designer 合并在单个数组项中的 Maven 参数若能无歧义解析，应在同步时直接规范化并保存为独立 argv，不消耗自动纠正次数；只有引号未闭合等无法安全解析的输入才按无效 LoopSpec 回送纠正。
- 草稿确认必须是幂等边界；确认后创建唯一 `PENDING_START` Task，且确认事务不申请执行资源。默认由用户显式请求开始；当前 Designer 会话处于全自动授权时，确认完成后再复用正式 Task Start 边界请求执行。
- Designer 可按单个会话启用默认关闭的全自动模式；每次启用或从普通 `BLOCKED` 重新授权都必须由本地 UI 显示风险确认。V34 独立持久化 `DISABLED / ACTIVE / BLOCKED / COMPLETED`、最近动作、错误、Task 和乐观锁版本；750ms Monitor 每轮每会话最多推进一个动作，且只能复用已有画像推荐、问题回答、需求确认、工作包批准、草稿确认与 Task Start 权威入口。全自动模式只能遵循当前已持久化的大型任务选择，不得自动打开、关闭或在 `LARGE_TASK_MODE_REQUIRED` 后自动切换。普通模式所有首次 Router 结果均须人工确认；全自动模式只把成功且通过服务端危险证据与组件选择校验的 Router 意图/主要制品单独持久化为 `AUTO_RECOMMENDED`，保留原置信度。Router 超时、运行失败、危险操作或组件歧义必须进入人工确认，不能静默采用降级设置。历史 `BLOCKED + TASK_PROFILE_DECISION_REQUIRED` 允许一次专用 `RESUME` 后在下一轮采用同一推荐，不要求关闭再授权。自动答案按中英文推荐标记选择，无标记时兼容首项，多选只取推荐项；全部动作写入 System 消息和生命周期审计。其他异常进入 `BLOCKED` 后不得高频自动重试；关闭只停止后续动作，不撤销已完成动作或终止正在运行的模型调用。自动授权在 Task Start 成功请求后终止，执行期问题、危险权限、恢复、结果确认、提交、推送、合并和发布始终保持人工处理。
- Designer 页面在消息时间线现有的当前角色卡片内每 1.2 秒读取有界活动投影，只替换展示一条最新活动，不得另建顶部活动面板或累积历史。交互设计师使用消息一致的 Markdown 展示思考、增量文字和普通/MCP 工具调用；Router 在专用任务设置弹窗复用同一活动组件，但其正常新会话不得调用工具；检测到 marker、JSON 或任务画像机器字段时必须替换为“正在整理任务设置识别结果”，不得泄露原始对象；Decomposer/Compiler/Reviewer/repair/finalizer 等其他结构化角色只展示最新工具活动和权威步骤。Router 运行弹窗必须显示真实已用时间、远端状态和 Provider Token，不得展示超时上限，运行中禁止遮罩、Esc 或关闭，但必须提供“取消识别，手动设置”；服务端确认远端停止后弹窗直接打开人工控件，并以信息提示区别于识别失败。终态分字段展示中文结果，不得拼接“0% · Java”或使用虚假进度。未决定时允许关闭结果弹窗，但任务设置卡必须保留“查看识别结果”，刷新后恢复确认门。断线只保留最后一条活动并重连，活动投影不得推进生命周期。V40 的 `model_token_usage` 只按 Designer 或 Task 范围累计 Provider 报告的远端 Session Token；活动消息与 Token 必须复用同一次读取，单次轮询最多额外补齐一个历史远端。Designer 当前角色卡和 Task 模型输出头部只显示紧凑数字与正增量 `+xxx`，不得显示额度、成本、无限 Token 或解释文案，也不得用时间/文本长度伪造 Token。
- Designer “清理并重新开始”必须先把会话转为 `STOPPING` 并停止 Router、需求/包设计师、Decomposer、Compiler、repair/finalizer 和 Reviewer 等全部活动远端 Session；`STOPPING` 期间 Monitor/全自动不得继续派发。失败时保留工作区、保持未归档并允许幂等重试；全部确认停止后才进入 `CANCELLED` 并归档。确认设计取得 Task ID 后，前端必须清除工作区指针和未发送内容、跳过本次离页警告并打开任务详情；以后从左侧进入 Designer 必须是新建设计，旧稿仅从历史只读查看。
- 项目 `taskCount` 只统计已创建的 Task，不得把确认前 Designer 会话伪装成任务；服务端必须按项目另行投影每个未确认草稿的最新 Designer Session 和 `openDesignerSessionCount`。浏览器工作区 ID 只是恢复提示，服务重启或短暂 API 失败不得清除。Designer 起始页只负责新建，不得平铺历史会话；独立“历史设计”页负责项目/状态/归档筛选与时间排序，未确认设计可继续、修改、归档和恢复，已确认设计必须关联 Task 只读展示且不得提供继续、修改或归档。V29 归档只增加可恢复投影，不删除草稿、消息、问题、候选或批准，且归档项不计入 `openDesignerSessionCount`。
- Designer 双栏和 PageHeader 操作区必须以 `min-width: 0`、换行和响应式单列保持在视口边界内；总体确认按钮除页头外还必须在 Review Gate 内提供同一权威动作，不能因窄视口变得不可点击。
- Designer 双重验收矩阵的验收条件必须占据可伸缩主列，模式、机器验收和 AI 评审作为可换行状态组；禁止用固定窄列压缩长条件，窄屏时状态组整体下移并左对齐。
- 聚合和滚动两种大型软件流程都只创建一个 Task、一个任务分支和一次发布；Stage/包严格串行。滚动模式中每包设计确认和执行开始始终是两次人工动作，全自动只能推进 Router、拆包和只读设计生成；最后一次包事实冻结后只运行一次 Requirement/Risk 双 Judge。
- 滚动工作包命令能力和写入校验统一由 `RollingPackageCommandPolicy` 持有，`RollingPackageCommandContextService` 是读模型与命令端唯一的持久化事实构造入口；两者必须输入同一 Task/Run/Queue、等待原因、Checkpoint 和活动 owner 事实。工作台响应必须在同一权威快照中返回 `taskVersion`、`currentPackageRunId`、各包版本与完整 `packageCapabilities`，前端不得混用旧 Task overview 的能力；当前包处于 `DESIGNING / COMPILING / VALIDATING` 或存在活动 Designer、writer、verifier、Judge 时调整剩余拆包失败关闭，并在并发 409 后刷新工作台。计划确认原子推进提案、后缀 Run、首包设计和父 Task；包启动原子推进 Run、Task、Queue 与 Lease。三者混合状态不得当作幂等成功。Task 取消只有在 Designer、PackageRun、Attempt、Stage 和 Cycle 全部收束后才能从 `STOPPING` 进入 `CANCELLED`。
- 新建、导入和模板新版本使用 LoopSpec v2：每阶段必须显式声明 `implementationKind`，并至少有一个可观察 `acceptanceCriteria`。条件通过 `verificationMode` 选择 `MACHINE`、`JUDGE` 或 `BOTH`；机器模式必须由服务端分类为 `BEHAVIOR` 的验证器通过 `criterionIds` 覆盖，Judge 模式必须提供 `judgeRubric`，仅 Judge 还必须提供 `judgeOnlyReason` 且不能已有机器行为映射。每阶段无论模式都至少有一个阻断性确定性验证器。旧 v2 缺少 `implementationKind` 时只允许查看，再次保存、发布模板或确认前必须补齐；已持久化且未写模式的 v2 条件默认 `MACHINE`；v1 继续兼容且不得原地改版，只能复制为新 v2 草稿后补齐计划。

- 聚焦测试到验收场景的映射必须来自正向交付、明确覆盖关系或无歧义的阶段回指。包内只有一个正向交付声明的聚焦测试时，未显式点名其他测试的新增场景均由该目标覆盖，“同一/该/本聚焦测试类”也可回指该目标；存在多个交付测试时不得猜测。既有测试“保持通过/继续回归”和“测试风格一致”只形成独立必跑约束，不得凭词汇相似度覆盖新增业务场景。

- V68 对新 Designer 冻结 `PER_PACKAGE_V1`，单包承接需求讨论 Session，多包全局讨论和每包各自持有 Session；问题、修改、编译/待确认不退休，单包内部自动批准仅为聚合，最终草稿确认才交接。已退休后重开须创建新轮次，旧设计保持旧策略。会话/回合身份由 `DesignerConversationCoordinator` 持久化并 CAS 领取；当前消息及其子回复决定结果，未知发送不得盲目重发。候选运行存续时问题投影不得改变其工作包版本；停止模型回合不等于退休会话。模型、权限和私有 MCP 能力固定于首次创建；每回合工具按阶段收紧。统计存活以会话记录为准，正常流程每个实际设计会话只有一次自动 start/complete，手动重试另记。

### 5.3.1 Designer 附件上下文

- OOXML 原件保留、校验及冻结，但所有允许消费者仅向模型提供其确定性文本，提示不含嵌入图片/布局。`OpenCodeAttachmentResources` 在发送前核对完整 SHA-256/UTF-8，向受管 OpenCode 签发私有 MCP 资源描述，不发送 file/data URL。文本每个表示 128 KiB，图片/PDF blob 每个 10 MiB，超限失败关闭。资源是按远端 Session/代际签发的不可猜测能力凭据，15 分钟有效、确认 abort 撤销；单批 24 个表示、每代 1024 批、64 MiB 原始字节缓存上限，不枚举附件，仅公开通用模板。私有 MCP 只增加资源读取，公共六工具和候选权限不变，Router 不接收资源。非受管连接明确拒绝附件。
- MCP 投递必须先取得资源读取回执，再回读 OpenCode 持久化输入并核对完整有序文本/blob 哈希；两阶段各等待 30 秒，输入回读仍受既有 HTTP timeout 约束，不得在 SQLite 事务内等待。没有完整证明不得继续，私有 candidate 提交也等待投递证明。精确恢复兼容旧 file parts 和新资源展开；临时 URI 不改变冻结附件及消息哈希身份。真实验收还必须观测模型引用附件独有内容，204/noReply 或读取回执不能代替模型证据。

- 设计工作台拖放或文件选择只进入当前 composer 暂存区；未选文件时只显示轻量添加入口，选中后才显示独立“文件上下文”卡片并按当前作用域逐行列出类型、大小和移除操作，移除最后一个文件后整卡隐藏。文件必须随非空文字显式发送，纯附件消息拒绝。一次最多 10 个文件、每个最多 20 MiB、Designer Session 累计最多 50 MiB；PDF/OOXML 的确定性文本表示和严格 UTF-8 文本均不得超过 128 KiB，不截断、不跳过、不调用模型摘要。
- `DesignerAttachmentContext` 是上传、类型识别、受管存储、逻辑替换、停用、Task 冻结和模型装配的唯一深模块。文件 I/O、格式解析、哈希和完整性检查必须在数据库事务外完成；公开 REST 不返回绝对受管路径。V46 的附件 submission、Designer 历史附件和 Task 冻结清单保持独立事实。
- Requirement 附件是全局上下文，Work Package 附件只属于该包；Router 始终只有正文。Requirement/Package Designer、Decomposer、Compiler、只读 Reviewer、Implementation、Recovery 和双 Judge 只能通过 `withContext` 获得相应活动或冻结清单。
- 默认 Candidate 双 Judge 必须在 `JudgeDecisionCandidateWorkflow` 的 INITIAL 派发前装配 `taskAllPackages(launch.taskId())`，保留确定性消息 ID；回归须经过两种角色的真实派发入口，Legacy/共享装配测试不能代替。MCP Judge 仅移除服务端完整精确的旧版 Markdown 输出后缀，保留冻结评审事实并要求单行中文理由；不放宽控制字符、证据、权限或重试预算。Reviewer `limitations` 必须为字符串数组，类型反馈指向具体字段，禁止把 `contractVersion` 等服务端元数据加进候选。
- 附件装配仅在调用方未提供消息 ID 时生成 `msg_loopper_attachment_<sha256-prefix>`，满足 OpenCode HTTP `messageID` 的 `msg` 前缀约束；显式身份与无附件请求不改写，历史 dispatch 不迁移、不自动重放。回归必须串联真实附件存储/装配与 HTTP adapter 的协议校验，覆盖 TXT 和 DOCX，不能只测手写合法 ID。设计错误展示先解开 `SYSTEM_ERROR[层级]`，保留实际错误类别与中文恢复方向；历史无具体码时按层级兜底，不能只显示“错误”。
- 附件是不可信补充资料，不能覆盖用户正文、确认需求、LoopSpec、路径授权、危险操作边界、验证证据或 Judge 合同。显式上传 `.env`/私钥只授权使用该文件快照，不放宽项目敏感文件读取策略。
- 支持严格 UTF-8 文本/源码、JSON、CSV、PDF、PNG/JPEG/GIF/WebP 及无宏 DOCX/XLSX/PPTX；压缩包、可执行文件、旧式 Office、宏/ActiveX OOXML、SVG 和未知二进制失败关闭。HTML 只按源码文本处理，任何预览都不得执行附件脚本或宏。
- 同一作用域、文件名完全相同才逻辑替换；跨作用域同名并存。替换或停止未来使用不得改写历史；若旧 OpenCode Session 可复用，必须先取得正向停止证明、清空复用指针，再让后续 role handoff 创建新 Session。Task 确认冻结活动附件及 SHA，Recovery 从父 Task 清单继承，不能重算 Designer 当前集合。

### 5.4 验证器与 Judge

- `PROCESS.command` 是 argv 数组，直接调用程序；禁止 `sh -c`、`bash -c`、`cmd /c`、管道、重定向和 shell 插值。
- Windows PROCESS 必须在启动前按任务根目录解析 `mvnw`/`gradlew` 包装器，并按 Loopper 进程 `PATH`/`PATHEXT` 解析裸程序的 `.com`/`.exe`/`.bat`/`.cmd` 入口；证据保存实际绝对 argv 与解析原因。Linux/macOS 保留原生 PATH 与可执行位语义。该适配不得放开用户 shell 启动器或 shell 片段，Windows 批处理启动必须启用 JDK 严格命令引用模式。
- Maven 参数兼容规范化只能进行确定性 token 拆分，不得启动 shell；新草稿保存规范化 argv，执行器还需兼容规范化历史草稿，并在证据中记录发生过拆分。
- v2 `PROCESS TEST` 只按精确 basename 识别 Maven、Gradle 和 npm 测试入口；必须同时拒绝拆分/合并形式的测试排除参数、npm 可选脚本和相似前缀伪装入口。草稿分析与实际进程启动前共用同一策略，并在 Maven argv 规范化后再次检查，禁止持久化历史合同绕过当前执行边界。
- Stage 的 `allowedPaths` / `forbiddenPaths` 只是 Agent 提示；只有显式 `GIT_DIFF` 才是路径/删除的强验收门槛。
- v2 Compiler 规划、草稿保存和人工确认必须复用运行期规范化且有界的路径策略校验：非法 glob，或被单条 `forbiddenPaths` 完整覆盖的 `allowedPaths` 规则，必须在 Task/Attempt/可写 Session 创建前退回规划修复或拒绝；宽允许范围配合更窄的禁止子树仍然有效。
- 普通可写 Stage 的显式 `GIT_DIFF` 与 Attempt handoff 必须使用该 Stage 首次 Attempt/Session 前捕获的私有基线；同一 Task 共用对象库、Stage 使用独立索引，重试和重启复用 `stage:<taskId>:<stageId>:<treeSha>`。前置 Stage 文件不得进入后续 Stage 差异或满足其 `requireChanges`，但后续 Stage 再次修改、删除或重命名前置文件必须可观测。`VERIFY_ONLY` 与最终自动差异继续使用任务基线，证据必须写明 `baselineScope`，Stage 范围还要包含 `stageId`。
- 显式 `GIT_DIFF.allowedPaths` 对范围外新增文件自动放行并记录 `autoAllowedOutsideNewPaths`；命中禁止路径仍硬失败。范围外修改、删除或重命名 Stage 基线已有文件时不得直接关闭 Attempt，而应保持 Stage/Attempt 运行并让 Task 进入 `WAITING_INPUT`。本地弹窗必须使用代码审阅布局：左侧导航并标记各文件待定/接受/拒绝状态，右侧集中展示当前文件 Stage 基线的旧/新行号、修改前后内容和 hunk 位置，底部持续汇总决定数量；全部文件逐项放行/拒绝后才可继续。决定必须绑定 Task 版本、请求 ID、Stage 基线和文件 patch SHA-256，内容变化后旧决定失效并重新展示。用户拒绝后才进入普通验证失败；`forbidDeletes`、禁止路径、containment、预览截断和基线错误不得进入授权路径。
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
- 任务详情的当前双评审卡只显示 Requirement 与 Risk 各自最高 ordinal；新一轮开始后旧卡立即退出当前操作区，但全部旧 Judge 行继续作为不可变审计证据保留。
- 最终 `VERIFICATION_SUMMARY` 必须按阶段顺序聚合每个成功 Stage 的最终成功 Attempt 与全部验证结果；单项证据摘录限制为 4 KiB UTF-8 并保留完整证据 SHA-256。确认目标、上下文和全部 Judge 合同总计不得超过 96 KiB UTF-8，完整 Judge 提示不得超过 128 KiB；每轮必须先批量构造并校验全部待启动角色的提示，任一超限时整批都不得创建 Judge row、只读 Session 或模型调用，直接进入 `WAITING_INPUT`，错误码为 `JUDGE_PROMPT_BUDGET_EXCEEDED`。
- `REVISE`、`BLOCKED`、Judge 冲突或 JSON 无法解析时进入人工处理/重新评审，不得丢弃已有确定性证据或伪造成功。
- Attempt 交接的差异扫描、文件读取、内容哈希和新 Session 创建都在 SQLite transaction 外执行；按实际读取字节限制 16 MiB，并在读取前后核对文件大小、修改时间和 file key；不可完整读取或读取期间变化的快照标记为不可比较，不得据此触发停滞。

### 5.5 工作区、租约与 Recovery

- 计划确认与执行资源申请必须分离：确认可做只读项目/重做基线校验，但不得识别并持久化 Queue/Lease、fetch 或切换 Git。首次显式开始才解析当前工作区身份，并在短事务中原子完成 `PENDING_START -> QUEUED` 与准入/排队；外部 Git/文件 I/O 随后执行。Recovery 子任务同样先停在 `PENDING_START`；自动化只有在已授权的自动开始或人工批准动作中才调用同一开始边界。
- 有可用 Git HEAD：登记目录有未提交或未跟踪文件时，任务持有租约进入 `WAITING_INPUT`，本地 UI 显示具体文件并要求逐文件选择提交、stash 或移除；处理决定必须绑定分支、HEAD、索引、状态和内容快照。取消入口在同一脏文件弹窗内二次确认，即使列表读取失败也必须可用；确认后走统一 `STOPPING → CANCELLED` 协议并保持文件原样。重新检查干净后，在登记目录本身创建并切换 `loopper/<任务名>`；本地或远端跟踪分支已有同名时从第二次起追加 `(第2次)`、`(第3次)`，Git 禁止字符确定性替换为 `-`，分支叶名称按 UTF-8 字节安全截断，并在截断后重新修正 `.lock` 等非法结尾。
- 创建任务分支前以非交互方式 fetch 当前分支的 upstream/明确首选远端；远端线性领先时从远端最新提交创建任务。本地领先时保留本地提交；认证失败、fetch 失败或历史分叉必须 fail closed。未收到逐路径确认时禁止自动 stash、提交、覆盖或丢弃改动；移除操作必须二次确认。外部 Git 操作部分成功后不得伪造事务回滚，必须回读最新状态继续处理。
- 原项目分支 checkout 使用独立 10 分钟有界超时和命令局部 `core.longpaths=true`；短 Git 检查仍使用 30 秒边界，失败诊断保留输出尾部。
- OpenCode 的 canonical `directory` 查询值必须作为 URI 模板变量百分号编码，禁止让合法路径中的 `+` 被表单语义解码为空格；创建 Session 后必须回报与登记项目根一致的规范执行目录，缺失或不一致时不得发送实施提示；实施提示明确 AgentBridge、搜索、命令和验证器都使用该目录及当前任务分支。
- 无可用 Git HEAD：直接使用登记根目录，并在 `direct-baselines/<taskId>` 保存私有 Git-compatible 基线；不得在用户项目中隐式初始化或提交 Git。
- 所有路径 canonicalize 后进行 containment 和符号链接检查。
- 同一登记 root（Git 或 Direct）同时只能有一个未释放写租约；旧写入者状态未知时保持租约并阻断 Recovery/Automation。执行轮次结束后只有在旧 writer 已确认停止、全部 tracked/deleted/untracked 修改已通过临时 index 冻结到 `refs/loopper/checkpoints/<taskId>/<cycleId>`、工作区已清理且源分支可恢复时，才释放租约；私有 checkpoint ref 与 stash 永不推送。继续或派生写入前必须复核 root、分支、HEAD、ref、commit、tree 和清单，任一不一致都 fail closed。
- Checkpoint `CAPTURING`/`RESTORING` 必须可在应用重启后幂等续接：已落盘的私有 ref 不得被干净工作区覆盖，已恢复工作区必须重算 tree 精确匹配后才能推进；已终止 Execution Cycle 但尚未写入 Task 投影时只能恢复到 `AWAITING_DECISION`，不得凭空创建重试。成功等待任务发布时以 `PUBLICATION` 来源重新参与同一 FIFO 准入并恢复冻结点。
- Task、Queue 与 Lease 必须保持独立状态机，并由统一协调器维护跨状态不变量：`ADMITTED` 必须与非 `RELEASED` 租约的 holder 一致。终态 holder 只有在写入 Session/验证运行时已确认停止、项目指纹一致、工作区干净且源分支可安全恢复时，才允许完成队列项并严格按 FIFO 原子转移租约；Git/指纹检查在 SQLite 事务外，真正的完成/转移在短事务内复核。启动恢复、取消/Session 清理、归档前置、手动检查和仅扫描“终态 holder + QUEUED waiter”的 10 秒后台协调必须复用同一逻辑，且并发幂等。`DISCONNECTED` 或历史 `SESSION_ABORT_UNCONFIRMED` 不能作为本地终态证明；重启及显式本地 UI 修复必须重新核验精确远端 Session，消费 abort `true`、精确 404 已不存在或独立终态状态，并在成功时持久化 `SESSION_ABORT_CLEANUP_CONFIRMED` 后再进入协调器，失败继续保持 `RELEASE_PENDING`。活动 holder 或 `ADMITTED` 任务不得归档/永久删除，删除路径不得清空 holder 绕过状态机；任何阻塞均 fail closed，不得自动 stash、提交、删除或强制切分支。
- `AWAITING_DECISION` 的失败轮次支持继续当前 Task、`INHERIT_CHANGES` 派生、`REWORK_ALL_STAGES`、`VERIFY_ONLY` 审计和专用结果取消；成功轮次还支持发布、选择 Stage 继续优化及空变更显式接受。结果取消复用 `STOPPING` 协议，但不得把已终结轮次或 Stage 改写为中断/取消。派生子任务保持 `PENDING_START`，继承修改只把冻结 tree 作为未提交工作区种子，Task baseline 仍为父任务开始前 baseline；历史 `FAILED`/`CANCELLED` Recovery 保持只读兼容。
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


### 5.7 人工评审认定、冻结历史、工具与筛选（0.3.28）

- AI 最终 Requirement/Risk 双评审只供参考。`TaskJudgeApprovalService` 在当前 `WAITING_INPUT`、所有 Stage 与最新 Attempt 成功、最终批次已停止且无未确认 writer/candidate/verification cleanup 时，允许本地 UI 携带 Task/Cycle/batch 版本明确人工通过。V64 `task_judge_approval` 独立记录不可变认定，同一事务结束当前 Cycle 为 SUCCEEDED 并转 AWAITING_DECISION，不修改任何 Judge verdict/accepted result。事务外冻结工作区并协调租约；启动恢复负责补完中断的认定后交接。缺少批次的历史 Judge 可绑定当前成功 Attempt；过期、运行中、失败验收或停止不确定继续拒绝。新操作使用 `X-Loopper-Local-UI: 1`，不开放给公共 MCP。
- 冻结任务设计历史从其源 Designer 的持久化 discussion revisions 投影 answeredQuestions；`frozenDesignTimeline` 只展示 USER/DESIGNER 与复用 `DesignerDiscussionHistory` 的问答卡片，即使锚点为隐藏的系统快照也保留原位置。设计中页面与完整审计记录保持独立。SSE 规范化提示按任务/内容去重，最多四种并翻译公开标签。
- 系统 `/tools` 从 OpenCode 项目作用域 `/mcp` 与 `/config` 读取状态，按需经既有 MCP 配置执行 initialize/tools/list，绝不 tools/call、不调用模型、不修改配置或启用停用服务。stdout 协议、stderr、URL/认证配置与内部随机 Server 身份不得出现在用户响应；内部 Server 使用固定显示别名。工具描述仅作为文本展示；超时、OAuth 无凭据或不完整分页必须显式提示，不能伪造成零工具。读取有请求超时、512 工具上限及短期缓存。
- `InsightPageMapper/InsightSql` 统一筛选列表、Token 和成本，支持 projectId/state/quality/archive/query；前端切换筛选清空 cursor 并防止旧请求覆盖。未知用量继续 null，人工认定独立投影，不伪造 AI PASS。
- 取消已停止且仍持有租约的 Git holder：`WorkspaceLeaseReconciliationService` 在根指纹核验后复用 `TaskWorkspaceCheckpointService` 保存脏文件，再调用 `restoreMainBranch`（origin/HEAD 本地对应分支、main、master）并释放租约。保存/切换失败不得强制 checkout/reset 或丢弃文件；未申请资源、排队、Direct 或已转交的工作区不得切换其他 holder 的分支。

统计交接必须按远端 Session 协调普通 abort 与正在运行的命令：自动清理不得中断未返回的 complete；手动取消仅释放自己的消息保护，迟到 HTTP 不得继续占用交接。新 start 等待同一绑定链已退役 Session 的 complete 尝试收束，不要求成功；只有 start 明确 FAILED 时才自动追加一次同 Session、同编号的 continue，UNKNOWN/CANCELLED 不补发，continue 失败不递归重试。V67 的自动降级与显式重试都用 retry_of 保存调用链与唯一消息身份，保留原始结果/输出/关闭记录；仅最新 FAILED/UNKNOWN/CANCELLED 调用可由本地 UI 按原操作重发，且原业务拥有者必须已退役、同一 Session 无其他活动统计。成功 complete 后不再重开 BEGIN；能力和禁用原因由同一服务端策略供接口与弹窗使用。

## 6. 后端开发约定

- 所有新增和修改必须遵守 `docs/code-design-contract.md`：编排、策略、持久化、传输、解析和展示职责分离，依赖从适配层指向稳定领域合同，优先组合而非为复用实现建立继承层级。
- 新生产 Java 文件默认不得超过 600 行；推荐类不超过 400 行、接口不超过 250 行、方法不超过 40 行、构造依赖不超过 8 个。存量超限文件由 `CodeStructureContractTest` 维护只降不升的债务上限，拆分时必须同步降低上限，不得为通过构建调高阈值。
- 设计模式只用于隔离真实变化轴：可替换算法用 Strategy，确定性判断用 Policy，构造与协议装配用 Factory/Assembler，有界用例用 Coordinator，外部系统用 Adapter。禁止用无行为的转发层掩盖原 God Class。

- API Controller 只做输入/输出边界、校验和 DTO 映射；业务编排留在 `service/`。
- 领域状态使用现有枚举和 typed failure；不要用散落字符串复制状态语义。
- `DesignerSessionService` 只协调 Designer 生命周期和远端角色步骤；紧凑包计划的规范化、语义校验与可执行证据生成归 `DesignerPackagePlanCompiler`，机器传输形状归 `DesignerSemanticContracts`；OpenCode `question` 能力选择、回答校验、决策日志编码和聊天降级投影归 `DesignerQuestionSupport`，该协作者不得推进 Designer 生命周期，上述职责均不得回流到会话编排器。
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

- 所有已有 Flyway 迁移（当前 V1–V75）均不可修改；Schema 变化新增下一序号迁移，并同时验证全新数据库和至少一个受支持旧版本升级路径。
- SQLite 外键级联不能只靠假设；活动连接必须明确启用，终止删除路径仍要按依赖顺序显式清理并验证事务回滚。CandidateSubmission 的 owner/scope 校验必须在历史 run 复制时已生效，跨作用域旧数据必须让整个迁移失败关闭并保留可恢复的上一版数据。
- 数据库枚举码、artifact kind、错误码和 audit event 是兼容性契约；修改前先搜索所有 Java、SQL、前端 type/label 和测试消费者。

## 7. 前端开发约定

- TypeScript 类型以 `frontend/src/types/domain.ts` 为边界，API 变更必须同步 DTO、client、store、view 和测试；Designer 保存/确认必须无损往返 Stage `workPackageId` 以及全部 LoopSpec limits、model、sessionPolicy 和 nextAttemptPromptTemplate。
- 任务详情只为 `PENDING_START` 显示“开始执行”；该状态必须明确尚未入队、占用租约或切换分支。`READY` 是已请求执行后的短暂内部状态，只显示自动继续语义，不得再次显示开始按钮。
- Task 等待动作以服务端 `waitingReasonCode` / `loopRetryAvailable` 投影为准；当前原因必须优先来自最新 Task `WAITING_INPUT` 转换的 `reason_code`，旧数据只能在该转换对应的状态轮次内从元数据或错误兼容推导，前端不得从完整历史错误推断当前“继续一轮”入口。
- 所有 `TASK` 错误事件都作为不可变审计历史保留，但详情页红色当前告警必须跟随权威生命周期：`WAITING_INPUT` 只显示与当前 `waitingReasonCode` 精确匹配的最新错误，失败轮次 `AWAITING_DECISION` 和历史 `FAILED` 只显示最新 Task 错误；进入排队、准备、`READY`、运行、验证、重试、暂停、评审、停止中或成功/取消/接续终态后，不得把旧 Task 错误继续渲染成当前故障。`SOURCE_BRANCH_WORKSPACE_DIRTY` 同样遵守该通用规则。
- `SOURCE_BRANCH_WORKSPACE_DIRTY` 仅在 Task 尚无任务分支和执行目录时可作为当前原因，并打开不可静默关闭的文件处理弹窗，逐文件选择提交、stash 或移除；重新检查成功前不得制造任务分支已创建的状态。弹窗内确认取消必须复用统一 `STOPPING → CANCELLED` 协议；即使旧页面保留了已过期弹窗或列表读取失败，也不得因特殊入口拒绝本来可取消的 Task，且不得修改已有文件、分支或执行目录。
- 服务端是权威状态；不要用计时器伪造阶段进度、用量、成本、Session 完成或 Judge 结果。
- Task 实施 Session 的 OpenCode Todo 只能作为非权威进度投影：卡片以完成数/总数、分段状态和一个当前项为首屏，其他实施项允许折叠且长列表内部有界滚动；桌面端在无待回答问题时必须占用工具栏与模型输出之间的独立布局行，输出在其下方独立滚动，禁止用 sticky/fixed 覆盖输出；有问题时立即回到输出文档流让回答入口优先，窄屏始终按正常文档流展示。不得把 Todo 计数冒充 Stage 百分比或 Stage/Task 完成；键盘焦点和减少动态效果设置必须可用。
- Designer 验收意图卡只把当前失败、待覆盖、路径待归属或路径守恒阻断显示为黄色告警；已成功归属的路径以成功样式显示为当前证明，成功编译后保留的历史消歧原因去重并折叠为中性说明，不得让历史数组继续伪装当前失败，也不得据此绕过服务端 Review Gate。
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
JAR=target/opencode-loopper-0.3.81.jar
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

设置页保存的非敏感启动配置镜像固定为 `${LOOPPER_DATA_DIR}/config/startup-overrides.properties`。两平台脚本只能逐项解析白名单，禁止 `source`、`eval`、`call` 或执行文件内容；优先级固定为显式环境变量、页面文件、脚本默认值。未知键告警忽略，已知键格式非法必须终止。两平台脚本默认导出 `OPENCODE_ENABLE_QUESTION_TOOL=true`，`OpenCodeRuntimeManager` 也必须显式注入所有受管子进程；已启动的外部进程不得被 Loopper 为此重启或假装继承，只能在其自身启动环境设置并重启前使用聊天降级。数据目录、Java Home、JAR 路径、MCP Token、OpenCode 密码和 GitLab Token 不得进入页面、数据库设置 JSON 或启动镜像，服务监听继续固定 loopback。

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

### 故事绑定与统计隔离

- V65 故事绑定仅在 Designer 创建时配置，默认关闭；项目作用域 `/command` 实探包含 `aicoding` 才开放开关，不创建 Session 或调用模型。系统/故事编号按字符串保留，Task 确认与 Recovery 继承同一链。
- `StoryAccountingCoordinator` 独立持有每个远端 Session 的 BEGIN/COMPLETE 调用与消息身份；只接收需求设计师、工作包设计师（含 PACKAGE_DESIGN_V1）和 IMPLEMENTATION；每个新 Session 均先使用 start，明确 FAILED 时在同 Session、同编号下自动追加一次 continue，UNKNOWN/CANCELLED 不补发且 continue 失败不递归，相同 Session 不重复绑定。Router、规划、Compiler、Reviewer、Judge、其他修复/finalizer 与未知拥有者均不新建或补发统计；历史记录保留供审计与消息隔离。业务结果先保存，所属流程不再复用后才 complete；IDLE、等待回答或单独 abort 不等于业务结束。失败/取消也收尾，未工作的 fork 与服务端步骤不伪造统计。
- 统计不设置自动超时，全局弹窗显示开启/继续/完成阶段与真实模型输出，用户可取消当前统计并继续任务。V66 持久化 CANCELLING/CANCELLED、活动快照及关闭确认；取消先领取调用并在业务屏障释放前校验远端最新 user 消息身份，禁止对已经继续工作的业务会话补发 abort，迟到结果不能覆盖取消。BEGIN 等待按区间并集从相关业务时限中扣除。统计与通知失败不得改变业务生命周期、重试预算、全自动授权或结果。调用前短事务落库；start 和自动 continue 使用独立消息 ID，continue 以 retry_of 关联失败 start；重启将遗留 PREPARED/CANCELLING 记 UNKNOWN，不自动重发。SQLite 使用 WAL + IMMEDIATE 事务，避免后台统计写入使业务事务产生 SQLITE_BUSY_SNAPSHOT。SQLite JDBC 在失败的 BEGIN/事务重启后可能遗留错误的 auto-commit 状态；Hikari 必须丢弃 SQLITE_BUSY/LOCKED 及明确“no transaction is active”的连接，不能复用到业务查询。该连接处理不自动重试任何统计或业务操作。
- 统计失败通过专属 SSE 类型触发消息刷新，前端 REST/SSE 解析均保留该类型，不更新业务状态；刷新和事件重放按持久化消息 ID 去重。Designer 业务消息与统计通知共用数据库原子追加序号，不使用独立的 MAX+INSERT；并发回归使用与生产一致的文件 SQLite。完整 Vitest 门禁最多并行 4 个 worker，避免并发 jsdom 资源争用导致超时。
- 统计 Agent 权限必须按 `* deny`、`aicoding* allow` 顺序序列化，禁止用无序 Map.of 生成有优先级的规则。受管运行时安装 `loopper-accounting` Agent 及 `loopper-accounting-guard.mjs`；guard 不实现 aicoding，只按保留消息 ID 隔离模型上下文并阻止统计回合调用业务工具。受管 Designer 的 Session 权限增加仅供统计回合使用的 aicoding_* 例外，避免 Session deny-all 覆盖统计 Agent；guard 以每条消息的 tools 禁用业务回合的统计工具、统计回合的 question/业务工具，且在工具执行前再次按归属拦截，不覆盖 Session 原有读写/路径权限；其候选提交识别必须覆盖七个角色专属 Tool 及恢复专用 `submit_candidate`，不能只匹配旧后缀。普通 OpenCode 手动会话不受此保护插件限制。业务提示显式恢复业务 Agent/模型；BEGIN 屏障期间对业务投影 RUNNING 与空问题列表，HTTP 读模型同样排除统计消息及子回复，不把统计结果送入必须提问检查。
- 开发模拟插件、接收服务与资格脚本位于 `scripts/aicoding/`，只运行于隔离端口/数据/XDG 目录，不访问内网统计平台；模拟成功不能替代内网插件回执和并行语义验证。详见 `docs/story-binding.md`。


- Luna 离线复杂回合必须使用精确 Codex task ID 续跑，整理阶段禁用提交工具；报告必须绑定候选 SHA 并覆盖独立固定语义清单，编译 ACCEPTED 不自动计为正确。实际模型请求数不可得时保留 null，GEPA 在请求前预算接口验证阶段停止；隔离 HTTP/SQLite 测试不得宣称真实 OpenCode Provider 已通过。

## 12. 维护记录

每次实际代码修改必须追加或更新本表，记录范围、验证和 JAR 结果。0.3.80 及以前的原始记录已完整归档至 [历史维护记录](docs/history/maintenance-through-0.3.80.md)。

| 日期 | 范围 | 文档/契约变化 | 验证与 JAR |
| --- | --- | --- | --- |
| 2026-09-08 | 全项目清理，0.3.81 | 删除无调用方方法、查询、DTO、枚举及无用导入；收紧规模门禁；归档旧发布说明与已完成工单；统一 MCP 冻结次数策略说明，保留全部迁移和 Legacy 恢复合同 | 聚焦后端 95/95、前端 42 文件 276 项、guard 7 项通过；./scripts/verify.sh BUILD SUCCESS：后端 1534/0/0/2，前端 42 文件 276 项、guard 7 项；JAR 289494542 bytes，1586 生产类、113 静态文件与本次构建逐字节一致，75 个迁移原样，V2 true 且无已删除类型/实验类/SAT4J；SHA-256 a6ab23d7dda2b7a15862cea1cad43a688a069f13e7d28d877c6e89f14a2c0a73；GitNexus 影响低风险，归档保真与链接检查通过；未部署、未重启 |
