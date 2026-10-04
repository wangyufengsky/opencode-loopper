# A：隔离真实 REST 合同

本轮复用原 A（`/root/react_flow_workflow`）。原启动记录为 `gpt-6.1-sol / xhigh`；工具不能实时读取平台模型字段。本文件只记录指定 REST runner、自测试及源码核对，未启动、安装、编译 Spring，未调用真实模型或外部服务，未手写数据库。运行时业务源码基线为 `11ca25a3bb764a2d80ac924350139a7087639121`。

## 当前交付与状态

- [rest-contracts.mjs](../../../scripts/backend-integration/rest-contracts.mjs)：30 个顺序公开 REST 场景。初轮制作时全部 NOT_RUN；续接真实首跑为 **4 PASS / 1 FAIL / 25 NOT_RUN / exit1**，详见后文，保留首错。
- [rest-contracts.test.mjs](../../../scripts/backend-integration/rest-contracts.test.mjs)：Node 内置测试，不开启测试 HTTP server，不依赖后端。最新18/18 PASS、0 FAIL/SKIP，exit 0（原14项+4项真实合成 Git 隔离）。此前13、14项候选日志保留；14项版本包含父 ready/revision 与 canonical data/projectRoot 负控。
- 最新原始自测：[runner-unit-synthetic-git-first.tap](/workspace/backend-integration-20261004/evidence/A/runner-unit-synthetic-git-first.tap)；原14项 [runner-unit-final-ready.tap](/workspace/backend-integration-20261004/evidence/A/runner-unit-final-ready.tap) 来源保留。自测不计为真实 Spring 的30项通过。

父控制独立 SQLite、Fake 注入、端口、依赖、classpath、Spring 启停与 PID。脚本只在父提供的 `project-parent` 下用 `mkdtemp` 建立全新子项目，`wx` 写内容为 `fixture` 的 README，并按父追加授权建立该合成目录自己的本地 Git 仓库。注册/变更的项目、流程、需求、LoopDraft、Task 都在该进程的数据范围内；不发布、读取用户全局 Git 配置或修改祖先/外部 Git 仓库，不删除执行后的业务对象/目录。

## 启动输入与预检

需要父声明（字段是本地测试基础设施证明，不是新增生产 API）：

```json
{
  "ready": true,
  "revision": "父冻结当次完整40位源码SHA",
  "baseUrl": "http://127.0.0.1:父端口",
  "runRoot": "/workspace/backend-integration-20261004/phase1-run-1",
  "dataDir": "/workspace/backend-integration-20261004/phase1-run-1/data",
  "projectRoot": "/workspace/backend-integration-20261004/phase1-run-1/projects",
  "opencodeMode": "fake",
  "schedulingEnabled": false,
  "startupRecoveryEnabled": false,
  "model": "fake/model"
}
```

```sh
node scripts/backend-integration/rest-contracts.mjs \
  --base-url http://127.0.0.1:父端口 \
  --project-parent /workspace/backend-integration-20261004/phase1-run-1/projects/A \
  --isolation /workspace/backend-integration-20261004/父启动证明.json \
  --expected-revision 父冻结当次完整40位源码SHA \
  --report /workspace/backend-integration-20261004/evidence/A/首次REST结果.json
```

`--expected-revision` 必须由调用方明确提供全 40 位小写 hex，不能用证明文件的 revision 自证。省略时只接受业务基线 `11ca25a3…`。父提交测试/文档后实际 HEAD 会变化，运行时业务基线和当次基础设施 HEAD 分别记录，正式运行用后者的冻结 SHA。

P1 首 HTTP 前验证 `ready === true`、revision 精确匹配外部期望、HTTP loopback 明确端口，无用户凭据/query/hash/path；不允许重定向。`runRoot/dataDir/projectRoot/projectParent` 均逐字比 `realpath`，拒绝 symlink alias；data 与 projects 必须分别位于声明的专属 runRoot 下，parent 在 projects 内，不能相互包含。四路径应由父先创建。之后唯一公开预检是 `GET /api/runtime/opencode`，必须 `status=AVAILABLE/version=fake/managed=false/pid=null/model=fake/model`。

公开 runtime DTO 不暴露 SQLite URL、调度/启动恢复开关或 Git revision，因此这些由父启动证明核实，不声称 REST 能独立验证。业务基线 [OpenCodeConfiguration.java](../../../src/main/java/io/opencode/loopper/runtime/OpenCodeConfiguration.java#L54) 的 `mode=fake` 创建既有 FakeOpenCodeClient；[OpenCodeRuntimeManager.java](../../../src/main/java/io/opencode/loopper/runtime/OpenCodeRuntimeManager.java#L161) 定义公开 Fake runtime。无需发明测试 profile 或 Fake 控制 endpoint。`loopper.scheduling.enabled=false`、`loopper.startup-recovery.enabled=false` 必须显式配置，不能沿生产默认 true。

## 真实可执行场景与源码锚点

下表为脚本的 30 个父 case。复合请求属于同一个 case；实际执行后 JSON 保留每条请求的 method/path、raw JSON body 的解析值、原 key、SHA256、用途、HTTP status、完整隔离 DTO/ProblemDetail。此表目前是 **源码核对/待执行**，不是静态宣称复现或通过。

| Case | 请求/断言合同 | 真实源码及已有测试（本轮未执行 Java） |
|---|---|---|
| P1 | ready/revision/canonical 目录、公开 Fake runtime，首写前拒非隔离配置 | runner:33、279；RuntimeController:26；OpenCodeRuntimeManager:161 |
| V1 | 缺 `X-Loopper-Local-UI:1` 的 create 是 400/LOCAL_UI_HEADER_REQUIRED | WorkflowTemplateController:30、51 |
| V2 | 非 16–100 字符合法 key 是 400/WORKFLOW_REQUEST_KEY_INVALID | WorkflowCommands:20–24 |
| V3 | null graph 是 400/WORKFLOW_REQUIRED | WorkflowEncoding:20–28 |
| V4 | Project 空 name/rootPath 是 400/FIELD_VALIDATION，并保字段错误 | ProjectController:111 的 ProjectRequest；ApiExceptionHandler:44–50 |
| J1 | 专属合成 Git 新目录，先 exact checkout root/git-dir，再 POST project 201及 exact ID/rootPath GET | ProjectController:52；ProjectService.create；runner createSyntheticGitProject/J1 |
| T1 | HUMAN 结构化 graph 执行校验无诊断；create ACTIVE、revision1、GET 完整 DTO | WorkflowGraphValidator；WorkflowTemplates:45；WorkflowNodeExecutionIntegrationTest:224 |
| T2 | 人为丢弃客户端可用回执后，只显式重发同 endpoint/raw body/key，receipt 完全相同 | WorkflowCommands:20–28；WorkflowPlanningIntegrationTest:98 |
| T3 | 同 key 改 title 是 409/WORKFLOW_REQUEST_CONFLICT | WorkflowCommands:25；WorkflowPlanningIntegrationTest:98 |
| T4 | graph expectedVersion+expectedRevision CAS；相同 request 重放；revision1 历史 graph 不变 | WorkflowTemplates:59；WorkflowPlanningIntegrationTest:35、98 |
| T5 | 新 key+旧 graph CAS 是 409，权威当前 graph 未被覆盖 | WorkflowTemplates:62；WorkflowPlanningIntegrationTest:98 |
| T6 | layout expectedRevision+expectedLayoutVersion CAS 独立于 graph version；同身份重放 | WorkflowTemplates:88；WorkflowPlanningIntegrationTest:82 |
| T7 | 旧 layout CAS409、zoom0 校验400，原 layout 不变 | WorkflowTemplates:94；WorkflowEncoding:33；WorkflowPlanningIntegrationTest:82 |
| T8 | 指定 sourceRevision1 copy；同原 request 重放同 copy ID，provenance/graph 精确匹配 | WorkflowTemplates:70；WorkflowPlanningIntegrationTest:35 |
| T9 | 两记录 limit1 游标不重不漏；summary 无 graph/layout/definitionJson/layoutJson | WorkflowTemplates:26；WorkflowPlanningIntegrationTest:121 |
| R1 | 四字段 create 的原 POST/body/key 恢复；改 objective 同 key409；无 by-request GET | WorkflowRequirementController:20；WorkflowPlans:51；WorkflowPlanningIntegrationTest:60 |
| R2 | 已知 ID GET sourceSnapshot；按 project 的轻量列表不能含 graph | WorkflowPlans:30、39；WorkflowPlanningIntegrationTest:35 |
| R3 | plan 独立 CAS/原 request replay；旧 plan 历史与源 template 均不改；过期 CAS409 | WorkflowPlans:68；WorkflowPlanningIntegrationTest:98 |
| R4 | confirm/replay 只到 PENDING_START；control 未配置、human attempts 空、该项目 tasks 空 | WorkflowPlans:81；WorkflowControlsIntegrationTest:312 |
| R5 | confirmed requirement layout save/replay，state 与 graph version 不变；旧 layout409 | WorkflowPlans:104；WorkflowPlanningIntegrationTest:82 |
| R6 | confirm 的旧 version409 | WorkflowPlans:118；WorkflowPlanningIntegrationTest:60 |
| R7 | 只对真实未启动需求 cancel→CANCELLED；replay 相同，历史保留/attempts0；不能再 plan edit | WorkflowPlans:94、118；WorkflowPlanningIntegrationTest:131 |
| T10 | archive original replay，列表隐藏；历史 graph 可 GET，已创建需求仍读原 source | WorkflowTemplates:80；WorkflowPlanningIntegrationTest:35 |
| D1 | 公共新 draft 不接受 v1，400/LOOPSPEC_V2_REQUIRED | LoopDraftController:27；LoopDraftService:67 |
| D2 | 合法 v2 assessment、create201、GET；update CAS+1，旧 expectedVersion409且新草稿不丢 | LoopDraftService:67、122；TaskServiceIntegrationTest:2590 提供 FILE_CONTENT/AC-1 结构 |
| D3 | missing/stale confirm version 分别400/409；正确 confirm 仅 PENDING_START、attempt0/session0 | LoopDraftController:43、51；LoopDraftService:179 |
| K1 | 公开 start 合法 Fake Task；先证明 RUNNING + 已知 IMPLEMENTATION writer + HELD lease | TaskController:184；TaskService:666；TaskSessionMonitorService:205 |
| K2 | 公开 cancel 后权威 GET CANCELLED，同原 externalSessionId ABORTED、活动读取 ABORTED、lease RELEASED、无新 attempt | TaskCancellationCoordinator:104、176；TaskWriterTerminationService:67、110；FakeOpenCodeClient:414 |
| K3 | cancelled terminal task start409/TASK_TERMINAL；sessions 精确不变，不能创建替代 writer | TaskService.start；TaskController:184 |
| S1 | 真 Task events：初读持久事件，关闭，再 Last-Event-ID 重连；严格递增、排除 cursor/不漏不重；最终 REST 仍 CANCELLED | TaskController:327–367；TaskEventServiceTest；SseEmitterLifecycleTest:11、31、47 |

表内裸文件名均相对 `src/main/java/io/opencode/loopper/` 或 `src/test/java/io/opencode/loopper/`，当前源码读取锚点。runner 的 CASES:217 是唯一运行父 case 名单，check:279 起按序执行，不跳过红项、不以错误夹具当产品通过。

## DTO、身份和恢复边界

Workflow Receipt `{id,revision,version,layoutVersion,state}` 的三个整数必须合法，readback 必须 exact ID/revision/version/layoutVersion。Template Detail 还核 graph/layout/diagnostics；Requirement Detail 核 projectId/sourceTemplateId/sourceRevision。graph 和 layout 两条 CAS 各自冻结；显式 replay 使用 `prepareRequest` 快照的原 raw body/key，不从新的 GET 投影构造替代命令。不同 key 的 stale-CAS 负控是独立测试输入，不冒称未知命令恢复。

本轮 client-receipt discard 是脚本**有意丢弃已收到的成功回执**，服务端回执仍在原始证据。它证明服务端同 body/key durable replay，不证明真实丢包、进程重启、浏览器刷新、跨刷新 File/draft 恢复。创建需求没有公开 by-request GET；UNKNOWN 且 ID 未知只能用户明确按原 POST/body/key 恢复，不能虚构纯查询。已知 accepted receipt 的读取只调用现有 ID GET；读本身不 mint key、不自动再写。

HTTP 传输/JSON 回执解析失败导致 `uncertainWrite=true`；这次 run 后续所有非 GET 均阻断，保留原 request trace。任一父 case 首失败后剩余 case 是 NOT_RUN；预检/传输标 ENV_BLOCKED，业务断言标 REPRODUCED_FAIL。失败不重跑覆盖证据。报告采用 `wx` 先占文件，既有结果拒覆盖，退出非0。未启动后端时不能将 fixture 单测结果转写为 REST PASS。

Task create/confirm/start/cancel 的 endpoint 没有 Workflow 风格 requestKey 协议：脚本不添加虚构业务身份、不自动重发 keyless start/cancel。公共 `createNew` 要 v2，不能借 Java 内部 `drafts.create(v1)` 的停止测试夹具伪造 REST 可达正控。采用 NON_JAVA + FILE_CONTENT EXACT/AC-1/MACHINE，不运行 PROCESS/BROWSER/外部 verifier。

K1 必须真实读到原活动 writer 与 HELD lease，才进入 K2；若实际配置不能建立该正控，将原失败保留，不能先把 Task 标 cancelled 再声称证明了活动 writer 停止。K2 是现有 Fake 正常 abort 的可观察正控：源代码确认 abort acknowledgement 后才更新原 Session/Task 与 lease。REST 的本地 ABORTED 单独不足以证明远端停止，故另查同原 externalSessionId 的 remote activity；仍不把这个正控外推为故障、并发或真实 OpenCode 终止证明。

## 必须保持 Java 覆盖的能力缺口

1. Fake `failNextAborts`/holdProfileOpen 等是 Java 方法，不存在公开注入 endpoint。UNKNOWN abort、相同原 writer 的 failure cleanup 与并发协调必须复用 Java integration tests，当前只读核查、**NOT_RUN**：TaskStopCoordinationIntegrationTest:52（用户/monitor 共用一次 in-flight abort）；TaskServiceIntegrationTest:678、714、746、1574、1716（不确定停止、lease/新 writer 阻断、重试与边界）。不发明 REST 控制功能或调用 session-failure 来假冒 unacknowledged abort。
2. R4/R7 的公共读取能证明当前项目 Task/attempt 与 control 未启动状态，但不能通过 REST 声称所有数据库表行数0。已有 WorkflowControlsIntegrationTest:312 直接真实 MyBatis/SQLite 检查 workspace_lease/attempt 并确认 Fake session create0；本轮等待父安排 Java测试，不手写 SQL。
3. SSE脚本两次实际请求并 cancel/release reader；资源自测证明 client deadline/reader cleanup，不能从关闭 TCP 推断服务器订阅账本0。已有 SseEmitterLifecycleTest:11/31/47 专测异步发送/容器异常/迟到 attach 清理；TaskEventServiceTest 保事务事件。均未在此代理执行。
4. 本层不设置 credentials、不调用 Git publish、不会将 Task SSE 包装成 Requirement SSE。设计门禁/W0未知保护和前端既有138最终验收是此前独立证据，不纳入这30条真实 REST 的计数。

## 冻结记录

执行环境 Node `v24.19.0`。最终命令为：

```sh
node --check scripts/backend-integration/rest-contracts.mjs
node --check scripts/backend-integration/rest-contracts.test.mjs
node --test --test-isolation=none --test-reporter=tap scripts/backend-integration/rest-contracts.test.mjs
```

两项语法检查分别 exit0；最终内置测试 **14 PASS / 0 FAIL / 0 SKIP / exit0**，TAP 有 `1..14` 与全部 fullName。初次默认 `node --test` 在当前环境只呈现一个 file-level 汇总，未把它当14独立结果；后续明确 `--test-isolation=none` 输出每项结果。测试使用注入 fetch 和内存 Response/ReadableStream；没有启动服务或请求实际后端。

| 冻结对象 | SHA256 |
|---|---|
| rest-contracts.mjs | `168d20697ef4a6e9cf4b27e2f75f58edbb5a1e17c2ae60386762b8abba5213b1` |
| rest-contracts.test.mjs | `3eb86bbe36799443217bcaa14f852ca5c0b65448658da31cae18189a3bf4f8bb` |
| runner-unit-final-ready.tap | `ac1c165f7248a8d4bd6d234dca9ad505de66ba3662d5e74a2ca71a154b089bcb` |

当前父尚未提供 actual endpoint/ready proof，未发 Spring GO。父报告 Maven 构建因既有无凭据代理授权待决属于 **ENV_BLOCKED**；此代理没有自行尝试 Maven。30项 live合同全部 **NOT_RUN**，不是产品失败，不用14项自测冒充实际联调。后续独立新 runRoot 与 explicit `--expected-revision` 已有接口，重新运行必须用新报告文件保留首错。

本代理初轮只新增本文和指定两份脚本，未改任何生产 Java/前端代码、数据库、依赖或服务配置。工作树中父/B/C并行基础设施文件不属于本代理修改。初轮文件已交回组长集成；下节是后续明确授权后的实际执行，保留初轮 NOT_RUN/阻塞记录，不覆盖历史证据。

## 续接：首次真实 Spring REST（2026-10-04）

用户批准仅本次 Maven 子进程使用既有无凭据代理，父报告真实 main/testcompile 成功并统一启动既有 Fake Spring。A没有自行执行 Maven、安装或启停服务。实际基础设施 HEAD `4f06621c11872c3b6ace9f0285ba9bdd669163e5`，初轮 runner/test SHA 不变；业务源码基线与该 HEAD 区分如上。

父 GO 的 endpoint 为 `http://127.0.0.1:47179`，ready proof 位于 `/workspace/backend-integration-20261004/phase1-run-2/isolation.json`，项目 parent 为该 fresh root 下 `projects/A`。命令使用显式 `--expected-revision 4f06621c11872c3b6ace9f0285ba9bdd669163e5` 和新增独占报告 `evidence/A/rest-live-first.json`，经正常 `with_additional_permissions network=true` 执行，没有改变网络策略。实际 **exit1，4 PASS / 1 REPRODUCED_FAIL / 0 ENV_BLOCKED / 25 NOT_RUN**。未自动 retry/replay，没有执行 J1 或创建业务项目。

| 已实际执行 case | 结果 |
|---|---|
| P1 | PASS；四路径 canonical、ready/revision 校验；真实 runtime `AVAILABLE/fake/managed=false/pid=null/model=fake/model` |
| V1 | PASS；缺本地授权 header 的真实 POST 返回 400/LOCAL_UI_HEADER_REQUIRED |
| V2 | PASS；短 requestKey 的真实 POST 返回 400/WORKFLOW_REQUEST_KEY_INVALID |
| V3 | PASS；null graph 的真实 POST 返回 400/WORKFLOW_REQUIRED |
| V4 | REPRODUCED_FAIL；真实空 Project字段请求虽为400，但原 typed validation 断言不成立 |
| J1–S1 余25项 | NOT_RUN；首错后停止，不宣称相关恢复/停止/SSE已通过 |

V4原请求 `POST /api/projects`，body 精确为 `{"name":"","rootPath":""}`、SHA `78b542d5dbec0900c127dc82461f6be3d9e2290f52e0a0769cd6f9fa85a0c0aa`。HTTP400返回：

```json
{"detail":"Invalid request content.","instance":"/api/projects","status":400,"title":"Bad Request"}
```

首断言是 `ProblemDetail.errorCode`，actual `undefined`、expected `FIELD_VALIDATION`；随后 `fields.name/rootPath` 尚未到达。原断言未放宽。ApiExceptionHandler:44声明 MethodArgumentNotValid 应有 FIELD_VALIDATION、errorLayer/fields；实际 Spring MVC ProblemDetail 优先级/异常边界交父生产作者诊断。仅凭本次响应不先宣称唯一根因，A没有改生产 advice 或配置。

原始证据：[rest-live-first.json](/workspace/backend-integration-20261004/evidence/A/rest-live-first.json) SHA `1eeb8831b9d9ed556bf3cdf9a760942d64d36bec287390724bfbea81a0a03996`；[rest-live-first.log](/workspace/backend-integration-20261004/evidence/A/rest-live-first.log) SHA `3ad2cf4f7664eef33b842ec45ec26dad8dddadf60455daec313996c98eff8c6c`。不覆盖初轮14自测/30 NOT_RUN manifest，不把真实5项与自测相加为通过数。

## 续接：TaskStop 并发夹具调查与隔离修正

父精选 Java 首批原始汇总 `java-focused-first-summary.json` 实际54项、52 PASS、2 FAIL、0 ERROR/SKIP。两红均为 TaskStopCoordinationIntegrationTest 原 `userAndMonitorShareAnInFlightTaskCancellation(boolean)` 的 false/true 变体，原line64 `entered.await(3,SECONDS)` 为false；同类 pause invalidation 正控通过。该批由父执行，A只读核实原 Surefire，不冒称作者独立跑过。

原 XML/txt/源码已在任何专项覆盖前原样归档到 [task-stop-before/manifest.json](/workspace/backend-integration-20261004/evidence/A/task-stop-before/manifest.json)：原 source `fa0eb6faab88322b7671824fda29ff1f5edc83b44175ed3b522a28f88a7049b1`；XML `1e5a075af48e8e1fcfaff533519277e82b693df8f7575ad371355a5b6b74c7ad`；txt `7e93fc3584bd1e4e1ec6626126efab878d6e3121c7d027a3cff19f83dd5189b6`。

静态事实：Fake submitPrompt:222–223 默认可立即 remote COMPLETED，但 TaskWriterTerminationService:67–101 对每个真实 active writer **先**调用 abortWithConfirmation；只有 abort 抛出后才检查自然终态。TaskService.startNewAttempt:1399–1424 默认 prompt 返回后不自动将本地 Session 改 COMPLETED，实际 test-classes 的 scheduling=false。因此 Fake 自然完成只是候选，不足以解释原 abort latch 未进入。不能直接加 hold 将未知路径掩盖。

父本次只移交该 test 文件。A暂增加正控/失败诊断：start 返回必须 RUNNING（附真实 errors），mapper原 active Session 必须单条 RUNNING/非空 externalID；原 first entered 3秒判断加当前 Task/active rows/取消 Future 已完成结果或异常说明。原两个 boolean/fullName、3/5秒、one-abort、最终 CANCELLED 全保；没有修改 Fake hold、生产路径或超时。候选 test SHA `297e9d1eb9d76353ee6f8c7d6eeebae3cf1458b49446cc4e24776a9f959e5d68`，待父 offline 单类回归取得真实 Task/Session/future 因果后才决定最小 fixture。当前不把诊断代码称为已修复或通过。

父随后实际执行该诊断候选：3 项为 **1 PASS / 2 FAIL**，两参数变体首错提前到真实 Start 正控：`state=QUEUED`、`errors=[]`，未进入原 abort 等待。该轮源码/XML/txt/log已原样另存 [task-stop-diagnostic-before-fixture/manifest.json](/workspace/backend-integration-20261004/evidence/A/task-stop-diagnostic-before-fixture/manifest.json)，不覆盖首批原断言失败证据。

A对该诊断专属 SQLite 使用 `mode=ro`、只执行四条 SELECT，得到确切跨 case 原因：先执行的 pause 场景 Task 是 PAUSED，其队列为 ADMITTED，持有 HELD；两个参数场景 Task 均 QUEUED、position 2/3。三条 queue 的 `canonical_root` 都为 `/tmp`。各 Project 的临时目录不同，但当前环境 `/tmp/.git` 存在，GitProjectScope:34–39 按祖先 `.git` 确定同一 checkout root；DirectWorkspaceLeaseCoordinator:221–230 使用此 canonical root，而 acquire:338–341 对已有 HELD holder 正确排队。该观测与 Surefire 的实际执行顺序对应。因此此处为**共享测试数据库中的跨 case lease 污染**，不是未启调度、Fake 自然终态或已确认生产取消故障。

最小 fixture 已冻结：本类增加 Flyway 注入及 `@BeforeEach clean/migrate`，复用 TaskServiceIntegrationTest:87–90 的既有集成隔离方式。目标仍是本类 DynamicPropertySource 的独立 DATA SQLite；不写 SQL、手改 lease、强制 admission/dispatch、初始化 Git、修改生产/Fake、改变原时限。保留真实 RUNNING/单 active writer 正控，原两 boolean/fullName、3/5秒、150ms、one-abort、并发双方最终 CANCELLED 断言全部保留。冻结 source SHA `0a3f5986bc29c890b0bc360ab71e4c28be63d1537259c74f08f421b9dc8b7b95`；[task-stop-fixture-candidate/manifest.json](/workspace/backend-integration-20261004/evidence/A/task-stop-fixture-candidate/manifest.json) 保存原/新源码及原合同 marker 逐项计数。

`git diff --check` 已 exit0。父已统一 offline Maven 回归 `TaskStopCoordinationIntegrationTest,ApiProblemDetailHttpIntegrationTest`：**6/6 PASS、0 FAIL/ERROR/SKIP、exit0**，其中本类原3项全部通过（pause及false/true并发取消）。A只读核验原XML/log和source SHA，不冒称自己独立执行Maven，也不把HTTP3计为A作者合同。TaskStop XML SHA `8f17d0fd76f579cb2bd130179abb5d6a4718f063c63d6b0c23ed49d8b4067925`；[task-stop-fixture-after-review.json](/workspace/backend-integration-20261004/evidence/A/task-stop-fixture-after-review.json) 记录命令原始结果、log/XML和当前source hashes以及3个fullNames。此处两失败归为夹具隔离问题已修复，原一abort/两取消结果及新RUNNING/activeSession正控均实际到达通过。

真实 REST 首跑仍4 PASS/1 FAIL/25 NOT_RUN；父HTTP聚焦绿不替代30REST重跑。等待当次新服务 ready/proof与明确GO后，才执行新的完整首轮，不把旧服务或文档修正当业务通过。

## J1 合成 Git 隔离补强（基础设施自测，尚未真实 HTTP 重跑）

父根据同类祖先 `.git` 风险，追加授权 REST runner 仅在自己新 `mkdtemp` 项目初始化本地合成 Git，避免后续 Task 的 canonical lease/分支/index归属祖先目录。`createSyntheticGitProject` 先验证父 canonical、创建新子目录和README，使用 Node `execFile('/usr/bin/git', argv)`，无 shell，单次10秒/16KiB上限。子进程使用固定最小环境，不继承 GIT_DIR/WORK_TREE、Git config 注入、Provider、代理或凭据；`GIT_CONFIG_NOSYSTEM=1`、`GIT_CONFIG_GLOBAL=/dev/null`、`GIT_TERMINAL_PROMPT=0`，每次 argv局部 `-c` 给合成身份并禁 signing/hooks/init templates。没有 remote、fetch、push、用户全局修改或假 `.git`。

`init --initial-branch=main` 后，真实 `rev-parse --show-toplevel` 与 `--absolute-git-dir` 必须精确等于新 root/其 `.git`，且各自 `realpath` 无alias；通过后才 add/commit唯一 README。再核初始 clean、tracked仅README及remote为空，Git命令/结果和初始commit写入 J1 evidence。此改变只影响夹具，30父case标题/业务断言、V4原红、原key/body恢复合同不变。

四项新增自测分别为：真实clean/branch/内容；嵌套已有合成Git时祖先HEAD/index/README仍不变；继承定位/config/用户签名配置不能重定向；symlink/noncanonical/relative parent在创建任何夹具前拒绝。最新18/18、0 FAIL/SKIP、exit0；两文件 `node --check` 和限定 `git diff --check` 均0。runner SHA `84a99dc6912c8d9efdd3b902a675a7736d562dbc6b3aa7e39b044b2b7e3bcce0`，test SHA `ec6971365bc94229ee9e511f88f4b9d4663d06ea1b5d01e9a57a64114ea7a115`；[runner-synthetic-git-freeze.json](/workspace/backend-integration-20261004/evidence/A/runner-synthetic-git-freeze.json) 保存源码与18项原TAP hashes。这是作者脚手架验证，独立评审由C核实；尚未运行新Spring J1/K1，不宣称端到端执行隔离已通过。
