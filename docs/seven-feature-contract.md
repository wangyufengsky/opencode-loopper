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

## Direct queue and lease

A canonical real path plus a fingerprint of the directory file key and creation
time identifies a Direct workspace, including on Linux filesystems that reuse an
inode immediately after deletion. A released lease may refresh that fingerprint
before admitting a new writer; active and release-pending leases still fail closed.
Only one non-released lease exists for a root. FIFO queue admission is persisted.
An abort response alone never releases a lease: the old writer must be observed
terminal. Unknown writer state keeps the lease and blocks Recovery and Automation.

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
`DATABASE_QUERY` accepts one read-only local SQLite `SELECT`/`WITH` statement.
Screenshots and traces live below the configured data directory; SQLite stores
only relative path, SHA-256, size and metadata.

## Usage and automation

Usage is idempotent per provider message. Missing provider usage/cost stays
`null`/unknown, never zero. Only reliable usage can stop the next model call at
a soft budget and move a Task to `WAITING_INPUT`.

Automation triggers are `MANUAL`, `CRON`, `GIT_HEAD_CHANGED`, and loopback
`WEBHOOK`. New rules are `DISABLED` and `REVIEW_REQUIRED`. `AUTO_START` requires
an explicitly approved immutable template version and still passes through the
queue, permissions, verifiers and both Judges.
