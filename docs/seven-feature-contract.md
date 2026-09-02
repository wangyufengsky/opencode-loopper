# Seven-feature contract v1

This contract freezes the first local macOS/SQLite release of recovery, direct
workspace admission, interactions, native verifiers, session checkpoints,
insights and automation. Server state is authoritative; the browser never
manufactures queue, progress, usage or cost data.

## Persistence versions

- V12: `workspace_lease`, `task_queue`, and unified `interaction`.
- V13: task recovery lineage, Session todo/checkpoint/usage, and filesystem-backed
  binary artifact metadata.
- V14: immutable LoopSpec template versions, automation rules, and run history.
- V21: Designer/Compiler source tracking and immutable Stage Java baselines.
- V22: frozen requirement revisions, Task Decomposer results, serial design work
  packages, package-scoped compiler fragments, and Stage `work_package_id`.
- V23: persisted Decomposer/Compiler semantic planning, evidence mappings, and
  restart-recoverable structured workflow steps before final JSON generation.
- V24: independent persisted planning and final-JSON repair budgets for
  Decomposer/Compiler, plus a valid terminal path from decomposition validation.
- V25: persisted Stage workspace baselines for Stage-local `GIT_DIFF` and
  Attempt handoff isolation.
- V26: persisted Decomposer/Compiler/Judge response mode and schema identity,
  plus implementation Session Todo capability.
- V27: recoverable requirement/package discussion and explicit approval state.
- V28: bounded AI-output normalization/tool-loop audit and Project Convention
  normalization notice.
- V30: compact Decomposer/Compiler semantic snapshots, independent format and
  semantic repair counts, and restart-safe server compilation of final objects.
- V31: grouped application settings plus one active persistent retry schedule per
  Task, including cause, ordinal, frozen delay/due time, pause remainder and version.

Machine-role exact markers remain preferred. The shared bounded extractor also
accepts a unique standard JSON object in a `json`/untyped fence, explanatory
prose, or the whole response. Equivalent candidates collapse; conflicting valid
objects, arrays, incomplete/non-standard JSON, oversize content, or ambiguous
normalization remain invalid. Safe deterministic normalization is audited and
runs the unchanged business and safety contracts without consuming a format
repair.

After the latest user turn, three consecutive calls with the same normalized
tool name and canonical arguments trigger an early best-effort abort. Each
persisted role step may use one no-tools finalizer with bounded deduplicated
evidence; it counts against the global model-call budget, not the format-repair
budget. The 24-step hard cap remains the fallback for other loop shapes.

Historical semantic Compiler plans store Stages, `DS-Lxxx` source refs and closed evidence
intentions in V30. Current v6 designs instead freeze the Designer Stage topology and let the
server compile complete verifier/runtime blueprints; only closed-set ambiguity or large-package
handoff creates one fill-hole Compiler turn. Both paths use the normal LoopSpec v2 execution
contract before freezing the final object. Legacy V23 complete plans remain readable.

Historical migrations remain immutable. Empty databases and supported V21/V24
databases must all migrate to V30. Legacy AI rows default to `TEXT_MARKER`; old
implementation Sessions default Todo capability to `UNKNOWN`.

## Interactions

`kind` is `QUESTION | PERMISSION`; `action` is
`REPLY | ONCE | SESSION | REJECT`. Questions accept only `REPLY/REJECT` and
permissions accept only `ONCE/SESSION/REJECT`. Resolve requests carry the
persisted version and update only a still-`PENDING` row; stale versions return
HTTP 409. Push, external-directory access, dangerous deletion and hard reset
are hard-denied before the provider reply and cannot be overridden.
An interaction is actionable only while its persisted local owner is one of the
same refreshable sessions: Task owners require an execution Session in
`CREATING/RUNNING`, and Designer owners require a `RUNNING` handoff in an
interactive design phase. Inbox refresh reconciles both owner domains before
provider polling; an open row whose local owner has stopped or disappeared moves
to `STALE` and leaves the Inbox. A transport failure for a still-active owner
does not erase its last pending snapshot.

## In-place workspace queue and lease

A canonical real path plus a fingerprint of the directory file key and creation
time identifies every registered in-place workspace, including Git task branches
and Direct mode, and remains safe on Linux filesystems that reuse an
inode immediately after deletion. A released lease may refresh that fingerprint
before admitting a new writer; active and release-pending leases still fail closed.
Only one non-released lease exists for a root. FIFO queue admission is persisted.
LoopSpec confirmation creates a `PENDING_START` Task only: no queue row or lease exists
and Git is untouched. The explicit Start action records `REQUEST_START`, queue
admission and lease acquisition; an admitted Task then prepares its workspace and
continues automatically into execution, while a waiter remains `QUEUED` with the same
execution request already recorded.
For rolling execution, `EXECUTION_READY -> QUEUED`, Task admission, Queue creation,
and Lease acquisition/transfer share that same short transaction. A queued package is
idempotent only when Task, package Run, and Queue all agree; mixed parent/child state is
an invariant failure, never a successful retry. Package-plan confirmation likewise
activates the proposal, supersedes the old suffix, creates the new Runs, and advances
the first package plus parent Task atomically before read-only Designer dispatch.
An abort response alone never releases a lease: the old writer must be observed
terminal. Unknown writer state keeps the lease and blocks Recovery and Automation.
`ADMITTED` must always correspond to the same Task as the non-released lease holder.
A shared reconciliation service completes a terminal holder and transfers exactly one
FIFO waiter only after writer/runtime termination, root fingerprint, clean checkout,
and safe source-branch restoration have all been checked. Filesystem and Git checks
run outside SQLite; queue completion and lease transfer are revalidated atomically in
a short transaction. Startup recovery, cancellation/Session cleanup, archive preflight,
the local-only `POST /api/tasks/{waiterId}/queue/reconcile`, and a 10-second monitor
reuse this service. The monitor scans only terminal holders with a real queued waiter.
An unchanged blocker is not re-audited, and no path may detach the holder, stash,
commit, delete, or force-switch files to manufacture safety. Active holders cannot be
archived or permanently deleted.
For a valid Git HEAD, a dirty admitted checkout holds the lease and moves the Task
to `WAITING_INPUT`. The local UI displays the exact status paths and requires a
snapshot-bound `COMMIT | STASH | REMOVE` choice for every path. A clean recheck
resumes preparation and switches that same registered directory to its `loopper/*`
branch; cancelling this dialog closes the current execution cycle and leaves files untouched.
Waiting-decision changes are held by an immutable private checkpoint rather than an
active writer lease. Commit-message suggestion reads the frozen baseline-to-tree diff
without restoring the checkout. Only a confirmed publication reacquires FIFO ownership
with source `PUBLICATION`, restores the checkpoint, commits it, and releases the checkout again.

## Recovery and Session lifecycle

New rolling software Tasks add a package checkpoint boundary inside one Task and one final
publication. A package may become `FACT_FROZEN` only when all of its deterministic verification
has passed and the successful Attempt, workspace tree, manifest, real diff, evidence digests,
accepted design revision, and cumulative TaskSpec digest are atomically linked. Requirement/Risk
Judges do not run at that boundary. Git projects then clean the registered checkout, restore the
source branch, and release the lease; the next package later queues with source `PACKAGE` and must
restore the exact root/ref/tree/manifest. Direct projects retain the writer lease and freeze a
private immutable tree/manifest without replacing the user's directory. Any external Direct drift
or Git snapshot mismatch fails closed.

An execution failure may preserve a candidate checkpoint so a Git lease can be released safely,
but that candidate is not a `PackageFactSnapshot` and cannot be injected as proven state. The local
decision is explicit: continue from the candidate, redesign from the preceding successful fact, or
cancel while retaining files and audit history. Checkpoint capture, fact insertion, design dispatch,
and lease transfer are individually idempotent recovery boundaries. Startup reuses a verified
durable checkpoint/fact; it never falls back to the original baseline when the latest fact cannot
be proven.

Only the unexecuted suffix may be replanned, and only with no active writer, verifier, Judge, or
Designer/Compiler/Validator and a verified current checkpoint. A package run in `DESIGNING` is
itself not replannable, including the dispatch gap before the external Session becomes visible.
Both read and command paths use the same persisted owner/checkpoint facts. While that run's child
work package is still `PENDING / QUESTIONING / DESIGNING`, the same fact projection exposes a
versioned design-continuation capability. Explicit continuation and startup recovery serialize
progress for that package, poll an existing live remote Session, and create a replacement only when
the persisted remote is absent or terminal; they must never dispatch a second live Designer.
`package.*` lifecycle events invalidate the browser's authoritative Task/workbench snapshot instead
of relying on elapsed-time UI progress. Confirmation supersedes
old unfinished package and design rows,
then starts read-only design for the new first suffix package. Frozen facts, their Stage/Attempt
history, and previous TaskSpec revisions remain immutable. A correction appends a package linked to
the frozen run; a final Judge request for changes follows this same correction path rather than
reopening an old package. After the last effective package freezes, exactly one final Judge batch is
created from the latest cumulative TaskSpec and all effective facts.

An AI suffix suggestion is a durable `PackagePlanRevision` lifecycle of
`GENERATING -> PROPOSED | FAILED`; only the human confirmation transition may make it `ACTIVE` and
supersede the previous active plan. The row freezes Task, current package and checkpoint versions,
the remote read-only Session and typed error. Polling revalidates the exact snapshot before accepting
output, rejects model questions, preserves provider `RETRY` on the same Session, and fails closed on
drift, timeout or malformed/source-invalid output.

New Tasks separate an execution-cycle result from user-confirmed finality.
Success and failure both enter `AWAITING_DECISION`; legacy `SUCCEEDED`/`FAILED`
rows remain terminal compatibility records. The decision API is local-UI-only,
requires optimistic Task and cycle versions, and exposes only actions whose Git
checkpoint and writer/lease preconditions are currently safe.

Failure disposition supports: continue the same Task, derive a new Task with
`INHERIT_CHANGES`, derive `REWORK_ALL_STAGES` from the original baseline, create a
`VERIFY_ONLY` audit, or cancel. Success additionally supports publication,
Stage-scoped continued improvement with a supplemental requirement, and explicit
acceptance when the frozen manifest is empty. Inheritance/rework makes the parent
`SUPERSEDED`; the child remains `PENDING_START`. Audit never creates a writable
Session. Direct mode cannot inherit a private Git checkpoint or rework a Git
baseline and therefore fails closed.

Each authorized continuation creates a persisted execution cycle with a fresh
budget window and associates new Attempts with that cycle. Prior successful
Stages remain successful; the failed/selected Stage and all later Stages reopen.
Final deterministic verification and both Judges run again, while all older
Attempts, evidence, cycle results, and audit transitions remain immutable.

Before offering mutable Recovery actions, Loopper confirms old writers stopped,
captures all tracked/deleted/untracked changes through a temporary Git index,
stores an immutable private ref, creates a named local stash to clean the checkout,
restores the source branch, and releases the FIFO lease. Private checkpoint refs
and stashes are never pushed. Snapshot, branch, root, ref, commit, and tree mismatch
block restoration before a new writer starts.
Startup resumes incomplete `CAPTURING`/`RESTORING` rows. It reuses an already durable
private ref, accepts an already restored worktree only after exact tree verification,
and projects an already terminal cycle to `AWAITING_DECISION` without inventing a retry.

OpenCode Todo is an implementation-only, non-authoritative projection. Loopper
first discovers workspace tool ids; only `todowrite` availability adds Todo
guidance to a new implementation prompt. The monitor reads at most once per two
seconds outside SQLite, stores only changed bounded snapshots, and retains at
most 64 items, 1 KiB per item, and 64 KiB total content. Stable ids derive from
normalized content plus duplicate occurrence. Checkpoints preserve exact id,
content, normalized status/priority, ordinal, and truncation detail. Todo errors,
empty lists, and completed items never advance or fail any lifecycle.

Normal verifier-loop continuation is distinct from Recovery. Every failed
Attempt stores a bounded immutable `ATTEMPT_HANDOFF`; only reliable equal
failure/workspace fingerprints increment the stagnation streak. At the configured
threshold the running Task moves to `WAITING_INPUT`, and only a confirmed local-UI
action records an override and creates a fresh Attempt/Session. Fingerprinting
counts actual bytes and rejects files whose size, modification time, or file key
changes during the read. The Task projection exposes the current wait reason and
whether this action is available; historical wait errors do not enable it.
Snapshot I/O and Session creation remain outside SQLite transactions.
Derived Recovery tasks copy the parent's requirement, decomposition, every
package design/compilation summary, and composite design artifacts. They retain
each Stage's `workPackageId`; Recovery must not collapse a decomposed parent to
only its last Designer message. Creating a derived Recovery also stops at
`PENDING_START`; it does not reserve the parent project or create/switch a branch.
The operator's explicit Start action applies the same queue/lease boundary as a
normal confirmed Task.

## Verifiers and artifacts

Existing `PROCESS`, `FILE_EXISTS`, `FILE_NOT_EXISTS`, and `GIT_DIFF` remain.
Native types are `HTTP_STATUS`, `JSON_PATH`, `FILE_CONTENT`, `FILE_HASH`,
`JUNIT_XML`, `BROWSER`, `DATABASE_QUERY`, `DOCUMENT_STRUCTURE`, and `TABULAR_DATA`. HTTP/browser access is loopback
only. Browser assertions are bounded and contain no arbitrary JavaScript. Browser
executable discovery is explicit override, then process `PATH`, then standard OS
locations; an invalid explicit override fails closed without fallback.
`FILE_CONTENT.expectedContent` is preserved as authored after admission; leading
or trailing whitespace and a final newline are not normalized away. `EXACT`
compares that persisted UTF-8 text without trimming, while an all-whitespace
expectation remains invalid as a missing acceptance contract.
`PROCESS` remains a direct argv contract. Windows resolves project wrapper
aliases from the Task root and bare programs through the Loopper process
`PATH`/`PATHEXT`, then stores the actual absolute executable and resolution
reason in evidence. Linux/macOS leave native PATH lookup unchanged and require
project scripts to be executable. Missing programs fail before launch with a
typed error; this compatibility layer does not permit user-supplied shell
launchers or snippets.
V2 `PROCESS TEST` recognition uses one `TestFrameworkPolicy` registry for exact
Maven/Gradle/npm/pytest/unittest entry points, including `python -m pytest` and
`python -m unittest`. It extracts explicit targets, detects split exclusion/skip
arguments and npm optional-script bypasses, and is
rechecked at the execution boundary after Maven argv normalization. A saved
contract therefore cannot gain behavior coverage from a lookalike executable or
later disable the tests it claimed to run. Business-mapped TEST evidence requires
explicit targets; a safe unmapped full-suite command may remain a blocking
supplemental report but never covers a criterion or satisfies the focused
Java-production gate.

`DOCUMENT_STRUCTURE` parses only bounded Markdown or DOCX and supports heading,
text, table-count, and local-link assertions. `TABULAR_DATA` parses bounded XLSX,
CSV, TSV, or Markdown tables and supports Sheet, row/column, header, cell, and
source-equivalence assertions. Assertion DTOs contain no scripts, expressions, or
formula evaluators. OOXML rejects macro formats, encryption, external relationships,
symbolic links, zip bombs, and configured size/count overflows. XLSX formulas use
stored display values without recalculation; merged cells retain only the top-left
value; only trailing completely empty rows/columns are removed. The frozen conversion
plan records those three policies explicitly and bounds package parts, Sheets, rows,
columns, cells, merged regions and merged-cell expansion.

`PROCESS TEST` is selected by frozen task policy, not inserted into every Stage.
Java production remains REQUIRED; existing framework evidence or an explicit user
test requirement makes other software REQUIRED. A standalone Python script without
a repository test system may use `SELF_CHECK` plus native output verification.
Documents, one-shot conversions, and read-only reports are `NOT_APPLICABLE`.
Server-owned document/tabular stages therefore never capture or evaluate the
production-Java baseline; their blocking behavior evidence remains
`DOCUMENT_STRUCTURE` or `TABULAR_DATA`.
`DATABASE_QUERY` accepts one read-only local SQLite `SELECT`/`WITH` statement.
Screenshots and traces live below the configured data directory; SQLite stores
only relative path, SHA-256, size and metadata.

Before the first writable Attempt/Session, Loopper captures a stable private Git
tree for that Stage under `stage-baselines/<taskId>` and persists its
`stage:<taskId>:<stageId>:<treeSha>` marker in V25. One Task object repository
is shared by per-Stage indexes without modifying the project `.git`, index, or
branch. Capture and validation I/O run outside SQLite transactions; instability
after one retry returns `STAGE_WORKSPACE_BASELINE_UNSTABLE`. Retries and restarts
must reuse the marker. An active historical Stage that already has an Attempt
but lacks the row fails with `STAGE_WORKSPACE_BASELINE_MISSING` before a new
writable Session or model call. Startup recovery removes only contained private
directories for Tasks that no longer exist.

Explicit `GIT_DIFF` and Attempt handoff for normal writable Stages compare with
this Stage baseline, so predecessor files neither violate later path scopes nor
satisfy later `requireChanges`; a later edit/delete/rename of a predecessor file
is still observed. Their evidence records `baselineScope: STAGE` and `stageId`.
`VERIFY_ONLY` Recovery and the final automatic Task diff retain the Task baseline
and record `baselineScope: TASK`, preserving cumulative task audit evidence.

For explicit allow-lists, outside-scope new files are accepted with auditable
`autoAllowedOutsideNewPaths` evidence. Existing baseline files changed outside
the allow-list require a local per-file decision instead of immediately failing
the Attempt. The Task waits in `WAITING_INPUT` while Stage and Attempt remain
running; no verifier result is persisted until the decision is complete. The UI
must load the exact Stage-baseline patch and show old/new lines and hunk locations.
Allow/reject decisions are complete, local-UI guarded, and content-bound by Task
version plus per-file patch SHA-256; stale content must be shown again. Rejecting
a file resumes verification as a normal FAIL. Forbidden paths, unsafe traversal,
missing baselines, truncated previews, and configured delete restrictions are
never eligible for this approval path.

Compiler planning, draft persistence, and confirmation use the same normalized,
bounded path-policy semantics as runtime `GIT_DIFF`. Malformed globs and any
allowed rule entirely shadowed by a forbidden rule are rejected before Task,
Attempt, or writable Session creation; the Compiler can spend its bounded
planning-repair turn to correct generated conflicts. A broad allow rule with a
narrower forbidden subtree remains a valid policy.

The v7 release gate never weakens this runtime contract. Its read-only corpus and
same-input shadow may report path-rule counts and path-conservation totals, but
never path values or raw input. Corpus mutation/hard-gap counts are versioned
expectations and cannot be passed to the evaluator as measurements; the actual same-input
production-pipeline result is an authoritative but incomplete measurement. Key guards publish
bounded actual cost, safety, and coverage counts through a deterministic test-only registry;
complete qualification checks them and requires every exact executable guard.
Per-sample regressions cannot be hidden by another sample's over-report. A gate cannot pass by disabling `GIT_DIFF`, making
delete permissive, replacing focused tests with Judge-only criteria, or broadening
fallback paths. See `weak-model-compiler-v7-evaluation.md`.

For new LoopSpec v2 contracts, the server classifies verifier evidence rather
than trusting a Designer label. Each observable criterion chooses deterministic
machine verification, final AI Judge review, or both. Machine modes need mapped
behavior evidence; Judge modes need an explicit rubric, and Judge-only use also
needs a reason. Every Stage still needs a blocking deterministic gate. Build/static checks, scope-only
`GIT_DIFF`, safety-only `FILE_NOT_EXISTS`, `JUNIT_XML` reports, and advisory
`FILE_EXISTS` cannot satisfy that mapping. The final Attempt's automatic task
diff remains separate audit evidence. After every deterministic Stage passes,
Loopper persists an ordered v2 summary containing each successful Stage Attempt
and all of its verifier results, then gives that aggregate to both read-only
Judges together with all stages' planned Judge criteria. Evidence excerpts are
bounded and hashed; the confirmed Judge contract and complete prompt have
separate UTF-8 byte limits. Every pending role prompt is preflighted as one
review batch, with overflow returning to explicit human handling before any
Judge row, read-only Session, or model call in that batch. `POST
/api/loop-drafts/validate` and MCP return the same classification and planning
result.

V63 makes that review batch a durable authority boundary. One
`judge_review_batch` binds the Task, current final-review Execution Cycle, final successful Attempt and source generation;
for rolling packages the Cycle may reference the last frozen package Attempt without changing that Attempt's original Cycle;
Requirement/Risk rows and role-local Session retries must retain its ID. A blocked or malformed
decision closes the batch as `WAITING_INPUT`; explicit local retry creates a new generation, and
the aggregate decision reads only the current batch, never a PASS from another generation. The
`JUDGE_DECISION_V1` Candidate route, default-on from 0.3.22 after independent Requirement/Risk qualification,
freezes the exact prompt and evidence catalog before remote I/O, accepts only role/verdict/reason/closed evidence IDs, and compiles both MCP and Legacy
inputs through one deterministic core. Candidate final text is ignored, and accepted results require
positive Session termination proof before settling Judge completion. CR/LF/TAB in `reason` and
ordinary extra fields are bounded same-Session mechanical corrections; NUL/BEL/C1 controls, mixed
or oversized dangerous controls, permission and semantic server-authority fields remain non-retryable security
failures and move the stopped current batch to local input rather than automatic Session retry. Legacy review stores
an immutable prompt/evidence/SHA artifact before remote create and reuses it at completion/finalization. Cancellation, Task failure,
timeout, interaction, transport, security, generation, budget and uncertain-stop paths close or
retain the batch fail-closed; they cannot synthesize a successful final review.
`LOOPPER_JUDGE_DECISION_CANDIDATE_V1_ENABLED=false` rolls back only new runs; persisted recovery and settlement
remain available. The [isolated 0.3.20 JAR qualification](mcp-default-enablement-qualification.md) proved both roles'
same-Session line-break rejection/correction and positive-stop settlement in one batch. Its test-only controlled
fault induction is not a natural error-rate or statistical model-reliability claim.

Every v2 Stage also declares `implementationKind`. A `JAVA_PRODUCTION` plan is
invalid unless it includes an unskipped focused Maven/Gradle `PROCESS TEST`,
concrete `testTargets`, and mappings from that test to all `MACHINE`/`BOTH`
business criteria. At runtime an immutable Stage-start Java baseline detects
added, modified, and rename-target production `.java` paths in both Git and
Direct workspaces. Classification mismatches and missing successful focused
tests are blocking verifier results that enter the ordinary Attempt retry loop.

For legacy aggregate decomposed Tasks, Stage order is also package order. A package owns
`min(stageCount * maxStageAttempts, stageCount + 2)` Attempts and reserves one
for each unstarted Stage; unused capacity cannot be borrowed by another package.
Aggregate admission raises `maxTaskAttempts` and maximum duration only to the
safe calculated floor and never raises token/cost budgets. The final ordered
verification summary still launches exactly one Requirement/Risk Judge batch
after all packages pass.

For rolling Tasks, package attempt budgets and execution cycles are allocated when each package is
started. Later confirmed package designs append Stage rows and a complete cumulative TaskSpec
revision; already executed rows are not reopened merely because a later plan changes. The final
verification summary uses the newest accepted cumulative revision and excludes superseded or
failed-candidate Stage contracts.

Network behavior coverage requires a Stage-managed runtime with dynamic
`{{LOOPPER_PORT}}`, bounded readiness, and direct argv startup. V19 records its
PID/start identity, port and argv hash. Cleanup covers completion, failure,
pause, cancellation and application restart; PID identity mismatch is fail
closed and never kills the unrelated process. Existing v1 templates,
Automations, tasks and Recovery keep their prior verifier semantics, while new
template versions and imports require v2.

## Usage and automation

Usage is idempotent per provider message. Missing provider usage/cost stays
`null`/unknown, never zero. Only reliable usage can stop the next model call at
a soft budget and move a Task to `WAITING_INPUT`.

V40 adds the separate `model_token_usage` presentation projection. It stores one monotonic
provider-reported total per external model Session and aggregates it across the current
Designer or Task scope for the compact live window. It backfills completed Task remotes from
existing reliable `session_usage` evidence, but it does not replace that per-message evidence,
change budget enforcement, infer cost, or create lifecycle events. V40 triggers register every
new external Session ID when its authoritative Designer/Task row is persisted, retaining the
old ID even when a repair or finalizer later replaces the source projection.

Automation triggers are `MANUAL`, `CRON`, `GIT_HEAD_CHANGED`, and loopback
`WEBHOOK`. New rules are `DISABLED` and `REVIEW_REQUIRED`. `AUTO_START` requires
an explicitly approved immutable template version and still passes through the
same `PENDING_START -> REQUEST_START -> QUEUED` boundary, permissions, verifiers
and both Judges. A review-required detection creates only a draft; its explicit
approval confirms the Task and immediately invokes that same Start boundary.
