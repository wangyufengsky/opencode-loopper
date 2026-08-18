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

New Compiler plans store semantic Stages, `DS-Lxxx` source refs and closed evidence
intentions in V30. The server generates complete verifier/runtime blueprints and
validates them with the normal LoopSpec v2 execution contract before compiling the
final object. Legacy V23 complete plans remain readable.

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
branch; cancelling this dialog fails the Task and leaves files untouched. Unpublished file changes keep
the lease after Task success; publication makes the checkout clean and releases it
before the next queued Task may switch branches.

## Recovery and Session lifecycle

Recovery is allowed from `FAILED` or `CANCELLED` with modes
`FROM_FAILED_STAGE` (default), `ALL_STAGES`, or `VERIFY_ONLY`. `VERIFY_ONLY`
never creates a writable Session and returns HTTP 409 on workspace fingerprint
mismatch. Direct in-place revert is forbidden; operators create a derived
Recovery instead. Fork/revert require a paused Task and confirmed old-writer
termination. A checkpoint hashes message, todo and diff references.

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
`JUNIT_XML`, `BROWSER`, and `DATABASE_QUERY`. HTTP/browser access is loopback
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
V2 `PROCESS TEST` recognition uses exact Maven/Gradle/npm executable basenames,
detects split exclusion arguments and npm optional-script bypasses, and is
rechecked at the execution boundary after Maven argv normalization. A saved
contract therefore cannot gain behavior coverage from a lookalike executable or
later disable the tests it claimed to run. Business-mapped TEST evidence requires
explicit targets; a safe unmapped full-suite command may remain a blocking
supplemental report but never covers a criterion or satisfies the focused
Java-production gate.
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

Compiler planning, draft persistence, and confirmation use the same normalized,
bounded path-policy semantics as runtime `GIT_DIFF`. Malformed globs and any
allowed rule entirely shadowed by a forbidden rule are rejected before Task,
Attempt, or writable Session creation; the Compiler can spend its bounded
planning-repair turn to correct generated conflicts. A broad allow rule with a
narrower forbidden subtree remains a valid policy.

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

Every v2 Stage also declares `implementationKind`. A `JAVA_PRODUCTION` plan is
invalid unless it includes an unskipped focused Maven/Gradle `PROCESS TEST`,
concrete `testTargets`, and mappings from that test to all `MACHINE`/`BOTH`
business criteria. At runtime an immutable Stage-start Java baseline detects
added, modified, and rename-target production `.java` paths in both Git and
Direct workspaces. Classification mismatches and missing successful focused
tests are blocking verifier results that enter the ordinary Attempt retry loop.

For decomposed Tasks, Stage order is also package order. A package owns
`min(stageCount * maxStageAttempts, stageCount + 2)` Attempts and reserves one
for each unstarted Stage; unused capacity cannot be borrowed by another package.
Aggregate admission raises `maxTaskAttempts` and maximum duration only to the
safe calculated floor and never raises token/cost budgets. The final ordered
verification summary still launches exactly one Requirement/Risk Judge batch
after all packages pass.

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

Automation triggers are `MANUAL`, `CRON`, `GIT_HEAD_CHANGED`, and loopback
`WEBHOOK`. New rules are `DISABLED` and `REVIEW_REQUIRED`. `AUTO_START` requires
an explicitly approved immutable template version and still passes through the
same `PENDING_START -> REQUEST_START -> QUEUED` boundary, permissions, verifiers
and both Judges. A review-required detection creates only a draft; its explicit
approval confirms the Task and immediately invokes that same Start boundary.
