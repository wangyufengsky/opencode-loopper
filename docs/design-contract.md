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

## Designer 附件交互

Designer 起始页和会话工作台的完整内容区都接受文件拖放；拖放只把文件加入当前 composer 的暂存区。未暂存文件时只显示“添加上下文文件”轻量入口，不渲染空卡或常驻限制说明；暂存后必须在输入框下方显示独立“文件上下文”卡片，表头显示当前“整体需求”或具体工作包作用域及数量，每个文件按行显示名称、类型、大小和移除操作，移除最后一个文件后整卡隐藏。文件选择按钮与拖放等价；正文为空时禁止发送纯附件消息。一次最多 10 个文件，卡片内提供 20 MiB/文件与失败保留提示，服务端仍是 50 MiB/会话等限制的权威门禁。

带附件的发送使用一个 `FormData` 请求，浏览器不得手写 multipart `Content-Type`。接口失败时正文、文件和稳定 submission ID 保留供幂等重试；只有成功响应才同时清空。整批解析失败不得出现部分附件或单独文本回合。历史卡显示文件名、类型、大小、作用域、SHA-256、提取器和 `ACTIVE / SUPERSEDED / STOPPED / FROZEN` 状态；同一作用域完全同名是逻辑替换，不改写旧卡。停止未来使用需要确认，并明确不会撤回历史或已经发生的 OpenCode 读取。

文本、Office 和 PDF 使用服务端安全文本预览；图片与 PDF 原文件只通过受限 inline endpoint 打开。页面不执行附件 HTML、SVG、Office 宏或脚本。Task 设计历史必须单独显示确认时的冻结附件清单、内容身份与 Recovery 来源，不能从当前 Designer 活动集合推断。

设计恢复横幅和系统消息历史必须先去掉 `SYSTEM_ERROR[FIELD/VERIFICATION/SESSION/TASK]` 包装，再依据真实错误码显示原因；`OPENCODE_DESIGNER_HANDOFF_FAILED` 显示“设计请求未能发送给 OpenCode”及版本兼容性、连接检查方向。没有具体错误码的历史记录按错误层级中文兜底，已有中文业务细节继续保留；不得把包装码误当根因而只显示“错误”，也不直接展示英文 HTTP 错误体。刷新应保留服务端投影的附件历史和相同故障说明，不自动清理或重放失败设计。

## 前端文案与可读性

附件格式不受模型支持的明确错误（`file part media type ... functionality not supported`）使用固定中文说明，提示升级 Loopper 或选择支持该格式的模型后新建设计，并说明无需清理项目文件；不得回显远端原文、令牌或受管路径。该展示规则不改变 Session 失败状态或自动重试权限。

附件改由私有 MCP Resource 读取，界面保留原文件历史与安全预览，不显示可转发的资源凭据。嵌套 `ATTACHMENT_MCP_*` 错误优先于外层 handoff 文案，明确区分连接要求、未读取、内容不完整、哈希变化及超限。图片/PDF 的 MCP 二进制上限为 10 MiB（上传存储仍为 20 MiB），Office 只提供确定性文本；不得提示成功读取或默默跳过被拒绝的资源。

- 页面采用中文优先的极简文案。标题、状态标签或操作本身已经能表达含义时，不再追加重复的说明段落；“全自动”等明确模式只展示一个状态标签，只有阻断或需要用户选择时才补充原因和动作。
- REST、SQLite、路由参数和选择控件 `value` 保留稳定英文枚举码，普通页面不得直接展示 `QUESTIONING_PACKAGE`、`MANUAL_OVERRIDE`、`ATTEMPT_LIMIT_EXHAUSTED` 等内部值。所有状态、角色、流程、验证器和错误码统一通过 `frontend/src/utils/displayLabels.ts` 转为中文；未知值使用安全的中文兜底，不把原始协议码回显给用户。
- 错误提示使用中文说明“发生了什么”和可执行的下一步。错误详情、消息提示、卡片、表格和工具提示遵守同一规则；原始命令输出只允许出现在用户主动展开的审计日志中。
- 普通页面不展示 Task、Designer Session、Session、Attempt、Draft、Work Package、Criterion 等内部记录 ID，也不展示外部 Session ID。列表和时间线使用名称、顺序、时间和中文状态区分记录；ID 仅可存在于 URL、请求参数、组件 key 和服务端审计数据中。
- 项目登记卡片在桌面宽度最多两列，路径、描述、统计和操作必须允许换行；不得用 `nowrap` 把长项目名、路径或按钮挤在同一行。窄屏按单列自然排布。
- Designer 双重验收矩阵必须让验收条件占据可伸缩主列，模式、机器验收和 AI 评审状态作为独立的可换行状态组；不得用固定窄列压缩长条件，窄屏下状态组应整体下移并保持左对齐。
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
The lightweight Task overview must carry the server-owned cancellation and retry
capabilities plus the design-history and archive flags used by the detail header.
Missing required boolean fields are an incomplete API projection, not `false`:
the frontend rejects that overview and falls back to the compatibility detail read
so an available action cannot disappear silently.
A newly confirmed Task is `PENDING_START`. It must show **开始执行** and a separate
confirmed **取消任务** action, and its summary must say that confirmation has not
created a queue row, acquired a write lease, allocated an execution directory, or
switched a Git branch. Cancelling this state changes only the Task to `CANCELLED`.

The `AWAITING_DECISION` result card owns a separate confirmed **取消任务** action.
It must call the versioned result-disposition endpoint rather than the ordinary
runtime-cancel endpoint. The server may briefly return `STOPPING` while it rechecks
all writers, then reaches `CANCELLED` without changing the frozen succeeded/failed
Execution Cycle or its historical Stage evidence.
Clicking **开始执行** is the single execution request: the server may move through
`QUEUED`, `PREPARING`, and the transient `READY` state into `RUNNING` without a
second click. The no-runtime cancellation still uses the same persisted stop intent,
but normally confirms `CANCELLED` in that request. If `READY` is observed during that continuation, the UI shows that
automatic startup is in progress and may retain the confirmed cancel action, but
does not render another Start button.
While a Task remains `QUEUED`, detail renders a server-backed **当前在排谁** card with
the holder title/link, Task state, archive flag, lease state, queue position, and stable
release blocker. Ordinary blockers use **重新检查并释放**. An unconfirmed writer uses
the explicit **终止遗留会话并释放** confirmation: it still posts only the waiter ID,
while the server locates the terminal holder, reissues termination/status checks, persists
positive cleanup evidence, and then runs the same safe lease reconciliation. A 409 keeps the
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
and produce `CANCELLED` rather than borrowing the Task-failure path. Its second confirmation stays
inside the same non-dismissible dialog instead of opening a competing global modal, and remains
available even when the file-list refresh fails. Snapshot conflicts refresh the authoritative list;
the browser never assumes cleanup or branch creation succeeded.
Every `TASK` error event remains immutable audit history, but Task detail must
separate that history from the active red alert. While the Task is in
`WAITING_INPUT`, only the newest error whose code exactly matches the authoritative
`waitingReasonCode` is active. While a failed execution cycle is in
`AWAITING_DECISION`, or for a legacy `FAILED` Task, only the newest Task error is
active. `PENDING_START`, `QUEUED`, `PREPARING`, `READY`, `RUNNING`, `VERIFYING`,
`RETRY_WAIT`, `PAUSED`, `JUDGING`, `STOPPING`, and successful/cancelled/superseded
terminal views do not promote historical Task errors into current red alerts.
This includes `SOURCE_BRANCH_WORKSPACE_DIRTY`: once preparation or execution
continues, its stale alert disappears without deleting evidence.

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
While a Router run is active, a dedicated task-settings dialog polls the authoritative Designer
snapshot and safe activity every 1.2 seconds. The dialog cannot be closed by its close button,
mask, or Escape key and displays real elapsed time, remote state, latest bounded activity, and
provider-reported Token usage. It does not show a timeout limit: after the remote Session is
connected there is no Router wall-clock deadline, and the server waits for its real terminal state. It never
renders an estimated progress percentage or raw Router object. Marker or JSON-like fragments are
replaced by **正在整理任务设置识别结果**.

The first successful ordinary-mode result remains in `ROUTING` and blocks requirement-Designer
creation until a versioned decision is saved. The result dialog separates **识别置信度** from
**技术栈**, and also displays task type, affected components, workflow, primary artifact,
execution strategy, and test policy. While running it exposes **取消识别，手动设置**; the server aborts
the remote Session before the dialog switches directly to the manual controls, and failure to confirm
the abort keeps the run active. Terminal actions are **确认并进入设计**, **重新识别**, and **手动修改**.
Closing a terminal dialog only dismisses that presentation; the task-settings card
keeps a **查看识别结果** entry, and refresh restores the dialog while no decision has been saved.
Failed, unconnected-timeout, or malformed runs show their comprehensible reason and fallback settings. Their
confidence field is explicitly **未产生**, never a misleading `0%` value.
Router cancellation is not a failed recommendation: it displays an informational manual-selection notice,
persists a full-auto blocker, and does not expose confirm/retry actions until the user leaves manual editing.

Reroute sends the expected run ID and profile version to
`POST /api/designer-sessions/{id}/task-profile/reroute`; the server accepts only a terminal latest
run whose current profile is still unresolved and always reuses its persisted requirement snapshot.
Confirmed profiles are no longer retryable; stale, resolved, or concurrent requests return 409.
An equivalent complete-requirement reroute carries the previous manual choice as
`USER_CONFIRMED_CARRIED_FORWARD`; a changed result reopens the dialog. Before any manual edit is
written, the browser calls the read-only profile update preview. The initial `ROUTING` gate has no
requirement Designer to stop, so changing workflow there does not show a false restart warning.
Later workflow changes retain the existing **停止当前设计并重新开始** confirmation and abort
boundary. A click concurrent with Router completion refreshes the authoritative snapshot rather
than raising a red toast.

An explicitly authorized full-auto Session may accept only a successful result that passes the
same server safety and component checks, recording `AUTO_RECOMMENDED` before continuing. Timeout,
run failure, unsafe-operation evidence, or required component selection remains blocked for human
reroute, override, or explicit confirmation; full-auto never silently adopts the fallback.
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
routing; equivalent carried-forward and safely authorized full-auto results may continue, while
the first ordinary result waits at the visible confirmation gate and never requires a history-page
restore. Confirmation freezes the project-profile id, manifest fingerprint,
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
Current Role Pack version `2026-08-dynamic-v7` inherits the v6 normalization of aliases into
Java, Python, Node, and Other families. JavaScript/TypeScript cannot trigger Java, multiple labels from the
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

Before a requirement revision exists, Designer must ask once with 1–3 choice questions. Loopper
uses the native `question` card only when the project-scoped OpenCode tool probe explicitly reports
that tool. If the tool is unavailable or the probe is inconclusive, Designer returns only numbered
ordinary-text questions and the page shows a **对话回答模式** notice. The normal composer becomes
the answer control even while the Session is `RUNNING` or full-auto is active; after persistence,
the same decision history and snapshot rules apply. In ordinary software mode it may then finish with empty text:
the server assembles the authoritative Markdown snapshot verbatim from requirement-scope
user messages and persisted final answers, and ignores free-form model text. In large-task
mode Designer still returns the complete replacement Markdown requirement predesign.
The native question card blocks ordinary chat until answered and offers one-click
selection of all recommended choices; compatibility mode instead exposes only the direct-answer
composer and never calls the `/question` endpoints. Follow-up messages repeat the active mode's contract but
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
confirmation remains the ordinary/legacy aggregate gate. In the rolling large-software
flow, confirming package 1 is the Task-creation gate; later design approvals append to
that same Task and never reopen the original LoopDraft.

Designer may optionally authorize a per-Session full-auto mode. It is disabled
by default and every enable or re-enable requires a local-UI risk confirmation.
The persisted `DISABLED / ACTIVE / BLOCKED / COMPLETED` state advances at most
one authoritative action per monitor tick and survives process restarts. It may
answer native pending design questions from explicitly recommended options (falling
back to the first option), confirm the requirement, and drive legacy design gates.
For a rolling large-software session it may generate the decomposition and read-only
package candidate, but must stop before every package-design approval and every package
execution start. These actions remain visible as System
messages and question decisions use `AUTO_RECOMMENDED`; manual decisions use
`MANUAL`.

Full-auto authorization ends once Task Start has been requested. It must never
answer execution-time questions, grant dangerous permissions, choose recovery,
accept execution results, commit, push, merge, or publish. A compatibility-mode ordinary-text
question has no machine-readable recommendation, so full-auto pauses for the user's direct chat
answer instead of guessing. Disabling stops only
future automatic actions. A low-confidence/conflicting task profile is resolved as a
separate, persisted auto-recommended action when full-auto is authorized; unsafe-operation
evidence remains fail-closed. Budget exhaustion, multi-task requirements, design or
validation failures, missing option data, optimistic conflicts, and Task Start
errors move the mode to `BLOCKED` without hot retry; the operator must disable
it or explicitly authorize it again after handling the cause.

Packages then run strictly serially. Each package reuses its healthy interactive
read-only Designer conversation across revisions and reconstructs a fresh one
from persisted snapshots/decisions after remote loss. Designer receives
the original requirement, frozen decomposition, current package, global
constraints, and bounded prerequisite handoffs, then emits one complete Markdown
replacement without a fixed byte-size ceiling. The server persists that exact design and
continues to enforce the controlled section shape plus scenario/fact limits. For current V7, the server first
resolves the exact Stage table and compiles a complete ordinary `WP-1`, large-task package, or active rolling
package without creating a remote Compiler Session or consuming a model call. Only unresolved closed-set
fact/capability bindings create one `COMPILER_BINDING_NO_TOOLS` Session; frozen V6 large-task packages retain
their historical one-turn Compiler compatibility path, while the frozen Stage topology remains immutable. Direct software
allows 1–6 Stages; each large-task package remains limited to 1–3. Decomposer and historical semantic-Compiler extraction failures receive at most
two format repairs, and field/verifier/traceability/coverage failures receive at most two semantic
patch repairs. Current v7 and frozen v6 disambiguation have no semantic repair loop: malformed or out-of-closure output becomes
targeted `AMBIGUOUS_ACCEPTANCE_INTENT`, never an empty binding or catch-all Stage; gaps receive one full
redesign of that package only. Large-task initial design and every
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
`outcome` is absent by contract in v6 disambiguation but remains a format failure for historical semantic
contracts and cannot fall into the legacy `status` parser, while an
invalid patch response cannot overwrite the last valid semantic snapshot.
Compiler's compact Stage field is `evidence`, never the final DTO field `verifiers`. A uniquely
reversible `/stages/<n>/verifiers...` repair pointer is normalized to `evidence`, and `replace`
of an absent model-owned object leaf is treated as `add`; both remain audited and must pass the
full contract again. Every `JAVA_PRODUCTION` Stage retains a focused Maven/Gradle TEST even when
its acceptance is Judge-only; full-suite/build evidence cannot replace that gate, so wiring-only
Java stages are merged with a related tested stage or carry a focused gate with `covers:[]`.

For a new rolling large-software Task, only package 1 is designed on `/designer`.
Approving its exact validated revision creates one `PENDING_START` Task and navigates
to `/tasks/:id`; no Queue, Lease, branch, Attempt, or writable Session exists yet.
Every later package stays on that task page and starts its read-only Designer automatically
from the preceding successful fact snapshot. Candidate arrival changes the Task to
`WAITING_INPUT / PACKAGE_DESIGN_APPROVAL_REQUIRED`; approval appends only that package's
Stages and a cumulative TaskSpec revision, then a separate **开始本包执行** button is
required. Full-auto cannot cross either button.

The rolling workbench is a three-column desktop layout: ordered package navigation,
current design/execution workspace, and fact evidence. Below 980 px it becomes a package
selector plus a single-column content flow. Navigation shows Chinese order, title, and
authoritative state only. The header says **已冻结 N/M 包** and never derives a percentage.
The fact column visually separates **已证明**, **已接受合同**, and **AI 导航摘要 · 非证据**;
older facts remain expandable history and never merge into the current fact. Existing
StageRail, Session Monitor, machine-verification evidence, and layered error panels remain
the Task-wide authoritative components below the rolling workspace.

The only package actions are server capabilities: `canDiscuss`, `canApproveDesign`,
`canStartPackage`, `canRetryPackage`, `canRedesignPackage`, `canResumeDesign`,
`canReplanRemaining`, and `canAddCorrectionPackage`. Missing any capability fails overview normalization and falls
back to full task detail; no button is inferred from a state name. The rolling workbench response
must return `taskVersion`, `currentPackageRunId`, package versions, and the complete capability
snapshot together; its Vue component must not gate a freshly loaded workbench with an older Task
overview capability. A current `DESIGNING` run whose persisted work package is still
`PENDING / QUESTIONING / DESIGNING` exposes **继续当前包设计**. That versioned command
polls the existing live remote Session or recreates only a missing/terminal one from the persisted
fact-aware prompt; startup recovery uses the same idempotent path and must never duplicate a live
Designer. `DESIGNING` and any persisted active Designer, Compiler, Validator, writer, verifier, or
Judge still fail closed for suffix replan. All writes carry Task, package-run, discussion, and design
versions and refresh after a successful empty `202/204`; a stale 409 also reloads the workbench
before another action is offered. Task SSE treats every `package.*` event as an authoritative
overview/workbench invalidation, so candidate arrival cannot leave the action row on the old
`DESIGNING` snapshot. **继续失败候选**, **回到上一事实点重新设计**, **AI 调整剩余拆包**,
**人工调整剩余拆包**,
**新增修正包**, and **修改整体需求并重开任务** are distinct decisions. Direct mode shows
before first execution that the registered directory remains leased until completion or
cancellation.

The AI replan action creates a durable asynchronous suggestion and polls the authoritative
`GENERATING / PROPOSED / FAILED` row. The browser never invents progress from elapsed time. A
completed suggestion opens the same server-computed impact confirmation as manual editing; it does
not activate the plan automatically. Closing or rejecting that confirmation leaves the active plan
unchanged.

OpenCode native agents are capability-discovered for server-side role selection,
but the compact Runtime page does not expose that diagnostic projection and the
Designer does not delegate to the native plan agent in this release. Designer
remains the human-readable Markdown role;
Decomposer and Compiler remain the machine-contract roles; the Validator remains
server-owned and deterministic. This preserves Review Gate labels, persistence,
repair budgets, source mapping, and the rule that raw machine JSON is not a chat
message.

Legacy aggregate Designer and Compiler inspect an immutable pre-execution repository baseline.
For a later legacy package, a predecessor whose package state is `APPROVED` has passed
its Designer/Compiler/Validator workflow but has intentionally not written its
production files yet. Loopper injects that predecessor's frozen objective,
Compiler summary, and bounded handoff contract into both prompts. Because the
single confirmed Task executes package Stages in dependency order, current
absence of such a deliverable is not `MISSING_SCOPE` and must not trigger a
redesign. A semantic gap remains valid only when the required contract is absent
from both the current frozen design and the predecessor contract/handoff.
Rolling package Designer/Compiler instead read the exact preceding successful state:
a managed read-only Checkpoint snapshot for Git, or the continuously leased registered
directory after a Direct tree/manifest equality check. They receive bounded fact indexes
and navigation, not a claim that the initial baseline already contains new files. Snapshot
drift blocks design as `PACKAGE_CHECKPOINT_BLOCKED`.

New software designs use fixed controlled Markdown sections: target/scope,
impact/delivery, acceptance scenarios, optional human review, acceptance constraints,
and stage dependencies. The acceptance table records scenario, precondition/trigger,
action, observable result, and invariant. Designer must not emit internal WP/AC/DS-L
ids, LoopSpec JSON, or executable argv; it names repository-native test classes or
targets and independence constraints, while the server creates safe capabilities.
Current v7 designs use exactly `阶段 | 目标 | 负责路径 | 包含场景/评审/交付 | 前置阶段`;
frozen v6 designs keep the historical four-column shape.
The responsibility cell lists repository-relative paths or path rules owned for writes by that Stage.
Every required create/write/move destination must have one provable owner; repeating it across Stages is blocking.
The inclusion cell copies earlier titles verbatim and separates multiple names with `；` or `;`;
dependencies copy only earlier Stage titles, while blank or `无` means no dependency.
This is a DMN-inspired decision-table input, not a second DMN runtime: rows stay readable
to the designer, lower directly to EARS criteria, and can be reviewed as
Given/When/Then/And scenarios without maintaining three competing sources of truth.
The controlled section names form one replacement document: each required section
must occur exactly once and an optional review section may occur at most once. A response
that repeats a complete design or omits part of the controlled shape is rejected for a
fresh design instead of merging duplicate tables. Negative framework constraints such as
`无 @SpringBootTest` cannot become executable test capabilities.

Before acceptance binding, the server persists immutable DesignFact and capability
snapshots. Current `DESIGN_ACCEPTANCE_V7` snapshots also freeze typed mutation obligations from
positively scoped repository-relative paths in the requirement, controlled deliverable/scope facts,
and explicit frozen package path rules. Each obligation is typed as an exact path or path rule and retains
its source reference, bounded excerpt, and SHA-256. Negative/invariant/example text, symbols, and
project-external paths produce no write obligation. Broad globs from requirements, controlled design, or
frozen package fields remain auditable path-rule obligations and cannot create write permission by themselves;
they require one explicit Stage responsibility or another uniquely covering runtime-compatible Stage rule. Delete requests and move sources remain
explicit blocking operations. Historical v5/v6 JSON without this catalog reads as an empty catalog and
is never reinterpreted from newer requirement text; a frozen `dynamic-v6` package that compiles after an
upgrade still creates a V6 acceptance snapshot rather than being rewritten as V7.
Explicit directory references are subtree `PATH_RULE` obligations and require a recursive runtime-compatible allow
rule; API routes and controlled business symbols remain symbols rather than repository paths. In requirement prose
without a mutation operation, a bare slash-separated identifier is not classified as a path from `/` alone: an
extension, glob, known repository-root segment, or explicit path/directory/file context is required to raise an
unclassified-path block. An explicit write/delete/move operation still turns the complete identifier into a mutation
obligation. Mutation verbs bind by
path position, while mixed write/delete/move ownership in one clause fails closed instead of assigning one verb to
every path. These classifications are v7-only and do not reinterpret frozen v5/v6 Stage path selection.

Exact matching applies Unicode NFKC, edge trimming, whitespace collapse, and Latin
case-folding only; punctuation is retained and substring/fuzzy matching is forbidden. For a newly frozen v7
single-Stage design, the server deterministically assigns every omitted acceptance fact to that only Stage and
audits away extra Stage-inclusion labels that do not name a frozen fact; frozen v5/v6 records retain their stricter
historical rejection. A multi-Stage closed-choice prompt lists each zero-based `stageIndex` and every fact's complete
allowed index set explicitly, so a human-readable “Stage 1” label cannot be mistaken for index `1`. The UI renders
an **验收意图识别** card with fact/scenario totals and
machine, machine+human, human-only, and unresolved counts. Expanded rows show only
human-readable scenario/capability names and Chinese issues—never internal indexes,
protocol enums, or raw JSON. Unresolved items remain blocking at the server Review
Gate; the client cannot infer confirmation eligibility from this card. The source label is
“服务端直接编译”, “规范工程师辅助消歧”, or “历史编译”. A server-direct result has no remote
activity card and does not poll a nonexistent Session; `serverCompiled` remains compatibility data,
not a source inference.

The card styles diagnostics from the current bounded status rather than from the mere
presence of historical arrays. Only a current failed state, unresolved acceptance count,
unassigned mutation count, or blocked path-conservation result uses warning styling.
Successful mutation bindings containing their Stage ownership render as green, collapsible
"current proof" rows. Routing and issue reasons retained after a successful compilation are
deduplicated and placed under a neutral, collapsed historical-disambiguation section marked
resolved. This presentation split never weakens or infers the server Review Gate.

Current v7 capability resolution is global across all coverable facts. The server selects the only optimum
after complete coverage and mandatory-capability checks, then compares fewer Judge-only capabilities, fewer
nondeterministic capabilities, fewer total capabilities, and greater evidence strength. Stable indexes order
the output only. A unique optimum records `compilerAvoidedReason=UNIQUE_OPTIMUM` and invokes no model; only
multiple solutions equal on every business dimension increment `trueCapabilityTieCount` and may create the one
closed-choice Session. That Session receives only candidates whose membership differs across the exhaustive equal
optima, and its complete selected-index union must equal one listed optimum; common members, weaker alternatives,
and mixed non-optimal combinations are rejected. A bounded non-exhaustive search is blocking diagnostic evidence,
not permission to delegate authority to the model. The result may never trade a focused test for Judge-only acceptance.

Release qualification uses the versioned corpus and read-only same-input shadow in
`weak-model-compiler-v7-evaluation.md`. The UI does not display synthetic corpus
counts as production confidence, and no shadow result can create a Session, Task,
draft update, or Designer transition. Each corpus sample executes its exact guard
and publishes versioned mutation/hard-gap expectations, but those hand-written
counts are not measured gate input. The same-input shadow is an authoritative measurement
produced by the production planning pipeline. Key guards publish bounded actual call, redesign,
safety, and coverage counts through a test-only registry; only checked measurements together
with every exact production guard form complete qualification. Unavailable ratios remain unavailable rather
than defaulting to 100%. Only new v7 snapshots are eligible after all
path, hard-gap, cost, Judge-only, focused-test, SQLite, restart, direct, rolling,
and historical compatibility gates pass.

Decomposer uses one persisted compact semantic turn per candidate. The optional current package Compiler uses
one v7 closed-choice turn and cannot edit locked Stages; frozen v6 records retain their original fill-hole turn.
Decomposer returns business packages and RQ coverage by index;
structured Markdown requirements are grouped by level-two business section so
presentation-only headings and metadata do not become separate coverage work;
the server preserves the Designer's 1–6 Stages for direct software or 1–3 per large-task package,
maps each observable criterion to stable `DS-Lxxx` Designer source references, and uses only closed
capability preferences from the optional disambiguation. The
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

For v7 plans, `DesignerMutationStageBinder` runs after Stage assembly. It first honors one explicit
`负责路径` owner, then accepts an exact controlled fact reference, one uniquely covering existing Stage rule,
an exact legacy file/class/path-tail symbol present in exactly one Stage title/objective, or an exact
write/move-destination path added to the plan's only Stage. Symbol recovery is token-exact, never fuzzy; duplicate
responsibility or multiple symbol/rule matches remain blocked and are never delegated to the Compiler.
`MutationConservationPolicy` then runs before package lowering. Every frozen `WRITE` or move destination must
have one of those justified Stage owners whose `allowedPaths`
covers it with the runtime `VerifierPathPolicy` semantics, and no effective forbidden rule may cover
the same path. A technology-only fallback such as `src/main/java/**` cannot prove an explicit
obligation; package scope and global-fact fallbacks have the same limitation. The owning Stage,
focused-test evidence, and explicit `GIT_DIFF` must reuse the same
normalized allowed/forbidden sets. Missing ownership returns
`REQUIRED_MUTATION_PATH_UNASSIGNED`; forbidden, delete, and move-source obligations return
`REQUIRED_MUTATION_PATH_FORBIDDEN`. Diagnostics expose bounded business counts, project-relative paths,
binding reasons, and Stage names without internal indexes or raw JSON. These gaps stay `DESIGN_INCOMPLETE` and cannot be converted to
Judge-only acceptance or a catch-all Stage.
For the current V7 role pack, the same server-direct path applies to direct packages, large-task packages, and the
active rolling package whenever closed facts and capabilities are resolved. Mutation ownership gaps wait for targeted
human input and do not spend the package's complete-redesign budget. The UI disables unchanged recompilation,
keeps scoped package feedback open, and sends all unresolved paths/candidate Stage names into a complete-replacement
Designer recovery prompt; generic compilation retry cannot loop over the same design revision.

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

For `PACKAGE_ACCEPTANCE_CLOSED_CHOICE_V7`, reversible aliases, singleton collection shape,
`null` collections, and contract-irrelevant explanatory fields are the only additional safe
normalizations. They produce one persisted `NORMALIZED` AI-output audit item and bounded
`safeNormalizations` diagnostics without spending a repair turn. The raw object is inspected before
normalization: any attempt to provide paths, commands, test targets, Stage topology, permissions, or
safety policy is rejected. Missing, duplicate, conflicting, or out-of-range selections and multiple
non-equivalent valid JSON candidates remain blocking. A rejected response preserves the immutable fact
catalog and already completed server bindings.

The compact Decomposer/Compiler steps prefer stable OpenCode JSON Schemas; current v7 acceptance uses
`PACKAGE_ACCEPTANCE_CLOSED_CHOICE_V7`, while frozen v6 rows keep
`PACKAGE_ACCEPTANCE_DISAMBIGUATION_V6`.
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

On a Loopper-managed runtime Designer, Implementation, Reviewer and Requirement/Risk Judge
have no fixed agentic-step limit, including package candidates and Judge finalizers. Machine-response
roles in this group select the zero-temperature `loopper-structured-unbounded` agent with no `steps`
setting. Decomposer, Compiler/binding/repair/acceptance choice, rolling package planning,
project convention and generic non-Judge finalizers retain the 24-step `loopper-structured` agent;
Router retains one step and the separate story-accounting command retains two. Loopper derives
its message-count check and managed agent from the same role policy. Three consecutive calls with the same normalized
tool name and arguments trigger an immediate best-effort abort and at most one
persisted, built-in-tools-disabled finalizer Session for that role step. Configured MCP tools
remain available under the same additive permission rule. The finalizer uses
bounded deduplicated evidence, counts against the global model-call budget, and
does not consume format-repair budget. Judge finalization preserves the no-step-limit policy
without opening built-in tools. Role timeouts, Task budgets, MCP submission-count policy and
permission boundaries remain independent. A returned maximum-steps control notice is shown as
a Chinese Session failure and cannot be saved as a design or enter acceptance compilation.
Existing stored output is not rewritten; the user must rerun the affected design after the new runtime is loaded.
For Decomposer
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

Frozen Task design history uses a separate read-only timeline: show only persisted USER and
DESIGNER speech plus `DesignerDiscussionHistory` question cards. System, Validator, Compiler
and Decomposer messages remain in the audit data but do not appear in that timeline. Questions
come from the source Designer's persisted discussion decisions (including inherited Tasks),
with options, descriptions and final answers; a hidden system snapshot may still anchor a card.
Reopening must not duplicate replayed normalization notices: deduplicate per Task and translated
explanation, retaining at most four distinct notices.

The following grouping rules apply to the active Designer console. Message origin comes from the persisted `actor` (`USER`, `DECOMPOSER`,
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
ordinary/MCP tool calls with name, status, arguments, and output. The task-settings Router reuses
the same activity component inside its modal and may show bounded thinking, output, and tool
fragments after server redaction; any marker or JSON-like result becomes a neutral整理提示.
Other structured roles render only the latest tool activity and their authoritative step. Raw
Router, Decomposer, Compiler, Reviewer, repair, finalizer, or Judge JSON is never shown. Reconnect
keeps that one latest fragment until a newer authoritative observation arrives.

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

Deterministic package aggregation may normalize and freeze the complete requirement for planning
evidence, but it must derive the aggregate `goal` from the first persisted user requirement. That
immutable input remains the default Task title and therefore the source for a later `loopper/<任务名>` branch;
the server-generated **需求快照** must never replace either user-facing identity.

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
The operational review section projects only the highest-ordinal Requirement row
and highest-ordinal Risk row. Starting a fresh batch immediately supersedes the
previous cards in that section, while every historical Judge row remains immutable
and available through audit history.
For rolling Tasks, Stage/Judge rows are additionally filtered by the latest cumulative
TaskSpec and successful fact chain: a failed package's stale Stage cannot leak into a
later accepted contract or final Judge. Plan-revision previews show added, removed,
reordered, dependency, split, and merge effects before confirmation; confirmation may
supersede only unfinished rows, while a correction links to but never mutates a frozen run.

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

History actions are explicit server capabilities. Every item carries required
`resumable` and `stopRetryAvailable`; `STOPPING` contributes to neither resumable
counts nor ordinary actions and renders only **重试停止**. `CANCELLED` is read-only.
The stop response distinguishes remote `failedSessions` from local
`pendingFinalizations`, so the page never labels a version-conflicted local closure
as a remote abort failure.

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

When `GIT_DIFF` finds an outside-allow-list mutation of an existing baseline
file, Task detail opens a dedicated decision dialog and keeps a visible reopen
card after dismissal. This is a calm code-review surface rather than a warning
wall: the left rail navigates affected existing files and shows each file's
pending/accepted/rejected state, while the right pane centralizes the current
file decision above the Stage-baseline unified diff with old line, new line,
removed content, added content, and hunk location. A persistent footer reports
accepted, rejected, and pending counts; the continue action is disabled until
all files have a choice. The page must describe this state as waiting for a
decision, not as a failed verification. Outside-scope new files are
auto-accepted in a neutral disclosure and retained in audit evidence, while
forbidden paths and delete restrictions remain hard failures. Decisions are
valid only for the displayed patch hashes; any later workspace mutation must
reopen a fresh request.

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
disconnect, shows no timeout limit, and provides **停止生成**. A connected generation has no
inactivity or wall-clock deadline and waits for the remote terminal state. While the server is `STOPPING`, close controls
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
The card presents a compact completed/total projection, segmented Todo states,
and one current item before the optional collapsible remainder. Completed items
are visually subdued and the count is explicitly non-authoritative rather than
a Stage percentage. On desktop, the card occupies a dedicated layout row between
the Session toolbar and the independently scrolling model output. It therefore
remains visible without overlaying or covering any model part. While an OpenCode
question is awaiting an answer, the card returns to the output document flow so
the answer path keeps priority. A long expanded list gets a bounded internal
scroll area so it cannot consume the whole console. Narrow viewports also keep
normal document flow. Controls keep visible keyboard focus, and non-essential
motion is removed when reduced motion is requested.
The desktop model-output viewport reserves 500 px minimum and 680 px maximum
height, with smaller responsive bounds on narrow screens, so the bounded Todo row
cannot dominate the visible Session monitor.

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
## 机器候选提交与选择性角色迁移

`MachineCandidateSubmission`/内部 MCP 只负责把机器候选送回 Loopper，并返回有界的接受、拒绝或等待输入结果；它不是设计事实、编译结果或状态转换的权威来源。候选是否可接受、如何编译以及如何写入，只能由服务端冻结版本对应的 `CandidatePolicy`、确定性 compiler 和 DB-only accepted writer 决定。模型声明成功、MCP 调用成功或候选 JSON 形状正确，都不能绕过服务端验证。

当前只允许以下七个候选合同：

- 七种合同在 `INTERNAL_MCP` 通道均不设提交次数上限；每次可修正拒绝必须返回有界 `code / JSON Pointer / detail / allowedValues`，保持同一 run 与远端 Session 为 `OPEN`，模型使用返回的 `submissionRevision` 提交完整替换候选。冻结的 `maxAttempts` 只约束 Legacy 修复预算，不是 MCP 配额。
- `DECOMPOSITION_PLAN_V2`：提交完整任务拆解；合同或覆盖问题继续在同一 Session 修正。模型明确声明真实 `NEEDS_INPUT / MULTI_TASK_REQUIRED` 时进入人工边界。
- `ACCEPTANCE_CLOSED_CHOICE_V7`：只允许服务端已证明自然可枚举、候选集合完备且存在真实机械同分的闭集选择。安全的 JSON/字段形状和闭集选择值错误均可修正；路径、权限、执行、拓扑以及不可枚举/非穷尽结果不开放候选修复面。
- `PACKAGE_DESIGN_V1`：每次提交完整替换的工作包语义对象，包含 `READY | NEEDS_INPUT`、需求语义、场景、交付物、评审点、Stage 目标/语义引用/依赖和闭集 gap code。`READY` 的内容缺失、覆盖不全、验收归属或验证能力歧义是模型可修正问题；服务端必须保留具体缺口说明，不能只返回泛化的 `AMBIGUOUS_ACCEPTANCE_INTENT`。模型明确提交 `NEEDS_INPUT`、大型任务模式选择及路径/安全/权限边界仍需人工。命令、可写路径清单、测试命令、Verifier、权限结论和稳定 ID 属于服务端权威字段，候选不得携带。
- `ROLLING_PACKAGE_PLAN_V1`：每次提交 1–6 个完整替换的剩余包，只允许 `packageKey/title/objective/replaces/dependencies/requirementRefs`。非法 JSON 根、载荷过大以及普通字段/闭集引用错误均返回可修正问题；稳定 run ID、checkpoint、顺序、impact、路径、命令、Verifier、权限和生命周期字段失败关闭。
- `REVIEWER_REPORT_V1`：只提交标题、必填摘要、受限 findings 和 limitations；普通额外字段或拼写错误返回闭集字段列表供修正，真正的状态/权限/身份字段失败关闭。每条 finding 必须引用冻结的受管源码相对路径、精确行号和摘要，任一无效即整份拒绝。
- `PROJECT_CONVENTION_V1`：只提交 `contractVersion/componentKeys/commandIds/pathIds`；普通额外字段返回闭集字段列表供修正，原始命令、路径、Markdown、权限、生命周期、稳定 ID 和 fallback 指令仍是禁止的权威字段。
- `JUDGE_DECISION_V1`：只提交 `contractVersion/role/verdict/reason/evidenceIds`；角色与证据 ID 必须匹配冻结批次，reason 为 1–4000 UTF-8 字节的单行文本。普通合同字段错误可修正，证据内容、批次、稳定 ID、权限和生命周期字段失败关闭，正向停止证明后才能结算。

工作包设计采用“双入口、单内核、单权威”。MCP 候选接受后由服务端生成规范 Markdown 作为设计历史，并直接进入确定性 `PackageDesignCompilation`，不创建独立 AI Compiler Session；模型最终自由文本被忽略。未调用 MCP 时，只有远端已经 `COMPLETED` 且最终 Markdown 非空，才完整复用现有 Markdown 编译路线；MCP 可修正拒绝不再因提交次数切换 Markdown。冻结需求明确选择 Markdown-only 或不使用私有提交时，Package Designer 的后置候选提示必须尊重该选择，不得用“优先调用”覆盖它；该 Session 仍受候选最小权限 profile 管控，但应以零次提交正常完成并走 Markdown 兜底。真实 `NEEDS_INPUT`、路径/安全/权限/修订/运行代次冲突、超时、传输失败和停止未确认都失败关闭，不得把可能不完整的输出当作兜底。

Compiler v7 的既有快速路径保持不变：

- 唯一最优解由服务端直接绑定，模型调用、candidate Session 和 candidate submission 均为 0。
- 非枚举歧义、路径守卫、安全边界或权限约束不得交给候选角色修复，保持 0 个 candidate，并由服务端失败关闭到人工输入；已经开放的候选若只有安全合同形状错误，则在同一 run 内修正。
- 真同分候选只允许在现有服务端路由明确 `compilerRequired=true` 且闭集证明成立后打开候选 run；不能由模型自行声称“这是闭集”。

候选 OpenCode Session 必须使用独立的最小权限 profile：Decomposer、工作包设计、滚动计划、Reviewer、项目公约和 Judge 候选只保留形成仓库证据所需的 `read / glob / grep`，交互式工作包候选可额外使用 `question`，验收闭集选择不开放任何内置工具；每个 profile 都只可见精确命名的私有内部 `submit_candidate`，不可见用户 MCP。该内部 MCP 仅允许 Loopper 受管 OpenCode 通过 loopback 和代际 bearer 调用，不属于公共六工具目录。Router 的单次零工具边界不变；Legacy Requirement/Risk 继续使用既有只读 profile，`JUDGE_CANDIDATE_READ_ONLY` 获得精确私有提交工具；0.3.22 默认启用该路线不扩大文件、交互或写权限。

滚动计划继续保持人工确认边界。启用 `ROLLING_PACKAGE_PLAN_V1` 后，派发前 flag 或私有 MCP 就绪证明缺失才允许创建全新的既有只读 Legacy Session；候选 Session/run 一旦存在，零提交、超时、Provider/传输、交互、安全、代次或停止不确定都不得读取 marker 输出。接受结果先以 V56 不可变行保存，只有远端完成或 abort/不存在的正向证明才与 `GENERATING -> PROPOSED` 在同一结算事务中绑定；未确认停止时保持 `GENERATING + DISCONNECTED`，不得自动确认计划或派发后续包。0.3.8 隔离成品 JAR 已证明真实模型主动调用私有工具、在前向依赖机械拒绝后于同一 Session 修正并接受，因此 0.3.9 起默认开启；显式设置 `LOOPPER_ROLLING_PACKAGE_PLAN_V1_ENABLED=false` 只把新建议送回全新 Legacy Session，不改变既有 run、接受结果和失败关闭边界。

外部 `auto/http` 兼容模式不注入私有 MCP。它们继续使用 `IN_PROCESS_LEGACY` 通道，而且每个候选 run 必须新建 OpenCode Session，不能把旧 JSON 会话升级为内部 MCP 会话。受管模式可在同一候选 Session 内根据服务端可修正拒绝持续重提，但不能跨 runtime generation 继续；每次问题列表和候选载荷仍各自有界。

候选 feature flag 只控制是否创建新的对应 run。关闭 flag 后不得再打开新 run；已经持久化的 run、恢复读取和兼容 adapter 必须继续可用，因此 persisted adapter 是常驻基础设施，不能通过条件 Bean 随 flag 一起消失。`PACKAGE_DESIGN_V1` 已由隔离成品 JAR 真实证明 MCP 修正接受和 Markdown-only 零提交兼容路线，生产默认开启。`ACCEPTANCE_CLOSED_CHOICE_V7` 已在 0.3.5 隔离成品 JAR 证明真实模型采用私有工具并同 Session 自修正，0.3.6 起默认开启；环境覆盖为 `false` 只把新真同分送回全新旧 JSON Session，不改变已有 run 的恢复。`ROLLING_PACKAGE_PLAN_V1` 已在 0.3.8 隔离成品 JAR 取得主动私有工具调用、机械拒绝后同 Session 自修正及 V56 结算证据，0.3.9 起默认开启；显式关闭只影响新建议。`REVIEWER_REPORT_V1` 已在 0.3.13 隔离成品 JAR 证明真实模型主动调用精确私有工具，并在同一 Session 将冻结源码范围拒绝的第 99 行修正为第 1 行；0.3.14 起默认开启，显式关闭只回滚新报告到全新 Legacy Session，不改变已有 run、源码快照、accepted result 和终止结算的恢复。

V62 已接通 `PROJECT_CONVENTION_V1` 的 Coordinator、冻结证据和 accepted result；0.3.17 还要求 INITIAL 提示公布当前 `expectedSubmissionRevision`，机械拒绝后只使用工具返回的 `submissionRevision`。0.3.17 隔离 JAR 的两次首投接受当时不满足同 Session 修正门槛，开关仍关闭；随后隔离 0.3.20 JAR 中的真实模型因重复合法组件键被拒绝，在同一 run/Session 第二次提交后接受，取得正向停止证明并结算为 `READY`。因此 0.3.22 起默认开启，`LOOPPER_PROJECT_CONVENTION_CANDIDATE_V1_ENABLED=false` 只回滚新公约，既有 run 恢复不受影响。

V63/0.3.19 已接通 `JUDGE_DECISION_V1`：Requirement/Risk 必须绑定同一冻结 `judge_review_batch`，Legacy 与 MCP 共用 `JudgeDecisionCompilation`，模型只提交 verdict/reason/闭集 evidence IDs，最终自由文本不是权威。0.3.20 将 `reason` 中的 CR/LF/TAB 和普通额外说明字段限定为同 Session 可修正机械错误，危险控制字符先于长度检查，NUL/BEL/C1、混合危险控制字符、权限和语义权威字段继续失败关闭；不可纠正候选停止后直接进入当前批次人工输入，不自动开新 Session。Legacy 入口也在任何远端创建前冻结 prompt/evidence/SHA，完成与 finalizer 只读取该快照。Judge 开关当时保持关闭；随后两个角色分别在隔离 0.3.20 JAR 中主动调用私有工具，接收 reason 换行机械拒绝后在自己的同一 run/Session 第二次提交并接受，两个 PASS 在正向停止证明后结算到同一批次。因此 0.3.22 起默认开启，`LOOPPER_JUDGE_DECISION_CANDIDATE_V1_ENABLED=false` 只回滚新双评审，已有 Judge run 的恢复不依赖当前开关。

三角色的[默认启用资格记录](mcp-default-enablement-qualification.md) 来自真实 `opencode/gpt-5.4` 与隔离成品 JAR；机械缺陷通过测试专用启动 wrapper 追加的一次性模型 system prompt 诱发，服务端、权限及工具请求/响应未修改。这是受控故障后的同 Session 修正和结算证明，不是自然犯错率或统计可靠性结论，生产默认不包含故障注入指令。

验收候选创建、提示、停止和父流程收束必须使用 V51-V55 的持久化协议。internal launch 在远端创建前冻结
owner/source、路由、运行代次、权限/请求摘要和一次性创建凭证，只有回读远端与冻结计划完全一致时才可用
settlement certificate 原子打开 run；未知创建结果只允许 cleanup。每个提示先持久化模型调用和派发边界，
不能确认时不得盲重发。取消、需求替换和首次提示失败先写唯一 termination intent，停止 run/prompt/remote
并取得正向证明后，才允许父 Designer、需求修订和 compilation 收束；后到的取消/替换只能提升既有 ready
失败 intent，不能新建竞态终止路径。

资格与界面必须分别统计 `candidateSessions` 和 `candidateSubmissions`，两者都是独立、非负的服务端事实，不能用模型调用数推导，也不能互相替代：

- 唯一最优：`modelCalls=0`、`candidateSessions=0`、`candidateSubmissions=0`。
- 真实闭集同分：`modelCalls=1`、`candidateSessions=1`、`candidateSubmissions=1..2`。
- 非枚举或路径安全阻断：三项均为 0。

读模型可以不展示值为 0 的 candidate 计数，避免制造无意义噪声；不展示只是一种呈现规则，不能把 0 解释为缺测或从其他计数补算。

大型任务的工作包轨道必须展示服务端持久化的 `candidateRunState`、`candidateSessions`、
`candidateSubmissions`、`compilationSource`、`fallbackReason` 和 `serverCompiled`。默认
`DIRECT_SOFTWARE_DESIGN` 继续隐藏大型任务的审批轨道，但只要单包产生候选事实，就必须
显示独立的单包候选摘要，并投影同一组字段；不能因为隐藏审批轨道而同时隐藏 MCP/兜底事实。
“MCP 已接受”“候选提交 N 次”“Markdown 兜底”和“等待人工补充”都只能由这些字段
驱动；前端不得用模型调用次数、消息数量或 assistant 文本反推提交次数和采用路线。

## Task review decisions and system tools (0.3.28)

The final dual-review panel says AI results are advisory and exposes “人工认定通过” only from
server capability data. Confirmation submits the exact displayed Task/Cycle/batch generation.
After acceptance show its persisted human source without changing the original Judge cards;
reload the Task so ordinary submission, push and merge-request actions become available.

System navigation includes `/tools`. The user selects global runtime or a project, sees live
MCP connection states, and expands a server to read names and descriptions. Descriptions are
escaped text. Unavailable/incomplete tool lists are visibly different from an empty complete
list; no model or tool execution is triggered. Search covers server names and loaded descriptions.
The Quality & Usage page filters project, Task state, quality, archive and title, applying the
same filters to page rows and aggregate values. Its loaded-task count is labelled as loaded.

## Story binding at design creation

The creation form offers an off-by-default story-binding switch. While project-scoped OpenCode command detection is pending, missing or failed, the switch is disabled with a reason and recheck action; normal creation remains available. Project/runtime identity changes invalidate the prior detection. Enabling reveals required text fields for system and story codes; leading zeros survive API normalization, reload and task/Recovery inheritance. Both ordinary and attachment creation carry the same creation-only storyBinding value.

Only Requirement Designers, Package Designers and Implementation record accounting. Each new eligible Session starts with start, never continue; reused Sessions do not rebind. Accounting cannot satisfy or fail the Designer question requirement: its message tools and outputs are isolated before business dispatch.

Accounting failures are persistent Designer system messages or task events, with one notification per call and browser replay deduplication. They never disable automatic design, consume business retries, or replace the business error panel. The bound codes remain visible on the created design. See [story binding](story-binding.md).

### 故事统计活动弹窗

`StoryAccountingDialog` 挂在全局 App，独立于设计创建响应；设计师的首条业务提示发送前也可查看及取消。显示开启/完成阶段、系统与故事字符串、真实用时和严格按统计消息身份提取的模型正文。持续等待不设自动超时；取消只针对当前调用并继续业务，失败不关闭全自动模式。支持并行调用切换、刷新恢复与终态输出快照，关闭结果由服务端持久化且历史升级不重复弹出；完成弹窗本身不是业务确认门。详细协议见 `story-binding.md`。

统计失败、结果未知或取消后显示“重新发起 start／complete”，可用性和禁用原因均由服务端投影。原远端仍用于业务/提问或已有统计运行时禁用；新调用创建后弹窗切换到它，保留旧记录供查看。重试不重启设计或任务，也不自动重发任何失败请求。


### 设计师会话与业务回合（V68）

新 Designer 在 Router 前冻结 `PER_PACKAGE_V1` 策略；缺少该记录的历史设计沿用旧行为。`DesignerConversationCoordinator` 管理逻辑设计会话，业务入口只提供所属设计/包和阶段。单包冻结需求创建 WP-1 时承接需求会话；多包确认全局需求后退休全局会话，各包独立创建并在包内复用。新软件设计的冻结需求来自服务端持久化用户输入、补充和答案，排除模型推断；旧设计及非软件流程保持原有快照兼容规则。

一个会话只允许一个活动业务回合。讨论、包内问题、设计生成均有独立持久化 message ID；每次生成对应新的候选 run，机械修正仍留在该 run。候选运行持有的工作包版本不能被问题等待/回答的界面投影递增，否则正常提问会导致 `CANDIDATE_OWNER_REVISION_STALE`；此时问题阶段由 Designer/讨论记录投影，候选的包归属、源修订、代次和版本校验保持不变。原生答案先持久化再送达远端；必需问题尚未回答时拒绝候选提交。

业务结果保存后，明确批准包/最终确认草稿/彻底终止才退休。单包内部自动批准不等于最终交接；最终确认前修改继续同一会话。已交接后重开产生下一代次，详情 `designConversations` 返回安全标识、scope、generation、实际 Session ID、状态和结束原因。页面保留现有阶段，重新打开或替换时提示新轮次；现有写接口兼容。

会话的模型、运行实例和私有 MCP 能力在创建时固定，不在复用时升级普通会话。不同阶段使用当前阶段提示及消息工具限制，原只读和路径权限保持有效。Compiler、规划、实施、Reviewer 和 Judge 保持各自生命周期。实际会话数按远端 ID 去重，候选提交与模型回合分别累计；Token 按消息身份更新已有用量，不因当前回合过滤丢失历史，也不把统计计入业务。

### 角色提示与当前设计阶段

需求讨论各分支携带已确认画像及专属提问主题，已回答项和任务设置不重复确认。工作包设计只描述语义、路径归属
与已证明的测试目标，不携带编译器 argv/Verifier 字段。逐包闭环的后续设计使用最新 checkpoint；历史聚合设计
中的前置 APPROVED 包仅提供执行承诺。MCP 对象输出取代通用 Markdown 格式要求，但冻结用户的 Markdown-only
选择继续优先。候选模板明确人工评审对象与缺口码，并与生产解析器共同回归。
