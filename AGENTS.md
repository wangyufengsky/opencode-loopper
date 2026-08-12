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
   test -s target/opencode-loopper-0.1.26.jar
   jar tf target/opencode-loopper-0.1.26.jar \
     | rg 'BOOT-INF/classes/static/(index.html|assets/)'
   shasum -a 256 target/opencode-loopper-0.1.26.jar
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

- Maven 项目版本：`0.1.26`。
- 正式产物：`target/opencode-loopper-0.1.26.jar`。
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

### 5.2 错误层级

`ErrorLayer` 是公开持久化契约：

- `FIELD`：输入或草稿校验，不改变运行状态。
- `VERIFICATION`：当前 Attempt 验收失败，保留证据并在预算内继续循环。
- `SESSION`：OpenCode Session 失败，关闭当前 Session/Attempt，安全确认后创建新 Session。
- `TASK`：无法安全继续或预算耗尽，关闭所有子运行并进入 `FAILED`。

Session adapter 不得直接把 Task 写成 `FAILED`；重试耗尽后的升级由编排器负责。终止 Task 不能伪造远端 Session 已停止：无法确认的写入者保留为 `DISCONNECTED`，并阻止重叠写入。

`WAITING_INPUT` 任务必须在本地任务详情中保留直接取消入口；取消需二次确认并保留执行目录、分支和证据，不得伪装成回滚。

验证失败后的 Attempt 必须固化有界 `ATTEMPT_HANDOFF`，下一轮只能使用新 Attempt 和新可写 Session；不得复用旧实施对话。只有可靠且相同的失败签名与工作区内容指纹才累计停滞次数，达到 `stagnationLimit` 后必须进入 `WAITING_INPUT`，由本地 UI 明确确认继续。

### 5.3 Designer 和 LoopSpec

- Designer 只能创建只读 OpenCode Session。
- 可见 Markdown 与机器 LoopSpec 是不同内容：只显示清理后的 Markdown；只解析 `LOOPSPEC_JSON_START/END` 包裹的完整 JSON。
- 只有完成、项目匹配、版本匹配且验证通过的 LoopSpec 才能同步到绑定草稿。
- 无效 LoopSpec 最多进行项目规定次数的只读自动纠正；仍无效时保持未同步，不得创建 Task 或写源码。
- Designer 合并在单个数组项中的 Maven 参数若能无歧义解析，应在同步时直接规范化并保存为独立 argv，不消耗自动纠正次数；只有引号未闭合等无法安全解析的输入才按无效 LoopSpec 回送纠正。
- 人工确认必须是幂等边界；确认后创建唯一 Task，再由用户显式启动 `READY` Task。
- 确认时冻结完整 Designer 设计为只读 `DESIGN_CONTEXT`；结构化 LoopSpec/verifier 合同优先级更高。
- 非简单任务优先拆为 2–6 个依赖有序阶段；每阶段必须有聚焦、可立即执行的功能验收，不能把所有证明推迟到最后阶段。

### 5.4 验证器与 Judge

- `PROCESS.command` 是 argv 数组，直接调用程序；禁止 `sh -c`、`bash -c`、`cmd /c`、管道、重定向和 shell 插值。
- Windows PROCESS 必须在启动前按任务根目录解析 `mvnw`/`gradlew` 包装器，并按 Loopper 进程 `PATH`/`PATHEXT` 解析裸程序的 `.com`/`.exe`/`.bat`/`.cmd` 入口；证据保存实际绝对 argv 与解析原因。Linux/macOS 保留原生 PATH 与可执行位语义。该适配不得放开用户 shell 启动器或 shell 片段，Windows 批处理启动必须启用 JDK 严格命令引用模式。
- Maven 参数兼容规范化只能进行确定性 token 拆分，不得启动 shell；新草稿保存规范化 argv，执行器还需兼容规范化历史草稿，并在证据中记录发生过拆分。
- Stage 的 `allowedPaths` / `forbiddenPaths` 只是 Agent 提示；只有显式 `GIT_DIFF` 才是路径/删除的强验收门槛。
- `GIT_DIFF` 只证明改动范围，不能作为一个阶段唯一的功能验证。
- `FILE_EXISTS` 是兼容旧草稿的非阻断审计提示；不要为 Designer 新生成它。需要证明产物时，用会在缺失时非零退出的 `PROCESS` 自检，并可要求明确的 `outputContains` 标记。
- `FILE_NOT_EXISTS` 只用于明确的安全不变量。
- HTTP/JSON/BROWSER 只访问 loopback；BROWSER 不允许任意 JavaScript，必须保留截图和 trace 证据。
- BROWSER 可执行文件发现顺序固定为显式 `LOOPPER_CHROME_EXECUTABLE`、进程 `PATH`、操作系统标准位置；显式路径无效时必须 fail closed。
- `DATABASE_QUERY` 只接受本地 SQLite 的只读单条 `SELECT`/`WITH`。
- 外部进程、HTTP、浏览器和模型调用不能在 SQLite transaction 内执行。
- 确定性验证成功与 Judge 成功是两套证据。Requirement 和 Risk Judge 都是独立只读 Session，必须明确 `PASS`。
- `REVISE`、`BLOCKED`、Judge 冲突或 JSON 无法解析时进入人工处理/重新评审，不得丢弃已有确定性证据或伪造成功。
- Attempt 交接的差异扫描、文件读取、内容哈希和新 Session 创建都在 SQLite transaction 外执行；按实际读取字节限制 16 MiB，并在读取前后核对文件大小、修改时间和 file key；不可完整读取或读取期间变化的快照标记为不可比较，不得据此触发停滞。

### 5.5 工作区、租约与 Recovery

- 有可用 Git HEAD：登记目录有未提交或未跟踪文件时，任务持有租约进入 `WAITING_INPUT`，本地 UI 显示具体文件并要求逐文件选择提交、stash 或移除；处理决定必须绑定分支、HEAD、索引、状态和内容快照，取消弹窗直接把任务标记为失败且不改文件。重新检查干净后，在登记目录本身创建并切换 `loopper/<任务名>`；本地或远端跟踪分支已有同名时从第二次起追加 `(第2次)`、`(第3次)`，Git 禁止字符确定性替换为 `-`，分支叶名称按 UTF-8 字节安全截断，并在截断后重新修正 `.lock` 等非法结尾。
- 创建任务分支前以非交互方式 fetch 当前分支的 upstream/明确首选远端；远端线性领先时从远端最新提交创建任务。本地领先时保留本地提交；认证失败、fetch 失败或历史分叉必须 fail closed。未收到逐路径确认时禁止自动 stash、提交、覆盖或丢弃改动；移除操作必须二次确认。外部 Git 操作部分成功后不得伪造事务回滚，必须回读最新状态继续处理。
- 原项目分支 checkout 使用独立 10 分钟有界超时和命令局部 `core.longpaths=true`；短 Git 检查仍使用 30 秒边界，失败诊断保留输出尾部。
- OpenCode 创建 Session 后必须回报与登记项目根一致的规范执行目录，缺失或不一致时不得发送实施提示；实施提示明确 AgentBridge、搜索、命令和验证器都使用该目录及当前任务分支。
- 无可用 Git HEAD：直接使用登记根目录，并在 `direct-baselines/<taskId>` 保存私有 Git-compatible 基线；不得在用户项目中隐式初始化或提交 Git。
- 所有路径 canonicalize 后进行 containment 和符号链接检查。
- 同一登记 root（Git 或 Direct）同时只能有一个未释放写租约；旧写入者状态未知时保持租约并阻断 Recovery/Automation。Git 任务仍有未提交文件改动时保留租约；用户确认提交后恢复任务开始前的源分支并释放租约，有排队任务时再切换到下一任务分支。
- Recovery 仅从 `FAILED`/`CANCELLED` 派生，模式为 `FROM_FAILED_STAGE`、`ALL_STAGES` 或 `VERIFY_ONLY`。
- `VERIFY_ONLY` 不创建可写 Session；Direct 模式不提供原地回滚。
- fingerprint、baseline 或旧 writer 不匹配时必须 fail closed。
- Direct root fingerprint 必须同时包含 canonical path、目录 file key 和创建时间，避免 Linux inode 立即复用；只有 `RELEASED` 且无写入者的租约可在新任务准入时刷新指纹。

### 5.6 发布与历史删除

- 自动发布只面向 `SUCCEEDED` 的 Git 任务分支；Direct 任务由用户在源仓库手工处理。
- 用户必须提供四位数字工单号；提交格式为 `#dddd_subject`。AI 只能建议 subject，不能生成或替代工单号。
- 推送必须是普通非 force push；PR/MR 只打开预填创建页，最终创建和合并仍由平台/用户确认。
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
- 所有外部命令使用参数数组；不要拼接未验证路径或用户内容到 shell。
- Git fetch/分支检查和 checkout 必须暂停调用方 SQLite transaction；远端 fetch 设置 `GIT_TERMINAL_PROMPT=0`，不得因凭据提示无限等待。
- 时间、超时、重试和最大输出必须有界，重启恢复必须能处理提交后的中间空档。
- 浏览器 SSE 只是权威状态的尽力投影：Task 事件提交后再发布，各订阅者必须隔离；断线、超时、`IOException` 或已关闭的 Servlet `AsyncContext` 只移除对应订阅，不得升级为 Designer、OpenCode Session、Attempt 或 Task 失败。
- Secret 只来自进程环境/内存，不写入 SQLite、日志、artifact 或测试快照。

### 数据库迁移

- 已存在的 `V1`–`V17` 迁移视为不可变历史，禁止修改。
- Schema 变化新增下一序号迁移；必须同时验证全新数据库和至少一个受支持旧版本升级路径。
- SQLite 外键级联不能只靠假设；活动连接必须明确启用，终止删除路径仍要按依赖顺序显式清理并验证事务回滚。
- 数据库枚举码、artifact kind、错误码和 audit event 是兼容性契约；修改前先搜索所有 Java、SQL、前端 type/label 和测试消费者。

## 7. 前端开发约定

- TypeScript 类型以 `frontend/src/types/domain.ts` 为边界，API 变更必须同步 DTO、client、store、view 和测试；Designer 保存/确认必须无损往返全部 LoopSpec limits、model、sessionPolicy 和 nextAttemptPromptTemplate。
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
- 每个行为变化都在相邻 `.spec.ts` 中增加回归测试；路由级关键流程再考虑 `frontend/e2e/`。
- UI 图标必须使用项目已打包的 Iconify/Lucide 资源，不依赖外网 CDN。

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
JAR=target/opencode-loopper-0.1.26.jar
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
