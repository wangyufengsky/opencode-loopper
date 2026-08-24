# OpenCode Loopper design contract

The UI is a desktop-first developer console. Figma is the visual source of
truth; Vue components must expose the same states and terminology as the Figma
component variants.

Figma source: [OpenCode Loopper Control Plane](https://www.figma.com/design/dEUMnufilqNivuyK3vgyIT)

The file contains 43 variables in four collections, six text styles, three
effects, 18 reusable components, six desktop screens, and dedicated Session
warning / Task-terminal boards. Vue uses Element Plus for mature interaction
primitives and keeps the Figma tokens and error hierarchy in local components.

## Typography

- Product UI: `Inter`, with the platform sans-serif stack as fallback.
- Logs and code: `JetBrains Mono`, with the platform monospace stack as fallback.
- Main frames: 1440 px and 1280 px. Mobile is outside v1 acceptance.

## Dark-first tokens

| Token | Value | Purpose |
| --- | --- | --- |
| `--color-bg-canvas` | `#070B14` | application background |
| `--color-bg-surface` | `#0D1424` | navigation and base cards |
| `--color-bg-elevated` | `#121C30` | dialogs and selected panels |
| `--color-border-default` | `#21304B` | structural border |
| `--color-text-primary` | `#E6EDF8` | primary content |
| `--color-text-secondary` | `#9AA8BD` | secondary content |
| `--color-action-primary` | `#3B82F6` | primary action |
| `--color-accent-cyan` | `#22D3EE` | active execution and streams |
| `--color-accent-ai` | `#8B5CF6` | model and AI metadata |
| `--color-success` | `#22C55E` | deterministic success |
| `--color-session-warning` | `#F59E0B` | recoverable Session failure |
| `--color-task-danger` | `#EF4444` | terminal Task failure only |

Spacing follows a 4 px base grid. Use radii 6/10/14 px for controls, cards and
dialogs. Glows are reserved for active execution and must not reduce text
contrast.

## Required components

- App navigation and page header
- Button, input, select, tabs, dialog and status badge
- Project card and Task card
- Stage rail and Attempt timeline
- Session error card (`recoverable`, amber)
- Task fatal banner (`terminal`, red)
- Log viewer, Diff viewer and evidence panel
- Approval dialog and empty/loading states

## Browser history fallback

The packaged Spring application forwards every extensionless Vue history path,
including an unknown multi-segment path, to `index.html`; Vue Router then applies
its own not-found redirect. The catch-all must not turn missing `/api`,
`/actuator`, `/assets`, or file-extension URLs into HTML, so backend and static
resource errors remain observable as HTTP 404 responses.

## Error presentation invariant

1. Field errors remain inline and do not change runtime state.
2. Verification failures create a bounded, immutable Attempt handoff for the
   next fresh Session. Repeated reliable fingerprints stop at `WAITING_INPUT`.
3. Session failures close only the current Session and visibly announce that a
   fresh Session will continue the Loop.
4. Task failures stop scheduling and use the red terminal treatment.

## 前端文案与可读性

- 页面采用中文优先的极简文案。标题、状态标签或操作本身已经能表达含义时，不再追加重复的说明段落；“全自动”等明确模式只展示一个状态标签，只有阻断或需要用户选择时才补充原因和动作。
- REST、SQLite、路由参数和选择控件 `value` 保留稳定英文枚举码，普通页面不得直接展示 `QUESTIONING_PACKAGE`、`MANUAL_OVERRIDE`、`ATTEMPT_LIMIT_EXHAUSTED` 等内部值。所有状态、角色、流程、验证器和错误码统一通过 `frontend/src/utils/displayLabels.ts` 转为中文；未知值使用安全的中文兜底，不把原始协议码回显给用户。
- 错误提示使用中文说明“发生了什么”和可执行的下一步。错误详情、消息提示、卡片、表格和工具提示遵守同一规则；原始命令输出只允许出现在用户主动展开的审计日志中。
- 普通页面不展示 Task、Designer Session、Session、Attempt、Draft、Work Package、Criterion 等内部记录 ID，也不展示外部 Session ID。列表和时间线使用名称、顺序、时间和中文状态区分记录；ID 仅可存在于 URL、请求参数、组件 key 和服务端审计数据中。
- 项目登记卡片在桌面宽度最多两列，路径、描述、统计和操作必须允许换行；不得用 `nowrap` 把长项目名、路径或按钮挤在同一行。窄屏按单列自然排布。
- 产品名和不可翻译的技术名（OpenCode、Git、GitLab、Java、HTTP、JSON 等）可以保留；角色使用像同事的中文称谓：需求分析师、任务规划师、设计师、规范工程师、评审员、验收工程师、开发工程师，以及需求评审员、风险评审员。协议和数据库英文角色码保持不变。

A Task in any active execution state, including `WAITING_INPUT`, `PREPARING`,
`RUNNING`, `VERIFYING`, `RETRY_WAIT`, `PAUSED`, and `JUDGING`, must expose a
confirmed cancel action. The server first projects `STOPPING`; the page keeps
polling and offers **重试停止** until implementation/Judge Sessions and managed
verifiers are confirmed terminal. Only then may it show `CANCELLED`. Cancellation
retains the execution directory, branch, and evidence instead of implying rollback.

A Task in `QUEUED` must likewise expose a confirmed **取消任务** action on Task
detail. The confirmation explains that only the waiting queue row is cancelled:
the current writer and its workspace lease remain untouched. The server-authoritative
result passes through a normally short `STOPPING` confirmation before `CANCELLED`,
after which the existing Task-list archive and permanent-delete
flow becomes available only when the Task no longer owns an active workspace lease.
A newly confirmed Task is `PENDING_START`. It must show **开始执行** and a separate
confirmed **取消任务** action, and its summary must say that confirmation has not
created a queue row, acquired a write lease, allocated an execution directory, or
switched a Git branch. Cancelling this state changes only the Task to `CANCELLED`.
Clicking **开始执行** is the single execution request: the server may move through
`QUEUED`, `PREPARING`, and the transient `READY` state into `RUNNING` without a
second click. The no-runtime cancellation still uses the same persisted stop intent,
but normally confirms `CANCELLED` in that request. If `READY` is observed during that continuation, the UI shows that
automatic startup is in progress and may retain the confirmed cancel action, but
does not render another Start button.
While a Task remains `QUEUED`, detail renders a server-backed **当前在排谁** card with
the holder title/link, Task state, archive flag, lease state, queue position, and stable
release blocker. **重新检查并释放** posts only the waiter ID to the local-UI endpoint;
the server locates the holder and returns the latest queue projection. A 409 keeps the
card visible and renders its concrete dirty-workspace, unconfirmed-writer, fingerprint,
unavailable-root, or unsafe-branch reason. A successful action refreshes Task and queue
state and may continue the already-requested Task through preparation into model
execution; it never creates a second execution request. Archive failures caused by an active lease keep
the terminal Task in the active list and surface `TASK_ARCHIVE_WORKSPACE_LEASE_ACTIVE`
instead of hiding it.

When `waitingReasonCode` is `SOURCE_BRANCH_WORKSPACE_DIRTY`, Task detail opens a
non-dismissible **发现未提交文件** dialog backed by the server's current Git
snapshot. It lists every path and requires one explicit `提交 / stash / 移除`
decision per file before **重新检查并继续** is enabled. Removal has an additional
destructive confirmation. The only alternate exit is the confirmed **取消任务并保留文件**
action, which must leave local files unchanged, interrupt the active Execution Cycle,
and produce `CANCELLED` rather than borrowing the Task-failure path. Snapshot conflicts refresh the
authoritative list; the browser never assumes cleanup or branch creation succeeded.
The `SOURCE_BRANCH_WORKSPACE_DIRTY` error event remains immutable audit history,
but its active red alert is rendered only while the Task is still in
`WAITING_INPUT` with that exact `waitingReasonCode`. Once preparation reaches
`READY` or execution continues, the stale alert disappears without deleting evidence.

The Runtime failure card exposes **启动 OpenCode 并检查连接** only after an Auto
launch has failed. The button stays loading while the server performs the bounded
launch and health check. The UI announces success and replaces the failure card
only when the returned authoritative Runtime snapshot is online; process creation
or a frontend timer must never be presented as a successful connection.
Both explicit start and restart POSTs require the local-UI marker before the
server inspects or mutates Runtime ownership; the SPA sends that marker for both
actions.
The process card also displays the server snapshot's `loopperVersion`. The SPA
must not infer it from the frontend package or confuse it with the separately
reported OpenCode CLI `version`.
The Runtime page intentionally stays focused on the service summary, process
ownership, endpoint, model, and recovery actions. It does not render separate
native-capability or authorization-boundary cards; hiding those projections does
not weaken the server-owned capability selection or permission enforcement.

Task responses expose the current `waitingReasonCode` and authoritative
`loopRetryAvailable` projection. When the current wait reason is
`LOOP_STAGNATION_DETECTED` or `LOOP_FRESH_SESSION_REQUIRED`, Task detail explains
the stop and offers a confirmed **继续一轮** action. Historical errors never
enable that action. The browser does not infer progress or reset the streak
locally; the server records the override and returns the authoritative Task
state.

The overall design handoff states are `PENDING_HANDOFF`, `RUNNING`, `REVIEWING`,
`WAITING_INPUT`, `STOPPING`, `CANCELLED`, `COMPLETED`, and legacy-compatible `SESSION_ERROR`. Persisted
workflow phases include `DISCUSSING_REQUIREMENT`, `DECOMPOSING`,
`VALIDATING_DECOMPOSITION`, `QUESTIONING_PACKAGE`, `REVIEWING_PACKAGE`,
`AGGREGATING`, and `FINAL_REVIEW` alongside the machine-processing phases.
Normal human discussion/approval is `REVIEWING`, never `WAITING_INPUT`;
`WAITING_INPUT` means a real budget/model/validation recovery boundary.
`activeWorkPackageId`, `discussionScope`, and `discussionRevision` identify the
only scope/version that can accept a message or approval.

Before requirement confirmation, the page shows the provisional task profile,
confidence, evidence, workflow preview, Role Pack, execution strategy and test policy.
The Projects card also shows the latest persisted stack-profile summary without triggering
filesystem analysis: Java, Node, Python, mixed, unanalysed, partial, or failed, plus component
count and analysis time. Opening a project stack detail uses the dedicated profile endpoint.
The **AI 更新 Loopper 公约** action is the only manual refresh entry: its progress text first
states that the stack is being refreshed, then that AI is regenerating the managed sections.
`PARTIAL` is a visible review warning, while `FAILED` prevents proposal generation.
Task intent, artifact, workflow, execution and test-policy enum values are rendered as
Chinese labels in the user-facing **任务设置** summary and edit controls, while REST/SQLite continue
to use the stable English enum codes. The DTO exposes `decisionState` as
`ROUTING / NEEDS_CONFIRMATION / CONFIRMED / FROZEN`, a server-computed
`confirmationReady`, and an optional `previousConfirmedChoice`. Every single-package,
large-package, report, document, or conversion start action uses `confirmationReady`; the
browser must never derive readiness from `!decisionRequired`.
For software tasks the DTO also exposes the manifest fingerprint, selected component keys,
candidate components, and a server-owned `componentSelectionRequired`. A component selector is
rendered only for an ambiguous multi-stack result; a single-family project keeps the compact
summary. The selector submits stable component keys with the existing profile version. The server
validates project ownership and staleness, so the browser refreshes on a profile-fingerprint
conflict instead of retrying cached choices. Empty or failed repository evidence is shown as
generic/needs-confirmation and is never relabelled as Java by the UI.
While a complete requirement snapshot is being rerouted, the page polls every 1.2 seconds,
shows **任务设置识别中**, and keeps design actions disabled even when the Designer Session
itself is `REVIEWING`. An equivalent reroute safely carries forward the persisted manual
choice and refreshes technology, Role Pack, and test policy. A changed intent, primary
artifact, workflow mode, mutation mode, or new safety conflict displays **原设置** and
**本次识别结果** with explicit **继续使用原设置**, **使用本次识别结果**, and **修改设置**
actions. An initial ambiguous recognition without a previous choice instead shows **请确认**
with **确认并继续** and **修改设置**. Edit controls are hidden until the user selects
**修改设置**; an unchanged confirmed selection is disabled in the browser and is also a
server-side no-op. Before any edit is written, the browser calls the read-only profile update
preview. When the target workflow changes, a warning names the target workflow and the only
committing action is **停止当前设计并重新开始**; cancelling preserves the current profile and
remote Session. Internal evidence codes such as `router-running` are not rendered. A click
concurrent with Router completion only refreshes the authoritative snapshot rather than
raising a red toast.
Confidence below 80 or conflicting evidence blocks ordinary-mode confirmation until a
versioned profile confirmation or override is saved. An explicitly authorized full-auto Session may accept
the Router's current intent and primary artifact as an `AUTO_RECOMMENDED` decision; this
cannot bypass unsafe-operation evidence, and a manual override remains available before
requirement confirmation.
Unsafe-operation evidence is derived from the requested mutation target, not from an isolated
publication verb. Publishing an in-process domain event, message, notification, signal, metric,
or pub/sub contract remains software-domain language in any natural word order. Publishing a
version, artifact, image, package, environment, GitHub Release, or an otherwise unqualified
publication remains fail-closed alongside commit, push, service control, deletion, and external
system writes.
Negation is evaluated within the operation clause, and an external-system reference is unsafe
only when it is the target of a requested mutation. Evidence wording such as “do not fabricate
external-system results” is not a write request. Likewise, “configurable” capabilities and
negative dependency constraints do not select `LOCAL_MAINTENANCE`; that route requires an
explicit configuration/dependency maintenance action.
Read-only routing is likewise based on a task-level review, inspection, or diagnosis action.
Review checkpoints, read-only accessors, and acceptance-review vocabulary inside a writable
requirement snapshot do not select the Reviewer or create a mixed-mutation conflict. An explicit
task-level request to review and then modify code remains a mixed request that requires confirmation.
`ROUTING_PENDING/RUNNING` is backed by a persisted Router run;
the request starter and monitor cannot own the same run concurrently. The raw requirement
and every completed Designer snapshot are classified separately. Starting a replacement
discussion or changing to another workflow must first confirm the obsolete remote Session has
stopped; an abort failure keeps the current choice and Session unchanged and does not dispatch
a replacement. A newly submitted requirement remains on the active design page throughout
routing and continues automatically when classification completes; it must not require a
history-page restore. Confirmation freezes the project-profile id, manifest fingerprint,
component keys, and task profile together. Later project analysis cannot update existing
Task/Stage/Recovery displays. The progress rail is
template-driven: omitted Decomposer/package/Compiler steps are not displayed.
Historical `BLOCKED + TASK_PROFILE_DECISION_REQUIRED` rows use one bounded `RESUME` action,
then apply the same auto-recommended profile decision on the next monitor tick. A manual
override also resumes that obsolete blocker immediately without a second authorization.

Simple Markdown/DOCX and one-shot tabular conversion still require the ordinary
Designer question, then compile one implicit `WP-1` and enter `FINAL_REVIEW` without an
AI Decomposer or repeated package Designer. Review Gate shows Markdown content, a DOCX
structure summary, or source/target table conversion rules. No target file exists until
the confirmed Task is explicitly started. A large document must expose 2–6 `##` sections;
the server preserves those ordered structured fragments and aggregates the final plan
deterministically. Simple maintenance likewise uses implicit `WP-1`, but Review Gate only
becomes confirmable when exact relative targets and a no-delete `GIT_DIFF` are present.
Read-only review/research ends at a report
card with file/line/hash freshness and a “convert to design” action; that action creates
only a linked writable-design conversation and never directly creates a Task.

For full package workflows, each work package persists its own Role Pack, technology list,
execution strategy and test policy. The package Designer and Compiler prompts use that
package snapshot, so a mixed Java/Vue/Python request does not inherit one global
Java/Maven contract for every package. Confirmation copies those values to every Stage;
the task rail displays the frozen Role Pack and test policy used by implementation.
Historical or interrupted rows whose execution strategy or test policy is still null are
treated as an incomplete snapshot: the read-only rail remains available without enum
conversion errors, and the next authoritative package-role use refreezes all required fields.
The implicit `WP-1` of `DIRECT_SOFTWARE_DESIGN` is the complete frozen software requirement,
so its Role Pack, technology family and test policy inherit the confirmed task profile. Free-form
requirement prose such as “no mock dependency” or “configuration remains unchanged” cannot
downgrade it to local maintenance or clear its software family. A previously frozen direct-software
row with a non-software Role Pack is an inconsistent snapshot and is repaired on the next
authoritative package-role use, including manual recompilation. Package-local document or
configuration specialization is reserved for explicit `FULL_PACKAGE_DESIGN` packages.
Role Pack version `2026-08-dynamic-v5` first normalizes aliases into Java, Python, Node,
and Other families. JavaScript/TypeScript cannot trigger Java, multiple labels from the
same family remain one stack, real cross-family work selects the mixed pack, and an explicit
unknown single stack selects the generic software pack. Package-local matching uses token
boundaries, so a Java domain type containing `Node` does not create a Node family. Each Compiler-capable pack receives
its own stack-native planning example; document, table, and read-only report packs explicitly
state their server-owned or Reviewer bypass instead of inheriting a software fallback example.
For software acceptance, negative/no-change rows never create test capabilities. Positive deliverables,
stage relationships and acceptance constraints form the capability-to-scenario graph; qualified test
names are reduced to their subject symbols before competitive semantic matching. A business criterion
binds to at most one focused test through a title-weighted unique winner, while an independently required
target without its own scenario is shown exactly once as a separate source-backed machine criterion.
When exactly one focused test is declared by a positive package deliverable, stage text such as
“the same/this focused test class” may refer back to it and bind the scenarios named by that stage.
With multiple declared tests that reference remains ambiguous and must not be guessed. Regression-only
“remain passing” and test-style comparison clauses create no business-scenario coverage.
The model only advises grouping and preferences; if that advice cannot be normalized it is discarded and
the server compiles from the frozen fact/capability graph. The server alone derives `COMPILED` or a concrete
`DESIGN_INCOMPLETE` gap, and test-only Java paths remain `JAVA_TEST_ONLY`.

Before a requirement revision exists, Designer must call `question` once with
1–3 choice questions. In ordinary software mode it may then finish with empty text:
the server assembles the authoritative Markdown snapshot verbatim from requirement-scope
user messages and persisted final answers, and ignores free-form model text. In large-task
mode Designer still returns the complete replacement Markdown requirement predesign.
The question card blocks ordinary chat until answered and offers one-click
selection of all recommended choices. Follow-up messages repeat the active mode's contract but
do not invoke Decomposer. Software sessions expose a default-off **大型任务** switch
while the profile remains provisional. The default `DIRECT_SOFTWARE_DESIGN` path
freezes the latest snapshot, deterministically creates one `DIRECT_DESIGN / WP-1`,
and proceeds through package Designer and Compiler without a Decomposer Session,
package question card, or package-acceptance button. Initial WP-1 design, feedback revisions,
and final-review redesigns directly produce a replacement design. That direct package accepts 1–6 Stages. Only explicit
`FULL_PACKAGE_DESIGN` starts the independent
Task Decomposer and produces 2–6 vertical packages, with 1–3 Stages per package and
at most 18 total. Every source requirement segment must
be assigned to a global constraint or at least one package. Multiple project
roots, more than six packages, or independent release boundaries produce
`MULTI_TASK_REQUIRED`; the product waits for the user and does not create child
Tasks. `NEEDS_INPUT` likewise displays an explicit new requirement input path. If
the direct Compiler cannot safely fit the design into 1–6 Stages, it stops once with
`LARGE_TASK_MODE_REQUIRED`; the user may explicitly reopen the requirement in large
mode, but the server and full-auto mode never enable it automatically. Final overall
confirmation remains a separate human gate for both modes.

Designer may optionally authorize a per-Session full-auto mode. It is disabled
by default and every enable or re-enable requires a local-UI risk confirmation.
The persisted `DISABLED / ACTIVE / BLOCKED / COMPLETED` state advances at most
one authoritative action per monitor tick and survives process restarts. It may
answer pending design questions from explicitly recommended options (falling
back to the first option), confirm the requirement, accept only the current
deterministically validated package revision, confirm the final draft, and call
the ordinary Task Start boundary. These actions remain visible as System
messages and question decisions use `AUTO_RECOMMENDED`; manual decisions use
`MANUAL`.

Full-auto authorization ends once Task Start has been requested. It must never
answer execution-time questions, grant dangerous permissions, choose recovery,
accept execution results, commit, push, merge, or publish. Disabling stops only
future automatic actions. A low-confidence/conflicting task profile is resolved as a
separate, persisted auto-recommended action when full-auto is authorized; unsafe-operation
evidence remains fail-closed. Budget exhaustion, multi-task requirements, design or
validation failures, missing option data, optimistic conflicts, and Task Start
errors move the mode to `BLOCKED` without hot retry; the operator must disable
it or explicitly authorize it again after handling the cause.

Packages then run strictly serially. Each package reuses its healthy interactive
read-only Designer conversation across revisions and reconstructs a fresh one
from persisted snapshots/decisions after remote loss; each candidate uses a fresh
read-only LoopSpec Compiler with the configured model. Designer receives
the original requirement, frozen decomposition, current package, global
constraints, and bounded prerequisite handoffs, then emits at most 24 KiB UTF-8
of complete Markdown. Compiler emits a compact semantic plan plus
`DS-Lxxx` sources and a handoff summary of at most 4 KiB; the server compiles the
package fragment. Direct software allows 1–6 Stages; each large-task package remains
limited to 1–3. Decomposer and historical semantic-Compiler extraction failures receive at most
two format repairs, and field/verifier/traceability/coverage failures receive at most two semantic
patch repairs. Current v5 acceptance grouping/preferences are optional advice: an unreadable advice
object is discarded and the frozen server graph compiles without a repair Session;
gaps receive one full redesign of that package only. Large-task initial design and every
human revision must ask questions first; direct WP-1 never asks again. Both return a complete snapshot. A valid
candidate enters `REVIEWING` and the next package stays locked until the user
accepts that exact revision; a failed replacement retains the last valid
candidate. Each package allows at most five human revisions. The complete
requirement revision has a shared hard ceiling of 96 model calls, but package
content retry counters remain independent. Draft concurrency, exhausted budgets, and
unassignable aggregation conflicts enter `WAITING_INPUT` without synchronizing
the draft or creating a Task.
Each applicable historical Compiler repair runs in a new built-in-tools-disabled Session after best-effort aborting the
original repository-reading Session; configured MCP tools remain available without changing
repository-write or command boundaries. Format repair returns one complete compact object; semantic
repair returns only the `AI_SEMANTIC_PATCH_V1` patch envelope. A current response missing
`outcome` is valid for v5 acceptance advice but remains a format failure for historical semantic
contracts and cannot fall into the legacy `status` parser, while an
invalid patch response cannot overwrite the last valid semantic snapshot.
Compiler's compact Stage field is `evidence`, never the final DTO field `verifiers`. A uniquely
reversible `/stages/<n>/verifiers...` repair pointer is normalized to `evidence`, and `replace`
of an absent model-owned object leaf is treated as `add`; both remain audited and must pass the
full contract again. Every `JAVA_PRODUCTION` Stage retains a focused Maven/Gradle TEST even when
its acceptance is Judge-only; full-suite/build evidence cannot replace that gate, so wiring-only
Java stages are merged with a related tested stage or carry a focused gate with `covers:[]`.

OpenCode native agents are capability-discovered for server-side role selection,
but the compact Runtime page does not expose that diagnostic projection and the
Designer does not delegate to the native plan agent in this release. Designer
remains the human-readable Markdown role;
Decomposer and Compiler remain the machine-contract roles; the Validator remains
server-owned and deterministic. This preserves Review Gate labels, persistence,
repair budgets, source mapping, and the rule that raw machine JSON is not a chat
message.

Designer and Compiler inspect an immutable pre-execution repository baseline.
For a later package, a predecessor whose package state is `APPROVED` has passed
its Designer/Compiler/Validator workflow but has intentionally not written its
production files yet. Loopper injects that predecessor's frozen objective,
Compiler summary, and bounded handoff contract into both prompts. Because the
single confirmed Task executes package Stages in dependency order, current
absence of such a deliverable is not `MISSING_SCOPE` and must not trigger a
redesign. A semantic gap remains valid only when the required contract is absent
from both the current frozen design and the predecessor contract/handoff.

New software designs use fixed controlled Markdown sections: target/scope,
impact/delivery, acceptance scenarios, optional human review, acceptance constraints,
and stage dependencies. The acceptance table records scenario, precondition/trigger,
action, observable result, and invariant. Designer must not emit internal WP/AC/DS-L
ids, LoopSpec JSON, or executable argv; it names repository-native test classes or
targets and independence constraints, while the server creates safe capabilities.
This is a DMN-inspired decision-table input, not a second DMN runtime: rows stay readable
to the designer, lower directly to EARS criteria, and can be reviewed as
Given/When/Then/And scenarios without maintaining three competing sources of truth.
The controlled section names form one replacement document: each required section
must occur exactly once and an optional review section may occur at most once. A response
that repeats a complete design or omits part of the controlled shape is rejected for a
fresh design instead of merging duplicate tables. Negative framework constraints such as
`无 @SpringBootTest` cannot become executable test capabilities.

Before Compiler binding, the server persists immutable DesignFact and capability
snapshots. The UI renders an **验收意图识别** card with fact/scenario totals and
machine, machine+human, human-only, and unresolved counts. Expanded rows show only
human-readable scenario/capability names and Chinese issues—never internal indexes,
protocol enums, or raw JSON. Unresolved items remain blocking at the server Review
Gate; the client cannot infer confirmation eligibility from this card.

Decomposer and package Compiler use one persisted compact semantic turn per
candidate. Decomposer returns business packages and RQ coverage by index;
structured Markdown requirements are grouped by level-two business section so
presentation-only headings and metadata do not become separate coverage work;
Compiler plans 1–6 Stages for direct software or 1–3 for each large-task package and
maps each observable criterion to stable `DS-Lxxx`
Designer source references plus deterministic/Judge evidence intentions. The
server derives statuses, stable IDs, reverse references, exact excerpts,
`criterionIds`, safe test targets and complete `VerifierSpec` objects, then runs
the normal LoopSpec v2 execution assessment before freezing, so shell
launchers, non-behavior mappings and invalid Java/runtime evidence are repaired
in the evidence-mapping turn. That assessment reuses runtime path-policy
semantics: malformed globs and an `allowedPaths` rule entirely shadowed by one
`forbiddenPaths` rule must consume the bounded planning-repair path instead of
being frozen. Broad allow rules with narrower exclusions remain valid. Draft
persistence and confirmation repeat the check so imported or historical input
cannot create a Task, Attempt, or writable Session with an unsatisfiable path
contract. Before that assessment, the server deterministically
numbers criteria as `<workPackageId>-AC-n`, restores an exact source slice when
the model's excerpt has one unique whitespace/Markdown-format-insensitive match,
and copies focused TEST `criterionIds`/`testTargets` from the corresponding
evidence mapping into the matching verifier command. Designer lines that explicitly
name focused Maven/Gradle commands or unit-test targets are also injected as a
bounded mandatory-evidence list in the initial and repair prompts. When a focused
command contains an explicit `-Dtest`, `-Dit.test`, or `--tests` selector, Loopper
extracts those targets without executing a shell; if a Java Stage has exactly one
matching focused TEST, the server fills omitted duplicate `testCommand`/`testTargets`
fields and can materialize the equivalent verifier blueprint before validation.
Loopper never invents a test from prose, a broad full-suite command, or an ambiguous
set of focused tests. Ambiguous or absent source matches and semantically incomplete
test evidence still fail the authoritative validation.

Stage grouping preserves the non-acceptance facts referenced by each group. Those
positive deliverable/scope facts produce that Stage's path and deliverable boundary;
natural-language scope descriptions are not treated as repository path rules merely
because they contain a slash. A `JAVA_PRODUCTION` Stage that contains only Judge
criteria still receives a `covers:[]` focused-test gate when the Stage explicitly
references one package test or the package exposes exactly one focused capability.
If that gate is absent or ambiguous, deterministic planning returns
`DESIGN_INCOMPLETE` before final LoopSpec validation or any model repair. Final LoopSpec
validation accepts that empty mapping only when every criterion in the Java production
Stage is Judge-only; it remains blocking execution evidence without pretending to cover
a subjective criterion. Any machine criterion still requires explicit test mapping.

Only observable business outcomes become acceptance criteria. If a weak Compiler
duplicates code style, source/annotation/assembly shape, build/test success, or
delivery hygiene as criteria without an explicit focused-test mapping, Loopper
keeps those facts in the frozen design but deterministically removes the redundant
criteria and remaps evidence. A sole positive focused-test deliverable owns otherwise
unmapped package business scenarios; named regression gates remain independently required
with no scenario coverage. Multiple positive deliverable candidates remain ambiguous unless
the frozen design names the relationship.
Before any semantic repair, Loopper reports all deterministic failures together
with exact JSON Pointers so one bounded patch can fix the complete set. Source-text
search commands remain invalid behavior `SELF_CHECK` evidence.
A valid semantic plan is
compiled directly by the server into the historical final Decomposition or
CompiledPackage shape; no final-JSON model prompt is sent. V30 persists the
semantic snapshot, independent format/semantic repair counters and server-compiled
flag. Historical active final-generation rows with a planning snapshot are
server-compiled during recovery; only rows without one retain the old final path.
All machine roles share one bounded extractor. Native structured data and exact
role markers remain preferred, followed by `json`/untyped fences, a complete
object embedded in short prose, and the whole response. It accepts only standard
JSON objects, deduplicates equivalent candidates, and rejects conflicting valid
candidates, arrays, incomplete JSON, comments, trailing commas, or ambiguous
normalization. Deterministic field/collection/enum/argv normalization proceeds
directly to the unchanged planning, coverage, dependency, execution, and final
envelope checks without consuming a planning/final repair. The UI shows one
ordinary `NORMALIZED` information item rather than an error or raw model dump.
No raw machine JSON is displayed as a chat message; the status strip exposes
planning, bounded format/semantic repair, and ordinary server-compilation notices.

The compact Decomposer/Compiler steps prefer stable OpenCode JSON Schemas;
the final Judge contract has its own schema. Legacy final schemas remain for
historical recovery only. Capability-unknown starts
optimistically in schema mode except for the verified OpenCode 1.18.12–1.18.18
stored-Schema decoder defect, which starts directly in marker compatibility
mode. Only explicit format rejection, typed
structured-output failure, or a completed response without structured data may
switch that exact step to the legacy marker contract, in one fresh read-only
role Session and within the same persisted repair/model-call budgets. Existing
active rows stay marker-compatible. Structured output remains hidden behind the
same deterministic validation and Review Gate; schema acceptance is not semantic
success.

The Decomposer, Compiler, and final Judge machine-response roles disable Thinking
only for a step using `JSON_SCHEMA`. On the managed DeepSeek runtime those Schema
prompts select Loopper's private `loopper-no-thinking` variant, because OpenCode's
schema transport requires a tool choice that DeepSeek Thinking rejects. A
`TEXT_MARKER` step, including its fresh fallback Session, retains configured
Thinking or the provider default while still passing through the same extraction
and deterministic validation. The Markdown Designer remains on the configured
model behavior, so the transport workaround does not silently reduce interactive
design or implementation quality.

On a Loopper-managed runtime those same three machine-response roles select the
private zero-temperature `loopper-structured` agent, capped at 24 agentic steps
and instructed to stop exploring after collecting sufficient evidence. Loopper
also enforces the same bound from message records rather than trusting the
OpenCode agent setting alone. Three consecutive calls with the same normalized
tool name and arguments trigger an immediate best-effort abort and at most one
persisted, built-in-tools-disabled finalizer Session for that role step. Configured MCP tools
remain available under the same additive permission rule. The finalizer uses
bounded deduplicated evidence, counts against the global model-call budget, and
does not consume format-repair budget; the 24-step cap remains the final safety
net. For Decomposer
and Compiler, OpenCode status `RETRY` is displayed as an in-progress remote state
and keeps the same Session; it must not increment the design transport-retry
counter. Interactive Designer uses the same transient rule: requirement,
work-package, and compatibility polling keep the original remote Session and
the workflow in `RUNNING`; auto mode remains `ACTIVE` until a true terminal
Designer state is persisted. The same provider-recovery rule applies to Judge,
Implementation, project-convention, publication-suggestion, and local-sync
Sessions: no new local execution row or Loopper retry budget is consumed, while
the existing operation timeout still applies. Even while OpenCode reports `busy`, Loopper reads the bounded machine
response transcript. If OpenCode rejects the stored Schema or reports a failed
`StructuredOutput` tool part, the workflow shows the existing format fallback
rather than a generic connection failure. A card may say the
role is stopped or waiting for input only after Loopper has attempted to abort
the corresponding remote Session.

Compiler is not expected to reverse-engineer backend DTOs from validation text.
Its planning, final-generation and bounded repair prompts contain the complete
contract appropriate to that step plus canonical envelopes. In particular, verifier,
command, criterion mapping, test target, runtime and design-gap fields retain
their object/array/null types. The deterministic validator still rejects any
unsupported or semantically invalid value; the richer prompt does not weaken or
bypass Review Gate.

After every package is explicitly accepted, the server deterministically
concatenates Stage fragments, raises only the minimum attempt/time limits,
validates the complete LoopSpec, and atomically updates Review Gate at the frozen
draft version. This dedicated aggregation mutation may replace a pre-design
placeholder `workPackageId`, but only when the result represents every frozen
package in dependency order; ordinary draft mutation continues to preserve the
exact mapping after aggregation. Re-aggregation canonicalizes the frozen decomposition constraints into
one traceable context section instead of appending duplicate sections. A successful deterministic aggregation advances the requirement's
optimistic draft checkpoint while leaving its frozen text and source message unchanged. Legacy aggregates
without that checkpoint may be adopted once only when their draft is exactly one version newer than the
Decomposer baseline and still contains every frozen package in order; any later external draft edit remains
`DESIGNER_DRAFT_CHANGED`. Reopening an accepted package first lists and then marks only its
transitive dependents `STALE`; unrelated accepted packages remain valid. Reopening from `FINAL_REVIEW`
atomically reactivates the same immutable requirement revision through `COMPLETED + RETRY -> ACTIVE`; an explicit
recompile then advances the reopened package through `REVIEWING + RETRY -> COMPILING`, so package
feedback, redesign or compiler retry cannot remain stranded behind the prior aggregate state. Review
Gate cannot be edited as a complete LoopSpec before `FINAL_REVIEW`. Human recovery
actions target decomposition or the current package; the old generic
compiler/redesign endpoints remain a compatibility alias.

Message origin comes from the persisted `actor` (`USER`, `DECOMPOSER`,
`DESIGNER`, `COMPILER`, `VALIDATOR`, or `SYSTEM`), never from role text
inference. The console renders user cards blue, Decomposer summaries indigo,
Designer Markdown purple, Compiler summaries/gaps cyan,
Validator results retain green/yellow/red item states inside one collapsed
**确定性校验** disclosure instead of producing an unbounded run of cards; expanding
it reveals each persisted result in order. Consecutive System notices render as one
full-width, collapsed **系统消息** disclosure even when their requirement revision or
requirement/work-package scope metadata changes. Its summary follows the same visual
structure as **需求讨论**, shows the persisted notice count, and expands to every
notice in chronological order. User, Designer, discussion, or Validator timeline
items start a new System group. Error groups use the same disclosure with an alert
state; the separate active workflow error banner remains visible.
A four-step bar shows requirement discussion,
package design, final confirmation, and task creation. The selectable package
rail exposes question/discussion/accept for the current package, **重新讨论** for
accepted packages, dependency reasons for locked packages, and the invalidating
upstream id for stale packages. The composer always names its overall-requirement
or `WP-N` scope. The top bar and thinking animation follow the authoritative
`workflowPhase`, `activeActor`, requirement revision, active package, package
retry counters, and shared model-call count. Compiler and Decomposer raw JSON is shown
only through the right-hand Review Gate; it is neither persisted as chat content
nor copied from SSE into the conversation. Page refresh restores cards and
workflow state from the server snapshot. A transient browser GET failure keeps
the page in bounded-backoff reconnect without fabricating model output or a
validation result. The shared browser API transport treats every successful
empty response, including `202 Accepted` and `204 No Content`, as a completed
void operation and refreshes the authoritative snapshot instead of attempting
to parse an absent JSON body. During package review, the right panel is read-only and shows
`同步中`, `已同步 Rn`, or `同步失败，保留上一版`; the structured editor is enabled
only after deterministic aggregation and round-trips every Stage
`workPackageId`, LoopSpec limit, model selection, Session policy, and
next-Attempt prompt template. Saving or confirming an aggregated package draft
must not flatten its Stage mapping; the server rejects removal, reordering, or
reassignment of an existing package mapping, and confirmation also verifies
that every approved package remains represented in dependency order.

The current-role card inside the message timeline polls `GET /activity` every 1.2 seconds and
replaces its body with only the latest safe fragment; it is not a separate top-level panel and
does not accumulate activity history. Interactive Designer content uses the same Markdown
presentation as persisted messages and may show bounded thinking, incremental output, and
ordinary/MCP tool calls with name, status, arguments, and output. Structured roles render only
the latest tool activity and their authoritative step; raw Router, Decomposer, Compiler,
Reviewer, repair, finalizer, or Judge JSON is never shown. Reconnect keeps that one latest
fragment until a newer authoritative observation arrives.

The same current-role card includes one compact numeric token window. Its total is the
server-persisted, provider-reported aggregate for every remote role Session in the current
Designer scope. The first observation establishes a silent baseline; each later positive
change briefly renders `+N` and immediately advances the total. The window adds no visible
budget, cost, quota, or explanatory copy, never overlays the activity content, never counts
time locally, and suppresses its burst animation under reduced-motion preferences.

After final design confirmation returns a Task ID, the page marks that navigation as committed,
clears the Designer workspace pointer and unsent input, skips the unsaved-design leave warning,
and opens Task detail. The left navigation's next **设计** visit therefore opens the new-design
page; the confirmed design remains available only as read-only history. Manual navigation with
dirty unsent or unconfirmed design still uses the leave warning.

**清理并重新开始** calls the local-UI stop endpoint before clearing browser state. While the
server is `STOPPING`, the composer and dispatch controls remain disabled. The page clears the
workspace only after the server reports both `CANCELLED` and archived; a partial remote-stop
failure keeps the current design visible, reports the failed count, and offers the same action
again. Repeated stop calls are idempotent.

Task detail groups Stage progress by `workPackageId` and displays the independent
attempt pool. Historical design restores the frozen requirement, Decomposer
summary, every package design/compilation summary, and the final Stage mapping.
The interface states explicitly that only one final Requirement/Risk Judge batch
runs after all packages pass.

The Task Session monitor header reuses the same numeric token window for the authoritative
Task-wide aggregate across implementation and Judge remotes. Selecting another Session does
not reset the total; changing Task scope does. An older or incomplete response cannot decrease
the rendered value or produce a negative delta.

New drafts use LoopSpec v2. Each Stage declares `implementationKind` and
observable `acceptanceCriteria`. Each criterion selects `verificationMode` as `MACHINE`,
`JUDGE`, or `BOTH`: machine modes require at least one server-classified
`BEHAVIOR` verifier mapped through `criterionIds`; Judge modes require an
explicit `judgeRubric`, and Judge-only criteria also require `judgeOnlyReason`
and cannot carry machine behavior coverage. Every Stage retains at least one
blocking deterministic verifier even when all of its criteria are Judge-only.
Review Gate renders machine coverage and planned final Judge review separately;
planned review is never labelled as executed or passed. Invalid planning
blocks Compiler synchronization, manual save, template publication, MCP
proposal and confirmation without an ignore action. Persisted v1 drafts show
**旧合同（兼容）** and keep their old behavior. Their schema version is immutable;
the operator can copy one to a new v2 draft and then complete its criteria.
The confirmed goal, context, and complete cross-Stage `JUDGE`/`BOTH` contract
must fit within 96 KiB UTF-8; the Review Gate reports an explicit field error
instead of allowing a prompt that cannot be reviewed safely. At runtime, both
pending final-review role prompts are preflighted as one batch before either
Judge row, read-only Session, or model call is created; local-UI double-review
retries use the same all-role boundary.

Live SSE delivery is presentation transport only. A page refresh, timeout,
client disconnect, or already-closed servlet `AsyncContext` removes that one
subscription and leaves the Designer/Task/OpenCode lifecycle unchanged. Task
pages reconnect with `Last-Event-ID` replay, while Designer pages reload the
latest persisted snapshot. These transport failures must never surface as
`SESSION_RUNTIME_ERROR` or trigger remote Session cleanup.

Designer work is also discoverable without browser-local state.
The project projection reports confirmed `taskCount` and
`openDesignerSessionCount` separately. The Designer start page is reserved for
creating a new design and never renders the recoverable-session collection.
The dedicated **历史设计** page lists the latest persisted Session for every
draft, with project/status/archive filters, newest/oldest sorting, and bounded
cards whose action row never leaves the viewport. For non-confirmed drafts,
**继续** reloads the exact scope, **修改** opens the overall-requirement edit
boundary (including the existing downstream-invalidation confirmation), and
recoverable archive only removes an item from active counts without deleting
snapshots. A confirmed draft is joined to its Task, remains visible as read-only
history, and offers only the complete Task design-history view—never continue,
modify, or archive. A browser
workspace pointer is only a resume hint: transient network or server-restart
failures must retain it, while malformed, archived, confirmed, or missing records
may be discarded. Selecting a persisted entry reloads the authoritative Session
and draft before reconnecting live transport; it never manufactures a Task.

V27 stores every requirement/package turn as a complete Markdown snapshot with
its decision log, mandatory-question state, candidate compilation reference,
approval revision, and invalidation reason. Refresh and process restart restore
the exact question, scope, package rail, last valid candidate, and available
confirmation action. Historical unconfirmed packages that were previously
`COMPLETED` migrate to `REVIEWING`; already confirmed drafts and created Tasks
are unchanged.

After a mandatory Designer question is answered, it remains part of the
authoritative discussion projection instead of disappearing. Each scope and
discussion revision forms its own collapsed **需求讨论** disclosure immediately
before the corresponding Designer Markdown snapshot; later messages must not
push that disclosure to the bottom or combine it with another design revision.
The server projects the persisted `designMessageId` link for exact placement;
only historical rows without that link fall back to same-scope revision order.
Expanding it reveals the original question, every offered option and description,
and the user's final answer.
New decisions persist the full question structure in the revision decision log;
historical logs that only contain question text and answers remain readable.
This projection is restored from the server after refresh or process restart and
must not depend on browser-local state.

The page header and Designer two-column workspace must remain width-bounded and
wrap actions instead of clipping them. Final review exposes the same authoritative
**确认设计并创建任务** action both in the header and beside the Review Gate, so a
narrow viewport cannot make confirmation unreachable.

Confirming a draft is an idempotent handoff. After the server returns the
persisted Task id, the client loads that Task into the live store and navigates
to its detail page even when execution-workspace preparation ended in a
terminal Task error. Projects without a usable Git HEAD fall back to direct
execution in the registered directory. A `READY` Task
exposes an explicit **Start execution** action on its detail page.

The Task detail diff tab uses the final Attempt's persisted deterministic
baseline-diff snapshot even when the confirmed LoopSpec did not include a
`GIT_DIFF` verifier. The latter remains a path-policy acceptance gate, not a UI
data prerequisite. After publication switches the registered checkout away,
file previews still address the completed Task branch explicitly. A pushed Task
shows one ordinary **创建合并请求** button; one click opens the confirmation
dialog, with no duplicate single-item dropdown step.

Task detail renders execution and delivery separately: a Task can be **已成功**
while delivery is **待提交**, **已推送**, **合并请求已创建**, **合并请求已关闭**,
or **已合并**. Opening a GitLab creation page changes only the available actions;
it does not claim that an MR exists. After that action, the page offers a manual
**检查合并状态** and a secondary **重新打开创建页**. An opened MR offers
**查看合并请求** plus manual checking; a closed MR remains visibly closed and can
open a new creation page. **已合并** is a disabled final badge and hides all
original-Task commit, push, MR creation, and judge-retry actions while preserving
**新分支重做**.

A derived Task's design-history page renders its own frozen LoopSpec and follows
its persisted design-origin reference for the parent requirement, answered questions,
and role-tagged conversation. It labels that provenance and never copies historical
messages into the child draft.

The Project Convention dialog uses the same restrained active-work treatment as
Designer: one rotating current-activity card shows the newest safe thinking, tool,
or output fragment plus the provider-authoritative token total. It polls the draft
and activity projection together, preserves one last fragment during transient
disconnect, and provides **停止生成**. While the server is `STOPPING`, close controls
remain disabled and the dialog explains that remote termination is still being
confirmed; `FAILED` or `CANCELLED` exposes an explicit fresh-generation action.

The page performs one reconciliation on entry and when browser focus returns,
with a 30-second cooldown and no background polling. Missing GitLab credentials
show **无法自动确认合并状态**. GitHub displays that automatic merge confirmation
is not yet integrated. The browser never infers a merge from a missing branch;
all labels and actions project the server's persisted Publication state.

Route entry is demand-driven. The application shell does not preload Projects,
Tasks, or Runtime. Task lists and Designer history use server filters plus a
50-row keyset page and append through **加载更多**; changing any filter discards
the old cursor. Task detail renders the lightweight overview as soon as it
arrives, then loads audit metadata independently. An audit failure must leave
the overview usable. Evidence, error details, Judge raw output, and artifact
content load only when the matching row is expanded and are cached by record ID
for that page lifetime. Task/Stage SSE events invalidate overview, while
Attempt/Session/Verification/Judge/Error/Artifact events invalidate audit; each
partition coalesces refreshes for 180 ms and never refetches already cached
content. The audit panel, Session monitor, publication editor, and CodeMirror
are asynchronous chunks rather than prerequisites for first content paint.

Each Task detail page also exposes a read-only **Model output / Thinking**
monitor. The operator can select any implementation or judge Session belonging
to that Task. While a Session is active, the panel polls frequently and follows
the newest provider-exposed `THINKING`, `OUTPUT`, and `TOOL` parts; terminal
Sessions remain selectable as history and refresh at a slower interval. Before
the first model text arrives, an animated thinking indicator makes the active
handoff distinguishable from an error or empty state.

For implementation Sessions only, the same panel may show an **OpenCode Todo**
list with status and priority. It is rendered only when tool capability was
available or a legacy/manual refresh produced a snapshot. Truncation and read
failure are shown as projection details; Todo states never become completion
badges for Stage, Task, verifier, or Judge. Designer and Judge Sessions do not
receive Todo instructions or Todo UI.

Designer acceptance criteria are not advisory prose. `PROCESS` is classified by
the server: compile/package/build/typecheck/lint/install are `BUILD`; a
recognized non-skipping Maven/Gradle/npm test mapped to a criterion and carrying
`testTargets` is `BEHAVIOR`; a safe unmapped full-suite test may remain a
blocking supplemental `REPORT` but cannot cover a business criterion;
`SELF_CHECK` becomes `BEHAVIOR` only with an explicit `outputContains` marker.
`GIT_DIFF`, `FILE_NOT_EXISTS`, `JUNIT_XML`, and `FILE_EXISTS` are respectively
scope, safety, report, and advisory evidence and cannot cover behavior. The
automatic final Task baseline-diff snapshot is presentation/audit data, never a
LoopSpec verifier and never a source for the coverage matrix.

HTTP, JSON and browser criteria must address the same Stage-managed instance at
`127.0.0.1:{{LOOPPER_PORT}}`. A fixed loopback check may remain supplemental but
cannot cover a criterion. The editor exposes all native verifier fields and the
managed runtime command/readiness/timeouts; its numeric maxima match the domain
contract (`startupTimeoutSeconds=300`, `shutdownTimeoutSeconds=60`, and
`maxStageAttempts=20`). Verifier types come from a closed selection rather than
arbitrary input.

Each v2 Stage explicitly selects `JAVA_PRODUCTION`, `JAVA_TEST_ONLY`, or
`NON_JAVA`. `JAVA_PRODUCTION` keeps production code and an unskipped focused
Maven/Gradle unit test in the same Stage, declares concrete `testTargets`, and
maps that test to every `MACHINE`/`BOTH` business criterion. A separate
"tests pass" meta-criterion is not generated. The planned test target may be a
Stage deliverable and need not exist while Designer is read-only. Old v2 drafts
without the field remain viewable but cannot be saved, published as a template,
or confirmed until completed; v1 remains compatible. At runtime the server
compares an immutable Stage-start production-Java path/hash baseline in Git or
Direct mode. Added, modified, and rename-target `.java` files outside test and
generated directories trigger the gate. A declaration mismatch or absence of a
successful focused Stage test fails with `JAVA_CHANGE_CLASSIFICATION_MISMATCH`
or `JAVA_UNIT_TEST_ACCEPTANCE_REQUIRED` through the normal Attempt loop.

Maven verifier input is tolerant only where the argv boundary is deterministic.
For example, `["mvn", "test -Dtest=FooTest -pl module"]` is normalized and stored
as five direct argv items without a Designer retry or shell execution. Ambiguous
input such as an unclosed quote remains a field validation error and enters the
same bounded, read-only LoopSpec correction flow as other invalid Designer
output. The portable `./mvnw` alias is offered only when the registered project
contains the current platform wrapper (`mvnw` on Linux/macOS, `mvnw.cmd` on
Windows); otherwise Designer selects an evidenced repository command such as
`mvn`. Windows executable suffix resolution happens at execution time and does
not widen the accepted LoopSpec shell syntax. A rejected correction never
mutates the draft or creates a Task.
