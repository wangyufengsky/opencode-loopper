# Seven-feature contract v1

## 当前路径验收边界

Stage 允许路径提示、OpenCode 执行权限、canonical containment 与 `GIT_DIFF` 结果验收分别生效。`allowedPaths` 不是所有文件变化的统一硬白名单：当前显式 GIT_DIFF 对范围外新增保留审计并自动允许，对范围外已有文件修改/删除/重命名转入本地逐文件决定。`forbiddenPaths`、删除保护、基线与 containment 始终阻断，不能通过人工范围放行绕过。V2 编译前范围守卫证明的是冻结授权与编译结果关系，不改变此运行期语义；详见本文件 GIT_DIFF 章节。

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
text, nonempty-text (`TEXT_NON_EMPTY`), table-count, and local-link assertions.
Nonempty/format checks do not establish semantic completeness; document authoring also
requires its frozen JUDGE content criterion. `TABULAR_DATA` parses bounded XLSX,
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
branch. For a Git workspace, snapshot membership comes from the source repository's
`ls-files --cached --others --exclude-standard`, preserving root/nested `.gitignore`,
repository-local excludes, configured excludes and case rules. Source-tracked files
remain included even if an ignore pattern matches them. New-file discovery uses the
same source authority; the managed data directory and project `.git` remain excluded.
Plain directories use private Git's `.gitignore` rules. Previously captured baseline
trees stay immutable; changing ignore rules does not erase changes to existing baseline
files or silently rewrite historical approvals. A fresh Recovery captures new Stage
baselines with the corrected membership.
Capture and validation I/O run outside SQLite transactions; instability
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
Automations, tasks and Recovery keep their prior verifier semantics, while historical
template versions retain their frozen v1/v2 contracts. New template editing and imports are retired.

## Usage and automation

The paged insight endpoint accepts `projectId`, `state`, `quality`, `archive` (ALL/ACTIVE/ARCHIVED),
and literal `query` (up to 200 characters). All token/currency totals describe the full filtered
population, independent of the current cursor page. Unknown usage stays null. The current Cycle's
V64 human approval is a separate quality field; it can satisfy acceptance while both AI verdict
fields remain false. The compatibility insight endpoint reports the same human source.


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

### 内置模板任务

V77 将新任务入口改为 `/template-tasks`，旧 `/automations` 页面重定向到新入口。内置目录由服务端代码持有，首批为 `CODE_REVIEW` 与 `CONTRIBUTION_REPORT`；新任务版本为 `5`，历史版本 `1`、`2`、`3`、`4` 继续按冻结合同恢复。用户只能手动选择模板和参数，不编辑 LoopSpec JSON、创建自定义模板或定时/事件规则。旧模板、规则、检测健康与运行记录保留查询；旧 API 的创建、编辑、导入、手动触发、确认触发和 Webhook 均返回 `LEGACY_AUTOMATION_RETIRED`。升级将 ENABLED 规则停用，后台只对既有 run 做状态对账，不再探测 Git HEAD 或派发 CRON。

参数必须来自已登记项目及该项目当前可选分支。项目与分支使用可搜索、服务端分页的下拉框。优先使用远程 origin 的默认分支；其他来源只有能明确识别主分支才可默认选择，不能用当前 checkout 分支猜测。缺少明确默认时要求用户选择。切换项目清空分支，拒绝旧查询结果覆盖新项目。日期传输为 `YYYY-MM-DD`，默认北京时间今天及之前六天；同日允许，结束日期不得早于开始日期。后端与前端均校验，范围为 Asia/Shanghai 的开始日零点至结束次日零点（右开）。

V78 增加项目文档路径和任务的文档生成路径。参数优先级为本次填写、项目默认、原任务目录；支持项目相对路径或绝对路径。服务端在确认前校验并冻结规范绝对路径，确认不创建目录，项目设置后续修改不影响历史任务或相同请求键重试。项目设置以版本 CAS 更新，冲突要求刷新。路径拒绝上级跳转、文件占位、敏感目录和符号链接；写入前再次检查。模板参数、项目文档路径设置和登记项目表单均提供系统文件夹选择器，并保留手动输入。选择取消或失败保留原值；切换项目、关闭表单或输入已被替换后，不接受旧选择结果；选择期间禁止提交。选择器只回填路径，保存和执行仍经过原路径校验与冻结流程。新报告在配置路径下按 `报告类型_项目_yyyyMMdd-yyyyMMdd_序号/` 隔离任务和报告版本；留空且项目无默认时在任务工作目录的 `reports/` 下使用同名目录。序号分配与主子报告规则由[固定报告格式](template-report-format.md)持有；历史 V1/V2 报告继续使用原 Task/Attempt 目录。仅服务端将冻结报告写入此明确选择的目录，模型不获得文件权限，已有文件内容不一致时停止而不覆盖。选择项目内目录时，仅该生成目录允许新增报告，原项目 checkout、index 和提交保持不变。

模板页面只展示服务端目录和参数，不加载执行记录。目录用分类、搜索和可滚动列表组织，标题、说明、分类、图标与是否展示评分标准来自目录元数据，不按两个固定模板布局。执行记录统一进入 `/tasks`，任务类型筛选为模板任务或普通任务，可与项目、状态、搜索、归档和游标分页组合；摘要和计数使用同一服务端范围。页面省略时区和流程的重复说明，实际日期统计规则保持不变。

参数确认只创建 `PENDING_START` Task、两个固定 Stage、不可变模板合同；不创建 Designer、不申请 Queue/Lease、不准备执行目录。界面的“开始执行”先幂等确认，再调用正式 Start；确认或 Start 响应丢失时复用原请求键与任务。`TEMPLATE_REPORT` 使用 `ISOLATED_REPORT`：正式开始后获得任务自有目录的租约，在 `dataDir/template-tasks/{taskId}/repository.git` 采集证据，不改原项目的 checkout、index、分支或未提交文件。任务不能通过普通代码发布、Recovery 或新分支重做路径改写源仓库。

新模板任务暂不支持故事统计，表单不展示或提交绑定配置，服务端拒绝开启统计的新请求且不产生任务。历史绑定和相同请求键的幂等恢复保留，详细边界见[故事绑定与统计](story-binding.md)。

固定阶段为 Git 证据与报告分析，批次作为第二阶段的可见进度。分析开始前持久化代码批次和贡献者批次总数，详情通过聚合查询展示本轮已验证、执行中、失败和剩余数量；总数包含尚未创建会话的批次，缓存命中计入已验证，旧轮次不计入返修轮次。总数未知时不显示百分比；全部分析完成仍须单独生成、校验和双评审，不由分析百分比推导 Task 成功。详情 SSE 与每四秒的轻量 overview 刷新只更新状态，不读取报告或会话正文。历史已结束任务缺少计划时保持总数未知，旧活动任务从冻结快照建立计划后继续。远程分支每个新 Task 执行时 fetch，冻结分支 tip、所有可达且 committer 时间在范围内的提交、before/after blob、原始变更和身份映射。非单调提交时间不得令 Git 遍历提前停止。冻结 `.mailmap`；同邮箱合并身份，同名不同邮箱分开；Co-authored-by 等分归属，显式机器人单列。两父合并仅计独有冲突解决，不重复计算传入变更；当前多父 octopus merge 明确阻断，不假称已覆盖。生成标记、vendor/cache、重复补丁和二进制无文本计量均保留排除依据；敏感文件只保留元数据，报告明确正文未审查。空范围仍生成无提交说明。

采集以 Git 2.30.2 为最低基线：分支查询与采集前校验版本；完整遍历 SHA 与 committer 时间后由程序筛选，不能用 `--since` 提前剪枝；任务 bare index 经 `read-tree` 加载当前提交后使用 `check-attr --cached`。两父合并使用任务自有临时工作目录和 index 执行递归合并，冻结含冲突标记的合并基线，再与实际提交比较；不执行项目 hooks、全局自定义过滤器或合并驱动，不跟随源项目符号链接。命令完成且停止确认后清理本次临时目录，未知停止状态保留目录与阻断。Git 错误只输出已分类的安全诊断、操作名与退出码，原始 stderr 不持久化；版本、参数、认证、网络、仓库与文件读写错误分开。

单次 Git 输出上限 4,000,000 字节，总补丁上限 64,000,000 字符、100,000 个提交；最终 Judge 提示沿用 128 KiB UTF-8 上限。超限明确阻断，不能静默截断后报告完整成功。浅克隆来源和已保存的浅快照均不能证明完整历史，须补齐来源后重新发起。补丁切片保留原始行号；两父合并的 before blob 为 Git 重建的冲突基线，不能冒充第一父提交的文件。

Git 采集开始前持久化外部操作意图，进程停止得到证明后落库停止证据；超时或输出上限触发停止时保留已观察到的子进程句柄，父进程退出不代表子进程已停止。重启若遗留未证明停止的 Git 采集，保留目录、Task 阻断和租约，不自动重叠执行。已完整冻结的采集可继续从快照恢复。会话创建和投递分别保存 SessionCreationPlan、精确 PromptRequest 和哈希；恢复先查精确身份，未知投递不得盲重发。取消先等待本地 I/O 返回，再确认全部远端停止；未知结果保持 STOPPING。

新模板 V5 的报告分析使用 `TEMPLATE_ANALYSIS_CANDIDATE_NO_TOOLS`，只接收嵌入的冻结证据，仅开放当前托管代际的 `submit_template_analysis` 私有 MCP 工具；文件、命令、question 和用户 MCP 均禁用。新任务要求托管私有 MCP 已就绪，不静默退回最终文本。历史 V1–V4 仍按 `TEMPLATE_ANALYSIS_NO_TOOLS` 的冻结 JSON 文本协议恢复，历史已绑定任务的统计命令维持独立守卫。模板会话不能通过普通摘要、分叉或回退接口改变冻结分析。

模板 MCP 请求携带批次 `runId`、`idempotencyKey`、`expectedSubmissionRevision` 与完整 `candidate`。服务端校验批次/Task/Attempt/Session 与当前托管代际，严格解析身份、片段覆盖、字段和问题行号；每次新提交的回执追加保存在 V80 表，同键同内容返回原回执，同键异内容拒绝，旧 revision 返回当前 revision。候选不合法时返回 `isError=true`、`FIX_AND_RESUBMIT` 和具体问题，批次保持运行，模型在同一会话纠正，不消耗新的 Task/Stage 尝试。MCP 纠正不设固定次数上限，仍受冻结时限、预算、取消和重复工具调用守卫约束。候选接受仅保存结果，必须独立确认远端完成后才能验证批次和汇总报告；最终文本、工具调用成功或候选回执不能代替停止证明。没有已接受的工具提交时，即使最终文本包含有效 JSON 也不生成报告。停止/取消后的新候选与旧代际提交不得推进任务；同代际既有回执允许精确重放，不能产生新的状态变化。任务删除在同一事务内先显式删除候选回执，再删除批次；失败回滚必须恢复两者。

报告内容返修最多两轮，且同时受冻结的阶段/任务尝试次数、时限及预算约束。输入、合同、模型和快照完全相同且最终已经接受的分析可缓存；返修和资格验收不使用该缓存。模型 `finish=length` 保留为独立的生成长度耗尽错误，而非通用运行环境异常；Loopper 不为模板另设单次 token 上限，OpenCode/Provider 的单次生成限制与任务总预算独立，MCP 无法反馈尚未发生的工具调用。

新任务在确认时保存 `REPORT_LAYOUT_V3` 的四份固定模板正文及 SHA-256；章节、表格、空数据说明与评分展示由服务端持有，模型不能选择格式。历史 V2 合同继续使用冻结模板，缺少此字段的旧合同使用保留的 V1 渲染器。格式定义见[固定报告格式](template-report-format.md)。服务端以已验证候选生成完整 Markdown，先冻结制品及 SHA-256，再原子写文件；各版报告与评审证据保留。代码审查生成总结、完整问题清单、提交目录与逐提交详情；贡献任务生成项目总结、个人详情、问题清单与评分标准。主子报告复用已验证候选，不增加模型会话。正文按制品 ID 加载，必须校验所属 Task，页面支持安全 Markdown 预览、同版相对链接和单文件/整包下载。完成确定性校验后进入现有同批双 Judge；评审对象是报告覆盖、事实、归属和评分依据，发现代码缺陷或低分不构成报告失败。只有本轮双 Judge PASS 且所有执行子状态和租约收束后，自动 `COMPLETED`。人工认可不替代模板报告的双评审要求。

### 贡献评分

冻结 `CONTRIBUTION_SCORE_V1`：数量 30、代码价值 25、必要技术难度 15、质量与验证证据 20、工程维护 10。数量分为 `30 × ln(1+L) / ln(1+Lmax)`，L 为去噪和共同作者等分后的有效增删行，Lmax 为本次人类贡献者的最大值；全零时数量分为零。其他维度由模型给出 0–4 离散等级、原因与本人的证据 ID，服务器按各维权重换算。各级定义由 `ContributionScore.DIMENSIONS` 持有，随报告完整输出；不得在运行时变更标准。只使用完全有证据支持的最高等级，测试源码只能证明测试意图。

保留两位小数，采用 1、1、3 的同分竞赛排名和稳定身份排序；无有效变更者保留零分记录，机器人不排名。总结报告展示概览、总分与排名，个人详细报告完整列出原始量、有效量、维度分、总分及理由，评分标准独立成子报告。权重是本项目的内置观察标准，不是行业统一绩效模型，不推测工时、未记录的评审、业务收益或没有 Git 提交的人员。

## Cancellation checkout and advisory review (0.3.28)

A stopped cancelled Git holder preserves dirty content in the existing durable checkpoint/stash
before restoring the local default branch and releasing its lease. Root identity, ownership and
writer-stop proof remain mandatory. An unavailable default branch or unsafe checkpoint blocks
handoff without discarding files. Direct, pending and queued Tasks cannot switch another holder.
V64 human final-review acceptance is versioned, explicit local UI authority, separate from AI
judgments, and never overrides deterministic execution failure or uncertain writer termination.
