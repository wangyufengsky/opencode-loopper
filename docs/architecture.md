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
  required before the project file is created or its Looper block is updated
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

Deterministic verifier I/O never runs while a SQLite transaction is active.
The Task first enters `VERIFYING` in a short transaction, process/HTTP/browser
checks run outside the database lock, and their results plus the next lifecycle
decision are committed in a second short transaction. Restart recovery handles
the deliberate post-commit gaps before the next Stage Session or Judge Session
is created, and final evidence capture is idempotent.

The transition history is forward-only from Flyway V15: existing rows are not
given fabricated creation events. `GET /api/state-transitions` can page either
one machine/entity or one aggregate scope in ascending sequence order. The
absence of earlier events for a pre-V15 entity is therefore not evidence that
the entity had no prior transitions.

OpenCode `external_session_state`, Designer message delivery state, provider
Todo snapshots, and immutable verifier outcomes are projections or results,
not Loopper-owned lifecycle state. Refreshing those values never creates a
business state transition.

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
its runner terminates the observed process tree on timeout or output overflow.
On Windows, the runner resolves project `mvnw`/`gradlew` aliases against the
Task directory and other bare executables against the Loopper process
`PATH`/`PATHEXT` (`.COM`, `.EXE`, `.BAT`, `.CMD`). It records the resolved
absolute executable and forces the JDK's strict Windows command quoting mode;
the LoopSpec still cannot supply `cmd`, PowerShell, shell syntax, expansion,
pipes, or redirects. Linux/macOS retain native direct-argv lookup and executable
permission semantics. The runner is not an OS sandbox. A deliberately
daemonizing hostile executable must be isolated by an external Job Object,
cgroup or container rather than trusted as a LoopSpec verifier.

For v2 `PROCESS` entries with `processPurpose=TEST`, executable recognition is
an exact basename allowlist for Maven, Gradle, and npm rather than a prefix
match. Split and joined Maven/Gradle/npm skip flags are rejected. The same
policy runs again immediately before process launch, after deterministic Maven
argv normalization, so an older persisted contract cannot bypass the current
test-evidence boundary.

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

V22 adds a lifecycle before Designer. Every user submission freezes a complete
`design_requirement_revision` and starts an independent read-only Task
Decomposer Session. The Decomposer may return one `DIRECT_DESIGN` work package,
2–6 dependency-ordered vertical packages, `NEEDS_INPUT`, or
`MULTI_TASK_REQUIRED`; the server numbers and verifies requirement-segment
coverage, package identity, backward-only dependencies, and the single-Task
boundary. A newer user submission supersedes the old decomposition and package
results without deleting their audit history. Each requirement revision has a
hard 24-call model budget including Decomposer, Designer, Compiler, content
repair, and the single transport retry available to each read-only role.

Package work is strictly serial. Every package uses a fresh Designer Session and
a fresh Compiler Session, produces 1–3 Stages carrying `workPackageId`, and is
validated before the next package starts. Compiler output is a package fragment,
not a complete LoopSpec. After all packages complete, the server concatenates
Stages in package order and atomically synchronizes one aggregate LoopSpec at
the original draft version; no model performs a second merge. Confirmation
freezes `REQUIREMENT_CONTEXT`, `DECOMPOSITION_CONTEXT`, per-package
`WORK_PACKAGE_DESIGN` and `WORK_PACKAGE_COMPILATION_SUMMARY` artifacts, plus a
composite compatibility `DESIGN_CONTEXT`. A draft version conflict stops before
the next model call and never creates a Task.

The confirmed aggregate still creates exactly one Task, one task branch, and
one publication. Stage execution remains serial, while each package owns an
attempt pool of `min(stageCount * maxStageAttempts, stageCount + 2)` and earlier
Stages cannot consume the reserved first Attempt of an unstarted Stage. Package
reserves do not transfer. `maxTaskAttempts` and safe duration are raised to the
deterministic minimum during aggregation, but token and cost budgets are never
raised. All Stages must pass before the one final Requirement/Risk Judge batch.

A v2 Stage may own one temporary verification runtime. Loopper allocates a
dynamic loopback port and private temp directory outside SQLite transactions,
replaces only `{{LOOPPER_PORT}}` and `{{LOOPPER_TEMP}}`, starts a direct argv
root process, and polls bounded readiness before running bound HTTP/JSON/BROWSER
checks. Every exit path stops the observed process tree and records resolved
argv, PID/start identity, port, readiness attempts, bounded output, and cleanup.
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
engine with identical behavior on all supported operating systems, and rejected
at the LoopSpec boundary when path policy size limits are exceeded. Before entering `VERIFYING`, the
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

Planning may inspect a registered root read-only. When a project has a valid
Git HEAD, execution first snapshots the registered checkout. A dirty checkout
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
