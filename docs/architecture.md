# Architecture

OpenCode Loopper is a local modular monolith: Spring Boot owns authoritative
state and verification, Vue renders the console, SQLite persists state, and a
local OpenCode server performs model Sessions inside the registered project
checkout after Loopper switches it to a serialized per-Task Git branch, or,
when Git HEAD is unavailable, directly inside that same registered root.

## Module boundaries

- `project`: registered roots, Git baseline validation, and immutable module-level stack profiles
- `designer`: real read-only OpenCode planning Session and versioned Loop drafts
- `project conventions`: a forced deterministic stack refresh followed by read-only
  OpenCode regeneration of a persisted `AGENTS.md` preview; an explicit local-UI apply,
  matching source hash, and matching manifest fingerprint are required before the
  project file is created or its Looper block is updated;
  apply first commits `APPLYING`, writes the file outside SQLite, then commits
  `APPLIED`, and restart recovery compares the original/proposed hashes before
  completing or failing the interrupted write
- `task`: Task, Stage and Attempt aggregate
- `orchestrator`: state transitions, retry policy and recovery
- `opencode`: HTTP/SSE adapter and managed process lifecycle
- `verifier`: bounded-worker direct-process, file and Git diff evidence
- `workspace`: source-branch/direct-root selection, FIFO writer lease, baseline lifecycle and path containment
- `event`: persisted timeline and browser SSE
- `judge`: independent read-only Requirement and Risk final review Sessions
- `artifact`: immutable diffs, verification summaries and Judge metadata/results

Controllers accept validated DTOs and delegate to application services. Only
repositories/mappers update SQLite. Process and HTTP details remain behind
adapters so deterministic fakes can exercise the orchestration state machine.
The implementation follows [the code design contract](code-design-contract.md):
large workflow facades remain compatibility coordinators while changing axes are
owned by focused collaborators. `DesignerSessionService` delegates prompt and
frozen package-context construction plus compact package-plan normalization,
semantic validation, executable evidence compilation, and question capability /
decision-log handling; its shared machine payloads live outside the workflow facade.
`DesignerQuestionSupport` owns the latter compatibility policy but never advances a
Designer lifecycle. `TaskService` delegates retry decisions,
execution prompts, Judge response parsing, lifecycle row persistence, and immutable
design/verification/diff/Judge evidence assembly;
`RollingPackageService`, `RollingPackageCheckpointService`, and
`RollingPackagePlanService` separately own rolling package commands, the
Checkpoint-to-fact saga, and confirmed plan revisions; `RollingPackagePlanGenerationService`
owns the restart-safe read-only AI suggestion transport and `RollingPackageReadService` owns the
lightweight workbench projection. None of those collaborators rewrites an accepted
LoopDraft or absorbs package state into Stage/Attempt/Designer enums.
`HttpOpenCodeClient` delegates permission policy and response/Todo/machine-output
parsing; `GitWorktreeManager` delegates branch naming, dirty-workspace handling,
and checkpoint operations. These collaborators do not own the parent lifecycle.

`LoopperMapper` is a compatibility aggregate only. SQL statements are grouped by
infrastructure, project, Designer, and Task mapper interfaces so new services can
depend on the narrow persistence boundary. Cross-aggregate transaction callers
may retain the aggregate type, but new SQL must be added to the owning fragment.
The web boundary forwards extensionless browser-history paths to the packaged
SPA, including unknown deep links, but excludes API, actuator, asset, and
file-extension namespaces so operational 404s are never replaced with HTML.

Browser SSE is a best-effort projection of authoritative server state, not a
runtime lifecycle input. Persisted Task events become eligible for live
publication only after their transaction commits, and each subscriber is
isolated so one stale browser connection cannot interrupt persistence or other
subscribers. An `IOException`, closed Tomcat `AsyncContext`, timeout, or browser
disconnect only removes that subscription; it must never create a Designer,
OpenCode Session, Attempt, or Task failure. Task streams recover with persisted
event replay from `Last-Event-ID`; Designer streams recover from the latest
persisted snapshot.

Designer discovery is server-authoritative across process restarts. For each
project, the API exposes the latest Designer Session per draft whose draft is
not `CONFIRMED`; the Project projection exposes that collection's count on a
separate axis from persisted Task rows. Browser storage can accelerate reopening
one Session but cannot be the only index, and a temporary API failure must not
erase that pointer. Confirmation removes the draft from this recovery list at
the same boundary where the persisted Task becomes discoverable.

## Read models and bounded responses

SQLite remains the authoritative store and runs in WAL mode. Query-facing APIs
use dedicated read-only services and mappers instead of rebuilding list and
detail responses through lifecycle services. `CursorPage<T>` uses an opaque
timestamp-and-ID keyset cursor; list endpoints default to 50 rows and reject
limits above 100, so equal timestamps do not create duplicates or gaps.

`GET /api/tasks/summaries` contains only list columns and facets. Task detail
loads `/overview` first, then `/audit`; verification evidence, error evidence,
Judge raw output, and artifact content are task-scoped lazy endpoints. The audit
projection joins Attempts to Sessions and unions error/Judge/artifact metadata,
so its three database queries are independent of the number of Attempts. Large
`spec_json`, `evidence_json.output`, `raw_output`, and artifact `content` columns
never enter summary, overview, or audit responses.

Task summary grouping is server-owned. `statusGroup=PROCESSING | SUCCESSFUL |
TERMINATED` is mutually exclusive with explicit `status`; processing means every
nonterminal Task, successful means `COMPLETED` plus historical `SUCCEEDED`, and
terminated means historical `FAILED` plus `CANCELLED`. Facets expose those three
groups, `MATCHED_TOTAL`, and `ARCHIVED_TOTAL` from the same project/search/archive
population without depending on pagination. The browser consumes these values and
must not maintain a second production status-group list.

Designer history uses a window function to select the latest Session per draft,
and project counters use grouped CTEs instead of per-project lookups. Projects,
insights, templates and automation runs are assembled in bounded batch queries.
V33 adds keyset and child-lookup indexes for Tasks, Designer Sessions, Attempts,
execution Sessions and automation runs. Project Git inspection is outside
SQLite, cached for five seconds, and refreshed through at most four workers.
Task summary and overview select exactly one retry schedule by state priority and
latest update; a transient or recovered `CLAIMED` row may coexist with a new
`SCHEDULED`/`PAUSED` row but must never multiply a single Task projection.
The overview also carries every server-owned boolean used to expose Task-detail
actions (`loopRetryAvailable`, `cancellationAvailable`) and adjacent history/archive
controls. Those fields are required in the frontend overview adapter: an incomplete
projection fails over to the compatibility detail endpoint instead of silently
hiding an action by coercing a missing value to `false`. List summaries remain
intentionally smaller and do not expose Task-detail capabilities.
Rolling overviews additionally require `executionMode`, workspace policy, current
package/frozen counts, and the complete package capability object. Missing capability
fields fail the frontend adapter and trigger the full-detail fallback; clients may
not infer package actions from Task or package enums.
Runtime data is requested only on its own route; SSE invalidates overview and
audit independently with a short coalescing window. JSON/text responses above
2 KiB are compressed, Inbox responses use shallow ETags, and read-model metrics
use only fixed projection names as tags.

V34 persists one optional `designer_auto_mode` row per Designer Session. Missing
historical rows project as `DISABLED`. The independent lifecycle uses optimistic
locking and records enable, disable, block, and completion transitions. The
750 ms Designer monitor invokes the auto coordinator only after polling current
read-only handoffs; each Session is process-deduplicated and advances at most one
existing authority boundary per tick. Confirmed drafts and already-started Tasks
are reused so restart recovery cannot duplicate a Task or execution request.
The first ordinary-mode task profile is an explicit human gate. An authorized full-auto Session
may persist a successful, server-validated Router intent/artifact as `AUTO_RECOMMENDED` without
inflating its confidence, then continue on a later tick. Timeout, run failure, unsafe-operation
evidence, and required component selection remain blocked for human handling. Legacy
`BLOCKED + TASK_PROFILE_DECISION_REQUIRED` rows may
take one dedicated `RESUME` transition before that action; all other blocked causes retain
explicit disable/re-authorize recovery.

V35 adds a frozen task profile before workflow construction. `designer_task_profile`
stores intent, workflow template, mutation mode, artifact kinds, technologies, test
policy, execution strategy, Role Pack version, confidence and bounded evidence;
historical rows without it project as `LEGACY_SOFTWARE` and keep the previous software
path. V38 persists every raw or complete requirement snapshot in
`task_profile_router_run`, including the built-in-tool-denied external Session, response mode, labels,
terminal error and timestamps. Restart recovery polls the same Session; a replacement
discussion must receive a successful abort acknowledgement before superseding the old run or
creating its replacement. The synchronous request starter and the 750 ms monitor claim each
persisted Router run exclusively; if an optimistic update is lost after remote creation, that
unowned remote Session is aborted instead of escaping as a parallel run. The server scans only
bounded, non-symlink manifest/file facts, while an independent
`ROUTER_NO_TOOLS` OpenCode Session returns only intent, one primary artifact, and SIMPLE/PACKAGED
through a fixed marker envelope and the closed `TASK_PROFILE_ROUTER_V2` contract. Historical V1
extra fields remain parse-compatible but cannot influence technology or confidence. Router deliberately does not persist an
OpenCode JSON Schema response format because affected OpenCode desktop versions reject that stored
shape while loading the Session. Router skips MCP discovery, denies every built-in and configured MCP
tool, and on a managed runtime uses a one-step, zero-temperature, non-thinking `loopper-router` Agent.
Its prompt allows only immediate task classification and forbids repository search, design, implementation
planning, or reasoning exposition. Technology/component selection and confidence are derived only from
bounded server evidence plus agreement with those three labels. The AI Router never owns permissions,
commands, workflow selection, or authorization. Session/output failure produces a generic decision-required profile
instead of terminating the Designer. The configurable 240-second Router boundary applies only until
an external Session ID has been persisted. Once connected, the server keeps polling until the remote
Session reaches a real terminal state and exposes no deadline in the read model. An unconnected
deadline records retryable `ROUTER_TIMEOUT`. The latest run is exposed in the Designer read model, and a
versioned reroute can only use its persisted requirement snapshot after the run is terminal. A
versioned user cancellation claims the same run owner, aborts the remote Session, persists the run as
`SUPERSEDED / ROUTER_USER_CANCELLED`, and materializes a provisional profile that only manual override
may advance; abort failure leaves the active run unchanged and duplicate/stale cancellation returns 409. A
successful first ordinary result is persisted but does not create a requirement Designer Session
until confirmation or override; equivalent later reroutes may carry forward the prior explicit
choice. Profile references are copied to
requirement/decomposition/package/Task and Recovery reuses the frozen values.

The server owns five workflow templates. Software and complex maintenance use the full
package lifecycle. Simple documents and tabular conversions compile an implicit `WP-1`
and frozen `artifact_plan` without Decomposer or package Designer repetition. A normal
Attempt is created only after Start; `SERVER_DOCUMENT_MATERIALIZATION` and
`SERVER_TABULAR_CONVERSION` write atomically and proceed directly to native verification
without an OpenCode Session. Large documents require 2–6 level-two sections; their bounded
structured blocks are kept in source order and deterministically aggregated into one
frozen plan. Every decomposed software package freezes its own detected technology list,
Role Pack version, execution strategy and test policy before its Designer/Compiler prompt.
Role Pack `2026-08-dynamic-v6` groups normalized technology aliases into Java, Python,
Node and Other software families. Node matching precedes Java so JavaScript is not Java;
same-family aliases do not create a mixed pack, real cross-family work does, and explicit
unknown single stacks use `software-generic` instead of inheriting Java/Maven. Document,
tabular and report profiles keep their server-owned or Reviewer execution paths and never
enter Compiler merely because a software example was available.
JUnit/Jupiter/Surefire labels remain in the Java family instead of creating a false mixed stack.
Package-local technology discovery uses token boundaries, so domain symbols such as
`ChainNodeInvoker` cannot manufacture a Node family signal.
Acceptance compilation consumes only positive deliverable-to-test relationships, uses target-symbol
and title-weighted semantic competition with one unique winner to bind each scenario once, preserves
independent required test targets exactly once as source-backed criteria, and derives the final outcome
on the server. New v6 designs carry an exact stage table; ordinary `DIRECT_SOFTWARE_DESIGN / WP-1`
uses that frozen topology directly when every fact and capability has one legal binding. Frozen v5 packages may resume
through their compatible historical path; newly frozen packages use v6.
Rows created before that freeze is complete are not valid Role Pack snapshots: read-only
Designer projection omits the incomplete snapshot, and the next package-role use repairs it
from the current or legacy task profile instead of parsing nullable enum columns.
V37 copies that package decision into each confirmed Stage, so retries and Recovery compose
implementation prompts from the immutable Stage snapshot. Read-only review/research creates
an independent `REVIEWER_READ_ONLY` Session. V39 requires `REVIEWER_REPORT_V1` structured
findings, validates every managed path and exact line before deterministically rendering
Markdown and hashing sources, persists the contract version/findings/response mode/deadline
in `analysis_report`, and never
creates a Task, lease, branch, Attempt, or writable Session. Safe local maintenance uses
one implicit package with exact relative paths and a mandatory no-delete `GIT_DIFF`; the
draft confirmation gate rejects wildcard paths, process/browser/database verifiers,
deletion, service control and external writes.

## Authoritative lifecycle state machines

Persisted business lifecycles use the project-local `FiniteStateMachine` rather
than an external state-machine dependency. Each machine has typed State and
Event enums with an internal Chinese `description`; the stable English enum
name remains the only database and API protocol value. Services retain domain
guards, while the machine is the final topology barrier before a versioned
mapper mutation.

`LifecycleTransitionService` commits the optimistic state mutation and its
`state_transition_event` audit row in the same SQLite transaction. Task child
machines share the Task scope, Designer/Draft/ProjectConvention share the
Project scope, Designer auto mode uses its Designer Session scope, and
workspace/automation records use their stable fingerprints
or rule ids. Audit metadata is bounded and must not contain prompts, tokens,
permission payloads, content, or filesystem paths.

Lifecycle transitions and ordinary optimistic-lock updates are separate APIs.
Calling `transition` with the same source and target does not silently become a
field update: it is rejected unless the machine declares an explicit business
self-transition event. Projection/content/heartbeat updates use the audit-free
`mutateWithoutTransition` path and mapper statements that do not write state.

External process, Git/filesystem, OpenCode HTTP, model-usage and verifier I/O
never runs while a SQLite transaction is active. Task confirmation performs only
read-only project/rework validation, then atomically commits the Task, Stages,
frozen design context and draft confirmation in `PENDING_START`; it does not create
a queue row, acquire a lease, fetch Git refs, create a branch, or switch the
registered checkout. An explicit execution request resolves the current workspace
identity outside SQLite, atomically records `PENDING_START -> QUEUED` together with
queue admission/lease acquisition, then performs checkout and preparation outside
the transaction. Session/Judge cleanup, polling and retries likewise persist each
state boundary separately from provider calls. Deterministic verification first
enters `VERIFYING` in a short transaction, runs process/HTTP/browser checks
outside the database lock, and commits their results plus the next lifecycle
decision in a second short transaction. Restart recovery handles the deliberate
post-commit gaps, and final evidence capture is idempotent.

Every new Task terminal transition is guarded by one aggregate consistency
boundary. Before `COMPLETED`, `SUPERSEDED`, or `CANCELLED` commits, all package
Runs, Attempts, Stages, Execution Cycles, Designer-owned child lifecycles, Queue
rows, and Leases must already be terminal or be finalized in that same short
transaction. A remote stop failure, optimistic conflict, active writer, active
Verifier/Judge, or inconsistent Queue/Lease pair fails closed and leaves the
parent nonterminal. Historical `SUCCEEDED` and `FAILED` remain readable
compatibility states but are never valid creation states or new transition
targets.

The transition history is forward-only from Flyway V15: existing rows are not
given fabricated creation events. `GET /api/state-transitions` can page either
one machine/entity or one aggregate scope in ascending sequence order. The
absence of earlier events for a pre-V15 entity is therefore not evidence that
the entity had no prior transitions.

OpenCode `external_session_state`, Designer message delivery state, provider
Todo snapshots, and immutable verifier outcomes are projections or results,
not Loopper-owned lifecycle state. Refreshing those values never creates a
business state transition. Implementation Todo snapshots are replaced only
when their bounded contents change; provider reads run outside SQLite and a
Todo transport failure cannot fail or complete a Session, Attempt, Stage, Task,
verifier, or Judge.

## Error layers

`ErrorLayer` is part of the public persisted contract:

- `FIELD`: request or draft validation; no runtime transition.
- `VERIFICATION`: an Attempt did not satisfy evidence; continue the Loop.
- `SESSION`: the OpenCode Session failed; finish the Attempt, create a fresh
  Session and continue the current Stage while limits remain.
- `TASK`: the current execution cycle cannot continue safely; abort children,
  record a failed cycle, freeze the workspace, and enter `AWAITING_DECISION`.

A Session adapter must never finalize a Task. It emits a typed
`SessionFailure`; the orchestrator owns retry/promotion. Exhausted Session
retries are promoted to a failed execution-cycle result with
`TASK/SESSION_RETRY_EXHAUSTED` and the complete chain of evidence.

OpenCode `RETRY` is not a `SESSION` failure. It is provider-managed recovery on
the same remote Session for every Loopper caller, including Designer and
machine roles, Implementation, project-convention discovery, publication
suggestions, and local synchronization. Loopper keeps polling the existing
Session without creating an Attempt/Judge, consuming a Loopper retry budget, or
persisting a Session error. The caller's existing operation timeout remains the
hard boundary; only a real remote terminal failure or that local timeout enters
the normal failure contract.

An application restart follows the same Session boundary. Loopper best-effort
aborts the old external Session, persists it and its Attempt as disconnected /
`SESSION_ERROR`, and preserves or creates a persistent `RETRY_WAIT` schedule
while limits remain. A Task-level failure closes every active Attempt and child Session; no
child is allowed to remain `RUNNING` while its parent waits for disposition.

An execution-cycle result does not fabricate remote terminality. A parsed `true` response from
OpenCode's HTTP abort endpoint is a positive termination acknowledgement; a 404 proves the exact
remote Session is already absent. If abort is unacknowledged and an independent status read also
fails to prove that a writer stopped, its local
Session becomes `DISCONNECTED` with `SESSION_ABORT_UNCONFIRMED`. The monitor
persists up to `loopper.abort-cleanup-attempts` abort retries across restarts;
success becomes `ABORTED`, while exhaustion stays `DISCONNECTED` with explicit
`SESSION_ABORT_CLEANUP_EXHAUSTED` evidence. Cleanup never creates an Attempt.

When a terminal holder blocks a queued Task, restart rehydration and the explicit
local queue action must re-probe `DISCONNECTED` writers instead of treating the
local row as a terminality proof. A positive abort acknowledgement, already-absent response,
or independent terminal status observation persists
`SESSION_ABORT_CLEANUP_CONFIRMED` even when the local Session was already terminal;
only then may the ordinary lease reconciler transfer ownership. Failure retains
`RELEASE_PENDING` and never force-releases the root.

Before every Session retry, Loopper aborts the old mutating Session. If abort
fails and that Session cannot be independently observed in a terminal state,
safe continuation is impossible. Recovery then promotes the condition to
`TASK/SESSION_ABORT_UNCONFIRMED` and does not start an overlapping writer.

V31 persists one active retry schedule per Task. `RATE_LIMIT`, `SESSION`, and
`VERIFICATION` use deterministic capped exponential delays of 60/300, 10/60,
and 5/30 seconds by default. Provider 429, `too frequent`, and `rate limit`
signals enter the first class. Only a confirmed-stopped old writer may create a
schedule; the monitor atomically claims a due row before creating one fresh
Attempt/Session. Restart keeps the recorded due time, and a historical
`RETRY_WAIT` row without a schedule receives a normal Session delay rather than
an immediate retry. Pause freezes the remaining seconds, resume re-arms that
duration, and terminal Task states cancel the active row. Settings changes only
affect schedules created afterward, so visible countdowns never move retroactively.

The grouped settings document is stored in SQLite and mirrored to
`${LOOPPER_DATA_DIR}/config/startup-overrides.properties` for the next process
start. The file contains only a fixed non-secret whitelist. Its atomic replacement
participates in the settings transaction through compensating restoration of the
previous bytes on failure. Startup scripts parse keys and values as data without
`source`, `eval`, or batch `call`; explicit process environment values win over
the mirror, which wins over script defaults. Runtime safety limits are applied as
the smaller of the global setting and the explicit LoopSpec value.

## Verification and final approval

Deterministic verifiers run on a dedicated bounded executor rather than the
scheduler thread. `PROCESS` is an argv contract and rejects shell launchers;
its runner terminates the observed process tree on timeout or output overflow.
On Windows, the runner resolves project `mvnw`/`gradlew` aliases against the
Task directory and other bare executables against the Loopper process
`PATH`/`PATHEXT` (`.COM`, `.EXE`, `.BAT`, `.CMD`). It records the resolved
absolute executable and forces the JDK's strict Windows command quoting mode;
the LoopSpec still cannot supply `cmd`, PowerShell, shell syntax, expansion,
pipes, or redirects. Linux/macOS retain native direct-argv lookup and executable
permission semantics. The Task `maxDurationSeconds` deadline remains
authoritative after entering `VERIFYING`: every verifier and failed-attempt
handoff receives the smaller of its configured timeout and the remaining Task
budget, while the monitor can fail an already-running verification and late
results lose the optimistic state check. The runner is not an OS sandbox. A
deliberately daemonizing hostile executable must be isolated by an external
Job Object, cgroup or container rather than trusted as a LoopSpec verifier.

For v2 `PROCESS` entries with `processPurpose=TEST`, executable recognition is
an exact basename allowlist for Maven, Gradle, and npm rather than a prefix
match. Split and joined Maven/Gradle/npm skip flags are rejected. The same
policy runs again immediately before process launch, after deterministic Maven
argv normalization, so an older persisted contract cannot bypass the current
test-evidence boundary. A TEST mapped to a business criterion still needs
explicit targets. A safe full-suite command with no targets and no criterion
mapping may be a blocking supplemental `REPORT`, but it cannot provide
`BEHAVIOR` coverage or satisfy the focused Java-test gate.

LoopSpec v2 acceptance analysis is a single server service shared by REST,
MCP, Compiler synchronization, draft save/confirm, templates and Automation.
Each criterion declares `MACHINE`, `JUDGE`, or `BOTH`. `MACHINE` and `BOTH`
require a valid mapped `BEHAVIOR` verifier; `JUDGE` and `BOTH` require an
explicit rubric, while Judge-only criteria also explain why deterministic proof
is unreliable. Every Stage still has a blocking deterministic gate. Evidence categories are
`BUILD`, `BEHAVIOR`, `SCOPE`, `SAFETY`, `REPORT`, and `ADVISORY`; only the second
forms machine coverage. Persisted v2 criteria without an explicit mode default
to `MACHINE`; persisted v1 contracts retain their historical behavior and are
never upgraded in place. New drafts, imports, and template versions are v2.

V21 persists the Designer-to-Compiler workflow without merging lifecycle axes.
`designer_session` records workflow phase, frozen design revision, and redesign
count; messages persist their stable actor; `loop_spec_compilation` binds each
Compiler Session to one source design message and draft version. Designer and
Compiler are independent read-only Sessions. Compiler repair is bounded to two
turns after initial compilation; semantic gaps allow one automatic complete
redesign. Trace excerpts must be exact substrings of the frozen design. The
server, not either model, determines validity and performs optimistic draft
synchronization outside model calls.

V21 also stores an immutable Stage-start production-Java path/hash baseline.
That baseline and its focused-test gate apply only to OpenCode software implementation stages;
server-owned document and tabular stages proceed directly from materialization to their native verifier.
For v2 `JAVA_PRODUCTION`, added, modified, or rename-target production `.java`
files require a successful focused Maven/Gradle test from the same Stage. Test
trees and generated `target`/`build` trees are excluded; deletion alone remains
under existing scope/risk rules. Actual Java changes in `JAVA_TEST_ONLY` or
`NON_JAVA` fail classification. File traversal and hashing stay outside SQLite
transactions for both Git and Direct workspaces.

V22 adds a lifecycle before package Designer. V27 places an explicit requirement
discussion gate in front of it. For ordinary software, the model asks once but does not
author the requirement: the server assembles a recoverable snapshot from chronological
user inputs and persisted answers, writes the exact content as `SERVER_REQUIREMENT_SNAPSHOT`,
and uses that message as the frozen revision source. AI prose, repository inference, and task
profile labels are excluded. Historical frozen AI snapshots remain compatibility baselines;
newer user input is appended. The 24 KiB UTF-8 limit fails with
`REQUIREMENT_SNAPSHOT_TOO_LARGE` rather than truncating or summarizing. Large-task sessions
retain the complete AI replacement snapshot. Neither mode invokes Decomposer before confirmation. Only the scoped requirement
confirmation freezes the next numbered `design_requirement_revision`. New software
sessions default to `DIRECT_SOFTWARE_DESIGN`: the server creates a compiled
`DIRECT_DESIGN / WP-1` context without an external Decomposer. Historical and explicitly
large sessions retain `FULL_PACKAGE_DESIGN`, which starts the independent read-only
Task Decomposer and returns 2–6 dependency-ordered vertical packages, `NEEDS_INPUT`, or
`MULTI_TASK_REQUIRED`; the server numbers and verifies requirement-segment
coverage, package identity, backward-only dependencies, and the single-Task
boundary. After decomposition, an unscoped user message is rejected; only an
explicitly confirmed requirement reopen supersedes the old decomposition and
package results without deleting their audit history. Direct WP-1 enters `DESIGNING` without
`QUESTIONING_PACKAGE`; its initial design and every replacement use the non-interactive
general read-only role. Direct WP-1 is automatically
approved only after Compiler and deterministic validation pass; aggregation and final
human confirmation are unchanged. `LARGE_TASK_MODE_REQUIRED` stops direct mode without
redesign or auto-switching and is recovered only by an explicit reopen into large mode.

V30 evolves the historical V23/V24 two-turn protocol into one compact semantic
turn per Decomposer/Compiler candidate. The server derives status, IDs, reverse
references, exact Designer sources, test targets and verifier mappings, validates
the normal LoopSpec v2 contract, and compiles the final envelope without another
model call. `semantic_plan_json`, independent format/semantic repair counters and
`server_compiled` make this restart-recoverable; old active final-generation rows
with planning JSON are completed server-side. A one-package flow saves two calls
and a six-package flow saves seven, so the latter needs 13 machine-role calls
instead of 20. V27 retains the 96-call ceiling so
interactive requirement/package revisions use the same explicit budget.
Confirmed transport retries and all content repairs still count against it.

New Compiler rows treat the compact semantic object as the default. Only an explicit
historical `evidenceMappings` member selects the legacy planning parser, so a malformed
current object without `outcome` cannot be misreported as a missing legacy `status` field.
The server persists a semantic snapshot only after a legal outcome is extracted, and a
`COMPILED` outcome additionally requires a nonempty Stage set; a repair envelope or malformed
object therefore cannot erase the last usable plan. Direct-mode transport and Schema bounds accept 1–6 Stages, while the
large-package business validator still enforces 1–3.

The semantic Compiler separates observable business criteria from engineering
metadata. Untested code-style, source/annotation/assembly-shape, build/test-result,
and delivery-hygiene entries remain available through the frozen design but do not
create artificial acceptance conditions. Evidence indexes are remapped, and a
single focused Java test can deterministically cover otherwise unmapped business
criteria in the same Stage; multiple candidates or missing real tests still fail.
Preflight batches all deterministic semantic errors with JSON Pointers into one
repair response, while the authoritative verifier continues to reject source-text
search as behavior evidence.
Every Compiler format or semantic repair first aborts the repository-reading Session and
uses a new `COMPILER_REPAIR_NO_TOOLS` profile. Format repair is constrained by the compact
Compiler Schema; semantic repair is constrained by `AI_SEMANTIC_PATCH_V1`, matching the
requested `patches` envelope. Java test targets retain the Maven/Gradle extractor, while
Python and Node targets are compiled through the shared pytest/unittest/npm registry.
Within that bounded semantic patch space, `/stages/<n>/verifiers...` is a uniquely reversible
alias for compact `/stages/<n>/evidence...`, and `replace` of an absent object leaf is normalized
to `add`; both corrections are audited before the entire semantic contract is re-run. Each
`JAVA_PRODUCTION` Stage still requires a focused Maven/Gradle TEST even if its criteria are
Judge-only, so supplemental full-suite/build evidence cannot create an untested Java Stage.

V26 adds orthogonal OpenCode capability and response-contract metadata without
merging lifecycle axes. Decomposition and compilation rows persist
`response_mode`/`schema_id`; Judge rows persist the same contract; implementation
Session rows persist `todo_capability`. V26 originally registered five stable
server-owned JSON Schemas; V30 new work selects compact Decomposer/Compiler plus
Judge schemas and retains the two legacy final schemas only for historical recovery. OpenCode's
provider retry count fixed at zero. Explicit format rejection, typed
structured-output failure, or missing structured data uses a fresh read-only
role Session and the same persisted Loopper repair/model-call budget to fall back
to the legacy marker parser. V26 defaults existing rows to marker mode and Todo
capability unknown, preserving restart behavior.

V27 makes human discussion and approval first-class persisted state. The
`design_discussion_revision` table stores complete Markdown snapshots, question
decisions, scope/revision concurrency tokens, candidate compilation references,
and errors for the overall requirement or one package. `designer_session`
projects `DISCUSSING_REQUIREMENT`, `QUESTIONING_PACKAGE`, `REVIEWING_PACKAGE`,
and `FINAL_REVIEW`; packages project `QUESTIONING`, `REVIEWING`, `APPROVED`, and
`STALE`. Normal review uses `REVIEWING`, while `WAITING_INPUT` is reserved for
budget exhaustion or a failed model/validation path that needs recovery. Each
package permits five human revisions after its initial design, and one current
requirement revision permits 96 model calls across discussion, decomposition,
design, compilation, format recovery, and repairs. Question answers resume the
already counted model turn and never create a hidden call.

The Designer Session DTO projects the latest non-empty requirement snapshot with its
discussion revision, `SERVER_ASSEMBLED | AI_ASSEMBLED` source, Markdown, and timestamp.
The server-owned source message remains available for audit and freezing but is excluded
from the ordinary System-message disclosure to avoid rendering the same snapshot twice.

V28 persists bounded AI-output handling events and Project Convention
normalization notices. Each role step can therefore claim at most one tool-loop
finalizer across process restart, and operators can distinguish wrapper/field
normalization from real format or semantic repairs without storing raw model
output in the audit row.

V29 adds recoverable `designer_session_archive` projection rows. Archiving is a
local-UI presentation boundary: it does not delete the draft, discussion
snapshots, questions, candidates, approvals, or messages, and it does not claim
that an external model Session stopped. Archived entries remain available from
the history endpoint but are excluded from project `openDesignerSessionCount`
and the active recovery query; restoring removes only the archive projection.

Designer task-profile confirmation is an explicit derived decision axis, not another
workflow enum or database migration. The current profile projects
`ROUTING / NEEDS_CONFIRMATION / CONFIRMED / FROZEN` plus `confirmationReady`. A persisted
`USER_CONFIRMED` or historical `USER_OVERRIDE` may be carried across a complete-requirement
reroute only when intent, primary artifact, workflow, and mutation mode are unchanged and no
new safety conflict exists; the carry-forward source is auditable as
`USER_CONFIRMED_CARRIED_FORWARD`. Changed decisions retain the previous choice for an explicit
comparison and block every design-start boundary until reconfirmed.

Designer cleanup adds real `STOPPING` and `CANCELLED` lifecycle states. The stop transaction
first prevents auto-mode and monitor dispatch, then external aborts cover every active Router,
requirement/package Designer, Decomposer, Compiler, repair/finalizer, and Reviewer Session.
Partial failure preserves `STOPPING` and the unarchived workspace so the same local-UI request
can retry. Complete stop terminalizes child projections, records `CANCELLED`, and archives;
the operation never equates a local state write with an unconfirmed remote stop.
`failedSessions` counts only remote Sessions whose stop is unconfirmed;
`pendingFinalizations` separately reports optimistic local closure conflicts. Router,
profile, decomposition, package, compilation, report, and the Designer Session are
finalized row-by-row with their own version predicates; lifecycle-owned rows use
`LifecycleTransitionService`, and the local terminalization plus optional archive is
one transaction after all remote stops have succeeded.

Designer activity is a bounded read model over the current remote Session plus persisted
workflow step. Interactive Designer may expose thinking/output/tool parts; structured roles
expose tool parts only, so transport observation never leaks raw planning JSON or becomes
lifecycle authority. The API and browser keep only the latest safe fragment and render it in
the existing current-role card inside the message timeline; there is no separate top activity
panel or activity history. The browser refreshes every 1.2 seconds and preserves that one
fragment across a reconnect without altering the remote Session.

V40 adds `model_token_usage` as an orthogonal, display-only read model. Each row belongs to
exactly one Designer Session or Task and one immutable external model Session. Provider token
totals are monotonically upserted, then summed across requirement, Router, Decomposer,
Designer, Compiler, repair/finalizer, Implementation, and Judge remotes in that scope. Active
remote IDs are registered by V40 insert/update triggers so replacement cannot erase an earlier
Session from the aggregate. Active activity parsing reuses the existing message response; at most one other incomplete remote is
reconciled per browser poll. This aggregate powers only the compact numeric window and cannot
advance lifecycle state, fabricate missing usage, enforce a budget, or rewrite the authoritative
per-message `session_usage` evidence.

V41 adds one `design_acceptance_planning` row per work-package compilation. It freezes the
controlled-Markdown design hash, DesignFact catalog and closed verification-capability catalog;
those identity fields are immutable. Package-design source and Designer message persistence have no
fixed byte-size ceiling; the controlled Markdown shape, 64-scenario ceiling and 128-fact ceiling remain
the bounded semantic contract. AI binding, solver diagnostics and failure detail are
versioned updates. New v6 rows resume from this snapshot, while compilations without a row remain
on the historical v3 parser/repair path. Planning state is a closed persistence contract:
`EXTRACTED` is the frozen input, `BOUND` records a valid binding whose deterministic result is
`DESIGN_INCOMPLETE`, `COMPILED` records a fully lowered plan, and `FAILED` records an invalid
planning boundary. Compiler outcome codes are never written directly into this lifecycle column.
The UI reads only a bounded human-facing projection.

V44 adds immutable-origin semantics to that projection through `binding_source`:
`UNDECIDED`, `SERVER_STAGE_HINTS`, `AI_DISAMBIGUATION_V6`, and `LEGACY_UNKNOWN`.
The v6 resolver builds symbols with Unicode NFKC, edge trimming, whitespace collapse, and
Latin case folding; punctuation is retained and substring/fuzzy matching is forbidden.
`SCENARIO` and `REVIEW` facts have exactly one Stage owner, `DELIVERABLE` may be shared,
and dependencies may name only an earlier Stage. Complete direct `WP-1` designs transition
`PENDING_HANDOFF -> RUNNING(SERVER_DIRECT) -> COMPLETED` without an OpenCode Compiler Session
or model call; active `SERVER_DIRECT` rows are selected by the monitor and resume idempotently.
Structural gaps become `DESIGN_INCOMPLETE`. Only unresolved closed-set assignments invoke one
`COMPILER_BINDING_NO_TOOLS` Session using `PACKAGE_ACCEPTANCE_DISAMBIGUATION_V6`; invalid output
becomes targeted `AMBIGUOUS_ACCEPTANCE_INTENT` redesign and never creates an empty or catch-all Stage.
Large software packages still take that single disambiguation turn so they can provide a handoff
summary, but their Stage names, order, objectives, and dependencies remain server-locked.
Historical rows upgraded from V41 use `LEGACY_UNKNOWN`; `serverCompiled` remains compatibility data and
never decides the source label or whether a remote Session should be polled.

V42 adds immutable `project_stack_profile` snapshots and their
`project_stack_component` rows. Registration of a new or re-managed project schedules one
bounded deterministic analysis after the registration transaction commits. Existing managed
projects are not backfilled: they project as `UNANALYZED` until their first convention update
or new Designer creation. Project list SQL joins only the latest persisted snapshot and never
walks registered roots, so historical projects cannot create an N+1 filesystem scan.

The analyzer follows at most 2,000 regular non-symbolic-link files to depth five, skips generated
directories, and recognizes Maven/Gradle as Java, `package.json` as Node, Python configuration or
test files as Python, and Go/Rust as Other. A component is rooted at the signal directory; only
different software families in that same component produce a mixed component. The manifest
fingerprint is SHA-256 over sorted relative signal paths plus their content hashes. A hard read/root
failure persists `FAILED`; a limit or incomplete read persists `PARTIAL`; neither state is silently
converted to Java. `GET /api/projects/{id}/stack-profile` exposes the bounded module projection.

Every Router run, confirmed task profile, work package, and Stage records the exact project-profile
id, manifest fingerprint, and selected component keys it used. Repository paths, module names, and
explicit technology words in the requirement may select only components supported by that snapshot;
the AI labels cannot select a technology or override execution and permission policy. A single family is selected
automatically, explicit cross-component work becomes mixed, ambiguous multi-stack work requires a
component choice, and an empty repository becomes generic plus confirmation. Direct `WP-1` inherits
the confirmed selection; only explicit full-package design may specialize it. Once frozen, Task,
Stage, and Recovery snapshots are immutable even if a later project analysis discovers changes.
An unfrozen Designer sees a new profile only when a new requirement reroute is scheduled, and
carry-forward is legal only when fingerprint, component selection, intent, and workflow agree.

Project-convention generation always forces that analysis outside the SQLite transaction before a
read-only AI Session starts. `FAILED` prevents the Session; `PARTIAL` continues with an explicit
review warning. The prompt includes only the latest structured snapshot and bounded existing file,
and the response must contain `技术栈与模块`, `构建与测试`, and `目录与边界`. Server validation
rejects unsupported technology claims. The persisted convention draft records the profile identity
and fingerprint, and apply rechecks both the current `AGENTS.md` hash and a live manifest fingerprint.
The first apply appends one Looper marker block; later applies replace only that block and preserve
all human content outside it.

V43 adds durable cancellation and design-provenance support. `task_lineage` stores the
root design Task, LoopDraft, and Designer Session used by a derived recovery/rework
Task. The child keeps its independently frozen LoopSpec while design-history reads the
referenced requirement, decomposition, packages, questions, and messages; legacy null
columns resolve by walking the parent lineage without copying history. The same migration
adds `project_convention_runtime`, whose versioned progress fingerprint and last-progress
time survive restart. Project-convention polling fingerprints remote state, latest safe
part/content, part count, and provider token total. A connected generation has no inactivity or
wall-clock deadline and remains `RUNNING` until the remote Session reaches a real terminal state;
explicit user cancellation uses the `RUNNING -> STOPPING` boundary and reaches `CANCELLED` only
after terminal remote status is observed.

Machine-response roles carry an explicit non-thinking model selection for steps whose persisted response
mode is `JSON_SCHEMA`, and Router uses it for its one-shot `TEXT_MARKER` classification. Managed DeepSeek
starts with a private `loopper-no-thinking` variant; other `TEXT_MARKER` initial, retry, fallback, and
finalizer Sessions retain the configured thinking choice or provider default. Markdown Designer and Implementation keep
their configured thinking behavior. This is a transport compatibility rule, not
a new lifecycle state or evidence source.

Managed runtimes define a private `loopper-structured` machine-response agent capped at 24 agentic steps.
Decomposer, Compiler, and Judge select it to bound read-only exploration; Router instead selects the
one-step zero-tool `loopper-router` agent. These do not replace Loopper model-call, repair,
timeout, validation, or lifecycle authority. OpenCode `RETRY` remains a
transient external Session projection for every caller: Loopper keeps the same
remote Session, while Designer workflows also remain `RUNNING`, so provider
self-recovery such as capacity overload neither consumes a fresh-Session retry
nor blocks Designer auto mode. A `RETRY` projection is not a terminal writer
observation and therefore cannot authorize an overlapping Session.
The adapter signs tool calls after the latest user turn using normalized tool
name and canonical arguments. Three identical consecutive signatures trigger
an immediate best-effort abort and, once per persisted role step, one built-in-tools-disabled
finalizer using bounded deduplicated evidence. The finalizer consumes the global
model-call budget but not a format-repair allowance; the 24-step cap remains a
last-resort bound for other loop shapes.
When a terminal structured-role path is persisted, Loopper first makes a
best-effort abort of the active remote Session so a local `WAITING_INPUT` or
`SESSION_ERROR` projection cannot intentionally leave an invisible writer/reader
running. Schema rejection from the message-read endpoint is classified like
prompt-time format rejection and uses the same one-time fresh marker fallback.

The OpenCode adapter owns typed prompts and role profiles: read-only roles deny
all before allowing only repository reads, Designer alone may ask a question,
and implementation explicitly allows discovered `todowrite` while retaining
the existing Git/destructive-command restrictions. Runtime capability discovery
may expose native agents and a `plan` agent, but no lifecycle or role selects it;
Designer Markdown, Compiler JSON, and deterministic validation remain separate.
Capabilities are observational, keyed by runtime/version/provider/model, and
their availability never substitutes for a validated model result.

Machine JSON roles share one bounded compatibility boundary. Native structured
payload and exact role markers are preferred, followed by `json`/untyped fences,
complete objects embedded in short prose, and the complete response. Only
standard object-root JSON is accepted; equivalent candidates collapse, while
conflicting valid candidates, arrays, incomplete/non-standard JSON, oversize
content, and ambiguous normalization are rejected. Unique deterministic
field/collection/enum/argv normalization is audited and enters the same
authoritative semantic validation without consuming a format repair. Project
Convention Markdown uses the analogous marker, unique fence, then full-response
policy and still enforces its reserved-marker and length boundaries.

The historical semantic package Compiler planning contract is version 2. It remains available
only for frozen pre-v6 records and non-deterministic compatibility flows. Current v6 software
packages instead freeze the Designer Stage table and closed DesignFact/capability catalogs first;
the server produces exact `VerifierSpec` objects, source mappings, IDs, and final package JSON.

Package work is strictly serial. A package keeps one healthy interactive
Designer Session across its discussion turns and reconstructs a replacement
Session from persisted snapshots/decisions after transport loss. A v6 direct candidate skips
Compiler when the server resolves every exact reference; a direct ambiguity or every large-task
candidate uses exactly one locked-topology no-tools disambiguation Session. The result produces
1–6 Stages for direct software or 1–3 Stages per large-task package carrying
`workPackageId`, and is deterministically validated into `REVIEWING`; the next
package cannot start until the user accepts that exact design revision. A failed
candidate keeps the previous valid candidate visible. Reopening an accepted
package marks only its transitive dependents `STALE`; unrelated accepted packages
remain valid. Compiler output is a package fragment, not a complete LoopSpec.
For `LEGACY_AGGREGATE` records, after all packages are `APPROVED` the server concatenates Stages in package
order and atomically synchronizes one aggregate LoopSpec at the original draft
version; no model performs a second merge. Confirmation
freezes `REQUIREMENT_CONTEXT`, `DECOMPOSITION_CONTEXT`, per-package
`WORK_PACKAGE_DESIGN` and `WORK_PACKAGE_COMPILATION_SUMMARY` artifacts, plus a
composite compatibility `DESIGN_CONTEXT`. A draft version conflict stops before
the next model call and never creates a Task.

Legacy aggregate package design Sessions read the same immutable pre-execution repository
baseline. An `APPROVED` predecessor means its frozen design/compilation contract
is valid and its Stages are ordered before the current package; it does not mean
the design-time repository already contains its files. The server injects the
predecessor summary/handoff as an available-at-execution contract, and optional Compiler
disambiguation must not turn baseline absence into `MISSING_SCOPE`. For v6, the server owns the
Stage topology and fact binding; the model may choose only listed unresolved assignments and capability
preferences. The server also owns mechanical criterion numbering, uniquely recoverable exact Designer
slices, and propagation of evidence-mapping TEST targets/criterion IDs to the matching verifier. Explicit
focused Maven/Gradle selectors in the frozen design or planned verifier are carried
into the Compiler prompt and deterministically parsed; a unique Stage-level match
may fill omitted duplicate commands/targets or the equivalent TEST verifier. Broad
test suites and ambiguous candidates are never guessed. The normal v2 assessment
still runs after canonicalization and remains authoritative.

V45 gives only newly created `FULL_PACKAGE_DESIGN` software work a distinct
`ROLLING_PACKAGES` execution mode; old Tasks, ordinary software, large documents,
and other artifact flows remain `LEGACY_AGGREGATE`. Package 1 approval atomically
creates one `PENDING_START` Task, active plan revision, package runs, TaskSpec revision,
and only package 1 Stages. It creates no Queue, Lease, execution directory, Attempt,
or writable Session. The accepted LoopDraft remains the immutable design source;
each later approval appends a full cumulative `TaskSpecRevision` and new Stages without
mutating or reordering any executed Stage.

`TaskPackageRun` is an independent lifecycle:
`PLANNED → DESIGNING → DESIGN_REVIEW → EXECUTION_READY → QUEUED → RUNNING → VERIFYING
→ CHECKPOINTING → FACT_FROZEN`. `WAITING_INPUT`, `SUPERSEDED`, and `CANCELLED` are
explicit alternate states. Task uses `PACKAGE_DESIGNING` between a successful
Checkpoint and a candidate design, then `WAITING_INPUT` with one of
`PACKAGE_DESIGN_APPROVAL_REQUIRED`, `PACKAGE_EXECUTION_START_REQUIRED`,
`PACKAGE_EXECUTION_FAILED`, or `PACKAGE_CHECKPOINT_BLOCKED`. Package transitions
use the shared lifecycle service and audit stream; they are never encoded as Stage,
Attempt, Cycle, or Designer states.

Successful package verification closes its `PACKAGE_EXECUTION` Cycle before the
Checkpoint/fact saga. One `PackageFactSnapshot` binds exactly one ready Checkpoint
and successful Attempt, and separates: machine-proven trees/manifest/diff/evidence
hashes, the accepted design/Stage/deliverable/acceptance contract, and a non-evidentiary
AI navigation summary. Prompt injection is capped at 4 KiB UTF-8 per package and
24 KiB total; complete bodies stay on task-scoped lazy evidence endpoints. A failure
candidate Checkpoint may support continuation but never creates a proven fact.

Git Tasks freeze the branch tree, materialize a managed read-only snapshot for the
next Designer, restore the registered source branch, and release the FIFO lease. A
later `PACKAGE` queue admission revalidates canonical root, ref, tree, and manifest
before restoring the same Task branch. Direct Tasks retain their lease and registered
directory throughout the package sequence; their private Git-compatible object database
freezes immutable trees, while every next-design/start boundary compares the live tree
with the preceding fact and fails closed on external drift. Neither policy falls back
to the initial repository baseline.

Plan revisions replace only nonterminal runs after proving that no writer/Verifier/Judge
is active and the latest successful Checkpoint still validates. Confirmation marks old
unfinished runs/designs `SUPERSEDED`, appends a new active plan, and starts only its first
read-only design. Frozen runs remain immutable; a correction is a monotonic new run with
`correctionOfPackageRunId`. The last fact enters `JUDGING`, creates one `FINAL_REVIEW`
Cycle and exactly one Requirement/Risk batch, then uses existing `AWAITING_DECISION` and
single publication semantics.

The implicit direct-software `WP-1` is not reclassified from arbitrary requirement prose: it
inherits the confirmed task profile's software Role Pack, technology family, and test policy.
Only explicit large-task packages may specialize their package role from package-local content.
If a direct-software package was previously frozen with a non-software Role Pack, the next
authoritative role use repairs that inconsistent snapshot before Compiler selection, so manual
recompilation enters the deterministic acceptance workflow instead of the historical compact path.

Both legacy and rolling package flows create exactly one Task, one task branch, and
one publication. Stage execution remains serial, while each package owns an
attempt pool of `min(stageCount * maxStageAttempts, stageCount + 2)` and earlier
Stages cannot consume the reserved first Attempt of an unstarted Stage. Package
reserves do not transfer. `maxTaskAttempts` and safe duration are raised to the
deterministic minimum during aggregation, but token and cost budgets are never
raised. All Stages must pass before the one final Requirement/Risk Judge batch.
The legacy aggregate Stage-to-package mapping is immutable once synchronized into the
Review Gate. Frontend normalization must preserve every `workPackageId`; the
draft update boundary rejects a removed or changed mapping, and confirmation
fails closed if approved packages are missing, unknown, or out of dependency
order. A package Task must never silently degrade into legacy flat Stage
execution.

A v2 Stage may own one temporary verification runtime. Loopper allocates a
dynamic loopback port and private temp directory outside SQLite transactions,
replaces only `{{LOOPPER_PORT}}` and `{{LOOPPER_TEMP}}`, starts a direct argv
root process, and polls bounded readiness before running bound HTTP/JSON/BROWSER
checks. Every exit path stops the observed process tree and records resolved
argv, PID/start identity, port, readiness attempts, bounded output, and cleanup.
Persisted screenshot and trace references always use `/`-separated paths below
`artifacts/`, including when the verifier runs on Windows, so database evidence
remains portable across hosts.
Git-visible changes made by the verifier fail with
`VERIFIER_WORKSPACE_MUTATED`. Unconfirmed termination becomes
`VERIFIER_RUNTIME_TERMINATION_UNCONFIRMED`, leaves the runtime `DISCONNECTED`,
retains the writer lease, and prevents overlapping work.

V19 persists `verifier_runtime` ownership with optimistic locking. Startup
recovery may terminate a residual PID only when its recorded start identity
matches. A missing process is reconciled as stopped; PID reuse or an unprovable
identity is never killed and instead moves the owning Task to manual-safe
failure. Pause and cancel stop active verification runtimes before changing Task
or Attempt state. Process operations and workspace scans remain outside SQLite
transactions.

After a deterministic failure, the orchestrator captures an immutable
`ATTEMPT_HANDOFF` artifact outside the SQLite transaction. It contains bounded
failure and verifier summaries, changed paths, and a workspace content digest.
The digest reads at most 64 changed paths and 16 MiB of actual file content.
It counts bytes while reading and compares size, modification time, and file key
before and after each file; excessive, unreadable, or concurrently changed input
marks the snapshot unreliable, and unreliable snapshots are never compared for
stagnation. A reliable fingerprint combines the Stage id,
failed-verifier signature, and workspace digest. Repeating that fingerprint up
to `limits.stagnationLimit` moves the Task to `WAITING_INPUT` with
`LOOP_STAGNATION_DETECTED` rather than scheduling another model call.

The local UI may explicitly continue that loop through
`POST /api/tasks/{taskId}/loop/retry`. The transition records a
`LOOP_STAGNATION_OVERRIDE`, rechecks budget, lease, writer and Stage state in a
short transaction, and first parses the latest persisted handoff. A missing or
malformed handoff aborts the transaction without changing Task state. After
commit, Loopper starts a fresh Attempt and fresh mutating Session. Manual and
automatic retry prompts use the same bounded `nextAttemptPromptTemplate`;
supported placeholders are
`attemptOrdinal`, `failureSummary`, `verificationSummary`, `changedPaths`, and
`workspaceFingerprint`, and the final retry handoff is capped at 12,000
characters. Implementation transcripts are never reused across
Attempts. Setting `createFreshOnVerifierFailure=false` therefore requires an
explicit UI continuation instead of authorizing reuse of the old Session.

Production scheduling is enabled by default through
`loopper.scheduling.enabled`, and durable ApplicationReady recovery is enabled by
default through `loopper.startup-recovery.enabled`. Integration tests disable both
automatic side-effect paths while retaining monitor and recovery services for
explicit calls, preventing Flyway schema resets and prepared test rows from racing
or contaminating another shared-SQLite ApplicationContext.

File paths are execution-root-relative and symlink-safe. Stage allowed/forbidden
path rules are advisory prompt context and never add an implicit acceptance
gate. When the confirmed LoopSpec explicitly contains a `GIT_DIFF` verifier,
its glob rules are normalized to `/`, matched by a bounded dynamic-programming
engine with identical behavior on all supported operating systems. Compiler
planning, draft persistence, and confirmation reuse that runtime policy to
reject malformed globs and any allowed rule entirely shadowed by one forbidden
rule before creating a Task, Attempt, or writable Session. A broad allow rule
with a narrower sensitive-path exclusion remains valid. Policies are also
rejected at the LoopSpec boundary when size or validation-work limits are
exceeded. Before the
first writable Attempt and OpenCode Session for a Stage, Loopper captures a
private Git tree under `$LOOPPER_DATA_DIR/stage-baselines/<taskId>`. Stages share
one object repository per Task and use separate indexes; the project `.git`,
index and branch are never changed. The persisted
`stage:<taskId>:<stageId>:<treeSha>` marker is reused by every retry and after a
service restart. Capture runs outside SQLite transactions, verifies workspace
stability, retries once, and fails with
`STAGE_WORKSPACE_BASELINE_UNSTABLE` if the workspace keeps changing.

Normal writable Stage `GIT_DIFF` checks and Attempt handoff snapshots compare
against that Stage baseline. Files delivered by predecessors therefore do not
satisfy `requireChanges` or violate a later Stage's narrow paths merely because
they already exist, while a later modification, deletion, or rename of those
files remains visible. Evidence identifies `baselineScope: STAGE` and the
`stageId`. `VERIFY_ONLY` Recovery deliberately keeps the Task baseline and
reports `baselineScope: TASK`; the final automatic Task diff is also Task-wide.
An older active Stage with existing Attempts but no persisted Stage baseline
fails closed with `STAGE_WORKSPACE_BASELINE_MISSING` before another Session is
created. V25 persists the marker by Stage, cascades it with Task/Stage deletion,
and startup recovery removes only private baseline directories whose Task no
longer exists after containment checks. The contained cleanup clears the
read-only attribute from regular Git object files before deletion, which is
required by Windows without widening the managed-directory boundary.

Before entering `VERIFYING`, the
orchestrator performs a second authoritative status read and requires the
implementation Session to be terminal-completed, so the public API cannot race
a still-mutating Session. Only after all deterministic gates
pass do two read-only OpenCode Judges run. Both Requirement and Risk Judges must
receive every Stage's `JUDGE`/`BOTH` criterion and rubric together with the
persisted deterministic summary and diff. The final `VERIFICATION_SUMMARY` v2
artifact is ordered by Stage and records that Stage's successful Attempt and
every verifier result; each evidence excerpt is limited to 4 KiB and retains a
SHA-256 for traceability. Confirmed goal/context/Judge criteria are limited to
96 KiB UTF-8, and the complete runtime Judge prompt to 128 KiB. Before starting
any pending Judge in a review batch, Loopper constructs and validates every
pending role prompt. A runtime overflow in either role creates no Judge row,
read-only Session, or model call for the entire batch and moves the Task to
`WAITING_INPUT` with `JUDGE_PROMPT_BUDGET_EXCEEDED`. They must return an explicit `PASS`;
conflicts, `REVISE`, `BLOCKED`, or unparseable output
move the Task to `WAITING_INPUT`, never to a fabricated success.

Independently of that optional acceptance gate, the final successful Attempt
persists a bounded deterministic baseline-diff snapshot with changed and
untracked paths. Task detail uses this snapshot as its primary diff file list.
While the Task branch is checked out, previews include its working tree; after
publication restores the source branch or admits another Task, previews compare
the persisted baseline with explicit `refs/heads/<taskBranch>`. They never infer
an old Task's diff from whichever branch happens to be checked out later.

## Workspace safety

Planning and confirmation may inspect a registered root read-only. A newly confirmed
Task remains `PENDING_START` with no queue row, write lease, execution directory or
task branch. Cancelling it changes only the Task to `CANCELLED`. The first explicit
start request is the sole boundary that can enqueue the Task, acquire the workspace
lease, fetch refs, capture a baseline and switch the registered checkout. Once the
request is accepted, admission and dirty-workspace resolution continue automatically
through the transient `READY` state into `RUNNING`; users do not click Start twice.

When a project has a valid Git HEAD, execution first snapshots the registered checkout. A dirty checkout
moves the admitted Task to `WAITING_INPUT` while retaining its writer lease and
exposes every porcelain-status path to the local UI. The user must choose
`COMMIT`, `STASH`, or `REMOVE` per path, or cancel the Task without changing the
files. Cancellation interrupts the active Execution Cycle and reaches `CANCELLED`;
it does not manufacture a Task failure. Cleanup is accepted only against the same branch, HEAD,
index, status, and file-content snapshot. After an authoritative clean recheck,
Loopper creates and checks out `loopper/<taskName>` in that registered checkout
itself. Repeated task names use
`loopper/<taskName>(第2次)`, `loopper/<taskName>(第3次)`, and so on when the
corresponding local or remote-tracking branch already exists. Characters
that Git forbids in branch names are deterministically replaced with `-`, and
the readable leaf is UTF-8 byte-bounded before the occurrence suffix is added.
Git ending rules are applied again after truncation so an exposed `.lock`, dot,
or other invalid tail cannot make branch creation fail. Before selecting the
baseline, Loopper performs a bounded non-interactive fetch of the current
branch's upstream (or the matching branch on the unambiguous preferred remote).
A linear remote advance becomes the Task baseline before the registered checkout
is switched to the Task branch; local commits ahead of the remote remain included.
Fetch/auth failure or diverged histories fail closed. Git cleanup and checkout
I/O runs with the caller's SQLite transaction suspended. A persistent FIFO writer
lease permits only one Task to own a registered checkout, so IDEA-bound AgentBridge,
OpenCode and every verifier observe the same canonical directory and current branch.
Any Task still in `QUEUED` may be explicitly cancelled before admission. Cancellation
transitions only that Task and its queue row through a persisted stop intent to
`CANCELLED`; it never releases or
transfers the current holder's lease. The cancelled terminal Task may then follow the
ordinary archive and protected history-deletion flow only after it no longer owns an
active lease.
A Task in the short-lived prepared `READY` state may likewise be explicitly cancelled
before its first writable OpenCode Session starts. The ordinary Task cancellation transition
preserves its branch, execution directory, and evidence, then reuses terminal-holder
reconciliation to restore the recorded source branch and release the workspace lease
only when the existing safety checks pass.
All other active Task states share that stop protocol. `CANCEL` first persists
`STOPPING`, then independently stops and rechecks implementation Sessions, Judge
Sessions, and exact-identity managed verifier processes. Unconfirmed writers keep
the Task, its lease, and evidence in `STOPPING`; restart and the monitor resume the
same intent. Only a fully confirmed stop cancels running Attempts and nonterminal
Stages, records the active Execution Cycle as `INTERRUPTED`, and advances the Task
to `CANCELLED`. This prevents local terminal state from getting ahead of remote work.
For rolling Tasks the same final transaction also cancels every nonterminal package
Run and closes the bound Designer subflow. A remote Designer stop failure or any
optimistic conflict leaves the parent in `STOPPING`, retains Queue/Lease ownership,
and permits the same cancellation command to retry. Queue settlement and lease
release occur only after that complete parent-and-child closure commits.
Task, Queue, and Lease remain separate lifecycle machines. Their cross-machine
invariant is coordinated by `WorkspaceLeaseReconciliationService`: an `ADMITTED`
queue row must name the same Task as the non-`RELEASED` lease holder, and a terminal
holder may complete `ADMITTED -> FINISHED` only after every writable Session and
managed verifier runtime is confirmed terminal, the canonical root fingerprint still
matches, the checkout is clean, and the recorded source branch can be restored safely.
Path identity, Git status, and branch restoration run outside SQLite transactions;
the coordinator then rechecks queue/lease ownership and performs completion plus one
FIFO transfer in a short transaction. Concurrent cancellation, restart, manual, and
background calls are idempotent. A fixed-delay 10-second monitor scans only terminal
holders which actually have a `QUEUED` waiter, while startup recovery and
`POST /api/tasks/{waiterId}/queue/reconcile` reuse the same logic. Blocked checks retain
the lease and audit `AUTO | MANUAL | ARCHIVE | RESTART` without repeating an unchanged
reason. No reconciliation path stashes, commits, deletes, force-switches, or detaches
an active holder. Archive first reconciles and otherwise returns
`TASK_ARCHIVE_WORKSPACE_LEASE_ACTIVE`; permanent deletion independently rejects both
active lease ownership and an `ADMITTED` row.
The local Task branch is not pushed until the post-success human publication action.
Branch checkout has its own bounded
10-minute timeout rather than the short Git-inspection timeout, suppresses
checkout progress noise so the fatal diagnostic remains visible, and enables
Git's command-local `core.longpaths` support for deep Windows repositories.
Otherwise OpenCode edits the canonical
registered root directly and Loopper stores a private Git-compatible baseline
under `$LOOPPER_DATA_DIR/direct-baselines/<taskId>` for deterministic path and
deletion checks. The private baseline does not initialize or commit the target
project. Canonical execution paths must equal the registered root for new Tasks.
Loopper never discards changes, creates a protection commit, or stashes paths
without explicit path-level local-UI decisions. A `REMOVE` decision is destructive
and requires a second confirmation. Because external Git operations cannot be one
SQLite transaction, a later action failure preserves completed Git actions and
returns a refreshed snapshot rather than claiming rollback.
After either Judge success or an unrecoverable execution failure, V32 records an
immutable execution-cycle result and moves the Task to `AWAITING_DECISION` rather
than finalizing it. Only after all mutating writers are confirmed stopped does
Loopper freeze tracked, deleted, and untracked changes into
`refs/loopper/checkpoints/<taskId>/<cycleId>`, clean the registered checkout, restore
the source branch, and release the FIFO lease. A missing or invalid checkpoint
keeps continuation/inheritance/audit disabled and retains the lease.

From a failed result, the user may continue the same Task in a fresh cycle,
derive a new Task seeded from the frozen changes, derive a full rework from the
original baseline, create a read-only audit Task, or cancel. From a successful
result the user may publish, continue an explicitly selected Stage with a
supplemental requirement, derive/audit, accept an empty result, or cancel. Same-Task
continuation reopens only the chosen/failed Stage and later Stages, creates fresh
Attempts/Sessions, resets limits for the new cycle, and reruns final verification
and both Judges; historical evidence remains immutable.

Result cancellation is a dedicated optimistic-lock disposition command, not the
ordinary runtime-cancel endpoint. It still enters the shared durable `STOPPING`
protocol so a stranded implementation/Judge Session or verifier cannot be hidden by
a local terminal state. Once termination is confirmed, the Task becomes `CANCELLED`;
the already-terminal Execution Cycle and its succeeded/failed Stage evidence remain
immutable rather than being rewritten as an interrupted execution.

The local UI publishes a successful waiting Task only after a human enters the four-digit work item
and confirms the AI-suggested `#dddd_subject`. Generating that suggestion reads the immutable
baseline-to-checkpoint-tree diff without restoring the checkout, acquiring a lease, or switching branches.
Only the confirmed commit action makes Loopper reacquire the FIFO lease with the auditable
`PUBLICATION` queue source, restore the verified checkpoint, and commit the Task branch.
The Task's recorded start branch is restored before any remote push; if another
Task is queued, lease transfer immediately switches the same registered checkout
from that source branch to the next Task branch. Push status and retry use the
explicit `refs/heads/<taskBranch>` ref, so neither push nor merge-request creation
requires the old Task branch to be checked out. A remote publication is a normal
non-force push. Without a remote, the commit remains only on the local Task branch;
the restored source branch is not fast-forwarded or overlaid. Direct-execution Tasks remain excluded.
Local-sync conflict inspection parses NUL-delimited Git path output. Those Git
commands disable `core.safecrlf` warnings only for the child command so CRLF
diagnostics cannot be mistaken for path records, while the user's repository and
global Git configuration remain unchanged. Cross-platform Git integration fixtures
fix `core.autocrlf=false` at initial clone time and use repository attributes for
exact-LF merge contracts; POSIX-only bare `mvn` fixture execution is kept separate
from Windows executable-resolution coverage. Automatic-merge fixtures keep their
independent edits far enough apart that supported Git/xdiff versions agree on the
hunk boundary; adjacent edits remain a legitimate manual-conflict case. On Windows,
tracked source-file modes come from the source repository index and untracked regular
files default to `100644`; NTFS ACL executability is not a Unix executable bit. POSIX
source files continue to use the actual filesystem executable bit.
Direct-root identity remains the hash of canonical path, filesystem file key, and
creation time without writing an identity marker into the user's project. NTFS can
tunnel creation time and reuse an immediately deleted same-name directory's exposed
file key; the cross-platform test forces distinct replacement metadata when that OS
collision occurs, while runtime decisions remain limited to metadata actually exposed
by the filesystem and fail closed on an observed mismatch.
The single-action “创建合并请求” button opens its confirmation dialog directly,
then opens a prefilled GitLab/GitHub creation page; the hosting
service still owns the final merge-request confirmation and merge. HTTP/HTTPS
Git remotes retain their explicit Web scheme unless their exact host appears in
`loopper.publication.http-web-hosts`; a matched host always uses HTTP even when
the remote explicitly says HTTPS. SSH remotes otherwise default to HTTPS. The
release startup scripts add `gitlab.spdb.com`; this changes only the generated
MR/PR Web address and never rewrites the remote or push transport.

Execution-cycle result, user-confirmed Task finality, and delivery are separate
state axes. V32 stores cycle results and immutable workspace checkpoints.
`AWAITING_DECISION` is not terminal; durable local commit or confirmed push advances
the Task to `COMPLETED`, an inherited/rework successor advances the parent to
`SUPERSEDED`, and explicit result cancellation advances it through `STOPPING` to
`CANCELLED` without changing the terminal cycle result. Historical
`SUCCEEDED`/`FAILED` rows remain readable legacy terminals and are never silently
reopened. A successful `COMPLETED` Task keeps any still-applicable publication
actions, including creating an MR/PR after push, until the independent delivery
axis reaches its immutable merged result. V20 independently stores
`TaskPublicationState` and its evidence in
`task_publication`. Startup resumes `CAPTURING` and `RESTORING` checkpoint sagas
idempotently. A private ref written before a crash is reused rather than replaced
by a clean tree; an already materialized workspace is accepted only when its
recomputed tree exactly matches the persisted checkpoint. A terminal cycle whose
Task projection was interrupted is restored to `AWAITING_DECISION` without creating
a retry cycle. Remote milestones advance through `COMMITTED`, `PUSHED`,
`MERGE_REQUEST_OPENED` or `MERGE_REQUEST_CLOSED`, and finally `MERGED`;
`MERGED` has no outgoing transition. `PUSHED` is historical evidence and does not
regress when a remote ref is deleted or pruned. No-remote Git Tasks terminate at
`LOCAL_COMPLETED`, while Direct Tasks are projected as `NOT_APPLICABLE`.

Opening the prefilled page records only `creationRequestedAt`. A bounded GitLab
API client may advance MR states only when the configured host exactly matches
both the Git remote and API base URL and one MR uniquely matches source branch,
target branch, and Task commit SHA. The API call and Git inspection run outside
SQLite transactions; Task identity/version and Publication version/commit are
rechecked before persisting the result. Credentials come from
`LOOPPER_GITLAB_PRIVATE_TOKEN`, are sent only to that exact host, and never enter
the database, artifact, log, or API DTO. Missing credentials, ambiguity, timeout,
authentication failure, malformed data, or an oversized response preserves the
previous milestone and records a bounded diagnostic. Source-branch deletion alone
never proves a merge. Historical Tasks are reconciled lazily rather than guessed
during migration. GitHub keeps its creation-page flow without automatic merge
confirmation in this version.

Every OpenCode Session creation response must confirm the same canonical
directory requested by Loopper. A missing or mismatched directory fails before
the implementation prompt is sent. Runtime permissions hard-deny commit and
Git ref/history/remote mutations in addition to push and destructive commands;
the Spring publication service remains the only supported commit/push path.
