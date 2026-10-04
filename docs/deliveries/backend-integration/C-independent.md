# C 独立审查：后端联调第一层

审查基线：`11ca25a3bb764a2d80ac924350139a7087639121`，`feat/react-full-migration`，2026-10-04。审查人为原 C（`/root/react_ppt_canvas`）；历史委派明确指定 `gpt-6.1-sol / xhigh`，当前工具未提供可实时读取该运行配置的平台字段。未增员。

本次只修改本报告。已读取根与 `src/AGENTS.md`，检查现有测试、API 和资格脚本；未安装、编译、启动 Spring/OpenCode/Codex daemon，未运行 Java 测试、浏览器或模型，未直接打开或输出认证文件、用户配置文件，未执行旧复制认证脚本。允许的 CLI help/features 命令不构成对其内部文件读取的系统调用审计。下文的测试选择器是建议，不是本轮执行结果。父任务报告的工具链下载成功和 Java DNS 失败明确作为父任务证据，不冒称 C 独立执行。

续接入场基准已独立核对为 `4f06621c11872c3b6ace9f0285ba9bdd669163e5`，当时工作区 clean；相对原业务基线，`src/main`、`pom.xml`、`.mvn` 没有差异。用户随后明确批准 Maven 子进程临时使用既有无凭据 proxy host/port，禁止写 settings 或永久配置；若发现代理认证要求仍须停止。后续正常重试保持同代理和官方目标。此授权覆盖先前“代理配置待决”的状态，不删除此前 DNS exit1，也不等于业务已通过。实际失败后，组长授权并持有 API advice 最小修复及 `0.4.91` 版本同步；A 仅持有停止测试夹具。准备阶段与实际运行分别记账。C 不执行 Maven、服务、浏览器或模型。

实际业务新批冻结为 `60759aeb19dd909a0303139ca44877c920ef2950`。同 revision 的完整新批精选 Java 57/57、实际 REST 30/30、浏览器 5/5 均 PASS；另一次明确授权的真实 Java→OpenCode1.18.23→本地 mock 有限运输实际 PASS。最新审查 HEAD 为 `0f32885859a5f3ca63f1c78db3bf2646b263c4a9`，相对607精确只有四个测试/运输脚手架文件变化，生产字节相同；该HEAD新的前端完整批275文件/2390测试PASS，typecheck/build/tooling39/accounting13/React-only audit均实际exit0。C核原始 XML/有限JSON、源摘要及两份严格首退出快照，没有执行这些测试。历史 `8553221a`、`33406d46` 及前端首错保留，不能拿修后的单项或异版绿拼接。相对 `4f06621c`，生产范围仍仅 `ApiExceptionHandler` 优先级及 `application.yml` / `pom.xml` 版本同步；其他 A/B 变化为隔离夹具/观测与文档。正式完整 JAR 门禁仍未通过，完整 verify 已中止exit143，部分137 XML/1075测试含16F/84E/5skip；准确层次见下文。

## 当前结论与门禁

本轮有限的 fake 业务及真实前后端端口联调已在同一冻结版本通过 57/30/5 门禁，包含原身份恢复、File/409、SSE 回放、停止证明和实例资源首样。MockMvc、真实本地 HTTP 客户端、真实 Spring 随机端口与真实浏览器分别记账；它们不证明真实 Provider、真实 OpenCode 二进制、完整产品业务或正式 JAR 门禁通过。

第二层暂不可直接执行旧 `scripts/aicoding` 资格套餐：草稿 CAS 请求、可访问名、managed runtime、继承环境、构建路径与进程归属门禁尚需处理。此前 DNS/PKIX、54 项 52 PASS/2 FAIL、REST 首批 4 PASS/1 FAIL/25 NOT_RUN、浏览器摘要门禁 5 NOT_RUN，以及 855 REST29/1、browser2/3 均保留为原结果；修后 6/6 与 self-test 不回填首批通过。334 的 browser2/3 是另一批观测夹具首错，607 完整新批才为 57/30/5 全绿。新的完整 verify 同样中止exit143，部分失败保留，不能称正式 JAR 通过。新增独立 native 脚手架只做一次已授权的本地 mock 运输，未执行旧认证脚本或完整资格套餐。Codex 完全禁工具仍未证，真实模型调用为 0；未进入真实 Provider/付费模型层。

| 项目 | 本轮结论 | 证据性质 |
| --- | --- | --- |
| fake REST / SSE / File / 恢复 / stop-proof 精选 Java | 首批 54：52 PASS / 2 FAIL，0 error / skip | C 解析原始 XML；两停止协调断言未通过 |
| 实际 Spring / SQLite / REST | 新 run-2 实际 health UP、fake；REST 4 PASS / 1 FAIL / 25 NOT_RUN | 不以启动健康或 MockMvc 替代尚未执行的业务 |
| 浏览器真实后端 | beforeAll 摘要不一致；0 业务正文执行 / 5 NOT_RUN | runner 1 FAIL / 4 skipped，属于准备协议失败，未降低摘要门槛 |
| `qualify-reuse --execute` 草稿确认 | 当前请求缺 `expectedVersion` | 确定的静态协议差异，未运行 |
| matrix 统计取消按钮定位 | 旧可访问名与当前中央语义不相等 | 确定的静态呈现差异，未运行 |
| Maven 与既有环境代理 | DNS / PKIX 首错保留；proxy-4 编译与 classpath 成功，测试导致 exit1 | 不是最终构建/JAR 门禁成功 |
| 最小修订后的聚焦门禁 | 停止协调 3/3 + 真实 HTTP DTO/fallback 3/3 PASS；排序 safety 8/8 PASS | C 核原始 after XML/log，未代替完整 verify、REST 30 或浏览器 5 |
| 855候选精选 Java 完整新批 | 57/57 PASS，18 suites，0 fail/error/skip，exit0 | C逐XML核实际child home/tmp；不属于完整JAR verify |
| 855真实端口新完整 REST / browser | REST29 PASS/1 FAIL；browser2 PASS/3 FAIL，0 skip/flaky | 全部正文已进入；失败中上传后续与Requirement polling未到达，不能当对应能力已验证 |
| 607同版有限业务新完整批 | 精选 Java57、REST30、browser5 全部 PASS，exit0 | XML18、真实 REST92+SSE2、browser workers1/retries0；两首样严格0，不冒全产品或正式 JAR |
| 0f前端完整新批 | 275文件/2390测试PASS；typecheck/build/React audit exit0；tooling39、accounting13全部PASS | C核最终result/log与四文件源码差异；原19首错与4timeout不删除，未增timeout；不替代完整Java/JAR |
| 正式完整 verify | 中止exit143；137 XML/1075测试，16F/84E/5skip，非最终全套分母 | 正确 JDK/home/tmp已核；祖先Git输入冲突有实际控制证明，未证根因仍UNKNOWN，不全归工具链或历史基线 |
| 一次真实 native / 本地 mock 运输 | PASS，Java exit0；本地模型请求1、advertised tools0、原msgID/全deny | 新隔离脚手架及实际binary，owned group成员首查空；不代替真实模型、生产Spring业务或完整资格门禁 |
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

实际 Spring 第一层还需单列实际端口 REST / multipart、真实 SSE 订阅断线再 GET 不改变业务、cancel/stop 原身份结果和错误授权负控；现有逻辑单测与 MockMvc 不能代替这些新增运行证据。首次启动与有限请求结果见下文，首批剩余业务不得按启动成功回填通过。

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

以下结论来自 C 对新增源码与生产合同的只读比较。准备阶段父任务执行的启动器安全测试 7/7、A 合成运输测试 14/14，与 B 的类型检查和 5 项收集成功分别记账；C 没有重新执行这些测试。后续实际结果单列，不能把合成 self-test 计为真实 Spring 业务通过。材料见 [总报告](README.md)、[A REST 报告](A-rest-review.md)、[B 浏览器报告](B-browser-upload.md)。

### 启动实例与清理归属

[isolated-server.py:23–58](../../../scripts/backend-integration/isolated-server.py#L23) 对启动路径要求绝对 canonical、拒 symlink alias，使用 literal argv 与非特权 loopback 端口。新 run root 以 `mkdir` 独占创建，不复用既有数据库；原 run-1 在创建 loopback socket 时受环境权限阻挡，未启动 Java/创建数据库，证据保留。使用正常权限后另建 run-2，不删除或复用 run-1。独立 `data/projects/tmp/home` 和 A/B 项目子根由本次实例创建。

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

### 准备冻结历史：差异与结论边界

A 初稿缺 ready/revision 和全部 canonical 路径门槛、B 初稿缺首写前隔离核对、以及 B 仍消费旧 `applicationClassSha256` 而启动器已输出 `runtimeHashes`，已在作者代码中补齐。最后非作者复核另发现两端 class/resource 摘要 JSON 的非 ASCII 编码约定曾不一致；启动器现在明确 `ensure_ascii=False`，B 使用未转义的 `JSON.stringify(entries)`，同为紧凑 UTF-8。C 已读取最终两端源码；父任务另实际用 ASCII 与中文两份合成路径完成跨 Python/Node 摘要对照 PASS，记录在 [hash-contract-final.json](/workspace/backend-integration-20261004/hash-contract-final.json)。该文件明确 `springStarted=false / modelCalls=0`，不当作业务 RED 或 live PASS。B 报告五个 test 的原文件声明锚点也已纠正为 13/45/79/115/143，未改业务断言。

准备冻结源码在下方逐 SHA 记录；当时所读 JSON 编码门槛内没有剩余已确认的静态差异，不代表排序已获完整运行验证，后续真实首错已另列。提交测试/文档后若 HEAD 改变，启动器、A 与 B 均须由调用方指定同一个实际冻结 revision，不能从 isolation.json 自证或继续沿用旧默认 SHA。

截至本轮准备交付，实际 Spring/SQLite/30 REST/5 浏览器/原生 SSE/owned Spring 停止均未执行。Maven 代理授权未决是 **ENV_BLOCKED**，实际编译依赖获取退出 1；不能以 7+14 个脚手架测试、协议对照、类型/收集或 JDK/Maven 下载成功替代第一层通过。第二层、真实模型和旧 managed 资格脚本继续不运行。

| 最终准备项 | 准确计数与状态 | 执行来源 |
| --- | --- | --- |
| 启动器安全 + A 合成运输自测 | 7 + 14 = 21 PASS | 父任务/作者实际执行；C 源码核对 |
| Python/Node UTF-8 摘要协议 | 2 个合成路径对照 PASS | 父任务实际执行；C 读取结果与最终源码 |
| 新 REST 场景 | 30 NOT_RUN | 没有实际 Spring 请求 |
| 新浏览器场景 | 5 NOT_RUN | 类型检查/收集不计行为执行 |
| Maven compile / classpath | exit 1，ENV_BLOCKED | 父任务实际运行，尚未进入编译/测试 |
| 真实 Luna 烟测 | 0 次调用，NOT_RUN / 工具门槛 BLOCKED | 完整禁工具未证；fake 层也未完成 |

## Maven 准备阶段的 `ENV_BLOCKED` 与续接授权

父任务已报告官方 Temurin/Maven 下载与 SHA 校验成功、curl 经现成环境对 Central 父 POM 得到 HTTP200，但 Java/Maven DNS `repo.maven.apache.org` 失败；只核代理环境变量 presence，未输出内容。C 未重复联网命令、修改 Maven settings/proxy/DNS/白名单或读取认证。

官方 [Maven 代理指南](https://maven.apache.org/guides/mini/guide-proxies.html) 给出的常规入口是 settings.xml 的 proxies，并明确 Java system properties 取决于 transport；[Resolver 配置](https://maven.apache.org/resolver/configuration.html) 的 system-properties 采用亦为显式 opt-in。curl 成功和 HTTP_PROXY/HTTPS_PROXY presence 不证明 Java Resolver 自动沿用环境代理。未找到在当前“不配置代理/网络”授权下可证明通行的正常 Maven 路径；临时 `-s` 或 proxy system properties 同样属于代理配置变更。完整已验证缓存可以正常离线使用，但本轮没有缓存完整性证据。没有建议 CONNECT、hosts/白名单修改、镜像绕过或 credentials 改动。

续接的明确授权已允许本次 Maven 子进程临时沿用既有无凭据 proxy host/port，不写 settings/永久配置，不改变官方目标。旧失败未删除，也不将此前未授权状态沿用为本次不可执行的结论。C 只读以下公开/脱敏证据，没有执行 Maven、读取认证或 cacerts 内容：

| 实际阶段 | 独立可核事实 | 准确边界 |
| --- | --- | --- |
| proxy-2 | 结果 exit1；下一阶段记录其 `MAVEN_OPTS` 引号被解释成 main class，Java 没有启动 Maven | 运输脚手架调用问题，不是 Spring/模型业务失败 |
| proxy-3 | [脱敏结果](/workspace/backend-integration-20261004/backend-compile-proxy-3-result.json) exit1；官方 Central parent POM 返回 PKIX path-building failure | 此次已不再是原 DNS 错误；仍未进入项目编译或测试 |
| 既有 OS Java 信任元数据 | [public-trust-metadata.json](/workspace/backend-integration-20261004/public-trust-metadata.json) 记录 Debian Java21.0.12.1，默认 cacerts 链接 OS 既有 trust；下载 Temurin 默认库不同 | 未逐项检查 CA 或审计 actual certificate chain；摘要/链接不同不单独证明 PKIX 因果 |
| OS compiler 元数据 | C 实际 `java --describe-module jdk.compiler` exit0，模块21.0.12.1 提供 `javax.tools.JavaCompiler`；`java -m jdk.compiler/com.sun.tools.javac.Main -version` exit0，输出 javac21.0.12.1 | 无源文件编译；`bin/javac` 缺失不等于 compiler 模块不存在 |
| proxy-4 | [当次结果](/workspace/backend-integration-20261004/backend-compile-proxy-4-result.json) exit1；OS Java 运行 Maven、锁定 Temurin javac fork、专属 build-home/tmp、同临时代理；TLS validation 未关闭、trustStore 未 override、新 CA 未导入、settings 未写 | 1481 个 main sources 与测试编译、runtime classpath 成功；54 精选测试中 2 FAIL，不能计 BUILD SUCCESS |

C 判断：选择已安装 OS Java21 的**既有默认**信任库是普通工具链选择，不是关闭 TLS、添加 CA 或设置自定义 trustStore；它确实使用了与下载 Temurin 不同的预设 trust 集合，不能声称两者信任根完全相同。同版本已校验 Temurin 编译器的临时 fork/executable 只影响该构建子进程，`pom.xml` 仍要求 Java/release21。此判断不扩大代理目标/凭据授权、模型调用预算或允许任何 TLS 绕过；最终编译与测试、实际 Spring 健康/隔离/退出须分别取得证据。

本次只读工具链证据 SHA-256：`public-trust-metadata.json` 为 `50dcfd74be93695d1ad0b8162c8f5d1cddf7fae951452dbf89abcbf63e2e4364`，proxy-3 result 为 `04955fdd128b61b4fef7da47df45948871dfd48e3516cab1974d0550b6098d91`，OS Java executable 为 `6698f6f10143ed8463b06062281c727152d2e0ae2a3569b1716fbb2c6cedf0ba`，已结束 proxy-4 result 为 `f1ae4024d2df111a616f4da4123697be620e1fede5421c2e4d01c0af94da36a3`。

## 首次实际构建、隔离 Spring 与首错

本节是 C 对父任务/A/B 原始产物的独立读取和核算，不是 C 执行 Maven、HTTP 或浏览器。首错目录保留，不以修后某个单项拼接成整批通过。

### Java 54 项与停止夹具诊断

[java-focused-first-summary.json](/workspace/backend-integration-20261004/java-focused-first-summary.json) 对应 proxy-4：17 个 Surefire XML 共 54 项，52 PASS / 2 assertion FAIL / 0 error / 0 skip；C 解析原始 XML 的数目与汇总一致。`HttpOpenCodeClient` 实际本地 HTTP 5 项、Knowledge MCP transport 9 项、PPT MCP transport 2 项在其中通过，但不代表真实 Provider 或业务浏览器已通过。

两个首错都是 `TaskStopCoordinationIntegrationTest.userAndMonitorShareAnInFlightTaskCancellation(boolean)[1/2]`，原 `entered.await(3, SECONDS)` 为 false。A 保留原源码及 XML/txt 后增加真实 RUNNING 与持久化 active-session 正控；[诊断结果](/workspace/backend-integration-20261004/task-stop-diagnostic-result.json) 仍 exit1，3 项为 1 PASS / 2 FAIL，首错前移为实际 `Task.state=QUEUED`，没有触达 abort spy。不能因此推断 fake 远端 COMPLETED 是原因：实际停止服务有 active Session 时会直接调用 abort；同样不能仅由 `scheduling=false` 推断所有 Start 都只排队，获得 lease 的原正式入口还会直接继续准备与执行。A 后续必须以真实 queue/lease 诊断证明夹具原因，再保原共用 abort、单次停止证明和 3/5 秒断言复验。

原 XML/SHA 与首错在 [A/task-stop-before/manifest.json](/workspace/backend-integration-20261004/evidence/A/task-stop-before/manifest.json)；54 汇总 SHA 为 `2c53a13d422392885e2a73ec8b54428eb62fcd03d80309d4d782e3b79914fcd5`，新诊断 result SHA 为 `a1b7b357860b6da46185bb5940684058b6ec26388d54ad540b152d9ec938c603`。

A 对诊断数据库只读 SELECT 的 [manifest](/workspace/backend-integration-20261004/evidence/A/task-stop-diagnostic-before-fixture/manifest.json) 显示：先执行的 pause 任务留下 PAUSED / ADMITTED / HELD，后两个变体 QUEUED、position2/3；不同临时项目的三条 canonical root 都是 `/tmp`。当前 `/tmp/.git` 的祖先仓库被 [GitProjectScope:34–39](../../../src/main/java/io/opencode/loopper/runtime/GitProjectScope.java#L34) 识别，同一 checkout lease 正确阻挡后继写者。C 只读该 SELECT 产物与正式 lease/admission 源码，归因为类内共享数据库的跨 case lease 污染，不是生产停止证明失败。

最小 test-only 修复注入 Flyway，在 `@BeforeEach` 对本类 `DynamicPropertySource` 明确持有的独立 DATA SQLite clean/migrate；沿用邻 `TaskServiceIntegrationTest:87–90`。没有写生产 lease、SQL 修改、私有 dispatch、假 RUNNING、改变 Fake 或加时限。C 对 original 与当前源码逐 marker 核：两个 boolean/fullName、所有原 3/5 秒 await/get、150ms、两处 one-abort、三个最终 CANCELLED、PAUSED 和原业务失败恢复断言均保留；新增 `get(0)` 仅失败诊断。该修订 SHA `0a3f5986bc29c890b0bc360ab71e4c28be63d1537259c74f08f421b9dc8b7b95`。

[focused-after 原始 XML](/workspace/backend-integration-20261004/focused-after-raw/TEST-io.opencode.loopper.service.TaskStopCoordinationIntegrationTest.xml) 实际三项全部 PASS，0 failure/error/skip，SHA `8f17d0fd76f579cb2bd130179abb5d6a4718f063c63d6b0c23ed49d8b4067925`。这包含两个变体的真实 RUNNING/原单 active writer 正控及保留的竞态断言；C 没有重跑 Java。修复既有测试隔离问题，不以清库证明业务重启恢复。

### 首次实际 Spring 的隔离与停止

run-1 在创建 loopback socket 时 `EPERM`，Java 未启动、数据库未创建；[失败结果](/workspace/backend-integration-20261004/spring-start-first-result.json) 保留。获正常工具权限后另建 run-2，原数据库不复用。实际 Spring 日志包含 Tomcat `127.0.0.1:47179`、`jdbc:sqlite:/workspace/backend-integration-20261004/phase1-run-2/data/loopper.db`、fresh schema 与 Started；实际 health UP、runtime AVAILABLE/fake/managed=false/fake-model 与 [isolation.json](/workspace/backend-integration-20261004/phase1-run-2/isolation.json) 一致。C 已逐字节核 3790 个 classes/resources 与 159 个依赖 jar，旧 proof 摘要和 jar 全部匹配。

组长停止本次 owned Java 与 preview 后，run-2 proof 为 `ready=false, stopped=true, ownedJavaPid=17215, javaExitCode=143`；数据库与首错保留。此最终 proof SHA 为 `a44c70e8c0ed15c9d415f16fd1b99d5b623707d3160e24e99bc049af5060365d`。这是启动器 wait/exit 的 owned Java 证明，不是所有 descendants、所有端口或全系统资源归零。C 读取 `/proc/17215/exe` 元数据受权限拒绝，未升级权限、未读取 environ/凭据，因此未独立完成 live executable/cwd 的操作系统核验；producer 的 PID/argv/cwd 声明与 actual Spring log 分开标明。

### REST 首错与锁定 Boot advice

[rest-live-first.json](/workspace/backend-integration-20261004/evidence/A/rest-live-first.json) 实际 5 次请求：P1 fake runtime、V1 缺 local-UI、V2 无效 requestKey、V3 缺 graph，共 4 PASS；V4 空项目 name/rootPath 返回 400，却只有框架 `Invalid request content.`，缺原 `FIELD_VALIDATION` / `errorLayer=FIELD` / 两字段错误，因此 1 REPRODUCED_FAIL；其余 25 NOT_RUN。不是把 400 本身当失败，也没有允许修改原 typed DTO 断言。该 raw SHA 为 `1eeb8831b9d9ed556bf3cdf9a760942d64d36bec287390724bfbea81a0a03996`。

C 用已锁定 Boot 4.1.0 的实际 jar/class 元数据只读核因果：`spring.mvc.problemdetails.enabled=true`；`WebMvcAutoConfiguration$ProblemDetailsErrorHandlingConfiguration.problemDetailsExceptionHandler` 的 bean 为 `@Order(0)`。原项目 `ApiExceptionHandler` 没有 Order，框架可先处理共同的 Bean Validation / JSON 异常。对应两个 class SHA 分别为 `777c5abe6885a9162cdac039928407e50684a851c387d5bd0bcd76c2b95f2330` 与 `f083549544576250f8d62bfcf76bb11abcd4e777e62f3f1c2fe7e22bc71510e7`。这是锁定本地 bytecode 与真实响应相符的原因证据，不是纯 MockMvc 推断。

组长新增 [ApiProblemDetailHttpIntegrationTest](../../../src/test/java/io/opencode/loopper/api/ApiProblemDetailHttpIntegrationTest.java#L23) 使用真实 Spring RANDOM_PORT、实际 HttpClient、独立文件 SQLite、fake/scheduler-off/recovery-off。原测试源码 SHA `af5edd84a68ccaec05b28afbfc35d3d316524796944cace6ec2602be0d68cfbc` 与归档 `test-source.java` 完全相同；[before XML](/workspace/backend-integration-20261004/http-error-before-raw/TEST-io.opencode.loopper.api.ApiProblemDetailHttpIntegrationTest.xml) 实际 3=1 PASS / 2 FAIL，分别缺 `FIELD_VALIDATION` 与 `INVALID_JSON`，local-UI 与框架 409 fallback 正控通过。无效项目“未持久化”断言在首 typed DTO 失败之后，不能冒称 before 已执行到它。

授权后的 [ApiExceptionHandler](../../../src/main/java/io/opencode/loopper/api/ApiExceptionHandler.java#L19) 最小 diff 仅 import + `@Order(Ordered.HIGHEST_PRECEDENCE)`；原 handlers、状态码、fields、ProblemDetails 开关不改，没有 catch-all，未处理的 `ResponseStatusException` 仍可交框架 fallback。C 静态复核通过；[focused-after HTTP XML](/workspace/backend-integration-20261004/focused-after-raw/TEST-io.opencode.loopper.api.ApiProblemDetailHttpIntegrationTest.xml) 同三个原测试实际 3/3 PASS / 0 failure/error/skip，SHA `f77171c5e9ce563cc45f55a5822c83cdb776645c4568b732cbbd1b87d1d86846`。原 typed DTO、两 invalid fields、GET 项目空列表、INVALID_JSON、local-UI 拒绝和未改的框架 409 fallback 全部到达；没有以关闭框架 fallback 或弱化断言补绿。

该关联批 [focused-after-result.json](/workspace/backend-integration-20261004/focused-after-result.json) exit0，6/6 PASS，result SHA `abd3fae77a443310e0d68455441d5b1bf4038339bad35609e0c47c8456674dd1`。使用离线 Maven、OS Java 默认 trust 与已锁定 Temurin compiler；日志 SHA `1542f72846464b7cdd94cbb3122d2b7ca290ab9c0ab7db275d21caa4e1ca795e`。本报告保留首轮实际 54、后续诊断 3、HTTP before3、关联 after6 的各自分母，不将 6 的绿冒称完整原 54 或正式 JAR 门禁已绿。

### B 首写前摘要门禁与最小 producer 修复

B [first-results.json](/workspace/backend-integration-20261004/evidence/B-live-first/first-results.json) 为 runner 1 FAIL / 4 skipped、workers1/retry0；beforeAll 失败，actual API requests/writes 均 0，5 个业务正文均 NOT_RUN。首错分类是 `TEST_INFRA_PROTOCOL_MISMATCH`，不是产品上传失败。3790 文件 count 与 159 jar 摘要先通过，整体 classes 摘要不同。

C 独立对同 3790 文件算得：旧 Python Path 组件排序 `ed3d4331eea44a8cc6621ea800dc2747209051897ab29fbec2889d14756a133a`；JS 完整相对字符串排序 `760effb65ded304949aff21b5abe6964c22abf374015cd1c346033152f95ff16`，与双方 raw 一致。第 3537 项首次顺序差异为 `role-prompts/prompt/v1/...` 与 `role-prompts/prompt-v1/...`，并非 class 字节变化。先前 ASCII/中文 JSON encoding crosscheck 只证编码，不足以证明整个目录排序协议；此次真实门禁成功暴露该准备缺陷。

组长只改 [runtime_hashes](../../../scripts/backend-integration/isolated-server.py#L73) 为按完整 `str(path.relative_to(classes))` 排序；B 标准字符串排序、完整 count/SHA/jar assertions 均未修改，不过滤文件、不手改旧 proof。新增 [原样精确回归](../../../scripts/backend-integration/test_isolated_server.py#L19) 包含 `prompt-v1` / `prompt/v1` 和中文文件名，校验 UTF-8 compact JSON 的完整 SHA、count=2、实际合成 jar SHA。父任务 [launcher-unit-after.log](/workspace/backend-integration-20261004/launcher-unit-after.log) 实际 8/8 PASS，SHA `316558a93ba028238bcd514bbec8a07c6b5122f2fa8e4ce8499b5d85bc6111f8`；C 只读，没有另跑。后续真实 browser 必须使用新 compiled assets 的新实例 proof，原 failed proof/trace 均保留。

当前修订源码 SHA：producer `d80dc915b0f932799af80b84612ff026c482d3cb565445d884b1f91402d1a6b1`，safety tests `397d308d78f7ef0ff6576d67fc22b843024ccedfbe50dd3198bd486e44d4bc09`，API advice `562cb7637e1c64be1156cd41708b36021778c7723ec2744ba3daafe83eb75e90`。版本 diff 仅把项目/前端包/应用配置/现有发行引用从 `0.4.90` 同步到 `0.4.91`，没有改变 Java/依赖版本；不是正式 JAR 已生成或已运行 0.4.91 的证据。后续完整 verify 与新隔离启动由组长持有。

### A 合成 Git 夹具增量

后续 J1 [createSyntheticGitProject](../../../scripts/backend-integration/rest-contracts.mjs#L25) 只在已核的 canonical A 项目父目录下 `mkdtemp` 新 child，原 README 用 `wx` 写入。绝对 `/usr/bin/git` + argv、`shell=false`、10s/16KiB 边界；显式环境不继承 Git 定位、Provider、proxy、凭据；系统/用户 Git 配置关闭，signing/hooks/templates 仅本命令关闭。先 init 本 child，再核 actual toplevel/gitdir 精确是该新 root，之后才 add/commit 合成 README，最后核无 remote、clean、唯一 tracked README。

C 只读 diff 确认原 REST 30 case/title/DTO/CAS/请求计数没有改，J1 是夹具创建补齐，不是把真实业务 API 换 mock。新增四个安全 self-test 覆盖实际独立 Git、嵌套祖先 HEAD/index 不动、poison 环境隔离、symlink/noncanonical/relative 父目录在创建前拒绝。父任务/作者 [runner-unit-synthetic-git-first.tap](/workspace/backend-integration-20261004/evidence/A/runner-unit-synthetic-git-first.tap) 实际 18/18 PASS / 0 skip（原14+新增4）；其 SHA `b424b8f432d7ef5ed954f479756cf992c6470ea354150a84683da9f536460c48`。C 没有运行 Git 或这些 self-test；它们不能当 J1 后续真实 Spring 写入已执行。

新 runner SHA `84a99dc6912c8d9efdd3b902a675a7736d562dbc6b3aa7e39b044b2b7e3bcce0`，test SHA `ec6971365bc94229ee9e511f88f4b9d4663d06ea1b5d01e9a57a64114ea7a115`；旧 14 项 freeze 与首 REST raw 仍保留其原字节，不拿旧摘要证明新夹具。

### Surefire home 与中断证明的独立边界

功能 after6 原 XML 两套均实际 `user.home=/home/agent, java.io.tmpdir=/tmp`。Maven JVM 的 `MAVEN_OPTS` 不是 forked Surefire child 系统属性传递证明；本报告的 6/6 仅功能绿与独立 DATA SQLite，不称完整 user-home/tmp 隔离最终验收，也不能据此推断已读或未读用户凭据。

组长提供的外部 `run-maven.py` 新 argv 显式加入 `-Duser.home=<task>/build-home-2` 与 `-Djava.io.tmpdir=<task>/build-tmp-2`。C 只读核其 child 环境是 allowlist，不展开父环境，未继承 Provider/credential/JAVA_TOOL_OPTIONS；proxy 模式只从既有环境解析 HTTP host/port，拒 username/password/query/fragment，对 stdout 脱敏，不写 settings、不改 trust/TLS。该源码静态条件通过，最终须由当次 Surefire XML 精确 home/tmp 和 fresh DB 证明，不能只看 Maven runner 的 result 声明。

首 full verify 的 wrapper result 为 exit130，reason 是补 child properties。但 C 实际发现 `full-verify-first.log` 在该时点之后仍继续出现 Surefire Running / Tests run；读取点 07:20:53 UTC 为 27,027,431 bytes / 219,877 行，首 Surefire 在第577行。与“Java 测试启动前已停止”的回执不符，已立即反馈组长核 owned process。C 没有自行信号或启新进程。原 runner `Popen` 没有独立 process-session 与 SIGINT/SIGTERM/finally wait 清理，因此 wrapper 130 不能证明 Maven/Surefire 已退出；此首轮不计完整门禁通过或全部产品回归，须先核各 owned descendants 和输出归属，避免并行 clean/compile 污染同一 target。首错日志/结果保留，新的完整 verify 必须有受控唯一构建与退出证明后独立记账。

后续已确认旧两个 Maven 均实际自然结束 exit1；130 是外层工具中断的先期记录，不是最终 Maven exit。first log 的最终 summary 为 2928 testcase invocations / 86 failure / 2482 error / 4 skipped；归档现存 447 XML 合计 2747 / 85 / 2482 / 4，不能把混合/缺失输出称作完整可靠 suite manifest。second log 为 0 tests、VM 启动失败。两批重叠 clean/test 使它们均不能提供最终产品 verdict，[invalid-overlapping-builds.json](/workspace/backend-integration-20261004/invalid-overlapping-builds.json) 明确 `INVALID_BUILD_EVIDENCE`，SHA `5ef868fedd8af192b17e0ac1dd8b089b4d864ab9c0f3bf413168b80f99743f4d`。父任务以正常权限遍历确认旧 owned Maven/runner 已结束，没有杀无关进程；C 只读结果，不把这项 producer 声明冒称自身操作系统遍历。

新的外部 runner（本轮读取 SHA `1accace30d2f1dcfc1881170d7fb97373c4cfc3660bcac57437ccac94ad7b96e`）使用 `start_new_session=True`，在执行后立即写 runnerPid/ownedMavenPid/ownedPGID/argv/start；SIGINT/SIGTERM 仅转发到本次 group，`finally` TERM 后 wait15、超时同组 KILL/wait5。未使用 killall/pkill、按端口杀进程或泛选 Java。显式 home/tmp、环境 allowlist、同临时无凭据代理与默认 trust 保持。[final owned-process.json](/workspace/backend-integration-20261004/full-verify-final-owned-process.json) 记录 runner26704 / Maven及PGID26707、新唯一 `clean verify`；C 在该检查点另核 HEAD 为候选855，JSON 没有自行声明 revision。SHA `25e01524427446ea37e8b5dbc106437727f9aa2be68aff3298bb001ab3734116`。该批实际最终 result为exit143、interrupted=true、ownedMavenExitObserved=143，不以 owned 声明当构建通过；后续精选57是独立执行，不补成完整verify。

C 已反馈一个准确停止边界：信号 handler 只发送 group signal，主流程仍同步读取 stdout；若 child 忽略信号或后代持续持有 pipe，`finally` 内的 15s 清理界限要在读取结束后才生效。因此当前代码不能证明**所有中断**必在该界限内完成，`child.wait` 也只证明 Maven parent exit，不证明所有后代不存在。此限制不自动使正常不中断的最终执行无效；最终退出与唯一构建/清理必须用当次实际证据分别记账。

最终串行构建的历史只读中间检查点：当时 92 个已完成 `target/surefire-reports` XML 全部实际 `user.home=<task>/build-home-2`、`java.io.tmpdir=<task>/build-tmp-2`、`java.home=/usr/lib/jvm/java-21-openjdk-amd64`，没有属性 mismatch / incomplete XML。这个 92 是隔离属性样本数，**不是通过 suite 数或最终分母**。C 仅对已声明两个 PID 读取 `pid/ppid/pgid/comm`，实际 runner26704→Java26707 / PGID26707，与启动声明一致，没有读取 cmdline/environment/凭据；不是全系统进程审计或最终退出证明。

本次 `build-home-2` / `build-tmp-2` / `full-verify-final-test-data` 均独立存在、canonical、非 symlink，环境 data root 位于任务根；没有对用户数据根复用的源码证据。仍须限定：这不是所有测试文件写入的系统调用审计，数据隔离声明不能自动证明全部测试 fixture 的预期路径正确。当前 runner PATH 只有锁定 JDK bin 与 `/usr/bin:/bin`，不包含锁定 Maven bin；`LOOPPER_DATA_DIR` 明确覆盖测试配置默认 `${java.io.tmpdir}/loopper-test-data`。它们是已核的运行输入差异；LocalSyncConflict / DirectDocumentDevelopment / VerifierEngine 实际首错由 A/root 继续依原代码和 raw 分类，不先说 baseline 或产品回归，不在本批中途改输入。该批后续已中止；[full-verify-final-result.json](/workspace/backend-integration-20261004/full-verify-final-result.json) SHA `707729d51bc297f00805590aa71d39aae1109b711546d6393f4ce64625be62db` 证明exit143，正式JAR门禁仍未完成。

## 855 真实端口完整首轮与测试修订独审

C只读取 producer 原始输出与当前diff，没有执行 Java/Node tests、浏览器、服务或网络请求。组长执行的 [focused-final-summary.json](/workspace/backend-integration-20261004/focused-final-summary.json) 为18 suites / 57 tests / 0 failures/errors/skipped，exit0。C逐个解析 `focused-final-raw/TEST-*.xml`，实际57 testcase 与summary一致；全部18 XML的 `user.home` 为任务 `build-home-2`、`java.io.tmpdir` 为任务 `build-tmp-2`、`java.home` 为OS Java21。这修正此前after6仅功能绿、child home仍为 `/home/agent` 的隔离不足；不把57精选当完整verify或正式JAR通过。该summary SHA `707bc45dd77496f76680042184b60f3437be06c498d4708509a3cfac572992db`，result SHA `233a9b69458ab393cf683dd8ce3c6202da1021e0031b36dc9a8d7bfb4d6ff13e`。

### A：S1首错在自有清理，不是10秒deadline

[REST完整首轮report.json](/workspace/backend-integration-20261004/evidence/A-rest-final-first/report.json) 实际29 PASS / 1 REPRODUCED_FAIL / 0 NOT_RUN，SHA `df569edf5dbdaee62f577cb6683a19c4402585e29fe1da59c3f5a3a605df3999`。S1起止 `07:49:57.185Z`→`.202Z`，**17ms**；stack精确指向原 `readTaskEvents:263 abort.abort()`。成功读取路径的finally先abort request，真实Fetch signal使body errored，随后cancel抛AbortError覆盖返回。故首错分类为runner清理缺陷；原raw尚不能证明S1已完成cursor重连或最终权威REST断言，不从此推断服务端SSE失败。

A先增加真实ReadableStream/AbortSignal绑定负控，原runner19项18 PASS/1 FAIL，before TAP SHA `974e1993b0cbcc67acd583e0151fcb89328ea664f908fe9e2f20b59c9cba1870`。当前 [readTaskEvents](../../../scripts/backend-integration/rest-contracts.mjs#L222) 最小diff仅 **cancel→releaseLock→abort**，finally清deadline不变，没有catch吞错；`releaseLock`与abort仍在finally，取消失败/传输失败继续reject。原SSE status/content-type、ID格式、1000events/2MiB上限、cursor严格递增/排除、两次回放deepEqual与最终CANCELLED GET均不变。C机器比对855源码，除这段授权finally替换完全相同；全部30个 `await check` ID/顺序一致。

A修后自测原18加3为21/21、0 fail/skip，after TAP SHA `8b587eaa482d6aa31ade89a5006ba979e9d64b0c5c332f8e29cfd26c516bc511`。新增成功信号绑定、原transport error对象身份、cancel真错误仍unlock/abort三项，分别保正负控；C只读源码和TAP，不冒称独立runner。修后脚本 SHA `7cbb2a64735c8f1c7c784a2b3cb42ac63175efbf21b29e1d49600ff384fd97ea`，tests SHA `58b11ad138bd7cb7f9368b7332b7133426174a471802d4be5ccc61a3b532293b`。**21自测不补首轮S1为PASS**，新冻结revision完整REST30仍待组长复跑。

### B：两个上传前标题预期与工具实例归属

[浏览器完整首轮first-results.json](/workspace/backend-integration-20261004/evidence/B-live-final-first/first-results.json) 5个正文均进入，2 PASS / 3 FAIL / 0 skip/flaky，beforeAll passed、无API mocks，SHA `8ae83fa366543f385b3f4d2ea289d3fff3d98ee74f9c67544b863884300ed988`。

| 原case | 首错与已到达边界 | 修订审查与尚未验证 |
| --- | --- | --- |
| DOCX parser / ordered bytes / identical replay / changed-body409 | 上传前H1旧expect“需求流程”，实际权威title“隔离联调需求 docx-positive”；TEST_FIXTURE_SELECTOR_FAIL | `openRequirement(id,title)` 改GET seed.title精确toHaveText，保原“人工核对文档”节点；所有上传字节/顺序/sha/重复/409断言原样，首批尚未到达 |
| TXT / broken DOCX / explicit reselection | 同上，实际title“隔离联调需求 parse-errors”；TEST_FIXTURE_SELECTOR_FAIL | 同精确标题修订；0POST TXT拒绝、真实DOCX错误、原File显式纠正及后续断言不变，首批尚未到达 |
| Knowledge SSE / Requirement polling immediate SPA cleanup | after严格listeners[]失败8个工具监听；TEST_TOOL_INSTRUMENTATION_FAIL | Knowledge实际close已证明，首样门槛未通过，后半Requirement polling NOT_RUN；预热代码已静态核目标实例挂载前的真实注册身份，完整5复跑仍待组长 |

标题修订3调用均传同seed.requirement.title，未用宽matcher、硬编码新title、fixture替代服务端DTO或改生产。当前support SHA `265f44eb80870c02a3929106f3e6fa2ca8ca54b91839bbfb16d4691c8d429760`，包含预热的5case spec SHA `c7121bf1789cb72898a4590f2bfe781032222699458e6b8f13d3548918fe106e`。

[Knowledge首次raw snapshot](/workspace/backend-integration-20261004/evidence/B-live-final-first/knowledge-first-snapshot.json) SHA `d159a1c8cdeb9e1d8f41f30bbd63cb3cd9e765140376b73fe4a1a462c3a762a7`：same document、目标root已detached，RO/RAF/capture/ownedtimer各0、无后续自然清理事件；仍有8个window/capture监听，全部同target6 / callback9，stack为锁定Playwright `_setupHitTargetInterceptors`。它们在before已注册，**不是canvas/SSE新增泄漏证据，也不能过滤后称strict通过**。目标conversation SSE精确1实例、opened=true、closeCalls=1、readyState=2；application StoryAccounting stream继续open属于外部owner，二者不混记。这不是全App或GC证明。

锁定Playwright source链：`locator.js:100` evaluate→`_withElement:58` waitForSelector→`frames.js:658` adopt main context→`dom.js:110–115` ElementHandle preview→main injectedScript。目标raw与该工具监听链相符。W2账本在addEventListener时按当时 `roots()` 记录归属，独立于armed；所以 **Knowledge root已挂后仅begin前预热仍不足**。C先向组长/B反馈；现落盘代码先真实 `/knowledge/history?project=<原projectId>` 前页只读 `html.locator.evaluate` 初始化工具，再经真实选中/打开SPA进入原IDLE conversation，目标begin和首样原顺序不动。仅预热工具、不删除监听、不改技术helper、不等自然事件；须记录预热路径、当时目标实例未挂、same document及原conversation.id。[App.tsx:65](../../../frontend/src/app/App.tsx#L65) 实际route host为 `key={scope.key}`；[ownership.ts:129](../../../frontend/src/app/ownership.ts#L129) history与knowledge path不同，真实SPA会换对应host。组长已授权该方案，后续须实证 oldHost≠newHost、oldHost detached、同documentIdentity及原id；不能仅靠源码推断运行归属。C已读最终预热diff：精确assert前页无目标Knowledge workspace/SSE；真实列表选中原title、ui.open后核同GET的id/project/title/model/IDLE；强断oldHost≠newHost、oldHost detached、currentHost connected、同documentIdentity/原id，再删测量引用；UIWrites[]保无额外write。主体首样与后半poll断言不变。静态方案通过，实际新5尚未执行，不冒称修后资源通过。

冻结W2 [resources.ts](../../../frontend/e2e/w2/resources.ts#L107) SHA `b2f55748d0109cee6e28002a3f135ca51ca686b80d4a21b7c692b8b52a5cd6bd`：5类资源exact[]、same document、sentinel/media正控、no-natural-cleanup input门槛原样。未知资源仍失败；本轮不改、不过滤source stack/工具callback，也不取消其他owner。

run-3已由组长停止：[isolation.json](/workspace/backend-integration-20261004/phase1-run-3/isolation.json) 实际stopped=true/ready=false/Java exit=-9，SHA `3fada0e3c6a2ca1fab2c7f18e9bbcafb00ed999a776e4b9cfceab183d8be156a`；[preview.json](/workspace/backend-integration-20261004/phase1-run-3/preview.json) Node exit143，SHA `747e368a9994a4e4baeeadfed16d4363bacde11b6cb8ab4f612b9413c2bec464`。Spring shutdown超过15s后走本次owned group KILL，不能说全部正常143或优雅退出。C只读核当次4个声明owned PID48048/48061/48490/48493的 `/proc/<pid>` 均不存在；组长提供两端口拒绝回执，C未另发网络探测。另4个较早owned PID21315/22587/27364/27891仍为PPID1/Z、cmdline空的未收割zombie（组长stat回执），不杀、不动PID1；这是“无执行中的旧owned Maven/Java”与“全部后代不存在”的区别，不声称全环境进程对象清空。

## 607 同版有限业务终态：57 / 30 / 5

以下均是 `60759aeb19dd909a0303139ca44877c920ef2950` 的新完整批；不复用 334 的绿或 855 首批后单项结果。C 解析 producer 的原始证据并静态比较断言，未执行 Maven、HTTP、浏览器或服务。源码/测试字节不改。

### 正确完整 JDK 与聚焦 Java

OS Java21 的既有默认 trust 可正常运行 Maven，但不能据 `jdk.compiler` module 存在推断完整编译工具链。[compiler-proof/result.json](/workspace/backend-integration-20261004/compiler-proof-j9s1k9ev/result.json) 实际以正确 module main 调用 `--release17/21` 均 exit2；OS `lib/ct.sym` 不存在。较早错误 module-main 调用另保留，不把它当 compiler 根因。相同 Probe source SHA `7788afa5c600240945289a3661771d4606dad0ccabc03a757fee509c63c92856` 经锁定官方 Temurin javac 的 release17/21 实际均 exit0。父 Maven 继续使用原 OS 默认 trust、同批准临时代理；仅 compiler fork 和 Surefire JVM 选择完整官方 JDK，不设置 trustStore、导 CA、关闭 TLS 或写 settings。

[focused-business-final-summary.json](/workspace/backend-integration-20261004/focused-business-final-summary.json) SHA `6bff6e386d157c48d1d7a9c1b538a4602ea958303b7c35f510234db0092b094b`；[result.json](/workspace/backend-integration-20261004/focused-business-final-result.json) SHA `c390ff7d7aa1eeb044b2e8f4cb0b3406981bd6adf7a9d4b54cc9a63fab0ee9fc`，exit0、interrupted=false、owned Maven exit0。C 实算原始18 XML 为57 testcase、0 failure/error/skipped；每个 XML 的 `user.home` / `java.io.tmpdir` 分别准确为任务 `build-home-4` / `build-tmp-4`，`java.home` 为 `/workspace/backend-integration-tools/jdk-21.0.12.1+1`。这些是精选假业务、MockMvc、实际本地 HTTP/随机 Spring 端口的混合合同证据，不是完整 verify 或正式 JAR。

### A：实际端口完整 REST30

[A-rest-business-final/report.json](/workspace/backend-integration-20261004/evidence/A-rest-business-final/report.json) SHA `684d3614783e1e42efae6217d88214589c1983aadccb22c2b9af0874361a2442`，30 PASS、0 FAIL/NOT_RUN；log SHA `51dadea1979fbcb553f0af1e59717579398abad98242cf27e09ced4c412f2d57`。原30 check ID/顺序保留。92 普通 REST 请求为 GET46/POST28/PUT16/DELETE2，另2个真实 native Fetch SSE 订阅，不混入92分母。原 IMPLEMENTATION local/external session 身份一致，RUNNING→ABORTED、Task→CANCELLED、Lease HELD→RELEASED；SSE 原 IDs1..7，cursor1 的重连精确回放2..7，末次权威 GET 与所有原断言均到达。

run-5 health 为 UP、runtime AVAILABLE/fake/managed=false，独立 SQLite/data/projects，scheduler/startup recovery 均关闭。该 stop-proof 为 fake writer 的原业务身份与权威状态证明；不冒称真实 OpenCode OS writer 已杀、真实网络故障注入、Provider 或服务端 SSE subscriber 全局归零。S1 的 cancel→release→abort 保持原错误传播；脚本 SHA `7cbb2a64735c8f1c7c784a2b3cb42ac63175efbf21b29e1d49600ff384fd97ea`、tests SHA `58b11ad138bd7cb7f9368b7332b7133426174a471802d4be5ccc61a3b532293b` 未再变化。

### B：334 首错保留，607 完整 browser5

334 [B-live-jdk-final/browser.json](/workspace/backend-integration-20261004/evidence/B-live-jdk-final/browser.json) 实际2 PASS/3 FAIL。DOCX 已完成 upload200/ready、2原字节 hash/顺序、FIRST parser 与下载后，`postDataBuffer()` 为 null 导致 metadata 观测 TypeError，replay/409尚未到；损坏 DOCX 的真实400/code/detail与原File已到，但猜测“文档无法解析”的 selector 阻止显式更正；Knowledge 首样已0/SSE close1，随后 poll 的 `page.goto` 换新 document，重新引入8个 Playwright 工具监听使严格门槛失败。三例分别是 metadata 观测、错误文本定位、工具实例归属夹具问题，不能说对应业务在首批全过，也不称产品泄漏。

批准的 v4 改法仅在 DOCX case 装测试 fetch wrapper：**替换函数身份**，立即原参数调用 nativeFetch 一次，原 Promise 原样返回，再被动读取该合成请求 FormData 的原 metadata Blob。无读取 headers/token/response、更改 File/请求、延迟投递或重发；观测失败进入测试 row，不吞原 I/O 错误。错误 DTO 改为真实 `DOCUMENT_READ_FAILED` / “不是有效的 Office 容器”，原可见性/原File/空uploads/显式更正200断言保留。poll 改同 prepared document 的真实列表选择/打开 SPA，核原id/GET、不同 host/旧 host detached，未改技术资源账本、过滤监听或取消他人资源。

[B-live-business-final/final-results.json](/workspace/backend-integration-20261004/evidence/B-live-business-final/final-results.json) SHA `872b5d6513279c74bf8d9abb29b383fd47e576c8a7bc342a4a6931f7bb5460a4`；[browser.json](/workspace/backend-integration-20261004/evidence/B-live-business-final/browser.json) SHA `2560315c646a4f4a9368a5d75858973e69fc9ff3902188836f9e2c7d8372ead3`。实际5/5 PASS、exit0、workers1/retries0、0 skip/flaky/runner errors、无 API response mocks。7个源文件 before/after 与当前607逐SHA相同；5个 literal fullName 未删、未降断言。spec SHA `e24765a24c4f907c8cb915b07ffaf6f755051ea2a4c126bcc8fe820ebc850d25`，support SHA `4d2d95d047073903525bb60daff748ceaedc907b7d0af5244088d9859ade33a2`，shared resources 仍为 `b2f55748d0109cee6e28002a3f135ca51ca686b80d4a21b7c692b8b52a5cd6bd`。

[docx-identity.json](/workspace/backend-integration-20261004/evidence/B-live-business-final/docx-identity.json) 实际捕获1份 metadata，原 requestKey/expectedVersion0/expectedRevision1；两个原 File 顺序、大小与SHA一致。后端 parser FIRST/下载字节断言均到，显式同身份 replay 返回同upload，逆序同key请求为409 `WORKFLOW_REQUEST_CONFLICT`，上传列表仍1条。原UI上传POST1与额外显式运输重入/冲突请求分开记。TXT UI0POST/API400；损坏DOCX400精确DTO/可见中文、原File/空列表保留，显式更正有效DOCX200/ready、UI POST总2。未把磁盘故障、局部上传恢复或自动重试称已验证。

[sse-poll-first-exit.json](/workspace/backend-integration-20261004/evidence/B-live-business-final/sse-poll-first-exit.json) SHA `1e693c9013ea903f6b095d05fdf6127a15b7628576233f94549dfd9b24350b20`：C 读取 **knowledgeAfter.resources** 实体字段，未用顶层缺项默认为空。Knowledge before listeners2/RO1/timers2，Requirement before listeners9/RO3/timer1，均 rootConnected=true；各自首 MutationObserver 退出样本 rootConnected=false，listeners/RO/RAF/captures/ownedtimers 五数组全部严格空。同一 documentIdentity、原活跃外部 sentinel 与 application media listener 精确保留。Knowledge 事件增量空；Requirement 仅真实 element blur，没有后续 pointerup/move/cancel 或 window blur 清理。原 Knowledge EventSource 精确1实例、close1/state2，外部 StoryAccounting SSE仍 open/state1。raw allTimers 在两个首样仍分别1/2，未删除外部记录、不冒全App0/GC/heap证据。

12个真实浏览器 PNG（3皮肤×4状态）的当前文件SHA/PNG自然尺寸全部与 [screenshot-manifest.json](/workspace/backend-integration-20261004/evidence/B-live-business-final/screenshot-manifest.json) 精确相同，manifest SHA `499dbb859852f57e7c421b5b92c53f7899096c3be6a341760cfd666a546664ab`。C实际查看 spdb DOCX parsed 与 tech-blue parser-error 两张代表；原文件/解析状态与真实错误恢复提示可见，FIRST完整正文由原DOM断言验证，不将截图外正文说成像素已看。其余图仅做完整性核对，B作者的12张查看不冒称C独立全图视觉审查。数据均为隔离合成、真实Spring返回，无个人数据或模型生成证明。

### 停止与完整 verify 的剩余门禁

[run-5/observed-stop.json](/workspace/backend-integration-20261004/phase1-run-5/observed-stop.json) SHA `338834174315fa585cf95744206e23b03a8752c95c7567a39408c89bd2c223d3`：组长记录本次4个owned PID不存在、38701/48180拒绝111，Java exit=-9、preview143。它不是全体优雅退出143；超时后owned group KILL与历史未收割zombie边界保留。C读取该结果，没有自行停止或端口探测。

先前OS-JRE完整批中止exit143，93 XML/607 tests/12F/2E/1skip为部分执行；不能全部归编译器或当最终分母。当前外部 runner SHA `8533b50872c0f584a4df28526944b195d698bda84686fd1db97334a18a9c2304` 使用 private `build-home-5/tmp-5`、锁定Maven/JDK PATH、无 `LOOPPER_DATA_DIR` 覆盖、显式compiler fork/`jvm`/Surefire公开 `argLine` 启动属性，父Maven默认trust与批准临时代理不变；仍只拥有本次process group。C核当前失败XML的启动home/tmp5及Temurin21准确，不能从静态argv承诺全部文件访问/子进程隔离。

完整批中止前，下列首错只作有证据的诊断，不改旧模块/断言：

| 已完成XML中的原case | 事实与分类边界 |
| --- | --- |
| Verifier `stageBaselineScopesDiffAndRequireChangesToWorkAfterThatStageStarted` | stage-project无自身.git，环境祖先 `/workspace/.git` 实际存在；进入 `requireNoSiblingChanges→require→rev-parse128`，前置Git scope失败，目标stage diff断言未到。具体Git stderr原因未证 |
| Verifier `gitDiffFailsClosedForTruncatedEvidenceAndExhaustedCombinedPolicyBudget` | 仅override `SafeProcessRunner.run`；scope查询由 `GitEvidenceProcess` 的实际 `runner.resolve/ProcessBuilder` 执行。实际先rev-parse128，未到safe-evidence-limit谓词。属于夹具/祖先Git前置冲突证据，不冒“旧baseline已知” |
| Verifier `outputLimitTerminatesInheritedOutputDescendants` | 到原497 `ProcessHandle.isAlive=true`，不能从XML区别执行进程与zombie；后代kill/wait具体原因 UNKNOWN，不称compiler问题 |
| LocalSync `cupXml2JavaStyleMergeKeepsBothDependenciesSyncsStateMachineAndPassesMaven` | 原574 APPLIED实际ROLLED_BACK；apply可因内容/外部路径/原mvn verifier异常rollback。XML未留errorMessage/verificationEvidence，具体根因 UNKNOWN |
| DirectDocument `singlePackageRunsCurrentDirectoryTestsAndReportsWithoutAcceptingResult(boolean)[1]` | 原183 JUDGING实际AWAITING_DECISION，诊断verifications空；未到finishJudges/REPORTING。XML未给权威error/audit原因，根因 UNKNOWN，不拼后续变体绿 |

这些错误均在完整官方JDK、正确home/tmp下出现，不能说JDK修复后全绿。随后实际中止及输入控制证据见下节；有限57/30/5或一次native运输通过不替代完整资格套餐、JAR或真模型门禁。

## 唯一 native 运输实测与完整门禁的真实阻塞

[native-transport-a529QP/report.json](/workspace/backend-integration-20261004/native-transport-a529QP/report.json) SHA `40e07e01f3e8c6e883cff840f8c69c3260cf23efbdd5c4c1cc96e62bb84ee703`：本次一次真实 Java client→真实 OpenCode1.18.23→确定性 loopback mock，实际PASS、Java exit0、interrupted=false。本地 mock 请求1、advertised tools0、accounting请求0；真实模型调用0，`productionSpringBusinessCoverage=false`。不是正式Spring业务、MCP/附件/停止故障全套餐或Luna通过。

C未启动、重试运输，也未读取child原始日志或潜在凭据。只读取有限 report / Java-result / 安装JSON与脚手架源码：[安装证明](/workspace/backend-integration-20261004/opencode-install.json) 及实际binary SHA同为 `de0724a36eaf3166e7f1ff38d0f4478b95ccc47725e9597b3fe66d3d3e18baa2`；官方archive integrity由producer校验。新Node脚手架 SHA `f573ae4a03ebccb7ae4ca24665ccddcc6736394b303c94d9f38c90fd20b2cc2c`、Java probe SHA `26b00ef276336eedc2bbe73307a0a9964d7c30f2f3839c87daee3cb62922dd0f` 与report精确一致。实际运输时两文件为组长新增脚手架，不以HEAD607冒称它们当时已在该commit；当前0f已纳入这两文件，C逐SHA核与实际运输原字节相同，没有新增运输调用。C另只读实算3287编译class的紧凑manifest摘要 `a1b456b1387fae7635fc4e78f2d19d49b94381651a844eda2a874a2eb9e645d4`，及159依赖jar逐SHA，与report全相同；该class摘要不声称覆盖Spring资源或整个JAR。

[java-result.json](/workspace/backend-integration-20261004/native-transport-a529QP/java-result.json) SHA `5b8f8f69f476ef672d8419ad76cae8b06f3afc5470d9a174055051736bcead95`，与report内javaResult对象完全相同。原 `msg_50df968b12144573967e805307f90e0d` 精确在user引用中，session COMPLETED/text=`BUSINESS_RESULT_OK`、transcript toolParts0；原 `ROUTER_NO_TOOLS` prompt调用只一处、没有恢复POST/重发。实际返回permission全部deny，包含`*/*/deny`及external_directory deny。对应断言不以提示词“不要工具”替代receiver工具数组与真实权限读取。

独立静态审查的原三个脚手架阻断已由组长有限修订：显式SIGINT/TERM取消进入finally；cleanup等待不被abort抢断、已退出历史PID不重killgroup；双stream StringDecoder缓存对原nonce/Basic统一脱敏并保足够跨chunk尾部；binary failclosed锁SHA及运行class/jar摘要。末次另补“清理中收到signal也FAIL”，不出现interrupted=true却PASS/exit0。child使用private HOME/XDG/empty manageddir、显式允许环境与offline npm；没有展开父认证/Provider/proxy环境，nonce只入子进程内存，不入argv。源码与有限实测证明不等于全文件访问或全部外部网络的系统调用审计。

本次实际cleanup数组：Java exit0、OpenCode owned child以SIGTERM退出，git/javac子进程exit0；最终 `/proc` group成员检查为空。这是该probe的owned groups证明，不抹掉此前Spring被owned KILL的-9、完整构建的143或其他旧zombie，也不称全环境进程对象归零。C仅核producer JSON，没有自行发信号。

[full-git-temp-input-partial-summary.json](/workspace/backend-integration-20261004/full-git-temp-input-partial-summary.json) SHA `65a28fc4ada86eff48638b8fa4fab9ae472379882c61d3f2d4233de62f14fed9`：actual exit143、`PARTIAL_ABORTED_CONFIRMED_INPUT_ERROR`，137 XML/1075 tests/16 failures/84 errors/5 skipped。C独立解析当前137 XML属性，计数与summary一致，全部Temurin21及build-home-5/tmp-5；这不是完整suite最终分母或正式JAR通过。

[git-checkout-root-control.json](/workspace/backend-integration-20261004/git-checkout-root-control.json) SHA `ec08c7022730db2fdf36f6ba9beb4784492137d4bd253272cf743a001cd7a0c3`，producer调用真实 `GitProjectScope.checkoutRoot`：workspace子路径选到 `/workspace`，tmp子路径选到 `/tmp`；两祖先均有只读无效.git metadata。C只读核源码的祖先 `.git` exists扫描条件与控制结果。[git-temp-root-control.json](/workspace/backend-integration-20261004/git-temp-root-control.json) SHA `8c3fdefd39a8b4027353e2d65dcac3d2f821db5d5d96028596569a3acb2b985f`，两个真实git读控制都exit128。这证明测试输入/祖先Git边界冲突，不能将全量100个failure/error全部归编译器或同一Git原因；未有具体权威错误证据的LocalSync/DirectDocument等仍UNKNOWN。

该partial summary只记录4个owned残留PID72590/72882/73409/109325为Z：没有执行中的旧group，不等于全部后代不存在。不能杀PID1、删除祖先.git、关闭containment或调低原断言补绿。父任务随后按用户仅准许的标准权限机制，对唯一 `/var/tmp/opencode-loopper-backend-20261004-0f328858` 路径申请写入并尝试mkdir；[var-tmp-permission-request-result.json](/workspace/backend-integration-20261004/var-tmp-permission-request-result.json) SHA `759dbca30eb4148db5bcd449e85b1b1091214c59239cfd98914a0368b068dc4f` 实际exit1、`Errno30 Read-only file system`、directoryCreated=false、furtherAttempts=0、configurationChanged=false、networkPermissionChanged=false。这是指定路径实际EROFS，不称自动审批拒绝，也未扩大权限/重试；完整Java门禁仍阻塞。本次前端unit19个首错与4个timeout原结果保留；0f完整新批已实际全部通过，单列如下，不回填此前失败。

## 0f最终前端完整批与测试适配边界

C独立读取当前HEAD及 `git diff --name-status 60759aeb19dd909a0303139ca44877c920ef2950 HEAD`：精确四文件为 [browserFetchEnvironment.ts](../../../frontend/src/test/browserFetchEnvironment.ts)、其新增 [六项spec](../../../frontend/src/test/browserFetchEnvironment.spec.ts)、[NativeTransportProbe.java](../../../scripts/backend-integration/NativeTransportProbe.java) 和 [native-transport-probe.mjs](../../../scripts/backend-integration/native-transport-probe.mjs)。没有 `src/main`、`pom.xml`、前端生产page/owner/API/依赖/画布变化。后两文件与实际唯一native运输SHA相同；前两文件当前SHA分别为 `a3aa79597ab815f10d867bbd25cb0ca9556a21e72e56c2cd43cfbddb121ba41c` / `1794deef2685ed7101133fad6c2636ac27eabc6b49d24a7683f9a64a29252fb2`。

test-only适配仅将真实但异realm的ArrayBuffer转换为共享原backing buffer的native Uint8Array，再以 `original.call(this, algorithm, data)` 调用Node真实digest。native BufferSource/typed-array offset与length、算法、receiver及错误保持；没有伪造hash、换File或吞TypeError。六个真实负/正控覆盖原FileReader字节、异realm buffer、带offset视图、伪对象/错误receiver、非法算法与重复安装。原业务assertion文件未修改；新增六项解释2384→2390，不把测试环境WebCrypto method包装称为生产crypto修改。

以下为组长实际执行、C只读核result与log的独立证据，全部result revision为0f；没有C再次运行或拼接单项：

| 完整新批 | 实际终态及证据 |
| --- | --- |
| frontend full unit | [result](/workspace/backend-integration-20261004/frontend-unit-sequential-final-result.json) SHA `20f4537f695a0d8d86e983ca587269f65e517ff95efe04fde79c4d6f526e146e`；09:31:46.910559→09:44:27.154646 UTC、exit0/interrupted=false；[log](/workspace/backend-integration-20261004/frontend-unit-sequential-final.log) SHA `c691ee6c4cdd0ce2352124804cf2cc4fbf5dbd338aa50401cfee12dd649a82e4` 明确275文件/2390测试全部PASS |
| typecheck | [result](/workspace/backend-integration-20261004/frontend-type-final-result.json) SHA `c8c912f9a3226448d3433cba4821333f7457c60af7bfa41d2c86c3f86c9cf51d`，exit0 |
| tooling | [正常权限result](/workspace/backend-integration-20261004/frontend-tooling-permission-final-result.json) SHA `952b25933900483fb4a3262e2898f3e66c67313b6d611ec03fcc7c4d20b0ae77`，exit0；原TAP39/39、fail0/skipped0 |
| accounting | [result](/workspace/backend-integration-20261004/frontend-accounting-permission-final-result.json) SHA `4400b5c206768e98e11eb19d60e391f99c90b1c410be3f48c4655261c96c4422`，exit0；原TAP13/13、fail0/skipped0 |
| React-only audit | [result](/workspace/backend-integration-20261004/frontend-audit-permission-final-result.json) SHA `e9dca1d4de42f5d9336a96ccea450e0c5ab77a846667ac6f93e9d4c1f861f6a2`，exit0；[log](/workspace/backend-integration-20261004/frontend-audit-permission-final.log) 明确ok=true、783 source/4035 sourceModuleReferences/36 script/426 dependencyPackages/3940 buildModules、errors=[] |
| frontend production build | [result](/workspace/backend-integration-20261004/frontend-build-final-result.json) SHA `a4a30c5b9f199e242730ea188f26be08ca73943a6cdd2fe774400040704e75f3`，09:46:00.506508→09:46:42.670698 UTC、exit0/interrupted=false；[log](/workspace/backend-integration-20261004/frontend-build-final.log) SHA `be8f6c48c7c0c520e5fe6766b6a8a2d9e43e3c9d29b7216aba9fe06dbf1e9e05` 有实际产物及built in15.78s |

full unit argv仅 `run test -- --maxWorkers=1`，没有增加timeout或筛选替代原整批。原19首错与四timeout留存；四timeout在该完整新批真实绿，不以候选聚焦补洞。普通受限运行的 [tooling exit1](/workspace/backend-integration-20261004/frontend-tooling-final-result.json) 与 [audit exit1](/workspace/backend-integration-20261004/frontend-react-audit-final-result.json) 也保留：tooling首次TAP6/3PASS/3FAIL只留子测试失败，不凭有限输出逐项猜根因；audit原log明确 `spawnSync npm EPERM`。正常权限重新执行同命令的当前完整39与audit exit0，没有改断言或放宽注册/计数门槛。

实际build主chunk `index-CrCH361c.js` 为1630.73kB/gzip501.07kB，原日志仍有minified chunk超过500kB警告。C记录该大小与警告，不以exit0表示包体预算已消除，也不把前端静态构建通过称为正式完整Java/JAR通过。此次只读核终态没有启动服务、浏览器或Provider。

## Codex `0.159.0-alpha.3`：部分开关可证，完全禁工具仍 `BLOCKED`

本机实际 `/opt/codex/bin/codex --version` 为 `codex-cli 0.159.0-alpha.3`。执行 `--help`、`exec/features/debug/app-server --help` 与父任务额外准许的 `codex --no-daemon features list`，均 exit0；只读命令有 PATH-alias 只读文件系统告警，未创建 alias，未启动 daemon/模型。features list 输出仅 feature 名称、阶段与布尔；没有直接读取 auth/config 文件、改 feature 或复制凭据。

后续另经父任务授权执行正式 `codex --no-daemon app-server generate-json-schema --experimental --out <task>/codex-protocol-C`，exit0，只输出本地协议 metadata、不启动 app-server/模型。当前440个JSON逐相对路径及SHA的紧凑UTF-8 manifest摘要为 `160ecb6be35625584e33edc74fceae95c9cb525a52904e59f3c4bd75e34a77bd`。`ThreadStartParams.dynamicTools` 与 `selectedCapabilityRoots` 仅能约束动态/选定能力；`TurnStartParams.disabledPluginIds` 只约束插件；`ConfigToolsV2` 的 web_search 与 connector 默认开关不能证明 shell/apply_patch/code-mode/全部MCP未注册。schema还允许额外config字段，不能据其枚举猜完整关工具配置。本次没有调用 config/auth API；CLI内部文件访问未做系统调用审计。此 metadata 增量仍不足完整 no-tools 证明，`BLOCKED` / Luna0保持。

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

以下 SHA-256 是准备阶段 C 只读实算的关键源码/工具字节；版本同步、测试夹具及 advice 后续修改不以这些历史摘要冒称最终字节。新增修订摘要在实际首错章节另列，未把 HEAD 代替运行证据：

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

新增准备脚手架的准备冻结字节如下。producer/safety 修订后的摘要另列；这些历史摘要不是实际 Java runtimeHashes、SQLite 或端口运行结果：

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

本轮源码审查、历史首错、607同版有限57/30/5、一次native/local mock运输及0f前端275文件/2390测试/type/build/tooling39/accounting13/audit终态已分别记录；Java / REST / 浏览器 / 启动健康 / owned stop / 前端build / 正式完整JAR口径不能互相替代。完整Java verify中止与未解决首错保留，指定标准 `/var/tmp` 请求实际EROFS且未创建/未重试；完整资格套餐及真实模型层未通过/未运行，模型0、Codex no-tools仍BLOCKED。保留独立数据根与原始失败/完整新批结果；C 未直接打开认证文件、未更改网络、未提交或对外发送。
