# Code design contract

This contract governs production code structure. It complements the runtime,
Designer, OpenCode, and UI contracts: those documents define observable
behavior, while this document defines how that behavior is divided into code.

## Design goals

- A class has one primary reason to change. Coordination, policy, persistence,
  transport, parsing, and presentation are separate responsibilities.
- Dependencies point from adapters and application orchestration toward stable
  domain contracts. Domain policies do not depend on Spring, MyBatis, HTTP, or
  process execution.
- Public facades stay small. They validate the boundary and delegate to focused
  collaborators; they do not become a second implementation of those collaborators.
- Prefer composition. Inheritance is reserved for genuine substitutability, not
  for sharing service implementation.
- Optimize for deletion and replacement: an adapter, policy, or parser should be
  testable and replaceable without constructing the complete application graph.

## Responsibility boundaries

| Responsibility | Code shape | Must not own |
| --- | --- | --- |
| Application workflow | `*Coordinator` / focused `*Service` | HTTP/SQL/process parsing details |
| Domain decision | immutable value, `*Policy`, strategy | Spring context, persistence, external I/O |
| Object construction | `*Factory` / assembler | lifecycle transitions or remote calls |
| Persistence | aggregate-oriented `*Mapper` / `*Persistence` | network, Git, model, browser, long file I/O |
| External integration | `*Client`, `*Transport`, adapter | Task/Designer lifecycle decisions |
| Project fact discovery | bounded `*Analyzer` plus immutable snapshot | AI routing, task mutation, long DB transactions |
| Untrusted payload parsing | `*Parser`, normalizer | persistence mutations or retries |

Use a design pattern only when it makes a changing axis explicit:

- Strategy for interchangeable algorithms selected by stable domain input.
- Factory/assembler for construction with validation or protocol-specific shape.
- Policy for deterministic decisions without side effects.
- Coordinator for a bounded multi-step use case.
- Adapter for HTTP, Git, process, filesystem, browser, or provider protocols.

Do not add a pattern merely to rename procedural code. A new abstraction must
reduce coupling, isolate a changing axis, or make a contract independently testable.

## Active compatibility-facade boundaries

`OpenCodeAgentPolicy` owns role step limits and managed agent definitions/selection together;
the HTTP prompt adapter and message inspector consume that policy. Step exemption must not remove
structured-output or repeated-tool inspection. `OpenCodeStepLimitNotice` recognizes runtime control
output at both the HTTP result and package Markdown fallback boundaries, before artifact persistence.

The historical `DesignerSessionService` and `TaskService` remain public
compatibility coordinators while their use cases are migrated. Their public
signatures may stay stable, but extracted implementation must follow these
ownership rules:

- `DesignerSessionService` owns Designer workflow ordering, lifecycle transitions,
  remote role invocation, and persistence coordination. It must not implement
  compact package-plan normalization, semantic validation, or executable verifier
  synthesis; those belong to `DesignerPackagePlanCompiler`. Shared machine payload
  shapes and marker-extraction patterns belong to `DesignerSemanticContracts` and must not
  depend back on the facade.
  OpenCode `question` capability selection, answer validation, durable decision-log
  encoding and compatibility-chat projection belong to `DesignerQuestionSupport`;
  that collaborator must not perform Designer lifecycle transitions.
- `TaskService` owns ordinary execution ordering and error-layer escalation. It
  must not assemble immutable design snapshots, verification aggregates, baseline
  diffs, or Judge evidence prompts; those belong to `TaskEvidenceService`.
  `TaskCancellationCoordinator` owns durable `STOPPING -> CANCELLED` child-state closure,
  while `TaskWriterTerminationService` owns remote Session/Judge termination proof and
  persistent unconfirmed-writer evidence; neither may release/admit the next Direct lease.
- Rolling execution is split by reason to change: `RollingPackageService` owns package command
  boundaries and cumulative TaskSpec/Stage append; `RollingPackageCheckpointService` owns the
  verify-checkpoint-fact-lease saga and crash recovery; `RollingPackagePlanService` owns only
  unfinished-suffix revisions and correction packages; `RollingPackagePlanGenerationService`
  owns only read-only AI suggestion Session dispatch, polling, bounded extraction, and base-snapshot
  verification; `RollingPackageReadService` owns bounded
  workbench/fact projections. It may project policy-owned capabilities but may not derive a second
  UI state matrix. None may rewrite the immutable LoopDraft.
  `RollingPackageTaskHooks` is the narrow bridge from the legacy Task facade, not a second workflow
  implementation. `TaskEvidenceService` remains the sole owner of proven diff/evidence material.
  `RollingPackageCommandPolicy` is the only authority for package command capabilities and
  write validation; `RollingPackageCommandContextService` is the single builder of persisted
  Task/Run/Queue, checkpoint, writer, Judge, verifier, and Designer facts passed to that policy.
  Both Task overview and rolling workbench reads use this context, and the workbench response owns
  its `taskVersion`, `currentPackageRunId`, package versions, and complete capability snapshot.
  Controllers and Vue components may not mix that response with an older overview, duplicate the
  state matrix, or repair inconsistent aggregates after commit.
- `ProjectStackAnalyzer` owns bounded filesystem evidence and manifest fingerprinting;
  `ProjectStackProfileService` owns immutable snapshot persistence and freshness checks.
  Analyzer snapshot collections must be emitted in canonical lexical order so filesystem
  visitation order cannot change persisted profiles or cross-platform verification results.
  Project list reads must use persisted summaries and must not call either filesystem path.
- Tests that mock canonical workspace/session paths must derive both the registered project root
  and the expected runtime key from the same normalized absolute `@TempDir`; Unix-only literals
  such as `/tmp` must not encode path equality assumptions into cross-platform contracts.
  Filesystem identity checks must also accept Windows long and 8.3 aliases via `Files.isSameFile`,
  and stdio fixtures must prove their child process has exited before temporary-directory cleanup.
- `TaskProfileRouter` owns deterministic selection from a supplied stack snapshot,
  `TaskProfileSafetyPolicy` owns requested external-mutation and publication-target classification,
  `TaskProfileIntentPolicy` owns task-level read-only review and positive-mutation classification,
  while `TaskProfileOverridePolicy` owns versioned component/profile override validation.
  `TaskProfileRouterRunService` owns request/monitor run claiming, confirmed remote cancellation,
  latest-run read projection, and optimistic terminal-reroute validation. `TaskProfileService`
  remains the profile persistence and routing coordinator.
  Neither may infer repository technologies from `AGENTS.md` or permit AI labels to create evidence.
- `DesignerVerificationIntentMapper` owns source-backed test-to-scenario relationships, including
  default ownership by a package's sole positive focused-test deliverable and unambiguous references
  back to it; regression-only, test-style, and negated framework references are execution constraints,
  not scenario coverage or capabilities. `DesignerDesignFactExtractor` rejects partial or duplicated
  controlled section sets before facts can be merged. `DesignerAcceptancePlanCompiler`
  owns fact grouping, closed capability selection and Stage assembly;
  `DesignerAcceptancePathPolicy` alone decides whether Designer-owned text is a standalone repository-relative
  path rule. Natural-language scope descriptions must not leak into executable `GIT_DIFF` policies.
- `DesignerAcceptanceFastPathResolver` alone owns v6/v7 exact-reference normalization, Stage topology
  validation, direct/AI/incomplete routing, and the merge of closed-set Compiler assignments. It must
  never perform fuzzy or substring matching. `DesignerAcceptanceWorkflow` owns persistence and solver
  coordination; `DesignerAcceptanceStatusProjector` owns the bounded read projection from frozen facts,
  capabilities, diagnostics, and failure metadata; `DesignerClosedChoiceContract` owns the current-v7
  minimal prompt projection and raw-output
  semantic firewall, while `DesignerCompilerPromptContracts` owns legacy, frozen-v6, and current-v7 Compiler
  role instructions. `OpenCodeStructuredSchemas` owns only the transport
  envelope and cannot authorize execution/topology/safety fields merely because an extra property is transport-valid.
  `DesignerSessionService` may select the route and coordinate lifecycle only. Adding this path must
  lower or preserve its legacy line-count ratchet; prompt text and parsing rules do not move back into it.
- `GitDiffScopeApprovalService` alone classifies approval-eligible outside-allow-list existing-file
  changes, builds Stage-baseline patch fingerprints, and resolves append-only local decisions. It must
  not weaken forbidden-path, delete, containment, or baseline failures. `VerifierEngine` owns raw Git
  change classification and remains fail-closed for callers outside Task orchestration; `TaskService`
  owns only lifecycle coordination into and out of `WAITING_INPUT`.
- `DesignerMutationObligationExtractor` alone freezes source-backed positive repository mutation obligations;
  the shared polarity classifier must classify path-bearing facts as positive, negative/example, or ambiguous,
  `DesignerRequirementMutationActionPolicy` binds actions to each referenced path without treating nearby business nouns
  as delete/move commands, while `DesignerRepositoryPathSyntax` owns common root-file, explicit-directory, and
  exact-path/path-rule typing. Clause/list scope, external-path rejection, source excerpts, and hashes must remain deterministic.
  `DesignerAcceptanceStagePathPlanner` owns executable Stage path selection and path provenance; package scope,
  global fact and technology fallbacks may shape execution but cannot prove a Stage owns a frozen obligation.
  `DesignerMutationStageBinder` alone owns v7 deterministic obligation-to-Stage binding: one explicit responsible-path
  owner, exact controlled fact reference, one uniquely covering existing Stage rule, one token-exact deliverable
  symbol in a unique legacy Stage title/objective, or an exact path added to the only Stage. It must keep duplicate
  responsibility and real multi-Stage candidates blocked and must not expose mutation choices to the model.
  `DesignerMutationOwnershipRecovery` owns the bounded unresolved-path recovery projection and prompt;
  `DesignerSessionService` may only gate unchanged retry and route scoped feedback/recovery through that
  collaborator, not recreate path reasoning. `DesignerAcceptanceWorkflow` remains below the default source limit,
  and this route must not raise the `DesignerSessionService` legacy ratchet.
  `DesignerAcceptanceCapabilitySolver` owns exact branch-and-bound capability cover, bounded greedy fallback,
  the complete business-score tuple, the full set of equal optimal solution signatures, and bounded
  non-exhaustive diagnostics. Stable candidate order may stabilize output but must not collapse a true business tie;
  a current-v7 Compiler may see only the membership-discriminating candidates from exhaustive equal optima and
  must select the complete discriminating signature of exactly one optimum. A non-exhaustive search is diagnostic
  evidence only and cannot authorize a model choice or a compiled plan.
  `DesignerAcceptancePlanCompiler` only orchestrates Stage assembly and lowering.
  `MutationConservationPolicy` runs before lowering and may only use bounded rule-containment/overlap relations
  shared with `VerifierPathPolicy`; unproved containment, forbidden overlap, delete, or move-source must fail closed.
- `DesignerAcceptanceShadowEvaluator` owns only bounded rollout arithmetic and per-sample count invariants. Its input
  contract carries an independent expected mutation/hard-gap baseline, rejects negative or over-reported counts, and
  forbids aggregate compensation of one sample's safety or focused-test regression by another sample. It excludes
  prompts, model output, path values, record IDs, persistence, lifecycle, and OpenCode dependencies. The versioned
  corpus executes its exact production guard methods, while the same-input compiler harness lives in tests. Key guards
  publish only bounded actual counts through a deterministic test-only registry; complete qualification checks those
  counts rather than inferring metrics from green test totals. All reports stay below `target/`, may block a release,
  and must never become a second planning authority.
- `LoopSpecAcceptanceService` owns the final cross-source acceptance contract. A focused Maven/Gradle
  test with no criterion mapping is a valid blocking gate only for a `JAVA_PRODUCTION` Stage whose
  criteria are all Judge-only; machine or mixed criteria still require explicit focused-test coverage.
- `ProjectConventionService` owns proposal/apply lifecycle coordination, while
  `ProjectConventionCandidateWorkflow` owns one persisted Candidate launch/recovery loop,
  `ProjectConventionCompilation` is the sole Legacy/MCP semantic authority, and
  `ProjectConventionEvidenceCatalogCapture` freezes server-owned component/command/path facts before remote I/O.
  `ProjectConventionStackPolicy` retains Legacy prompt and apply-time fingerprint guards. Guarded AGENTS.md
  reads, atomic writes, permission preservation and marker replacement belong only to
  `ProjectConventionDocumentStore`.
- A responsibility is considered extracted only when the old implementation body
  is removed, focused tests cover the new boundary, facade integration tests remain
  green, and the legacy line-count ratchet is lowered in the same change.
- New collaborators may return domain results or normalization notes. They must not
  call back into the compatibility facade, duplicate lifecycle transitions, or
  become generic utility bags for unrelated future methods.

## Size and dependency limits

- Prefer at most 400 physical lines per production class and 250 per interface.
- The default hard limit is 600 physical lines for a production Java source file.
  Existing files above the limit are explicit ratchet debt: every edit must keep
  them at or below the recorded cap, and structural work must lower that cap.
- Prefer methods of at most 40 lines; 80 lines is the review threshold. Split by
  named decision or phase, not arbitrary line chunks.
- Prefer at most 8 constructor dependencies; 12 is the review threshold. A larger
  graph normally means the class contains multiple application use cases.
- A Mapper groups one aggregate or tightly coupled transaction boundary. Cross-
  aggregate deletion/orchestration belongs in a named persistence coordinator.
- New nested DTOs are allowed only when private to the owner. Shared API/read-model
  contracts use a dedicated top-level type or a cohesive contract container.

`CodeStructureContractTest` enforces the source-file hard limit and the legacy
ratchet. It is intentionally dependency-free so it runs in every Maven test and
release build. Lower a legacy cap in the same change that extracts responsibility;
never raise a cap to make a build green.

The current compatibility ratchets are 5,399 physical lines for
`DesignerSessionService` and 2,727 for `TaskService`. Rolling package behavior must stay in the
collaborators above; a later change may only preserve or lower those caps.

## Change workflow

1. Identify the class's reasons to change and its upstream/downstream callers.
2. Protect observable behavior with the closest existing test.
3. Define the collaborator interface around domain terms, not the old method's locals.
4. Move one responsibility at a time; keep transactions and external I/O on their
   existing side of the boundary.
5. Add focused tests for extracted policies/parsers and rerun integration tests for
   the facade. Use fakes at external boundaries, not mocks of internal data plumbing.
6. Update this contract and `AGENTS.md` when a new structural rule or exception is needed.

## Review checklist

- Can the class's responsibility be stated without “and”?
- Are transaction boundaries short and free of HTTP, model, process, browser, and
  long filesystem operations?
- Is error ownership preserved at the correct FIELD/VERIFICATION/SESSION/TASK layer?
- Does a collaborator expose domain input/output instead of the caller's internal state?
- Are names concise and behavior-revealing, with no pass-through abstraction that
  merely adds indirection?
- Do focused and integration tests prove the extracted boundary and the unchanged flow?
## Designer facade 与候选提交依赖边界

候选提交采用“传输端口与业务裁决分离”的依赖方向。通用 `MachineCandidateSubmission` 只提供 run 生命周期、幂等提交、版本并发与结果信封；每个 run 以 `CandidateScope(type,id)` 指向一个 `DESIGNER_SESSION / TASK / PROJECT` 聚合，并以 `CandidateOwnerRef(type,id)` 指向作用域内稳定 owner。角色专属的 policy/compiler/writer 才拥有候选语义。通用提交模块不得依赖 Designer facade、角色 prompt 或具体 Compiler 合同，内部 MCP adapter 也只能做参数规范化和端口调用，不能复制校验或直接写业务表。

Designer 相关 collaborator 必须保持以下单向依赖：

```text
Designer facade
  ├── Coordinator
  ├── OutputCodec
  └── StatusProjector

Coordinator -> candidate/application ports -> server policy/compiler/writer
OutputCodec -> frozen contract DTO / serialization
StatusProjector -> persisted read snapshots
```

- Designer facade 持有用户命令编排、会话阶段和事务边界，可以调用上述 collaborator；任何新 collaborator 都不得保存 facade 引用、反向回调 facade，或通过事件监听绕回 facade 形成循环依赖。
- `Coordinator` 只协调一次角色运行所需的冻结输入、候选 run 与明确结果，不承担输出格式解析、状态展示拼装或角色专属 DB mutation。
- `OutputCodec` 只做冻结合同的机械编码、解析与有界错误归一化；不得调用 OpenCode、Mapper、candidate writer 或 Designer facade。
- `StatusProjector` 只从持久化状态生成只读投影；不得启动 Session、提交 candidate、推进生命周期或用缺失值推断执行事实。
- policy 必须是纯业务裁决，compiler 必须是确定性转换，accepted writer 必须是 DB-only 写入；三者不得相信模型的成功声明，也不得把 MCP transport 状态当作业务接受结果。
- 候选远端终止证明必须由窄的共享 `CandidateSessionTerminationProof` 闭集持有；角色 orchestrator、通用 runtime guard 与 Designer host 只能共同依赖该 policy，通用 guard 不得反向依赖具体角色 orchestrator。验收候选的轮询结果分派只通过 `DesignerAcceptanceCandidateWorkflow.Port` 的窄命令/结果函数接回既有 Designer 状态机，workflow 不得持有完整 `DesignerSessionService` facade；外部 I/O 后的 proof CAS 由独立 `AcceptanceCandidateProofService` 短事务负责。候选 `ACCEPTED/WAITING_INPUT/CLOSED` 与远端停止是两个状态轴，owner 版本只允许 accepted writer、单个 `DISCONNECTED` 恢复投影、proof 投影及 `SERVER_COMPILING/serverCompiled` 的精确已知步数，不得使用 `>=` 接受无关 mutation。
- internal run 创建前的 managed-binding 拒绝不得用 owner 已进入 `RUNNING` 冒充 run 已打开，也不得直接失败或切 legacy；应用动作只在旧 remote 得到 ACK/ALREADY_ABSENT 后启动 fresh legacy，未确认时持久化同 compilation/Session 的 `DISCONNECTED` 恢复点，Monitor 只重试 abort，不补发 prompt 或虚构 run。
- Acceptance 的 Legacy handoff、internal launch、prompt dispatch 与 termination 必须各自拥有窄状态机和持久化 ledger。所有远端创建输入及一次性 credential 在 I/O 前冻结，回读 remote 由请求摘要和 binding 证明；创建结果不确定只进入 cleanup。run open 与 settlement certificate 同事务，提示的模型调用消耗/可能派发在 HTTP 前不可逆落库。取消、需求替换和首次提示失败只能通过唯一 typed termination intent 取得停止权威；父状态、终端 launch 与 intent 完成同事务，后到用户动作提升既有 ready intent 而不是建立第二条终止 saga。

七类 `INTERNAL_MCP` 候选不设提交次数上限（V69）；持久化提交计数与幂等/版本守卫继续有效。每个 policy 必须区分 `CORRECTABLE / HUMAN_REQUIRED / SECURITY`：安全的 JSON/字段形状、候选拥有的语义遗漏和闭集引用错误保持 `OPEN / REJECTED` 并返回有界 `code / pointer / detail / allowedValues`；模型明确请求真实输入、路径/权限/身份/执行权威或服务端运行冲突才可进入人工/终止边界。只有 `PACKAGE_DESIGN_V1` 支持显式 Markdown fallback；非 MCP 历史兼容运行继续使用原有各角色格式修复预算。Reviewer 的 Legacy/MCP 入口只能做 transport 解码，不能另行过滤 finding、渲染 Markdown 或计算业务接受结果；任一 finding 证据失败必须使整份候选无结果。Convention 的 Legacy/MCP 入口同样只能调用 `ProjectConventionCompilation`，模型只可选择冻结证据 ID，安全 argv、路径、Markdown、内容哈希与应用边界属于服务端；accepted writer 只能从 DB 冻结事实写不可变结果，Session 停止证明落定前不能推进 owner。Judge 的 Legacy/MCP 入口只能调用 `JudgeDecisionCompilation`，评审批次、角色、证据目录、稳定 ID、状态与跨角色聚合属于服务端；accepted writer 只可保存当前冻结批次的规范结果，正向停止证明前不得完成 Judge。滚动计划的候选/手工入口必须共用 `RollingPackagePlanCompilation`。唯一解、非枚举、安全或路径问题必须在 candidate 层之外由服务端直接处理并失败关闭，不能为追求重试率而扩大模型权限。

候选 profile 的 MCP 权限只允许精确私有 `submit_candidate`，不允许用户 MCP；Decomposer、Reviewer 与 Convention 另外保留只读仓库证据工具，验收闭集选择保持零内置工具。Router 继续零工具；Judge Candidate 使用精确私有提交工具，Legacy Judge 不获得该权限。外部 `auto/http` 通过新 Session 使用 `IN_PROCESS_LEGACY`，不能依赖受管 runtime 的私有凭据或 generation。

Feature flag 只能位于“是否打开新 run”的应用决策点，不能包裹持久化 adapter、恢复 reader 或通用提交 Bean。这样关闭功能后仍能恢复和收束已存在 run，也避免应用重启把持久化协议变成不可读取状态。

V49 的 owner/scope 守卫必须在复制 V47/V48 历史 run 之前就生效；迁移不得把旧库中跨作用域的 owner 静默规范化。任一历史行不匹配必须使 V49 整体失败并保留可恢复的 V48 数据。Owner 删除 trigger 只负责数据库内的同一事务级联；现有七种真实 owner 表均必须有回归，独立 accepted-result 级联只适用于 `PACKAGE_DESIGN_V1`，不得为其他 kind 虚构结果表。

度量模型必须把 `candidateSessions` 与 `candidateSubmissions` 定义为两个非负独立计数，并与 `modelCalls` 分开采集；禁止从任一计数推导另一项。API/资格报告保留精确的 0，StatusProjector/界面可以隐藏 0 值，但不得把隐藏后的缺省字段当作未知或失败。

## Task experience responsibilities (0.3.28)

`TaskJudgeApprovalService` owns versioned local human review acceptance and checkpoint handoff,
with `TaskJudgeApprovalMapper` storing separate immutable evidence. It never mutates Judge verdicts.
`TaskLifecycleTopology` owns Task transitions and remains registered/audited by `LifecycleRegistry`,
following the existing candidate-topology extraction pattern. `InsightSql/InsightPageMapper` own
shared filtered facts and aggregates. `OpenCodeToolInventory` owns runtime discovery and projection;
`McpToolCatalogReader` owns bounded MCP transport/schema discovery, with no task lifecycle effects.
Frozen history projects persisted decisions through `DesignerQuestionSupport` and reuses the same
frontend discussion card as active requirements rather than interpreting Markdown as questions.

### SQLite 并发回归环境

涉及多个数据库连接的队列准入、租约交接和后续启动，应使用独立文件型 SQLite，启用与生产一致的 `foreign_keys=on`、`busy_timeout=5000` 和 `journal_mode=WAL`。共享内存 `cache=shared` 的表锁行为不同，不能据此判断生产 WAL 下的读写并发。`DirectWorkspaceProductionForeignKeyIntegrationTest` 验证人工/自动交接同时触发时只准入一个 FIFO 等待者，只建立一个 Attempt/Session，并且只释放一次旧租约。

Story accounting is owned by `StoryAccountingCoordinator` and `StoryBindingService`; `StoryAccountingActivityService` owns the bounded live-output/acknowledgement read model and `StoryAccountingClock` unions statistics waits for business budgets, independently of Task/Designer error transitions. Native command transport and identity filtering remain in runtime collaborators; the orchestration facades do not parse command replies or implement accounting retries.

`OpenCodeSessionCommandGate` coordinates native statistics requests and ordinary abort for one remote, with identity-specific release on explicit cancellation. `StoryAccountingRetryPolicy` shares the authoritative eligibility decision between activity projection and the transactional retry claim; retry endpoints never overwrite historical call rows.
