# C 独立审查：后端联调第一层

审查基线：`11ca25a3bb764a2d80ac924350139a7087639121`，`feat/react-full-migration`，2026-10-04。审查人为原 C（`/root/react_ppt_canvas`）；历史委派明确指定 `gpt-6.1-sol / xhigh`，当前工具未提供可实时读取该运行配置的平台字段。未增员。

本次只修改本报告。已读取根与 `src/AGENTS.md`，检查现有测试、API 和资格脚本；未安装、编译、启动 Spring/OpenCode/Codex daemon，未运行 Java 测试、浏览器或模型，未直接打开或输出认证文件、用户配置文件，未执行旧复制认证脚本。允许的 CLI help/features 命令不构成对其内部文件读取的系统调用审计。下文的测试选择器是建议，不是本轮执行结果。父任务报告的工具链下载成功和 Java DNS 失败明确作为父任务证据，不冒称 C 独立执行。

## 当前结论与门禁

现有源码有足够的 fake 业务、原身份恢复、停止证明和本地 HTTP 运输测试，可作为第一层聚焦门禁。MockMvc、真实本地 HTTP、真实 Spring 随机端口必须分别记账；这些测试仍不证明真实 Provider、真实 OpenCode 二进制或前端与后端的全链路已通过。

第二层暂不可直接执行 `scripts/aicoding`：一项确定的草稿 CAS 请求差异、一项确定的当前可访问名差异，以及 managed runtime、继承环境、构建路径与进程归属门禁尚需处理。当前 Maven 联网受阻；Codex 完全禁工具的纯文本调用也未有充分证明。未用这些阻塞推断产品业务失败。

| 项目 | 本轮结论 | 证据性质 |
| --- | --- | --- |
| fake REST / SSE / File / 恢复 / stop-proof 聚焦选择 | 可供父任务执行 | 源码审查，未运行 |
| 实际 Spring / SQLite / 原生 SSE 联调 | 待运行 | 不以 MockMvc 或编译替代 |
| `qualify-reuse --execute` 草稿确认 | 当前请求缺 `expectedVersion` | 确定的静态协议差异，未运行 |
| matrix 统计取消按钮定位 | 旧可访问名与当前中央语义不相等 | 确定的静态呈现差异，未运行 |
| Maven 使用现有环境代理而不配置代理 | `ENV_BLOCKED` | 父任务 DNS 失败；官方文档不保证自动继承环境变量 |
| Codex CLI 完全禁工具烟测 | `BLOCKED` | 部分受支持 feature 已核实，完整空工具注册表未核实 |

## 现有测试层次与保留断言

所有源码链接均指向本次基线。类名不表示测试已运行或整类全部功能已覆盖。

| 测试层 | 具体源码 / 入口 | 保留的行为证据 | 尚不能证明 |
| --- | --- | --- | --- |
| standalone MockMvc | [WorkflowUploadControllerTest](../../../src/test/java/io/opencode/loopper/api/WorkflowUploadControllerTest.java#L14)、[RecoveryControllerTest](../../../src/test/java/io/opencode/loopper/api/RecoveryControllerTest.java#L26) | local-UI header、multipart 有序字节与 requestKey/revision、历史游标、恢复 lineage、冲突映射；缺授权时不调用业务服务 | 实际 servlet 网络上传、持久化和真实业务闭环 |
| Spring + MockMvc + fake | [DesignerSessionMcpIntegrationTest](../../../src/test/java/io/opencode/loopper/api/DesignerSessionMcpIntegrationTest.java#L1556) `publicDraftMutationsRequireAndPreserveTheEditorsVersion` | 缺版本拒绝、旧版本 409、合法确认原 Task、同确认版本重入仍同 Task；类中 MCP asyncDispatch 也属于 MockMvc | 实际监听端口或实际 SSE 网络断线 |
| 初始附件幂等 | [DesignerInitialAttachmentSubmissionTest](../../../src/test/java/io/opencode/loopper/service/DesignerInitialAttachmentSubmissionTest.java#L35)、[DesignerAttachmentIdempotencyTest](../../../src/test/java/io/opencode/loopper/service/DesignerAttachmentIdempotencyTest.java#L24) | 并发 busy 不双建 session、未完成旧 submission 不盲重建、已发布同身份恢复不再次消耗预算/写文件 | 真实初始 HTTP 请求失联与实际数据库重启 |
| Spring / SQLite / fake 业务 | [TaskServiceIntegrationTest](../../../src/test/java/io/opencode/loopper/service/TaskServiceIntegrationTest.java#L95)、[RecoveryServiceIntegrationTest](../../../src/test/java/io/opencode/loopper/service/RecoveryServiceIntegrationTest.java#L83) | 冻结附件、合法终态 lineage、VERIFY_ONLY 不启动 writer；双只读 Judge 与同批证据、迟到回调不改变任务状态 | Provider 质量、外部 Git 平台发布或实际模型验收 |
| 停止证明 / 恢复 | [TaskStopCoordinationIntegrationTest](../../../src/test/java/io/opencode/loopper/service/TaskStopCoordinationIntegrationTest.java#L49)、[TaskServiceIntegrationTest](../../../src/test/java/io/opencode/loopper/service/TaskServiceIntegrationTest.java#L1716) | 用户与 monitor 共用一次 in-flight abort；缺停止证明保 RELEASE_PENDING 并拒 resume；重启不能证明旧 writer 停止时不启动新 writer | 真实操作系统进程已退出或真实 OpenCode abort 生效 |
| SSE 生命周期 / 事务 | [SseEmitterLifecycleTest](../../../src/test/java/io/opencode/loopper/api/SseEmitterLifecycleTest.java)、[EventHubIsolationTest](../../../src/test/java/io/opencode/loopper/service/EventHubIsolationTest.java)、[TaskEventServiceTest](../../../src/test/java/io/opencode/loopper/service/TaskEventServiceTest.java#L40) | send 失败关闭一次、迟到订阅清一次；单订阅断线不影响其他订阅；事务提交后才发布事件 | 浏览器断线/重连、实际 TCP/servlet 释放、跨重启游标回放 |
| 真实 HTTP 客户端到本地伪服务 | [HttpOpenCodeClientTest](../../../src/test/java/io/opencode/loopper/runtime/HttpOpenCodeClientTest.java#L63) | `127.0.0.1:0` 实际请求、directory / permission / session 绑定、正向 abort 证明、generation 不匹配拒绝、超时、精确消息/标题查找 | 实际 OpenCode 服务端、真实 Provider 或正式业务后端端口 |
| File 到实际本地 HTTP | [DesignerAttachmentTransportTest](../../../src/test/java/io/opencode/loopper/service/DesignerAttachmentTransportTest.java#L108) | 生成附件身份与实际字节一致、变化文本在 HTTP prompt 前拒绝、MCP resource receipt 不等于 OpenCode 已接受输入、冻结消费者一致 | Spring multipart 真实入口、真实模型读取文件 |
| 实际 Spring 随机端口 + 本地 OpenCode 伪服务 | [KnowledgeMcpTransportIntegrationTest](../../../src/test/java/io/opencode/loopper/runtime/KnowledgeMcpTransportIntegrationTest.java#L51)、[PptMcpTransportIntegrationTest](../../../src/test/java/io/opencode/loopper/runtime/PptMcpTransportIntegrationTest.java#L31) | 实际私有 MCP HTTP、scope / generation / stop 后失效、PPT 原操作 key 重入与不同 document 拒绝 | 真实 OpenCode 二进制/模型或浏览器到真实业务端口 |

Task 双 Judge 断言具体在 `TaskServiceIntegrationTest:1803–1899`：开始两个 JUDGE_READ_ONLY session、保 sourceSha256 与附件、两个 PASS 后仍为 `AWAITING_DECISION`，历史 attempt 迟到失败不修改状态或计数。此用例不会把双 PASS 自动说成最终人工批准。

## 建议最小聚焦选择器

[dev-check.sh:11–15](../../../scripts/dev-check.sh#L11) 接受类名、逗号与 `#`，不接受 `+`；每个方法使用独立 `Class#method` 并以逗号相连。`backend-dev` 输出 `target/backend-dev`，不构建正式静态资源/JAR。下面仅是待父任务选择并执行的命令，原测试断言不改。

第一组负责 REST / SSE / 重入与配置开关：

```sh
./scripts/dev-check.sh backend 'WorkflowUploadControllerTest,RecoveryControllerTest,SseEmitterLifecycleTest,EventHubIsolationTest,TaskEventServiceTest,DesignerInitialAttachmentSubmissionTest,DesignerAttachmentIdempotencyTest,SchedulingConfigurationTest,StartupRecoveryCoordinatorTest'
```

第二组负责最小 fake 持久化闭环和停止/恢复门禁，避免执行整个超大类：

```sh
./scripts/dev-check.sh backend 'DesignerSessionMcpIntegrationTest#publicDraftMutationsRequireAndPreserveTheEditorsVersion,TaskServiceIntegrationTest#confirmationFreezesTheActiveDesignerAttachmentManifest,TaskServiceIntegrationTest#finalDeterministicPassRequiresTwoReadOnlyJudgesAndRetainsEvidence,TaskServiceIntegrationTest#pauseWithUnconfirmedDirectWriterBlocksResumeUntilCleanupConfirmsTermination,TaskServiceIntegrationTest#restartRecoveryFailsTaskWhenOldMutatingSessionCannotBeConfirmedStopped,TaskStopCoordinationIntegrationTest,RecoveryServiceIntegrationTest#onlyTerminalParentsCreateAStageDerivedLineage,RecoveryServiceIntegrationTest#recoveryTaskInheritsTheParentsFrozenAttachmentManifest,RecoveryServiceIntegrationTest#verifyOnlyRunsNativeVerificationWithoutCreatingAnExecutionSession'
```

第三组必须分别记录为真实 loopback HTTP 客户端运输，不能混称 actual OpenCode：

```sh
./scripts/dev-check.sh backend 'HttpOpenCodeClientTest#usesDirectoryForSessionTransportAndCompletesOnlyAfterObservedBusy,HttpOpenCodeClientTest#requiresPositiveAbortAcknowledgementAndTreatsMissingSessionAsStopped,HttpOpenCodeClientTest#recoveredSessionCannotConsumeANewRuntime404AfterManagedGenerationRotation,HttpOpenCodeClientTest#requestTimeoutBoundsStalledOpenCodeTransport,HttpOpenCodeClientTest#exactPromptLookupTreatsOnly404AsAbsentAndRejectsMalformedOrDrifted200,DesignerAttachmentTransportTest#sendsGeneratedAttachmentIdentityThroughTheHttpProtocol,DesignerAttachmentTransportTest#rejectsChangedTextBeforeAnyHttpPromptIsSent'
```

若第一层实际端口门禁需要覆盖已有私有 MCP 与 PPT 实际 DTO，可再选择以下两个随机端口类；仍仅本地 fake 对端：

```sh
./scripts/dev-check.sh backend 'KnowledgeMcpTransportIntegrationTest,PptMcpTransportIntegrationTest'
```

这些建议不构成固定运行数。参数化方法会展开，实际 count/fullName/exit/source hash 应由本次 Surefire 输出确定；C 没有执行上述命令。

## 隔离计划的必要边界

[测试配置:13–35](../../../src/test/resources/application.yml#L13) 默认使用 shared-memory SQLite、允许 Flyway clean，`data-dir` 位于 Java tmpdir，fake、scheduler 和 startup recovery 均关闭。`TaskServiceIntegrationTest` / `RecoveryServiceIntegrationTest` 会 clean/migrate；因此不能并发复用同一个 shared-memory/schema 与数据根。随机端口 MCP 类已有独立文件 SQLite 与 TempDir，但仍需记录实际路径与归属。

[生产配置:75–86、141–145](../../../src/main/resources/application.yml#L75) 默认数据为 `./data`、scheduler/startup recovery 开启、OpenCode 为 managed。仅设置 scheduler=false 不够：[StartupRecoveryCoordinator:15、33–39](../../../src/main/java/io/opencode/loopper/config/StartupRecoveryCoordinator.java#L15) 独立监听 ApplicationReady，并会处理 baseline、local sync、Task、handoff 和 Automation 的恢复。

第一层启动前应实际证明：独立 canonical root、独立 SQLite/数据/Java tmpdir、loopback 独立端口、fake 模式、scheduler=false、startup-recovery=false。不能使用用户项目、已有 data、真实 remote、真实 DB 连接或 Provider。Git/验证器只操作新建本地临时项目；不把 “fake” 推断成完全无文件或进程副作用。需要验证重启恢复时，显式准备并保留同一独立数据库，先记录旧身份，再受控开启或直接调用恢复；不使用默认生产启动去碰未知数据。

清理仅操作本次拥有的 child/PID；记录 executable、启动时间/代际、parent、cwd/root、端口及闭合证据。发送 SIGTERM 不是停止证明，须实际 wait/exit 或可靠精确不存在；不能 killall/pkill、按端口泛杀或仅信任某个 runtime.pid 数值。失败时仍保数据库、raw 请求/事件/错误和原 File/hash/order，不能清库补绿。后台调度是否开启、业务 accepted/unknown 与清理结果分别记账。

实际 Spring 第一层还需单列实际端口 REST / multipart、真实 SSE 订阅断线再 GET 不改变业务、cancel/stop 原身份结果和错误授权负控；现有逻辑单测与 MockMvc 不能代替这些新增运行证据。当前父任务的 Spring 尚未启动。

## `scripts/aicoding` 当前兼容性与第二层准入

1. **确定的草稿 CAS 差异。** [qualify-reuse:57](../../../scripts/aicoding/qualify-reuse.mjs#L57) 向 confirm 发送 `{}`；[LoopDraftController:36–45](../../../src/main/java/io/opencode/loopper/api/LoopDraftController.java#L36) 要求 `expectedVersion`，缺少则 `DRAFT_VERSION_REQUIRED`。执行前必须读取准确 draft.id/version 并保同 body/CAS；不是删除版本断言。此问题只确认于 reuse 的 execute 分支，未声称所有 matrix/workflow 分支都使用该请求。
2. **确定的统计取消可访问名差异。** [qualify-matrix:175](../../../scripts/aicoding/qualify-matrix.mjs#L175) 以旧可见 label 做 exact role-name；[StoryAccountingDialog:21](../../../frontend/src/app/StoryAccountingDialog.tsx#L21) 使用 `accounting.cancel`，[UiActionButton:38](../../../frontend/src/foundation/components.tsx#L38) 的 aria-label 来自中央 name，目前为“取消本次故事点统计并继续任务”。可见 label 仍“取消本次统计，继续任务”。需按当前语义精确定位，保原取消、请求次数与继续任务断言。
3. **包重开 CAS 的静态风险。** reuse:39–44 使用 `workPackage.designRevision`，而 [DesignerSessionService:1032](../../../src/main/java/io/opencode/loopper/service/DesignerSessionService.java#L1032) 的重开比较 approvedDesignRevision。两版本相等时不构成失败；不等时旧脚本可能过时。执行前核准确批准版本与读取后的 package，不虚构新字段或移除 409。
4. **当前 backend-dev 产物路径不能直接套用旧 fallback。** [start-qualification:61–62](../../../scripts/aicoding/start-qualification.mjs#L61) 无 JAR 时复制 `target/classes` 并读取固定 `/tmp/loopper-story-classpath.txt`。当前第一层使用 `target/backend-dev`；旧固定路径存在也不证明属于本批。须用本批确证的 classes/classpath 或准确 JAR/hash；backend-dev 不是正式 JAR。
5. **不是第一层 fake 启动器。** start:47–56 明确 managed 且继承 `process.env`，虽本地 mock provider/XDG/data/root 已隔离，仍不能从源码证明不会继承用户 Provider/认证相关环境或读取外部插件。native-tools:53–54 的默认 plugin 入口还在用户配置目录中的包路径。只核源码，未读取该目录或任何凭据。
6. **进程清理与环境门禁仍未闭合。** start:83、103–116 仅凭 runtime.managed 与整数 PID 发信号，缺少独立 PID 归属和最终 managed exit 等待；close 的 exit0 不等于所有进程停止。probe-native:27–29 也继承环境并实际启动 OpenCode；93–95 SIGKILL 后没有再等待退出。不得将这些脚本原样当作本层安全证明。

已排除一项误报：[TaskController:86、368–394、506](../../../src/main/java/io/opencode/loopper/api/TaskController.java#L368) 的兼容 `GET /api/tasks/{id}` 仍包含完整 artifacts.content 与 judges。matrix 的该旧完整 DTO 读取在此基线没有发现分层接口断裂；不因新页面改用 overview/audit 而猜测这个接口已删除。

第二层须先通过第一层 fake 与实际 loopback 运输，修正/证明上述当前协议和环境差异，再证明本次 owned OpenCode 二进制、插件/依赖及 local mock provider 的准确版本/哈希、隔离环境/权限/停止证明。旧认证复制脚本绝不执行。所有 mock 结果只能证明程序合同与运输，不能说成真模型或付费 Provider 通过；最多一次真模型调用仍受单独准入约束。

## 新启动器与 A/B 运输脚手架的非作者复核

以下结论来自 C 对新增源码与生产合同的只读比较。父任务实际执行的启动器安全测试 7/7、A 合成运输测试 14/14，与 B 的类型检查和 5 项收集成功分别记账；C 没有重新执行这些测试，也没有实际 Spring、REST 或浏览器通过结果。准备材料见 [总报告](README.md)、[A REST 报告](A-rest-review.md)、[B 浏览器报告](B-browser-upload.md)。

### 启动实例与清理归属

[isolated-server.py:23–58](../../../scripts/backend-integration/isolated-server.py#L23) 对启动路径要求绝对 canonical、拒 symlink alias，使用 literal argv 与非特权 loopback 端口。新 run root 以 `mkdir` 独占创建，不复用既有数据库；本次拟用 `/workspace/backend-integration-20261004/phase1-run-1`，不删除此前空目录或证据。独立 `data/projects/tmp/home` 和 A/B 项目子根由本次实例创建。

环境使用显式 allowlist，没有展开或继承父进程的 Provider、proxy、credential、MCP、`JAVA_TOOL_OPTIONS` 等配置；Java `user.home` / tmpdir、数据根、项目根与 cwd 均指向该实例。生产 [application.yml:29](../../../src/main/resources/application.yml#L29) 的 SQLite URL 使用 `LOOPPER_DATA_DIR`，因此 env 与 `--loopper.data-dir` 同时设置；仅改变 cwd 不等于 SQLite 已隔离。fake、scheduler=false 和 startup-recovery=false 都是明确参数。

本次 child 的 `GIT_CONFIG_NOSYSTEM=1`、`GIT_CONFIG_GLOBAL=/dev/null`、`GIT_TERMINAL_PROMPT=0` 不写全局配置、不重新定义 HOME，避免合成本地 Git 操作读取用户系统/全局 Git 配置或交互索取凭据。它不构成所有文件访问或网络的沙箱证明；测试项目仍须保持新建、无外部 remote，不执行旧资格/认证复制脚本。

[启动证明:118–167](../../../scripts/backend-integration/isolated-server.py#L118) 锁准确 expected revision、backend-dev classes、完整依赖文件；`runtimeHashes` 记录全部 classes/resources 的逐文件摘要、总摘要/count 和每个 jar 摘要，不以单个 Application.class 代表全部运行时。actual health=UP 且 runtime=AVAILABLE/fake/managed=false 才标 ready。fake 的准确公开字段来自 [OpenCodeRuntimeManager:161–165](../../../src/main/java/io/opencode/loopper/runtime/OpenCodeRuntimeManager.java#L161)，不是猜测 `state=ONLINE`。

[stop_owned_child:86–97](../../../scripts/backend-integration/isolated-server.py#L86) 仅给 `start_new_session=True` 创建的 owned child group 发 TERM，等待该 Java child；超时才给同组 KILL，再 wait。已退出 child 不再信号。SIGINT/SIGTERM 经 finally 执行这一清理；`stopped/javaExitCode` 只能证明该 owned Java 的退出，不能宣称所有后代、监听端口或 OS 资源已归零。7 项安全测试不包含实际 Spring 启停或全部 descendants 检查。

### A 的 30 项实际 REST 计划

[P1:279](../../../scripts/backend-integration/rest-contracts.mjs#L279) 在首 HTTP 前核 ready、外部指定的完整 revision、baseUrl、fake/model 与两个后台开关；runRoot/data/projectRoot/projectParent 全部比对 realpath，且 data 与项目不互相包含。之后 GET runtime 严格要求 AVAILABLE/fake/managed=false/pid=null/fake/model，才进入写入场景。路径、SQLite 与 revision 是父启动声明，不是假称公开 runtime DTO 有这些字段。

[createRestClient:62–104](../../../scripts/backend-integration/rest-contracts.mjs#L62) 将 method/path、raw JSON、原 key、digest 冻结为 prepared request；显式恢复重用同一请求。传输或 JSON 解析异常后禁止后续写，只允许 GET；case 首失败后其余保持 NOT_RUN。成功 JSON 的业务 DTO 检查在后续断言中失败，同样结束该 run，不会自动追加 POST。入口只在显式执行脚本时调用 runner，不在 import 时写业务数据；报告先 `wx` 占位，不能覆盖旧结果。

T/R/D 组保 create/replay/changed-body 409、独立 graph/layout CAS、历史冻结、四字段需求、准确 draft version 与确认仅 PENDING_START。K1/K2 从实际 fake writer/HELD lease 正控开始，取消后核原 session ABORTED、activity ABORTED、lease RELEASED 和不增加 attempt；这是 fake 正向停止合同，不是实际 OpenCode/OS 进程停止或未知 abort 的负控。

[readTaskEvents:171–214](../../../scripts/backend-integration/rest-contracts.mjs#L171) 使用真实端口 SSE、明确 Last-Event-ID、严格递增序列、bytes/events/deadline 边界，并在 finally abort/cancel/release reader。S1 是已持久化取消事件的有界回放；不等同 live 浏览器断线、服务端 subscriber 数量或进程重启恢复。T2/R1 丢弃回执是显式客户端模拟，不冒称实际网络故障或跨刷新恢复。上述 30 项均 **NOT_RUN**。

### B 的 5 项实际浏览器计划

[beforeAll](../../../frontend/e2e/backend-integration/project-requirement-upload.spec.ts#L11) 调用 [verifyIsolation](../../../frontend/e2e/backend-integration/support.ts#L14)，在第一个 fixture mkdir/API write 前核 ready、完整外部预期 revision、Spring origin、fake/model、后台开关、ownedJavaPid 和 canonical `runRoot/data/projects/B`。最新完整 runtimeHashes 消费者还复算现存每个 jar 和编译 classes/resources 的 count/摘要；不是仅匹配 manifest 字段格式。

[preview.config.ts](../../../frontend/e2e/backend-integration/preview.config.ts) 使用既有生产 dist 与官方 Vite preview，只有 `/api`、`/actuator` 指向父提供的 loopback Spring。UI origin 与 Spring origin 分开记录；不是拿 frontend URL 与 Spring proof.baseUrl 直接比较。runtime/health 通过该实际代理 GET，之后才写隔离核对证据。Playwright 无 webServer，不私自启动后端；workers=1/retries=0。当前源码没有 route/fetch 的业务响应拦截，DOCX fixture 为本地合成 ZIP 字节，不含用户附件或外部 resource。

五项保留真实项目 dirty Stay/CAS 409、四字段 create 与 graph/layout 各一次保存、两份 DOCX 顺序/SHA/下载 bytes、原 multipart metadata/key 重入与改顺序 409、TXT UI 无 POST 加单独实际协议 400、损坏 DOCX parser 失败可见与明确用户重选。皮肤操作检查零额外写；截图本身不证明文件身份或清理。

Knowledge 场景只显式创建 IDLE 会话，不发送 prompt；观察真实原生 EventSource open，SPA 退出首 MutationObserver 样本要求原流 close=1/CLOSED。Requirement 另以真实 2.5s owned timeout 正控，再用未改的 W2 资源门槛核首次退出清零。不伪造自然 pointer 清理、不取消他人资源、不把 ledger 强引用当 heap/GC 证据。该计划不注入 partial disk failure/ready:false 补传、未知传输或真实模型。全部 5 项与 12 张拟定代表图均 **NOT_RUN**。

### 准备差异与结论边界

A 初稿缺 ready/revision 和全部 canonical 路径门槛、B 初稿缺首写前隔离核对、以及 B 仍消费旧 `applicationClassSha256` 而启动器已输出 `runtimeHashes`，已在作者代码中补齐。最后非作者复核另发现两端 class/resource 摘要 JSON 的非 ASCII 编码约定曾不一致；启动器现在明确 `ensure_ascii=False`，B 使用未转义的 `JSON.stringify(entries)`，同为紧凑 UTF-8。C 已读取最终两端源码；父任务另实际用 ASCII 与中文两份合成路径完成跨 Python/Node 摘要对照 PASS，记录在 [hash-contract-final.json](/workspace/backend-integration-20261004/hash-contract-final.json)。该文件明确 `springStarted=false / modelCalls=0`，不当作业务 RED 或 live PASS。B 报告五个 test 的原文件声明锚点也已纠正为 13/45/79/115/143，未改业务断言。

最终准备源码在下方逐 SHA 记录；在本次所读 producer/consumer 合同范围内，没有剩余已确认的静态接口阻塞。提交测试/文档后若 HEAD 改变，启动器、A 与 B 均须由调用方指定同一个实际冻结 revision，不能从 isolation.json 自证或继续沿用旧默认 SHA。

截至本轮准备交付，实际 Spring/SQLite/30 REST/5 浏览器/原生 SSE/owned Spring 停止均未执行。Maven 代理授权未决是 **ENV_BLOCKED**，实际编译依赖获取退出 1；不能以 7+14 个脚手架测试、协议对照、类型/收集或 JDK/Maven 下载成功替代第一层通过。第二层、真实模型和旧 managed 资格脚本继续不运行。

| 最终准备项 | 准确计数与状态 | 执行来源 |
| --- | --- | --- |
| 启动器安全 + A 合成运输自测 | 7 + 14 = 21 PASS | 父任务/作者实际执行；C 源码核对 |
| Python/Node UTF-8 摘要协议 | 2 个合成路径对照 PASS | 父任务实际执行；C 读取结果与最终源码 |
| 新 REST 场景 | 30 NOT_RUN | 没有实际 Spring 请求 |
| 新浏览器场景 | 5 NOT_RUN | 类型检查/收集不计行为执行 |
| Maven compile / classpath | exit 1，ENV_BLOCKED | 父任务实际运行，尚未进入编译/测试 |
| 真实 Luna 烟测 | 0 次调用，NOT_RUN / 工具门槛 BLOCKED | 完整禁工具未证；fake 层也未完成 |

## Maven 联网：当前 `ENV_BLOCKED`

父任务已报告官方 Temurin/Maven 下载与 SHA 校验成功、curl 经现成环境对 Central 父 POM 得到 HTTP200，但 Java/Maven DNS `repo.maven.apache.org` 失败；只核代理环境变量 presence，未输出内容。C 未重复联网命令、修改 Maven settings/proxy/DNS/白名单或读取认证。

官方 [Maven 代理指南](https://maven.apache.org/guides/mini/guide-proxies.html) 给出的常规入口是 settings.xml 的 proxies，并明确 Java system properties 取决于 transport；[Resolver 配置](https://maven.apache.org/resolver/configuration.html) 的 system-properties 采用亦为显式 opt-in。curl 成功和 HTTP_PROXY/HTTPS_PROXY presence 不证明 Java Resolver 自动沿用环境代理。未找到在当前“不配置代理/网络”授权下可证明通行的正常 Maven 路径；临时 `-s` 或 proxy system properties 同样属于代理配置变更。完整已验证缓存可以正常离线使用，但本轮没有缓存完整性证据。没有建议 CONNECT、hosts/白名单修改、镜像绕过或 credentials 改动。

## Codex `0.159.0-alpha.3`：部分开关可证，完全禁工具仍 `BLOCKED`

本机实际 `/opt/codex/bin/codex --version` 为 `codex-cli 0.159.0-alpha.3`。仅执行 `--help`、`exec/features/debug/app-server --help` 与父任务额外准许的 `codex --no-daemon features list`，均 exit0；只读命令有 PATH-alias 只读文件系统告警，未创建 alias，未启动 daemon/模型。features list 输出仅 feature 名称、阶段与布尔；没有直接读取 auth/config 文件、改 feature 或复制凭据。

| 已安装正式帮助 / metadata | 可证内容 | 不能推出的结论 |
| --- | --- | --- |
| `--no-daemon` | 不使用共享后台 server | 无工具能力 |
| exec `--ignore-user-config` | 不加载用户 config.toml；帮助仍说明 auth 使用 CODEX_HOME | 不使用认证或不加载任何项目/插件规则 |
| exec `--ephemeral` | 不持久化 session 文件 | 不执行工具或不发生外部调用 |
| `--strict-config` | 对未知配置字段报错 | 已知字段能覆盖全部工具注册 |
| repeatable `--disable <FEATURE>` | 支持临时 feature=false | 任意猜测 feature 名称合法，或全部工具因此关闭 |
| read-only sandbox / approval never | 限制 shell 文件写入 / 不请求批准 | shell 未注册、网络被彻底禁止或工具不会调用 |

正式 feature list 已确认当前 `shell_tool=true`、`unified_exec=true`、`code_mode=false`、`code_mode_host=true`、`multi_agent=true`、`multi_agent_v2=false`、`apps=true`、`plugins=true`、`remote_plugin=true`，以及 browser/computer/image/view_image/sleep 等其他能力。`--disable` 可对这些真实名字设置临时布尔；本轮未改变它们。

但 `apply_patch_freeform` 仅为 removed=false，不能当作核心 apply_patch 完全关闭证明；list 没有覆盖所有 MCP server/tool 注册的总禁用入口。web 的 removed/deprecated feature 状态也不能证明整个 web 注册表为空。帮助无受支持的统一 no-tools 开关；不猜 unknown flags，不用提示词“不要工具”充当强制约束，也不把 shell-disabled 等同 code-mode/MCP/apply_patch 禁止。当前只能确认部分开关存在，不能证明一次纯文本 `gpt-6-luna` 调用完全无自动工具，也未验证该 model 在当前认证/catalog 的可用性。因此烟测不执行；须先取得锁定版本正式空工具机制或完整受支持 tool-registration 证明。最多一次预算不能替代此门禁。

## 审查来源哈希与剩余事项

以下 SHA-256 是 C 本次只读实算的关键源码/工具字节，未把 HEAD 代替运行证据：

| 来源 | SHA-256 |
| --- | --- |
| `src/test/resources/application.yml` | `f8c84c6e21724a41d08c7d5b366a07d5e50d6fd9fea6d13664e98d959eb1d424` |
| `src/main/resources/application.yml` | `859a7c5ea6964c3dc42c971d6765a28b238791f5688dcd61847dabf1fe22cf17` |
| `scripts/dev-check.sh` | `38b5db7f7b3a5b3a2353126fa63cbb9f60d1071ca6db51cdd7771c04570f7288` |
| `scripts/aicoding/start-qualification.mjs` | `961d94c0996d668190b6ef4b388f3476129294c78f6e9507bc0128be3ca61a81` |
| `scripts/aicoding/probe-native.mjs` | `86e1b31268483819f6865f41b5a03928c6d521a5e7beb9c17a94ae5e6fd835b3` |
| `scripts/aicoding/qualify-reuse.mjs` | `ca1fc453870be09bc08e54b31ccd9e142ff7f07adc3c5fa824b17f1f45967663` |
| `scripts/aicoding/qualify-matrix.mjs` | `c576fb6767847ce4af26fb7d5f68e413ab7aed9bcca9d383f16ade3bd4c648f2` |
| `src/main/java/io/opencode/loopper/api/LoopDraftController.java` | `02be442b78751ad17b9cdf1f9ec53bd7e8431272394f8921e3848c4d8c702931` |
| `src/main/java/io/opencode/loopper/api/TaskController.java` | `b09b4c2868b2bc87ad0464ddbf48b4fb13e4788ad005c63e44f70d917b0d6f08` |
| `src/main/java/io/opencode/loopper/config/StartupRecoveryCoordinator.java` | `6eef88441a34c4b24f166268cd06ad33084b90c5452ce9d862644f701529566d` |
| `src/test/java/io/opencode/loopper/runtime/HttpOpenCodeClientTest.java` | `4cb64c2194eb0b6555ea320996f3616e05dd61fc8a1d56f94443f2845200c5e9` |
| `src/test/java/io/opencode/loopper/service/DesignerAttachmentTransportTest.java` | `760901ae210e61a833d0bc3c3c0f3a931ff0ea2198e6f82d4adcc358fced07d6` |
| `src/test/java/io/opencode/loopper/service/TaskServiceIntegrationTest.java` | `d051d1f8c4f137e611f80318d16c818169480b037017657e22eca7339be978a9` |
| `src/test/java/io/opencode/loopper/service/TaskStopCoordinationIntegrationTest.java` | `fa0eb6faab88322b7671824fda29ff1f5edc83b44175ed3b522a28f88a7049b1` |
| `src/test/java/io/opencode/loopper/runtime/KnowledgeMcpTransportIntegrationTest.java` | `a2263f46e0b9507315b076dbab4394b167494b614ccda4ca266030237c2f4a1b` |
| `src/test/java/io/opencode/loopper/runtime/PptMcpTransportIntegrationTest.java` | `ff954778090605ed466786edd87ed987f8b0b327214cf9b75ecf4c028af95b64` |
| `frontend/src/app/StoryAccountingDialog.tsx` | `6260a46e8249441a67fdeda23284d34f96545960ecf37c37af294b11c222953d` |
| `frontend/src/foundation/components.tsx` | `bfd27bd1f13fcbaa801ba86a0a22ec92c14fb4347635c8c0360367dcb90799df` |
| `frontend/src/foundation/semantic-registry-data.json` | `690b473acc0b6aba0224ccc6b5d176337b24b7025c7bd256fcdc7d8abb2a43a9` |
| `/opt/codex/bin/codex` | `981ade7b03926534c654fd718ced3a9f378b7b2841271e29156f939462d176e9` |

新增准备脚手架的最终只读审查字节如下。摘要对应脚手架源码，不代表尚未生成的实际 Java runtimeHashes、SQLite 或端口运行结果：

| 来源 | SHA-256 |
| --- | --- |
| `scripts/backend-integration/isolated-server.py` | `850741e802c0a86c0902aba8471d075561031af2d3408d7d786a6864b927c87c` |
| `scripts/backend-integration/test_isolated_server.py` | `186cb7c3c0282c0556668767762f37594f66fceaf48aca7b8a49bf326a09facd` |
| `scripts/backend-integration/rest-contracts.mjs` | `168d20697ef4a6e9cf4b27e2f75f58edbb5a1e17c2ae60386762b8abba5213b1` |
| `scripts/backend-integration/rest-contracts.test.mjs` | `3eb86bbe36799443217bcaa14f852ca5c0b65448658da31cae18189a3bf4f8bb` |
| `frontend/e2e/backend-integration/environment.ts` | `7ac6fad292589f24b60e5cd83a882e2a1ed685826e2156f3b8b044060a26b397` |
| `frontend/e2e/backend-integration/preview.config.ts` | `12f9c9950955afb64902819be538cc5438b0a8dc116b11c3887d1e55e6f8d4c4` |
| `frontend/e2e/backend-integration/playwright.config.ts` | `a9550683794309b9edbd985a2c1d0e540f91ce0b4b663a2817f827170dc3844c` |
| `frontend/e2e/backend-integration/files.ts` | `ca5c822bf2de61be841ad7f3acb781caa1d7ecbfe550b27e951b343d401916fb` |
| `frontend/e2e/backend-integration/project-requirement-upload.spec.ts` | `0fb294065885187039b06d7838f504d60086004433fa1679fd786b59ba373efc` |
| `frontend/e2e/backend-integration/support.ts` | `19c0f52263ba6686a7dd6506eaea0ae3106232f4f48509dc18756cb3f25f0f25` |

本轮新增启动器与 A/B producer/consumer 的源码审查已记录，当前无 Java/浏览器/模型执行 PASS 可由本报告回填。后续实际联调需独立 fresh root、准确当次 expected revision 与保留首错的运行证据；本轮未直接打开认证文件、未更改网络、未提交或对外发送。
