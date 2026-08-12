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

A Task in `WAITING_INPUT` must keep its context-specific recovery action when one
is available and also expose a destructive, confirmed cancel action. Cancellation
retains the execution directory, branch, and evidence instead of implying rollback.

When `waitingReasonCode` is `SOURCE_BRANCH_WORKSPACE_DIRTY`, Task detail opens a
non-dismissible **发现未提交文件** dialog backed by the server's current Git
snapshot. It lists every path and requires one explicit `提交 / stash / 移除`
decision per file before **重新检查并继续** is enabled. Removal has an additional
destructive confirmation. The only alternate exit is the confirmed **取消并标记任务失败**
action, which must leave local files unchanged. Snapshot conflicts refresh the
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

Task responses expose the current `waitingReasonCode` and authoritative
`loopRetryAvailable` projection. When the current wait reason is
`LOOP_STAGNATION_DETECTED` or `LOOP_FRESH_SESSION_REQUIRED`, Task detail explains
the stop and offers a confirmed **继续一轮** action. Historical errors never
enable that action. The browser does not infer progress or reset the streak
locally; the server records the override and returns the authoritative Task
state.

Designer handoff states are also explicit: `PENDING_HANDOFF`, `RUNNING`,
`COMPLETED`, and `SESSION_ERROR`. The console polls an active read-only Session
and persists/displays an ASSISTANT message only after real OpenCode output is
available; it never renders a queue notice as model output. A transient browser
GET failure keeps the Session in a bounded-backoff reconnect loop, and the
current Designer Session/draft pair is restored after a same-tab page refresh.
The Session API returns its bound draft on every poll. A validated Designer
reply therefore replaces the right-hand LoopSpec editor automatically while
preserving any locally dirty editor text for explicit human resolution. The
structured editor round-trips every LoopSpec limit, model selection, Session
policy, and next-Attempt prompt template before confirmation. Model
responses render as sanitized Markdown; fenced `mermaid` blocks render as SVG,
and the machine-only LoopSpec payload is not shown in the conversation.

New drafts use LoopSpec v2. Each Stage declares observable
`acceptanceCriteria`. Each criterion selects `verificationMode` as `MACHINE`,
`JUDGE`, or `BOTH`: machine modes require at least one server-classified
`BEHAVIOR` verifier mapped through `criterionIds`; Judge modes require an
explicit `judgeRubric`, and Judge-only criteria also require `judgeOnlyReason`
and cannot carry machine behavior coverage. Every Stage retains at least one
blocking deterministic verifier even when all of its criteria are Judge-only.
Review Gate renders machine coverage and planned final Judge review separately;
planned review is never labelled as executed or passed. Invalid planning
blocks Designer synchronization, manual save, template publication, MCP
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

The page performs one reconciliation on entry and when browser focus returns,
with a 30-second cooldown and no background polling. Missing GitLab credentials
show **无法自动确认合并状态**. GitHub displays that automatic merge confirmation
is not yet integrated. The browser never infers a merge from a missing branch;
all labels and actions project the server's persisted Publication state.

Each Task detail page also exposes a read-only **Model output / Thinking**
monitor. The operator can select any implementation or judge Session belonging
to that Task. While a Session is active, the panel polls frequently and follows
the newest provider-exposed `THINKING`, `OUTPUT`, and `TOOL` parts; terminal
Sessions remain selectable as history and refresh at a slower interval. Before
the first model text arrives, an animated thinking indicator makes the active
handoff distinguishable from an error or empty state.

Designer acceptance criteria are not advisory prose. `PROCESS` is classified by
the server: compile/package/build/typecheck/lint/install are `BUILD`; a
recognized non-skipping Maven/Gradle/npm test with `testTargets` is `BEHAVIOR`;
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

For Java production changes, Designer defaults behavior criteria to `BOTH` and
keeps the production code and focused Maven/Gradle unit test in the same Stage.
The planned test target is a deliverable and need not exist while Designer is
still read-only. Missing-target bypass flags are forbidden. `SELF_CHECK` is a
fallback only when a normal test framework cannot express the behavior; shell
launchers/fragments, source-text searches as behavior proof, and `java -e` are
rejected or repaired before confirmation.

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
