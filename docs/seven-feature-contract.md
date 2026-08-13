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

Historical migrations remain immutable. Empty databases and V11 databases must
both migrate to V14.

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
An abort response alone never releases a lease: the old writer must be observed
terminal. Unknown writer state keeps the lease and blocks Recovery and Automation.
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

Normal verifier-loop continuation is distinct from Recovery. Every failed
Attempt stores a bounded immutable `ATTEMPT_HANDOFF`; only reliable equal
failure/workspace fingerprints increment the stagnation streak. At the configured
threshold the running Task moves to `WAITING_INPUT`, and only a confirmed local-UI
action records an override and creates a fresh Attempt/Session. Fingerprinting
counts actual bytes and rejects files whose size, modification time, or file key
changes during the read. The Task projection exposes the current wait reason and
whether this action is available; historical wait errors do not enable it.
Snapshot I/O and Session creation remain outside SQLite transactions.

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
later disable the tests it claimed to run.
`DATABASE_QUERY` accepts one read-only local SQLite `SELECT`/`WITH` statement.
Screenshots and traces live below the configured data directory; SQLite stores
only relative path, SHA-256, size and metadata.

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
queue, permissions, verifiers and both Judges.
