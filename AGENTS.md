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
   - 版本采用递增且从未发布过的 SemVer，禁止复用 Maven 版本、Git 标签或 GitHub Release；
   - 同步更新 `pom.xml`、`frontend/package.json`、`frontend/package-lock.json`、`application.yml`、Java MCP server info、README、本文件、`scripts/start-linux.sh` 和 `scripts/start-windows.bat`；
   - 使用 `rg` 检查旧版本是否仍残留在应同步的发布路径中；
   - 同一版本下仅允许对失败的同一次构建做诊断重试；源码或交付内容再次变化后必须使用下一个版本。
5. 运行与改动直接相关的聚焦测试，再运行完整验证和打包：

   ```bash
   ./scripts/verify.sh
   ```

6. 确认生成新的可执行 JAR：

   ```bash
   test -s target/opencode-loopper-0.1.70.jar
   jar tf target/opencode-loopper-0.1.70.jar \
     | rg 'BOOT-INF/classes/static/(index.html|assets/)'
   shasum -a 256 target/opencode-loopper-0.1.70.jar
   ```

7. 执行 `git diff --check` 和 `git status --short`，确认没有误改、生成物污染或用户改动被覆盖。
8. 对需要交付的代码更新，提交并推送版本修改后创建不可移动的 `v<version>` 标签并推送；标签会触发 `.github/workflows/release.yml`，由 GitHub 在标签提交上重新测试、打包并发布 JAR、`start-linux.sh`、`start-windows.bat` 和 `SHA256SUMS`。必须等待工作流结束并回读 Release 资产状态与 digest。
9. 最终交付必须明确报告：修改文件、验证命令及结果、本地 JAR 路径与校验值、Git 标签、GitHub Release URL、Actions 结果、尚未执行的运行时验证和剩余限制。

**“源码已改”“测试通过”“JAR 已生成”“端口 8080 正在运行新 JAR”“浏览器已加载新静态资源”是五个不同结论。** 未实际核验时不得宣称后一个结论。

### 例外处理

- 用户明确要求不运行测试/不打包时，遵从用户要求，但必须在最终交付中醒目标注未生成新 JAR。
- 因环境、网络、依赖或已有用户改动导致完整验证失败时，不得伪造成功；先保留失败输出，尽可能运行安全的聚焦验证，并报告阻塞点。
- 纯调查、解释或代码评审不授权修改文件，也不要求为只读任务打包；一旦实际修改仓库文件，就按上述交付流程执行。
- 完整打包后仅回填本文件“维护记录”中的测试数、JAR 哈希和结果，不需要递归再次打包；该回填不改变可执行产物内容。
- 用户已将版本标签触发 GitHub Release 定义为本项目的标准代码交付流程，因此完成可交付代码更新时允许推送对应提交和新版本标签；除此之外，不要擅自推送其他分支、部署、重启服务或覆盖运行中的 JAR。

## 1. 项目目标与产品边界

OpenCode Loopper 是一个本机 AI 编程控制平面：将自然语言需求转换为经人工确认的分阶段 `LoopSpec`，在受控工作区中驱动 OpenCode 实施，并通过确定性验证、独立 Requirement/Risk 双 Judge、恢复和发布流程形成可审计闭环。

必须保持的产品边界：

- Loopper、受管 OpenCode、MCP 和验证器网络访问默认只绑定或允许 loopback。
- 服务端持久化状态是权威事实；前端不能制造队列、进度、用量、成本或模型输出。
- Designer 只读；确认 LoopSpec 之前不得写业务源码、创建执行任务或假装交付完成。
- 人工确认是不可跳过的边界：LoopSpec 确认、危险权限、成功任务发布、本地冲突写回都需要明确动作。
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

- Maven 项目版本：`0.1.70`。
- 正式产物：`target/opencode-loopper-0.1.70.jar`。
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
| Designer / LoopSpec / Review Gate | `docs/design-contract.md`、`docs/opencode-contract.md` | `DesignerSessionService.java`、`LoopDraftService.java`、`LoopSpec.java`、`DesignerView.vue` |
| OpenCode Runtime / Session | `docs/opencode-contract.md` | `runtime/OpenCode*.java`、`TaskSessionMonitorService.java`、`RuntimeView.vue` |
| 验证器 / Judge / 证据 | `docs/architecture.md`、`docs/seven-feature-contract.md` | `verification/`、`TaskVerificationDispatcher.java`、`TaskService.java` |
| Git 任务分支 / Direct / Recovery | `docs/architecture.md`、`docs/seven-feature-contract.md` | `GitWorktreeManager.java`、`DirectWorkspace*`、`RecoveryService.java` |
| 发布 / 本地同步冲突 | `docs/architecture.md` | `TaskPublicationService.java`、`LocalSyncConflictService.java`、`TaskPublicationActions.vue`、`CodeMergeEditor.vue` |
| Pending Center / 权限 | `docs/seven-feature-contract.md` | `InteractionService.java`、`InteractionController.java`、`InboxView.vue` |
| 自动化 / 模板 | `docs/seven-feature-contract.md` | `AutomationService.java`、`LoopSpecTemplateService.java`、`AutomationsView.vue` |
| 数据库变化 | 所有受影响契约 | `db/migration/`、`LoopperMapper.java`、对应集成测试 |
| UI 视觉/状态 | `docs/design-contract.md` | 相似 `views/`、`components/`、`styles/tokens.css` 和 `.spec.ts` |
| 打包/部署 | `README.md` | `pom.xml`、`application.yml`、`scripts/verify.sh`、`scripts/start-linux.sh`、`scripts/start-windows.bat` |

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
- OpenCode Todo 只允许作为实施 Session 的非权威进度投影：先探测 `todowrite`，可用时才注入提示；外部读取在 SQLite transaction 外，每两秒至多一次，只在内容变化时持久化。最多 64 项、单项 1 KiB、总计 64 KiB，稳定 ID 基于规范内容和重复序号；Todo 的成功、失败或完成状态都不得改变 Task/Stage/Attempt/Verifier/Judge 生命周期，Designer/Judge 不展示 Todo。

### 5.2 错误层级

`ErrorLayer` 是公开持久化契约：

- `FIELD`：输入或草稿校验，不改变运行状态。
- `VERIFICATION`：当前 Attempt 验收失败，保留证据并在预算内继续循环。
- `SESSION`：OpenCode Session 失败，关闭当前 Session/Attempt，安全确认后创建新 Session。
- `TASK`：无法安全继续或预算耗尽，关闭所有子运行并进入 `FAILED`。

Session adapter 不得直接把 Task 写成 `FAILED`；重试耗尽后的升级由编排器负责。终止 Task 不能伪造远端 Session 已停止：无法确认的写入者保留为 `DISCONNECTED`，并阻止重叠写入。

`PENDING_START`、`QUEUED`、`READY` 与 `WAITING_INPUT` 任务必须在本地任务详情中保留直接取消入口；取消需二次确认并保留已有执行目录、分支和证据，不得伪装成回滚。`PENDING_START` 取消只改变自身 Task，必须保持无 Queue/Lease/分支/执行目录；`READY` 取消不得伪称正在停止尚未创建的 Session/验证器，并按既有终态安全检查恢复源分支与释放自身租约。取消排队任务只能取消自身队列项并进入 `CANCELLED`，不得释放或转移当前 holder 的写租约；进入终态后再按既有规则归档或删除。

验证失败后的 Attempt 必须固化有界 `ATTEMPT_HANDOFF`，下一轮只能使用新 Attempt 和新可写 Session；不得复用旧实施对话。只有可靠且相同的失败签名与工作区内容指纹才累计停滞次数，达到 `stagnationLimit` 后必须进入 `WAITING_INPUT`，由本地 UI 明确确认继续。

### 5.3 Designer 和 LoopSpec

- Designer 新建会话先进入 `DISCUSSING_REQUIREMENT`：整体需求和每个工作包的初稿/人工修订都必须先调用一次 `question` 提出 1–3 个选择题，回答后在同一模型调用中返回不超过 24 KiB UTF-8 的完整 Markdown 替代快照。服务端持久化完整快照、决策、问题状态和讨论修订；遗漏问题时只允许在全新 Session 补问一次，再次遗漏以 `DESIGN_QUESTION_REQUIRED` 进入 `WAITING_INPUT`。正常讨论/评审使用 Designer Session `REVIEWING`，不得滥用 `WAITING_INPUT`。
- 拆包前的需求消息只更新需求讨论，不调用 Decomposer；只有显式确认需求才冻结下一编号需求版本并拆包。拆包后旧 `/messages` 缺少作用域时必须返回 `DISCUSSION_SCOPE_REQUIRED`；修改整体需求要显式重开并废弃当前拆包/批准，包级消息只能修改当前包且不得创建新需求版本或重跑 Decomposer。全部作用域写请求和批准都携带期望讨论/设计修订，过期操作返回 409。
- Designer 写接口允许用空响应体返回成功的 `202 Accepted` 或 `204 No Content`；前端公共 API transport 必须把任意成功空响应解释为已完成的 void 操作，再刷新服务端权威快照，不得对空内容调用 JSON 解析器并误报失败。
- 每条完整用户需求先冻结不可变需求版本并交给独立只读 Task Decomposer；只允许 `read/glob/grep`，不得写文件、执行命令、提问或创建 Task。服务端按非空段落/列表编号并校验每段被全局约束或至少一个工作包引用。
- `DIRECT_DESIGN` 恰好一个包；大型任务拆成 2–6 个依赖有序的纵向业务包，每包 1–3 个 Stage、总计不超过 18 个。禁止把数据库、后端、前端、测试机械分层拆包。多项目根、超过六包或多个独立发布边界必须返回 `MULTI_TASK_REQUIRED` 并等待人工，不自动创建子 Task。
- 工作包严格串行执行，每包在健康时复用自己的只读交互 Designer Session，每个候选使用全新的只读 Compiler Session，并使用当前配置的同一模型。Designer 只输出不超过 24 KiB UTF-8 的完整 Markdown；Compiler 只输出当前包 1–3 个 Stage、来源映射及不超过 4 KiB 的交接摘要。Stage/验收 ID 使用稳定 `workPackageId` 与 `<workPackageId>-AC-n`。
- Decomposer 和分包 Compiler 都必须使用同一只读 Session 的持久化两轮智能编译：第一轮按“规划 → 证据映射”顺序生成有界中间合同，服务端校验并冻结；第二轮只把冻结规划编码为最终 JSON，不得更改包边界、Stage、验收来源、测试证据或交接摘要。规划与最终原始 JSON 都不得进入聊天消息，SSE 只投影权威步骤和摘要。
- Decomposer 和每包 Compiler 的“规划/证据映射”与“最终 JSON”各有独立的最多两次格式修复预算；规划修复耗尽后如成功冻结，不得挤占最终 JSON 的修复机会。闭集语义缺口最多只让当前包完整重设计一次。每个只读角色已确认的传输失败允许一个全新 Session 重试。整个需求版本最多 96 次模型调用，需求/包级问题回答继续已经计数的同一次调用，不得制造隐藏预算；六包无重试机器链路固定消耗 20 次，各包内容次数互不挤占但受全局上限约束。
- Decomposer 规划/最终、Compiler 规划/最终和 Judge 必须优先使用服务端固定 ID 的 OpenCode JSON Schema；provider 内建 schema 重试固定为 0。只有格式接口拒绝、明确 `StructuredOutputError` 或完成后缺失 structured payload 才能在全新只读角色 Session 中回退到 marker，并计入当前步骤原有模型调用与修复预算；不得在失败 Session 内继续、增加隐藏重试池或绕过确定性语义校验。历史活动记录按 `TEXT_MARKER` 兼容。
- 对 Decomposer/Compiler，OpenCode `RETRY` 是 Provider 自恢复中的瞬态 Session 状态，不得当作设计流水线传输失败或消耗全新 Session 重试；Implementation 与 Judge 保留既有失败升级合同。Loopper 管理的 Decomposer、Compiler 和 Judge 必须选择最多 24 个 agentic steps 的私有 `loopper-structured` agent。若最近一次用户提示后的同一规范化工具名和参数连续出现 3 次，必须立即尽力 abort，并且每个角色步骤最多启动一次禁用全部工具的 finalizer Session；恢复资格和纠正类别持久化在 V28，finalizer 计入全局模型调用预算但不占格式修复次数，24 步仍是最终保险。若 structured prompt 已接受但消息读取接口随后以格式/Schema 400 拒绝，必须按结构化格式不支持进入既有全新 marker Session 回退。结构化角色最终进入 `WAITING_INPUT` 或 `SESSION_ERROR` 前必须尽力 abort 当前远端 Session，UI 的“已停止”不得与仍在读仓库的远端执行并存。
- Decomposer、Compiler 和最终 Judge 的机器响应 Session 必须显式使用 `thinking=false`；Loopper 管理的 DeepSeek Runtime 为当前配置模型注入 `loopper-no-thinking` variant（`thinking.type=disabled`）并在提示时选择它，避免 Thinking 与 JSON Schema 强制工具选择冲突。机器角色同时使用零温度和禁止重复/虚构工具调用的固定指令。OpenCode 1.18.12–1.18.18 已确认会在读取自身持久化 Schema 时返回 400，必须直接使用 marker 兼容模式；后续版本恢复能力探测。交互式 Markdown Designer 和可写 Implementation 继续保留配置/LoopSpec 的 thinking 选择；复用外部 DeepSeek Runtime 时由操作者提供同名 variant，缺失时不得绕过既有全新 Session marker 回退。
- OpenCode Session 使用角色权限模板：Decomposer/Compiler/Judge/通用只读只开放 `read`/`glob`/`grep`，Designer 额外开放 `question`；只读角色仍拒绝 `.env`/`.env.*`、外部目录和全部其他工具。Runtime 可展示 agent、原生 `plan` 与 structured-output 能力，但当前不得让 Designer 接管原生 plan；Designer Markdown、Compiler JSON 和 Validator 权威边界保持不变。
- Decomposer、Compiler、Judge 与项目公约共用有界包容性提取器：原生 structured payload、角色 marker、`json`/无语言代码块、说明文字中括号完整的 object 和整段 object 按优先级提取。仅接受标准 JSON object；字符串花括号、转义、BOM 和空白必须正确处理；等价候选去重，不等价且都合格的候选按歧义拒绝。字段名、可选集合、枚举和安全命令分词只做唯一可逆的确定性规范化，成功提取/规范化直接进入同一权威业务合同校验并记录 `AI_OUTPUT_NORMALIZED`，不得消耗格式修复次数；数组根、残缺/非标准 JSON、不可唯一推导的缺口以及安全或执行合同违规则继续阻断。
- Compiler 的规划、最终生成与格式修复提示必须注入当前步骤的完整 JSON 类型合同和规范信封，明确集合、验证器、直接 argv、验收映射、测试目标、托管运行时及设计缺口的对象/数组/null 边界；不得要求模型从 Java 反序列化错误反推 DTO 结构。v2 分包规划必须使用 `contractVersion=2` 并携带完整 `VerifierSpec` 蓝图和可选 `verificationRuntime`；服务端必须在冻结规划前使用权威 LoopSpec v2 执行合同校验直接命令、行为覆盖、Java 聚焦测试及运行时绑定，最终 JSON 只能逐字段复制该蓝图。丰富提示不能替代服务端确定性校验。
- 分包 Designer/Compiler 读取的仓库是不可变的执行前基线；前置包 `APPROVED` 表示其设计、编译和校验合同已通过且已经人工接受，不表示生产文件已写入基线仓库。服务端必须注入前置包的冻结目标、Compiler 摘要和交接合同；严格串行执行保证前置 Stage 先落地，因此当前 `read/glob/grep` 找不到前置交付物不得返回 `MISSING_SCOPE`。Compiler 负责 Stage/业务验收/证据语义；服务端在权威 v2 校验前确定性生成 `<workPackageId>-AC-n`、仅在唯一归一化匹配时恢复 Designer 精确原文，并把 Designer 明确列出的聚焦单测行作为强制证据注入规划/修复提示。服务端只允许从安全的 Maven/Gradle `-Dtest`、`-Dit.test`、`--tests` 显式选择器提取目标；同一 Java Stage 唯一匹配时可补齐重复的 `testCommand`/`testTargets`/`criterionIds` 或等价 TEST 验证器，不得从普通描述、全量测试或多个候选中猜测。歧义匹配或缺少语义证据仍必须阻断。
- 全部包完成后只能由服务端按包顺序确定性聚合，不允许模型二次合并；聚合保留草稿模型、Session 策略、预算和重试模板，并只在最初冻结的 draft version 上原子同步。草稿并发变化必须在启动下一包前停止，不再消耗模型调用。
- 聚合 Stage 的 `workPackageId` 映射进入 Review Gate 后不可删除、改写或重排；前端读取、结构化编辑、保存和确认必须无损往返。草稿更新边界拒绝映射漂移，确认边界还要校验每个已批准工作包均存在且保持依赖顺序，禁止静默降级成无包 Stage 任务。
- 工作包 Designer 在健康时复用该包自己的交互 Session；远端丢失时用持久化需求、当前完整包设计、决策和作用域消息重建，不得重跑已完成 Decomposer。每个候选仍使用独立只读 Compiler 并经过唯一权威 Validator；通过后进入 `REVIEWING`，只有人工接受当前已验证修订才启动下一包。初稿后每包最多 5 轮人工修改；失败候选不得覆盖上一版有效候选。重开已接受包时先展示影响，只把它的传递依赖标记 `STALE`，无关 `APPROVED` 包保持有效。
- 全部工作包 `APPROVED` 后才允许服务端确定性聚合并进入 `FINAL_REVIEW`；包级阶段右侧候选只读，最终聚合阶段才开放结构化编辑。V27 持久化讨论与批准；历史未确认 `COMPLETED` 包迁移为 `REVIEWING`，已确认草稿和已创建 Task 不变。
- 只有完成、项目匹配、版本匹配且经服务端确定性验证通过的聚合 LoopSpec 才能同步到绑定草稿；模型不得自报校验成功。人工确认前仍不得写业务源码、创建 Task 或制造执行状态。
- 确认时冻结完整 `REQUIREMENT_CONTEXT`、`DECOMPOSITION_CONTEXT`、每包 `WORK_PACKAGE_DESIGN`/`WORK_PACKAGE_COMPILATION_SUMMARY`，并保留组合 `DESIGN_CONTEXT` 兼容历史。执行提示按当前 Stage 的包只注入当前包设计、全局约束和前置包交接。
- 执行期若已冻结的 `DECOMPOSITION_CONTEXT` 无法解析、字段形状错误、缺少当前包或依赖 ID 无效，必须在创建可写 OpenCode Session 前以 `DECOMPOSITION_CONTEXT_INVALID` 失败关闭；不得静默丢弃全局约束或前置包交接。
- Designer 合并在单个数组项中的 Maven 参数若能无歧义解析，应在同步时直接规范化并保存为独立 argv，不消耗自动纠正次数；只有引号未闭合等无法安全解析的输入才按无效 LoopSpec 回送纠正。
- 人工确认必须是幂等边界；确认后创建唯一 `PENDING_START` Task，且不申请执行资源，再由用户显式请求开始。
- 项目 `taskCount` 只统计已创建的 Task，不得把确认前 Designer 会话伪装成任务；服务端必须按项目另行投影每个未确认草稿的最新 Designer Session 和 `openDesignerSessionCount`。浏览器工作区 ID 只是恢复提示，服务重启或短暂 API 失败不得清除；Designer 起始页必须能脱离浏览器本地状态从服务端选择并恢复权威 Session/草稿。
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
- 同一登记 root（Git 或 Direct）同时只能有一个未释放写租约；旧写入者状态未知时保持租约并阻断 Recovery/Automation。Git 任务仍有未提交文件改动时保留租约；用户确认提交后恢复任务开始前的源分支并释放租约，有排队任务时再切换到下一任务分支。
- Task、Queue 与 Lease 必须保持独立状态机，并由统一协调器维护跨状态不变量：`ADMITTED` 必须与非 `RELEASED` 租约的 holder 一致。终态 holder 只有在写入 Session/验证运行时已确认停止、项目指纹一致、工作区干净且源分支可安全恢复时，才允许完成队列项并严格按 FIFO 原子转移租约；Git/指纹检查在 SQLite 事务外，真正的完成/转移在短事务内复核。启动恢复、取消/Session 清理、归档前置、手动检查和仅扫描“终态 holder + QUEUED waiter”的 10 秒后台协调必须复用同一逻辑，且并发幂等。活动 holder 或 `ADMITTED` 任务不得归档/永久删除，删除路径不得清空 holder 绕过状态机；任何阻塞均 fail closed，不得自动 stash、提交、删除或强制切分支。
- Recovery 仅从 `FAILED`/`CANCELLED` 派生，模式为 `FROM_FAILED_STAGE`、`ALL_STAGES` 或 `VERIFY_ONLY`。
- `VERIFY_ONLY` 不创建可写 Session；Direct 模式不提供原地回滚。
- fingerprint、baseline 或旧 writer 不匹配时必须 fail closed。
- Direct root fingerprint 必须同时包含 canonical path、目录 file key 和创建时间，避免 Linux inode 立即复用；只有 `RELEASED` 且无写入者的租约可在新任务准入时刷新指纹。

### 5.6 发布与历史删除

- 自动发布只面向 `SUCCEEDED` 的 Git 任务分支；Direct 任务由用户在源仓库手工处理。
- 用户必须提供四位数字工单号；提交格式为 `#dddd_subject`。AI 只能建议 subject，不能生成或替代工单号。
- 推送必须是普通非 force push；PR/MR 只打开预填创建页，最终创建和合并仍由平台/用户确认。
- HTTP/HTTPS remote 的 MR/PR Web 地址保留显式协议；SSH remote 默认使用 HTTPS，但 `loopper.publication.http-web-hosts` 中精确列出的主机使用 HTTP。成品启动脚本必须默认加入 `gitlab.spdb.com`，且不得改变 SSH 推送协议。
- `TaskState.SUCCEEDED` 只表示执行验收成功；远端交付使用独立 `TaskPublicationState`。`COMMITTED`、`PUSHED`、MR 打开/关闭和 `MERGED` 均为持久化事实，其中 `MERGED` 无出向转换，且原任务的提交、推送、创建 MR 和重新评审入口必须拒绝；新分支重做不改变原记录。
- 只有配置主机完全匹配、并由 GitLab API 按源分支、目标分支和任务提交 SHA 唯一确认的 `merged` MR 才能推进 `MERGED`。删除源分支或引用、打开创建页、人工点击和本地 Git 推断都不能单独证明合并。Token 只从 `LOOPPER_GITLAB_PRIVATE_TOKEN` 注入，不写入持久化、日志、DTO 或 artifact；外部查询位于 SQLite transaction 外并在返回后复核 Task 与 Publication 版本。
- 新任务必须持久化任务开始前的源分支。提交任务分支后先恢复该源分支；有排队任务时再进入下一任务分支。推送、推送重试和 PR/MR 状态只使用明确的任务分支引用，不得为了发布旧任务而切换当前项目分支。
- 没有远端时在登记目录任务分支创建本地提交并记录证据，恢复后的源分支不快进、不覆盖；新任务不存在源目录与隐藏 worktree 的二次同步。
- 历史隐藏-worktree 任务仍保留旧版本地同步与冲突证据兼容能力，但不得用于新任务。
- 删除历史任务是终止操作：只允许已归档且终止的任务，需要二次确认，父任务仍有子 Recovery 时拒绝。
- 历史记录删除不得删除源文件、Git 分支或 worktree。

## 6. 后端开发约定

- API Controller 只做输入/输出边界、校验和 DTO 映射；业务编排留在 `service/`。
- 领域状态使用现有枚举和 typed failure；不要用散落字符串复制状态语义。
- `TaskService` 负责 OpenCode、验证器、Judge 等副作用编排；状态机只决定合法转换，不承载外部 I/O。
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

### 数据库迁移

- 已存在的 `V1`–`V19` 迁移视为不可变历史，禁止修改。
- 已有 V1–V28 Flyway 迁移不可修改；Schema 变化新增下一序号迁移，并同时验证全新数据库和至少一个受支持旧版本升级路径。
- SQLite 外键级联不能只靠假设；活动连接必须明确启用，终止删除路径仍要按依赖顺序显式清理并验证事务回滚。
- 数据库枚举码、artifact kind、错误码和 audit event 是兼容性契约；修改前先搜索所有 Java、SQL、前端 type/label 和测试消费者。

## 7. 前端开发约定

- TypeScript 类型以 `frontend/src/types/domain.ts` 为边界，API 变更必须同步 DTO、client、store、view 和测试；Designer 保存/确认必须无损往返 Stage `workPackageId` 以及全部 LoopSpec limits、model、sessionPolicy 和 nextAttemptPromptTemplate。
- 任务详情只为 `PENDING_START` 显示“开始执行”；该状态必须明确尚未入队、占用租约或切换分支。`READY` 是已请求执行后的短暂内部状态，只显示自动继续语义，不得再次显示开始按钮。
- Task 等待动作以服务端 `waitingReasonCode` / `loopRetryAvailable` 投影为准，前端不得从历史错误推断当前“继续一轮”入口。
- `SOURCE_BRANCH_WORKSPACE_DIRTY` 错误事件作为审计历史保留，但活动红色提示只在 Task 仍为 `WAITING_INPUT` 且当前 `waitingReasonCode` 与其一致时显示；进入 `READY`/执行阶段后不得残留为当前故障。
- `SOURCE_BRANCH_WORKSPACE_DIRTY` 必须打开不可静默关闭的文件处理弹窗，逐文件选择提交、stash 或移除；重新检查成功前不得制造任务分支已创建的状态，取消只能经确认后把任务标记为失败。
- 服务端是权威状态；不要用计时器伪造阶段进度、用量、成本、Session 完成或 Judge 结果。
- 所有等待、问题、权限、可恢复错误和终止错误都必须真实可见，并提供可执行的恢复动作；不要永久显示含糊的“待评审”。
- 使用 `displayLabels.ts` 和现有 `StatusBadge`/错误组件表达中文含义；不要在多个页面复制英文枚举到中文的映射。
- 遵循 `docs/design-contract.md` 的 dark-first token、错误层级和桌面优先结构；优先复用 `styles/tokens.css`，不要引入页面私有的另一套视觉系统。
- Markdown 必须经过 DOMPurify；Mermaid 错误必须抑制并清理渲染残留，不允许把原始不可信 HTML 插入 DOM。
- 冲突、代码、JSON 等编辑器优先复用 CodeMirror 组件和现有语言映射。
- 交互写操作要有 loading、错误、幂等/版本冲突处理；破坏性操作必须明确确认。
- Runtime 显式启动和重启都必须携带本地 UI 标识，服务端须在检查进程所有权或执行副作用前验证；LoopSpec 编辑器的数值上限必须与领域 Bean Validation 一致（启动 300 秒、停止 60 秒、单阶段尝试 20 次）。
- 运行环境页的 OpenCode Loopper 版本必须来自服务端 Runtime DTO，不能使用前端 package 版本硬编码，也不能与 OpenCode CLI 版本混为一个字段。
- 每个行为变化都在相邻 `.spec.ts` 中增加回归测试；路由级关键流程再考虑 `frontend/e2e/`。
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
JAR=target/opencode-loopper-0.1.70.jar
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

## 9. 文档同步规则

| 变化 | 必须同步 |
| --- | --- |
| 面向用户的功能、安装、配置、页面或故障排查 | `README.md` |
| 架构、生命周期、错误、事务、工作区或发布不变量 | `docs/architecture.md` |
| Designer、Review Gate、视觉状态、交互语义 | `docs/design-contract.md` |
| OpenCode API、Session、权限或 MCP | `docs/opencode-contract.md` |
| Recovery、Interaction、Verifier、Insight、Automation | `docs/seven-feature-contract.md` |
| Agent 命令、目录、开发规则、关键陷阱、完成定义 | `AGENTS.md` |
| 版本/JAR 名称 | README、AGENTS、POM、前端 package、Linux 脚本、application.yml |

更新文档时只写已经实现并验证的事实。计划、建议和未验证运行时结果必须清楚标注，不得写成现有能力。

## 10. Git、文件和协作安全

- 默认只修改用户明确要求的范围；不要顺手重构无关代码。
- 工作区可能不干净。现有修改属于用户，除非有明确证据，否则不得恢复、覆盖、格式化或纳入本任务。
- 禁止使用 `git reset --hard`、`git checkout -- <file>`、递归删除工作区或其他不可恢复操作。
- 不要手工编辑 `target/`、`frontend/dist/`、`frontend/node_modules/`、SQLite 数据库或 Flyway 已执行迁移来“修复”源码问题。
- 不创建提交、不切分支、不推送、不创建 PR/MR，除非用户明确要求。
- 本项目的版本发布公约是上条规则的已授权例外：完成交付型代码更新后，允许推送已核验的发布提交和全新 `v<version>` 标签，以触发标准 Release 工作流；禁止强推、移动或复用标签。
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
- [ ] 发布提交已推送，新 `v<version>` 标签指向该提交且与 Maven 版本一致。
- [ ] Release 工作流成功，GitHub 资产包含 JAR、`start-linux.sh`、`start-windows.bat` 和 `SHA256SUMS`，远端 digest 已回读。
- [ ] 如声称运行时有效，已核对真实 PID/cwd/JAR/health/浏览器证据。
- [ ] 最终回复列出文件、验证、JAR、运行时边界和剩余风险。

## 12. 维护记录

本表必须由每次实际修改代码的 Agent 在结束前追加或更新。保持简短；详细证据放在任务回复或对应契约文档中。

| 日期 | 范围 | 文档/契约变化 | 验证与 JAR |
| --- | --- | --- | --- |
| 2026-08-17 | AI 输出包容性解析、工具循环恢复与 0.1.70 交付 | Decomposer/Compiler/Judge 共用严格 JSON object 提取与可审计确定性规范化，项目公约兼容 marker/唯一 fence/整段 Markdown；V28 持久化纠正类别与每步骤一次 finalizer 资格；连续 3 次同工具同参数提前 abort 并无工具收口；纯验证元描述不生成业务验收项，全量测试仅可作补充报告；前端以普通信息样式展示规范化与恢复提示；同步 README、架构、设计、OpenCode、七特性合同与本公约正文 | 0.1.68 首次完整验证因发布脚本契约仍固定旧 JAR 名失败，0.1.69 再发现迁移测试仍断言 V27；均修正并按版本规则递增。聚焦验证：`mvn -Dtest=AiOutputExtractorTest,HttpOpenCodeClientTest,ProjectConventionServiceTest test` 后端 33/33、前端 159/159；`mvn -Dtest=DesignerSessionMcpIntegrationTest,TaskServiceIntegrationTest test` 后端 95/95、前端 159/159；`mvn -Dtest=FeatureMigrationTest,ReleasePackagingContractTest test` 后端 10/10、前端 159/159。完整 `./scripts/verify.sh` 通过：Java 402 项（跳过 1）、前端 159 项；JAR `target/opencode-loopper-0.1.70.jar` 为 263090915 bytes，SHA-256 `6fade8fe70745872ce7e4b4dcf1523337cbf41979ba94f0fd1802e7cd3d29484`，含 `BOOT-INF/classes/static/index.html` 与静态 assets。隔离实例在 18080 使用真实 DeepSeek/OpenCode 1.18.18 `TEXT_MARKER` 模式完成需求讨论、DIRECT_DESIGN 拆解、WP-1 设计/编译/批准与确定性聚合并进入 `FINAL_REVIEW`：模型调用 7/96，Decomposer 格式修复 0、最终修复 0、规划语义修复 1，Compiler 规划/最终修复均为 0、格式回退 0、工具循环 finalizer 0，`OUTPUT_MARKERS_MISSING` 消息 0，持久化 3 条 `NORMALIZED` 审计；未创建 Task，验证后已关闭隔离 Loopper/OpenCode，现有 8080 仍为 0.1.67/PID 38300。Release 证据待标签工作流完成后回填 |
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
