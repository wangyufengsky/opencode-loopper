# Architecture

OpenCode Loopper is a local modular monolith: Spring Boot owns authoritative
state and verification, Vue renders the console, SQLite persists state, and a
local OpenCode server performs model Sessions inside the registered project
checkout after Loopper switches it to a serialized per-Task Git branch, or,
when Git HEAD is unavailable, directly inside that same registered root.

## Module boundaries

- `project`: registered roots and Git baseline validation
- `designer`: real read-only OpenCode planning Session and versioned Loop drafts
- `project conventions`: read-only OpenCode project analysis plus a persisted
  `AGENTS.md` preview; an explicit local-UI apply and matching source hash are
  required before the project file is created or its Looper block is updated;
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
Project scope, and workspace/automation records use their stable fingerprints
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
- `TASK`: safe continuation is impossible; abort children and enter `FAILED`.

A Session adapter must never write `TaskState.FAILED`. It emits a typed
`SessionFailure`; the orchestrator owns retry/promotion. Exhausted Session
retries are promoted to `TASK/SESSION_RETRY_EXHAUSTED` with the complete chain
of evidence.

An application restart follows the same Session boundary. Loopper best-effort
aborts the old external Session, persists it and its Attempt as disconnected /
`SESSION_ERROR`, and preserves or creates a persistent `RETRY_WAIT` schedule
while limits remain. A Task-level failure closes every active Attempt and child Session; no
child is allowed to remain `RUNNING` under a terminal Task.

Task terminality does not fabricate remote terminality. If an abort and its
independent status read both fail to prove that a writer stopped, its local
Session becomes `DISCONNECTED` with `SESSION_ABORT_UNCONFIRMED`. The monitor
persists up to `loopper.abort-cleanup-attempts` abort retries across restarts;
success becomes `ABORTED`, while exhaustion stays `DISCONNECTED` with explicit
`SESSION_ABORT_CLEANUP_EXHAUSTED` evidence. Cleanup never creates an Attempt.

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
For v2 `JAVA_PRODUCTION`, added, modified, or rename-target production `.java`
files require a successful focused Maven/Gradle test from the same Stage. Test
trees and generated `target`/`build` trees are excluded; deletion alone remains
under existing scope/risk rules. Actual Java changes in `JAVA_TEST_ONLY` or
`NON_JAVA` fail classification. File traversal and hashing stay outside SQLite
transactions for both Git and Direct workspaces.

V22 adds a lifecycle before package Designer. V27 places an explicit requirement
discussion gate in front of it: user messages create complete, recoverable
requirement snapshots but do not invoke Decomposer. Only the scoped requirement
confirmation freezes the next numbered `design_requirement_revision` and starts
an independent read-only Task Decomposer Session. The Decomposer may return one `DIRECT_DESIGN` work package,
2–6 dependency-ordered vertical packages, `NEEDS_INPUT`, or
`MULTI_TASK_REQUIRED`; the server numbers and verifies requirement-segment
coverage, package identity, backward-only dependencies, and the single-Task
boundary. After decomposition, an unscoped user message is rejected; only an
explicitly confirmed requirement reopen supersedes the old decomposition and
package results without deleting their audit history.

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

The semantic Compiler separates observable business criteria from engineering
metadata. Untested code-style, source/annotation/assembly-shape, build/test-result,
and delivery-hygiene entries remain available through the frozen design but do not
create artificial acceptance conditions. Evidence indexes are remapped, and a
single focused Java test can deterministically cover otherwise unmapped business
criteria in the same Stage; multiple candidates or missing real tests still fail.
Preflight batches all deterministic semantic errors with JSON Pointers into one
repair response, while the authoritative verifier continues to reject source-text
search as behavior evidence.

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

Machine-response roles also carry an explicit non-thinking model selection.
Managed DeepSeek starts with a private `loopper-no-thinking` variant and
Decomposer/Compiler/Judge prompts select it; Markdown Designer and Implementation
keep their configured thinking behavior. This is a transport compatibility rule,
not a new lifecycle state or evidence source.

Managed runtimes also define a private `loopper-structured` machine-response
agent capped at 24 agentic steps. Decomposer, Compiler, and Judge select it to
bound read-only exploration; it does not replace Loopper model-call, repair,
timeout, validation, or lifecycle authority. For Decomposer and Compiler,
OpenCode `RETRY` remains a transient external Session projection and never
triggers the design pipeline's fresh-Session retry; Implementation and Judge
retain their existing failure-escalation behavior.
The adapter signs tool calls after the latest user turn using normalized tool
name and canonical arguments. Three identical consecutive signatures trigger
an immediate best-effort abort and, once per persisted role step, one no-tools
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

The package Compiler's current planning contract is version 2. Each planned
Stage already carries exact `VerifierSpec` blueprints and an optional managed
runtime. Before freezing, the server builds a temporary v2 execution contract
and runs the same direct-command, behavior-coverage, Java-test and runtime
assessment used by Review Gate. Shell strategies or unmapped MACHINE/BOTH
criteria are repaired while still planning. Final JSON must copy those verified
objects exactly, so generation cannot turn a valid evidence design into a
different verifier contract.

Package work is strictly serial. A package keeps one healthy interactive
Designer Session across its discussion turns and reconstructs a replacement
Session from persisted snapshots/decisions after transport loss. Every candidate
uses an independent read-only Compiler Session, produces 1–3 Stages carrying
`workPackageId`, and is deterministically validated into `REVIEWING`; the next
package cannot start until the user accepts that exact design revision. A failed
candidate keeps the previous valid candidate visible. Reopening an accepted
package marks only its transitive dependents `STALE`; unrelated accepted packages
remain valid. Compiler output is a package fragment, not a complete LoopSpec.
After all packages are `APPROVED`, the server concatenates Stages in package
order and atomically synchronizes one aggregate LoopSpec at the original draft
version; no model performs a second merge. Confirmation
freezes `REQUIREMENT_CONTEXT`, `DECOMPOSITION_CONTEXT`, per-package
`WORK_PACKAGE_DESIGN` and `WORK_PACKAGE_COMPILATION_SUMMARY` artifacts, plus a
composite compatibility `DESIGN_CONTEXT`. A draft version conflict stops before
the next model call and never creates a Task.

All package design Sessions read the same immutable pre-execution repository
baseline. An `APPROVED` predecessor means its frozen design/compilation contract
is valid and its Stages are ordered before the current package; it does not mean
the design-time repository already contains its files. The server injects the
predecessor summary/handoff as an available-at-execution contract, and Compiler
must not turn baseline absence into `MISSING_SCOPE`. During Compiler planning,
the model owns Stage and evidence semantics while the server owns mechanical
criterion numbering, uniquely recoverable exact Designer slices, and propagation
of evidence-mapping TEST targets/criterion IDs to the matching verifier. Explicit
focused Maven/Gradle selectors in the frozen design or planned verifier are carried
into the Compiler prompt and deterministically parsed; a unique Stage-level match
may fill omitted duplicate commands/targets or the equivalent TEST verifier. Broad
test suites and ambiguous candidates are never guessed. The normal v2 assessment
still runs after canonicalization and remains authoritative.

The confirmed aggregate still creates exactly one Task, one task branch, and
one publication. Stage execution remains serial, while each package owns an
attempt pool of `min(stageCount * maxStageAttempts, stageCount + 2)` and earlier
Stages cannot consume the reserved first Attempt of an unstarted Stage. Package
reserves do not transfer. `maxTaskAttempts` and safe duration are raised to the
deterministic minimum during aggregation, but token and cost budgets are never
raised. All Stages must pass before the one final Requirement/Risk Judge batch.
The aggregate Stage-to-package mapping is immutable once synchronized into the
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
`COMMIT`, `STASH`, or `REMOVE` per path, or cancel and fail the Task without
changing the files. Cleanup is accepted only against the same branch, HEAD,
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
transitions only that Task and its queue row to `CANCELLED`; it never releases or
transfers the current holder's lease. The cancelled terminal Task may then follow the
ordinary archive and protected history-deletion flow only after it no longer owns an
active lease.
A Task in the short-lived prepared `READY` state may likewise be explicitly cancelled
before its first writable OpenCode Session starts. The ordinary Task cancellation transition
preserves its branch, execution directory, and evidence, then reuses terminal-holder
reconciliation to restore the recorded source branch and release the workspace lease
only when the existing safety checks pass.
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
If a terminal Git Task still has file changes, its writer lease remains held until
the branch is published or manually cleaned; the next queued Task cannot switch
the checkout underneath it. After a Task reaches `SUCCEEDED`, the local UI may
explicitly publish its task branch: a human enters the four-digit work item,
confirms the AI-suggested `#dddd_subject`, then Loopper commits the Task branch.
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
Git remotes retain their explicit Web scheme. For SSH remotes, HTTPS remains
the default unless the exact host appears in `loopper.publication.http-web-hosts`;
the release startup scripts add `gitlab.spdb.com` so its MR page uses HTTP without
changing the SSH transport used for push.

Execution and delivery are separate state axes. `TaskState.SUCCEEDED` remains the
execution terminal state, while V20 stores `TaskPublicationState` and its evidence
in `task_publication`. Remote milestones advance through `COMMITTED`, `PUSHED`,
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
