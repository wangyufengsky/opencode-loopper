# Architecture

OpenCode Loopper is a local modular monolith: Spring Boot owns authoritative
state and verification, Vue renders the console, SQLite persists state, and a
local OpenCode server performs model Sessions inside per-Task Git worktrees or,
when Git HEAD is unavailable, directly inside the registered project root.

## Module boundaries

- `project`: registered roots and Git baseline validation
- `designer`: real read-only OpenCode planning Session and versioned Loop drafts
- `task`: Task, Stage and Attempt aggregate
- `orchestrator`: state transitions, retry policy and recovery
- `opencode`: HTTP/SSE adapter and managed process lifecycle
- `verifier`: bounded-worker direct-process, file and Git diff evidence
- `workspace`: isolated-worktree/direct-root selection, baseline lifecycle and path containment
- `event`: persisted timeline and browser SSE
- `judge`: independent read-only Requirement and Risk final review Sessions
- `artifact`: immutable diffs, verification summaries and Judge metadata/results

Controllers accept validated DTOs and delegate to application services. Only
repositories/mappers update SQLite. Process and HTTP details remain behind
adapters so deterministic fakes can exercise the orchestration state machine.

## Error layers

`ErrorLayer` is part of the public persisted contract:

- `FIELD`: request or draft validation; no runtime transition.
- `VERIFICATION`: an Attempt did not satisfy evidence; continue the Loop.
- `SESSION`: the OpenCode Session failed; finish the Attempt, create a fresh
  Session and continue the current Stage while limits remain.
- `TASK`: safe continuation is impossible; abort children and enter `FAILED`.

A Session adapter must never write `TaskStatus.FAILED`. It emits a typed
`SessionFailure`; the orchestrator owns retry/promotion. Exhausted Session
retries are promoted to `TASK/SESSION_RETRY_EXHAUSTED` with the complete chain
of evidence.

An application restart follows the same Session boundary. Loopper best-effort
aborts the old external Session, persists it and its Attempt as disconnected /
`SESSION_ERROR`, and starts a fresh Attempt/Session automatically while limits
remain. A Task-level failure closes every active Attempt and child Session; no
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

## Verification and final approval

Deterministic verifiers run on a dedicated bounded executor rather than the
scheduler thread. `PROCESS` is an argv contract and rejects shell launchers;
its runner terminates the observed process tree on timeout or output overflow,
but it is not an OS sandbox. A deliberately daemonizing hostile executable must
be isolated by an external Job Object, cgroup or container rather than trusted
as a LoopSpec verifier.

File paths are execution-root-relative and symlink-safe. Stage allowed/forbidden
path rules are advisory prompt context and never add an implicit acceptance
gate. When the confirmed LoopSpec explicitly contains a `GIT_DIFF` verifier,
its glob rules are normalized to `/`, matched by a bounded dynamic-programming
engine with identical behavior on all supported operating systems, and rejected
at the LoopSpec boundary when path policy size limits are exceeded. Before entering `VERIFYING`, the
orchestrator performs a second authoritative status read and requires the
implementation Session to be terminal-completed, so the public API cannot race
a still-mutating Session. Only after all deterministic gates
pass do two read-only OpenCode Judges run. Both Requirement and Risk Judges must
return an explicit `PASS`; conflicts, `REVISE`, `BLOCKED`, or unparseable output
move the Task to `WAITING_INPUT`, never to a fabricated success.

## Workspace safety

Planning may inspect a registered root read-only. When a project has a valid
Git HEAD, execution creates `loopper/<taskId>` under
`$LOOPPER_DATA_DIR/worktrees/<taskId>`. Otherwise OpenCode edits the canonical
registered root directly and Loopper stores a private Git-compatible baseline
under `$LOOPPER_DATA_DIR/direct-baselines/<taskId>` for deterministic path and
deletion checks. The private baseline does not initialize or commit the target
project. Canonical paths must stay under the registered root or Task worktree
as appropriate. Loopper never pushes, merges or deletes a completed worktree
automatically.
