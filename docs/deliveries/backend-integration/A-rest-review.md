# A：隔离真实 REST 合同

本轮复用原 A（`/root/react_flow_workflow`）。原启动记录为 `gpt-6.1-sol / xhigh`；工具不能实时读取平台模型字段。本文件只记录指定 REST runner、自测试及源码核对，未启动、安装、编译 Spring，未调用真实模型或外部服务，未手写数据库。运行时业务源码基线为 `11ca25a3bb764a2d80ac924350139a7087639121`。

## 当前交付与状态

- [rest-contracts.mjs](../../../scripts/backend-integration/rest-contracts.mjs)：30 个顺序公开 REST 场景，当前真实 Spring 执行状态全部 **NOT_RUN**，等待父进程启动证明、endpoint 和 GO。
- [rest-contracts.test.mjs](../../../scripts/backend-integration/rest-contracts.test.mjs)：Node 内置测试，不开启测试 HTTP server，不依赖后端。14/14 PASS、0 FAIL/SKIP，exit 0。此前 13 项候选日志保留；14 项版本包含新增父 ready/revision 与 canonical data/projectRoot 负控。
- 最终原始自测：[runner-unit-final-ready.tap](/workspace/backend-integration-20261004/evidence/A/runner-unit-final-ready.tap)。该自测不计为真实 Spring 的 30 项通过。

父控制独立 SQLite、Fake 注入、端口、依赖、classpath、Spring 启停与 PID。脚本只在父提供的 `project-parent` 下用 `mkdtemp` 建立全新子项目，并 `wx` 写一份内容为 `fixture` 的 README。注册/变更的项目、流程、需求、LoopDraft、Task 都在该进程的数据范围内；不初始化、发布或修改任何外部 Git 项目，不删除执行后的业务对象/目录。

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
| J1 | 专属新目录 POST project 201，再 exact ID/rootPath GET | ProjectController:52；ProjectService.create；runner:307 |
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

本代理只新增本文和指定两份脚本，未改任何生产 Java/前端代码、数据库、依赖或服务配置。工作树中父/B/C并行基础设施文件不属于本代理修改。父/C后续非作者审查独立记录，不以本作者单测替代。文件已交回组长集成，停止源码写入。
