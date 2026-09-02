# OpenCode 1.18.23 contract evidence

The base HTTP contract was verified locally on 2026-08-04 with OpenCode 1.18.12.
The managed MCP overlay, merge, bearer, reconnect, tool-name and Session-generation
claims below were re-verified on 2026-08-31 with OpenCode 1.18.23 in isolated
temporary directories.

| Operation | Endpoint | Observed result |
| --- | --- | --- |
| health | `GET /global/health` | `healthy=true`, version `1.18.23` in the current protocol probe |
| MCP status | `GET /mcp` | JSON object |
| create Session | `POST /session?directory=...` | Session with canonical directory |
| Session status | `GET /session/status` | JSON map |
| Session messages | `GET /session/{id}/message` | user/assistant messages with completion metadata |
| Session diff | `GET /session/{id}/diff` | JSON array |
| Session Todo | `GET /session/{id}/todo?directory=...` | bounded implementation progress projection |
| built-in tool ids | `GET /experimental/tool/ids?directory=...` | workspace-scoped tool capability list |
| native agents | `GET /agent` | native agent metadata, including optional `plan` |
| abort | `POST /session/{id}/abort` | boolean; parsed `true` is a positive stop acknowledgement, `false`/empty is unconfirmed, and 404 means the exact Session is already absent |
| delete | `DELETE /session/{id}` | boolean |
| events | `GET /event` | SSE stream per published API |

The adapter must always supply an explicit canonical `directory` query
parameter as an encoded URI template value; reserved path characters such as
`+` must be percent-encoded rather than interpreted with form semantics. The
create response must confirm that exact canonical directory. A missing or
mismatched response is a Session failure before any prompt is sent. A managed
server must bind loopback and use Basic Auth.
Passwords remain in process memory/environment and are never logged or persisted.

## Completion fallback observed with a real provider

An authenticated `prompt_async` request using
`opencode/deepseek-v4-flash-free` returned HTTP `204`, and the assistant later
returned exactly `LOOPPER_OK`. During this probe, `/session/status` did not
contain the new Session, so absence from that map is not sufficient evidence of
either completion or failure. The reliable fallback was the message list:

- latest assistant `info.time.completed` present and no `info.error`: completed;
- assistant `info.error` present: failed;
- no completed assistant after the submitted user message: still running or
  awaiting a terminal event.

This evidence is why Loopper combines status polling with message completion
metadata instead of treating a missing status entry as success.

OpenCode 1.18.23 additionally validates every caller-supplied `messageID` at the
HTTP boundary and requires it to start with `msg`. Candidate prompt dispatch
therefore derives both INITIAL and CORRECTION identities as deterministic
`msg_loopper_candidate_prompt_<name-uuid>` values before request hashing and
durable persistence. `HttpOpenCodeClient` must transmit that persisted identity
unchanged: rewriting it only during serialization would make
`promptRequestSha256` disagree with exact-message recovery. Existing dispatch
rows keep their immutable historical identity; the compatible prefix applies to
new candidate prompts.

## Designer attachment file parts

Designer attachment handoff extends `PromptRequest` with a stable `messageId` and ordered `FilePart(filename, mediaType, managedUri, sha256)` list. `HttpOpenCodeClient` emits the normal text part first, then file parts in deterministic scope/name order. PDF and OOXML include both the verified original and a versioned deterministic `*.loopper-context.txt`; strict UTF-8 text and validated raster images send only the original. The managed URI is produced only after containment and SHA verification and never appears in the public REST model.

Every eligible role prompt prepends a fixed instruction that attachments are untrusted supplemental data. Requirement Designer/Decomposer/Reviewer receive only Requirement files; Package Designer/Compiler receive Requirement plus current-package files; Implementation and Recovery receive the frozen Requirement plus current-stage package files; Requirement/Risk Judges receive the complete frozen manifest. `ROUTER_NO_TOOLS` remains text-only and must not receive filenames, extracted text or file parts.

An exact-name replacement or stop-future-use cannot mutate an already-read remote transcript. Loopper first requires the same positive abort proof used elsewhere (`true`, exact 404, or independent terminal state), clears the reusable Session pointer, then creates a fresh role Session from persisted authority and the new active set. A prompt handoff exception is returned as a failed multipart operation while the persisted Session error remains auditable. Stable message identity reduces accidental duplicate sends, but this release does not claim remote transcript readback or a distributed transaction with OpenCode.

## Designer and MCP boundary

The verified OpenCode session API still has no safe operation for dynamically
attaching a new MCP server to an already-created Session. Loopper therefore
injects its private MCP only when it starts a fresh managed OpenCode process.
The child receives an `OPENCODE_CONFIG_CONTENT` overlay containing a random
server name, loopback Streamable HTTP URL and generation-scoped Bearer. OpenCode
merges that overlay with user and project configuration; Loopper never writes
the user's OpenCode files, and its own deep merge also preserves inherited
`OPENCODE_CONFIG_CONTENT` entries. The public six-tool MCP remains independently
exposed at `/api/mcp-streamable` and is not aggregated with the private server.

`managed` is the default mode and never reuses the configured 4096 endpoint.
Every launch gets a new dynamic OpenCode port, Basic Auth secret, internal MCP
server name, Bearer and generation. Startup is successful only when both
`/global/health` and the exact internal entry in `/mcp` report ready. `auto` and
`http` remain compatibility modes; a reused external process has no injected
private MCP and therefore uses the explicit in-process legacy candidate adapter.
Changing generation invalidates old managed Sessions before any new HTTP request.
After Loopper's HTTP listener is ready, an `ApplicationReadyEvent` starts exactly
one managed generation immediately; no Runtime-page or first-model-call trigger is
required. `auto`, `http`, and `fake` remain lazy so application startup cannot
silently claim an operator-owned process.

V47 persists an `open_code_session_runtime_binding` before a newly created or
forked Session ID is exposed. Every later HTTP use proves the same generation and
endpoint fingerprint first; neither endpoint URLs nor credentials are stored.
An `auto`/`http` candidate must reuse the `EXTERNAL` binding persisted when HTTP created the Session;
it must not replace that identity with a worktree-derived fingerprint. Only the in-process fake adapter
may synthesize its explicit fake binding.
Pre-V47 Session IDs are backfilled as `LEGACY_UNKNOWN` and fail closed because
their original runtime identity cannot be proven.

V49 represents every candidate run with `CandidateScope(type,id)` plus
`CandidateOwnerRef(type,id)`. The scope type is a closed aggregate set
(`DESIGNER_SESSION`, `TASK`, `PROJECT`) backed by exactly one matching database
foreign key; the owner is a closed stable type/ID reference whose row must belong
to that scope. V47/V48 decomposition, closed-choice and package-design runs keep
their original Session, generation, revisions, attempts and accepted results while
migrating to the typed form. The owner/scope guard is active before that historical
copy, so a V48 cross-scope row fails the complete migration and remains recoverable
under V48; it is never silently rewritten. Owner deletion and its run/attempt cleanup
share the caller transaction. V49 initially gave only package design a separate
accepted-result row; V56 adds the rolling-plan accepted result and its exact owner/run
cascade. `ROLLING_PACKAGE_PLAN_V1`, `REVIEWER_REPORT_V1`,
`PROJECT_CONVENTION_V1` and `JUDGE_DECISION_V1` plus their owner types were reserved
for staged adapters. V56 activates `ROLLING_PACKAGE_PLAN_V1`; V57 adds the shared
`GENERIC_V1` durable creation/prompt/termination schema for the other three roles, and V58–V61
connect Reviewer behind its staged feature flag. The isolated 0.3.13 packaged JAR then proved real
`opencode/gpt-5.4` private-tool adoption and same-Session `line=99` rejection/`line=1` repair, so Reviewer
defaults on from 0.3.14 while an explicit false affects only new reports. V62 connects Convention and V63 connects
Judge, initially behind separate default-off qualification flags. Both default on from 0.3.22 after independent
real-model qualification; explicit false values affect only new runs. Rolling uses a dedicated
`ROLLING_PACKAGE_CANDIDATE_READ_ONLY` profile with `read/glob/grep` and only the
exact private submission tool. It has no Markdown/marker fallback after dispatch.
Only `PACKAGE_DESIGN_V1` retains the explicit Markdown-fallback compatibility policy.

V57 does not reuse the historical Acceptance launch ID. `CandidateLaunchRef` maps
`ACCEPTANCE_V55` only to `internal_launch_id` and `GENERIC_V1` only to
`candidate_launch_id`; both columns cannot be set. Reviewer, Convention and Judge
`INTERNAL_MCP` prompts require the latter and an exact settled launch, while their
`IN_PROCESS_LEGACY` runs remain valid without either reference. The generic launch freezes
the managed Session plan and attestation before I/O, opens its run with a deferred
settlement certificate in the same transaction, and fences INITIAL/CORRECTION progress,
termination and deletion at the database boundary. Reviewer freezes its bounded source manifest
before the first exact-title lookup or remote create, uses `REVIEWER_CANDIDATE_READ_ONLY`
(`read/glob/grep` plus only the exact private tool), and never reads assistant final text. Only a
proven pre-dispatch managed-runtime/lookup capability absence may hand the owner to Legacy. Once
dispatch may have occurred, zero submission, timeout, questions, transport/stop uncertainty,
safety failure and exhaustion remain Candidate failures. Convention, Requirement Judge and Risk Judge each obtained
their own packaged real-model qualification before default enablement; neither V57 nor Reviewer evidence substitutes
for another role's [qualification](mcp-default-enablement-qualification.md).

V62 supplies Convention's role orchestration while preserving the same launch gates. Its source `AGENTS.md`,
source revision/owner version, stack profile/fingerprint and canonical component/command/path evidence catalog are
persisted before the first exact-title lookup or remote create. `PROJECT_CONVENTION_CANDIDATE_READ_ONLY` allows only
`read/glob/grep` plus the exact private submit tool; it forbids shell, write, question and every user MCP tool. The
model submits only closed evidence IDs. Final assistant text is never read for compilation or fallback. A proven
pre-dispatch `CANDIDATE_MANAGED_RUNTIME_REQUIRED` or `OPENCODE_EXACT_LOOKUP_UNSUPPORTED` condition, with no create
attempt and no remote, may hand the owner to one fresh `PROJECT_CONVENTION_READ_ONLY` Legacy Session. After possible
dispatch, normal completion without a submission, transport failure, interaction, security rejection, attempt
exhaustion, generation conflict or uncertain stop must close or retain the Candidate recovery point; none may start
Legacy. Accepted content becomes `READY` only after `REMOTE_COMPLETED`, `ABORT_ACKNOWLEDGED` or `ALREADY_ABSENT`
proof. The 0.3.17 application flag remained false after first-pass-only acceptance evidence. A subsequent isolated
0.3.20 JAR run proved private-tool use, duplicate component-key rejection and second-submission acceptance in the
same Session. From 0.3.22 the flag defaults true; `LOOPPER_PROJECT_CONVENTION_CANDIDATE_V1_ENABLED=false` rolls back
only new runs and does not disable recovery of already persisted Convention runs.

V63 supplies the Judge-specific orchestration. One `judge_review_batch` freezes the Task, active final-review
Execution Cycle, final successful Attempt and source generation for both Requirement and Risk; a rolling final-review
Cycle may reference the last frozen package Attempt without rewriting that Attempt's original Cycle. Role-local Session retries retain that batch, while
explicit local retry creates a fresh generation and prevents cross-batch verdict aggregation. Before the exact-title
lookup or create boundary, Loopper persists the exact Judge prompt plus a canonical bounded evidence catalog.
`JUDGE_CANDIDATE_READ_ONLY` allows only `read/glob/grep` and the exact private submit tool; it forbids question,
shell, write and every user MCP tool. Candidate fields are limited to
`contractVersion/role/verdict/reason/evidenceIds`, and both the MCP decoder and Legacy adapter enter the same
`JudgeDecisionCompilation`. Final assistant text is never read. A proven pre-dispatch
`CANDIDATE_MANAGED_RUNTIME_REQUIRED` or `OPENCODE_EXACT_LOOKUP_UNSUPPORTED` condition may start one fresh
`JUDGE_READ_ONLY` Legacy Session; after possible dispatch, zero submission, interaction, timeout, transport,
security, generation, exhaustion or uncertain stop cannot fall back. CR/LF/TAB in `reason` and ordinary extra
fields are bounded mechanical errors with exact same-Session correction guidance; NUL/BEL/C1 controls, mixed
or oversized dangerous controls, permission and semantic server-owned authority fields remain fail-closed security
errors. A non-retryable Candidate rejection stops the remote writer and current review batch for local input; it is
not a Session transport error and cannot create a fresh automatic Judge Session. Legacy Judge transport freezes a
canonical prompt/evidence snapshot artifact before `createSession`; completion and MCP tool-loop finalization must
load the same artifact and SHA instead of rebuilding from current Task artifacts. An accepted
result settles the Judge only after `REMOTE_COMPLETED`, `ABORT_ACKNOWLEDGED` or `ALREADY_ABSENT`. The 0.3.20
application flag was default-off pending qualification. Both roles subsequently proved real private-tool adoption,
line-break rejection and second-submission correction in their respective same run/Session on that isolated JAR.
From 0.3.22 the flag defaults true; `LOOPPER_JUDGE_DECISION_CANDIDATE_V1_ENABLED=false` rolls back only new runs,
while persisted Judge Candidate recovery remains active regardless of the current flag. The three-role
[qualification record](mcp-default-enablement-qualification.md) distinguishes its test-only, one-time system-prompt
fault induction from the unchanged server/tool/permission protocol; it is not a natural error-rate or statistical
reliability claim.

The private `/api/internal-mcp-streamable` Router accepts only literal loopback
addresses and constant-time Bearer matches. It registers exactly one tool,
`submit_candidate`; it does not contribute a `ToolCallbackProvider`, resource,
prompt or completion to the public MCP. A candidate role receives only its exact
random `<server>_submit_candidate` permission. Router and ordinary roles never receive the private server;
only the dedicated Judge Candidate profile receives it for the staged Judge path; user MCP permissions remain
governed by their existing role profiles. OpenCode 1.18.23's experimental tool
endpoints list built-ins but not MCP tools, so they are not an internal-MCP
readiness proof. Loopper instead requires the exact server to be `connected` in
`/mcp` and keeps the one-tool name/input schema under its own MCP `tools/list`
integration contract.

`PACKAGE_DESIGN_V1` uses a fresh work-package-owned Session in the same managed
process generation; Loopper never starts an OpenCode process per Task. Its normal
profile exposes `read/glob/grep` plus the exact random private submit tool, and its
interactive profile adds `question`. Both deny user MCP even though the managed
process overlay still deep-merges and preserves all user/project MCP configuration
for unrelated roles. This permission narrowing is Session-local and does not rewrite
any OpenCode configuration file or change ordinary Designer/Implementation access.
The same prompt carries the exact closed nested field template and states that `key`
is only a candidate-local reference; the model must not infer field names or use
server `id` fields.

Dispatch checks the feature flag, owned generation, persisted Session binding and
exact private-MCP connected status before opening a candidate run. If readiness is
missing before dispatch, Loopper creates no run and uses the existing Designer plus
Markdown compilation flow. After dispatch, generation/binding mismatch, timeout,
transport failure or unconfirmed abort is terminal for that handoff: Loopper must not
read the partial assistant text as fallback. Only a normally completed Session with
nonempty final Markdown may take `MARKDOWN_FALLBACK`. An explicit frozen requirement for
Markdown-only/no private submission is authoritative over the later MCP preference text and must
complete with zero submissions; accepted MCP output first waits
for normal completion or the existing positive abort proof.

After the isolated packaged-JAR qualifications for same-Session MCP correction and
zero-submission Markdown fallback, the package-design flag defaults on. Setting
`LOOPPER_PACKAGE_DESIGN_CANDIDATE_V1_ENABLED=false` prevents only new package candidate
runs and provides an operational rollback; persisted runs and accepted-result recovery
remain available.

The REST workflow uses specialized model roles plus a deterministic server validator.
Before selecting those roles, the requirement Router supplies only intent, one primary artifact,
and SIMPLE/PACKAGED while the server combines them with the latest immutable V42 project stack snapshot. The snapshot is
created by deterministic bounded filesystem analysis, not by Router output or `AGENTS.md`.
Relative paths, module names, and requirement text may select only repository-backed component
keys. Ambiguous multi-stack and missing/partial evidence require user confirmation; an empty
project cannot become Java from an AI guess. The current `2026-08-dynamic-v7` selector inherits
the v6 normalization of technology aliases into Java, Python, Node, or Other families before choosing
a frozen Role Pack: JavaScript is never a Java signal, same-family aliases do not create a
mixed pack, and an explicit unknown single stack uses the generic software pack. The result
chooses Java, Python, Node, mixed, generic, document, tabular, report, or maintenance prompts; it cannot
grant permissions, choose commands, or bypass human confirmation. Router format failure
falls back to a generic profile question rather than failing the Designer Session.
Package-local technology matching uses token boundaries, so symbols such as `ChainNodeInvoker`
do not trigger the Node family. Router test-artifact aliases such as `TEST_SOURCE_CODE` remain
source code rather than failing the profile.
The Router profile is a true zero-tool classifier: it denies every built-in tool, including
read/glob/grep/question, skips MCP discovery, and does not allow configured MCP tools. A managed
runtime selects the private one-step, zero-temperature `loopper-router` agent and the non-thinking
variant where available. Its prompt forbids repository exploration, design, implementation planning,
and reasoning exposition, and requires the closed object immediately. It uses the
fixed marker envelope rather than an OpenCode JSON Schema response format, avoiding the desktop
Session-loader incompatibility that rejects a persisted `{type: json_schema, schema: ...}` object.
The V2 server contract contains only those three classification labels; V1 extra fields remain
parse-compatible but are ignored. Technology, components, and confidence are server-derived. Its configurable
`loopper.task-profile-router-timeout` boundary defaults to 240 seconds and applies only while the
run has no persisted external Session ID. Once connected, the monitor waits for the remote terminal
state without a wall-clock deadline. An unconnected deadline records `ROUTER_TIMEOUT` and
materializes a retryable fallback. A completed response is accepted only as one closed semantic object. Router
runs are persisted with their exact requirement snapshots and external Session IDs;
restart polls the same Session, while a newer discussion aborts and supersedes stale runs. Each
run also persists the project-profile id, manifest fingerprint, and selected component keys.
Those fields flow into the confirmed profile, direct work package, Stage, and Recovery snapshot;
the Router and later project analyses cannot rewrite a frozen task.

Project-convention generation is a separate read-only role. `POST /api/projects/{id}/agents-md`
first forces deterministic stack analysis outside SQLite. A `FAILED` snapshot stops before any
OpenCode Session; `PARTIAL` is included as an explicit evidence warning. The read-only prompt
contains the structured profile and bounded current `AGENTS.md`, and requires complete replacement
content for `技术栈与模块`, `构建与测试`, and `目录与边界`. Built-in access remains read-only.
The server rejects missing sections, unsafe size or markers, and known technologies absent from the
snapshot. The preview records the source-file hash plus profile id and fingerprint; apply verifies
the source hash and recomputes the live manifest fingerprint before writing only the marked block.
Analysis and AI preview never write the project file by themselves.

`GET /api/projects/{id}/agents-md/{draftId}/activity` performs one bounded transcript
read and returns only the latest safe thinking/output/tool part plus provider-reported
tokens from that same observation. The browser does not estimate tokens or infer progress.
The generation monitor persists a fingerprint over remote state, latest part/content,
part count, and token total for restart-safe observation. Once the read-only Session is connected,
neither inactivity nor elapsed wall-clock time automatically stops it; polling continues until a
real remote terminal state or explicit user cancellation, and the browser shows no timeout limit.
`DELETE /api/projects/{id}/agents-md/{draftId}` uses the same local-UI-only boundary for
manual cancellation. Polling failure and user cancellation pass through `STOPPING`;
retry/fresh generation is blocked until a positive abort acknowledgement, already-absent
response, or an independent terminal status confirms that the previous read-only Session is no
longer consuming work. The activity status map may remove an aborted Session before its latest
message receives a completion timestamp, so it cannot override a positive abort acknowledgement. A tool-loop
finalizer is likewise created only after the looping Session's termination is confirmed.

New software-package compilation first runs entirely on the server against frozen DesignFact and
verification-capability catalogs. Current v7 Designer output separates a `负责路径` write-ownership column from
scenario/review/delivery inclusion; frozen v6 output retains its historical four columns. The exact Stage table locks names, objectives, order,
dependencies, and every uniquely resolved fact. A complete ordinary `DIRECT_SOFTWARE_DESIGN / WP-1`
therefore creates no OpenCode Compiler Session and consumes no model call.

When v7 path ownership is unresolved, recompiling the unchanged design revision is rejected before any new Session.
Scoped package feedback and explicit package recovery remain read-only and create a full replacement-design prompt
containing every unresolved project-relative path and candidate Stage. The model is asked to fill the responsibility
column, but duplicate or ambiguous ownership remains a deterministic server block.

Requirement-side path discovery does not promote a bare slash-separated identifier to an unclassified mutation path
from `/` alone. Without an explicit mutation operation, the identifier needs an extension, glob, known repository-root
segment, or explicit path/directory/file context; explicit write/delete/move operations and all external-path safety
checks retain their existing fail-closed behavior.

For current v7, a unique global optimum is compiled by the server with zero model
calls, while non-enumerable, non-exhaustive, path-ownership, and permission-safety
results stop for human input with zero candidate Sessions. Only a proven exhaustive
true global capability-score tie can create one candidate Session; package shape
and a handoff summary alone do not. The compatibility JSON contract
`PACKAGE_ACCEPTANCE_CLOSED_CHOICE_V7` returns only `summary`,
`factAssignments`, `capabilityPreferences`, and `handoffSummary`; it cannot create, remove, rename,
merge, reorder, or change dependencies of a Stage, move a locked fact, or return outcome, gaps,
commands, paths, test targets, IDs, verifier objects, permissions, or safety policy. Reversible aliases,
singleton collection shapes, `null` collections, and explanatory fields are normalized and audited
without a repair turn. Raw execution/topology/safety fields, duplicate/out-of-range/conflicting assignments,
incomplete coverage, and multiple non-equivalent valid JSON candidates are rejected as
`AMBIGUOUS_ACCEPTANCE_INTENT`; frozen facts and server bindings remain intact. Frozen v6 packages retain
`PACKAGE_ACCEPTANCE_DISAMBIGUATION_V6` and its strict historical parser.
For a true current-v7 tie, the prompt exposes only candidates whose membership differs between exhaustive equal
optima. The response must name the complete discriminating index set of one listed optimum; common capabilities,
weaker candidates, partial choices, duplicate indexes, and cross-optimum mixtures are not valid choices. If the
bounded solver cannot prove exhaustiveness, no Compiler Session is created and the design remains incomplete.

The acceptance candidate transport is separately guarded by
`loopper.internal-candidate.acceptance-closed-choice-v7-enabled`, which defaults
to `true` after the isolated 0.3.5 packaged-JAR replay proved that the configured real model invokes the
private MCP tool and corrects a rejected candidate in the same Session. Setting
`LOOPPER_ACCEPTANCE_CLOSED_CHOICE_V7_ENABLED=false` prevents only new candidate runs and keeps the
existing JSON response path above as the authoritative rollback behavior; persisted candidate recovery remains available.
When enabled, only an already
persisted `compilerRequired` route that the server re-proves as a finite exhaustive
true tie may create one
`ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS` Session and an
`ACCEPTANCE_CLOSED_CHOICE_V7` run on `INTERNAL_MCP`. This profile has no built-in
tools and no user-MCP wildcard; its sole grant is the exact generation-scoped
`<server>_submit_candidate`. It permits at most two unique submissions in that
same Session. Only a mechanical closed-set selection error is retryable;
contract, path, permission, execution, topology, non-enumerable, or non-exhaustive
failures stop at `WAITING_INPUT`. The accepted projection is database-only and
cannot expand the frozen topology, mutation ownership, verifier, or permission
surface.

An `ACCEPTED` or `WAITING_INPUT` run is not remote termination evidence. Every
monitor pass revalidates the persisted runtime binding, owner, source revision,
and the exact known owner-version step before it may settle the result. Loopper
compiles an accepted candidate, exposes candidate `WAITING_INPUT`, closes a run,
or switches to the fresh legacy Session only after `sessionStatus.completed()`
or `abortWithConfirmation` returns `ACKNOWLEDGED / ALREADY_ABSENT`. The positive
proof is persisted as `REMOTE_COMPLETED / ABORT_ACKNOWLEDGED / ALREADY_ABSENT`;
`CANDIDATE_ACCEPTED` is never used as a stop claim. Transport, abort, or generation
uncertainty keeps the same run/Session recoverable as `DISCONNECTED` and creates
no Task or compiled plan. The unchanged open run recognizes exactly one persisted
`DISCONNECTED` owner checkpoint, so a late submission from that same external Session
can still settle without another prompt; a second owner drift is rejected. Run close
persists its exact reason. Only remote-completed zero-submission close may start legacy;
timeout, provider failure, forbidden interaction, owner-requested close, and historical
`CLOSED` rows with no reason never fall back. After any external termination I/O, a
short proof transaction revalidates the original run/version, Designer state, owner,
source, external Session and runtime binding before CAS; `STOPPING/CANCELLED` and stale
ownership preserve the current run and do not invoke generic failure settlement.
If an external runtime is rejected by the managed binding guard before an internal run exists,
its compilation and remote remain `RUNNING / DISCONNECTED`; Monitor retries abort without
creating a candidate run or prompt and opens the fresh legacy run only after ACK/absence proof.
Once a positive proof is persisted, restart settlement
may validate its frozen binding identity and owner/source without requiring the
old managed process generation to remain active; before proof, the original active
generation remains mandatory. The exact proof + `SERVER_COMPILING` + `serverCompiled`
version checkpoints are resumable, while any additional owner drift fails closed.
A legacy output is read only after its own Session
normally completes and still passes the same closed candidate policy.

V51-V55 make remote creation, prompt dispatch and termination independently recoverable.
Before creating either an internal candidate Session or a fresh Legacy successor, Loopper
persists the exact directory, title, generation, model, profile, permission digest, request
digest and an unguessable one-time local creation credential. A returned or recovered Session
is usable only when its attested creation request and managed binding match that frozen plan;
an unknown create result is fenced into a cleanup ledger and cannot be treated as success or
retried as a second create. The internal run opens only in the same transaction that writes a
settlement certificate and transfers the compilation to the attested external Session.

Each initial/correction prompt has one durable dispatch identity. Model-call consumption and
the fact that dispatch may have crossed the transport boundary are recorded before the HTTP
call; a missing lookup capability or unknown result remains `DISCONNECTED` and must be
reconciled rather than resent blindly. Designer cancellation, requirement replacement and
the three admitted initial-prompt failure facts persist a typed termination intent before
abort/status I/O. New submissions, prompts and launch progress are fenced while that intent
is active. Only positive stop proof plus no open run, live prompt or unresolved cleanup remote
permits the launch and parent to settle terminally. A user action that arrives after an
initial-prompt failure promotes the existing ready intent to cancellation/replacement instead
of creating a competing termination authority.

The closed-choice tool never accepts a shorthand for `capabilityPreferences`. If the candidate
otherwise stays inside the safe root contract, only a non-empty integer array or a non-empty object
array whose every item is strictly `{factIndex: integer, capabilityIndex: integer}` alongside valid
`factAssignments`, or the exact combined shorthand of non-empty
`factAssignments:[{factIndex: integer, capabilityIndex: integer}]` plus an all-integer
`capabilityPreferences` array, is classified as one mechanical
`ACCEPTANCE_CANDIDATE_SELECTION_INVALID` response with server-enumerated full
`{factIndex, capabilityIndexes:[...]}` values. The same Session may submit one full replacement;
mixed items, unknown fields, other contract shapes and all path, permission, execution,
topology or safety content remain non-retryable.

The structural candidate qualification observes model prompt calls, OpenCode candidate Sessions, and MCP
candidate submissions as three separate counters: unique optimum `0/0/0`, true tie `1/1/2`, and
non-enumerable and path-safety blocks `0/0/0`. It records 22/22 exact guards, 7/7 metric guards,
1/1 same-input measurement guard, and `candidateFeatureQualification.complete=true / passed=true`.
Those MCP requests are issued by the integration driver, however, so the result proves the server pipeline,
counter bounds, and persisted recovery but not real-model tool adoption or self-correction. It therefore cannot
turn the production default on. Persisted-run recovery remains available regardless of the feature flag.

Document and tabular direct-artifact workflows do not create an implementation Session:
the server materializes their frozen plans only after Task Start. Read-only report
workflows use a distinct `REVIEWER_READ_ONLY` profile that permits only read/glob/grep and
terminates without a Task. Completion alone is insufficient: Loopper accepts the report
only after the fixed `REVIEWER_REPORT_V1` object is parsed, each finding's managed path and
exact line resolves, and every cited source is hashed. The server renders report Markdown;
the Reviewer cannot bypass the evidence contract with free-form citations. A
report-to-design request treats the report as quoted input and starts a new Designer
Session without creating a Task or writable execution Session.

`question` is a discovered runtime capability, not an assumption derived from a configured
permission rule. Before each question-required Designer turn, Loopper probes the project-scoped
tool ids. Only an `AVAILABLE` probe that explicitly contains `question` selects
`DESIGNER_INTERACTIVE_READ_ONLY` and calls `/question`; `UNKNOWN`, `UNAVAILABLE`, or an available
tool list without `question` selects `GENERAL_READ_ONLY`. In that compatibility mode the model
returns only 1–3 numbered ordinary-text questions, Loopper persists them as `CHAT_QUESTION`, and
the existing chat composer accepts the user's direct answer even when Designer full-auto is active.
The answer is stored in the same decision log before the server assembles the direct requirement
snapshot or asks the same package Session for its complete replacement design. Capability absence
is not a Session error and never triggers the missing-native-question repair loop.

The packaged launchers default `OPENCODE_ENABLE_QUESTION_TOOL=true`, and every Loopper-managed
OpenCode child receives that environment variable explicitly. A healthy external OpenCode process
is never restarted or mutated merely to change tools and cannot inherit environment variables after
it has started. It therefore remains in compatibility mode until its own service manager/container/
launch command supplies the flag and restarts that external process; restarting only Loopper is not
sufficient. `opencode.json` permissions may allow a registered tool but cannot register a tool that
the OpenCode server omitted.

Each overall Designer Session is bound to the exact `loop_draft_id` shown in
Review Gate. Before any software package design runs, the interactive requirement Designer
must ask 1–3 design choices, using native `question` only when the capability probe proves it.
In default `DIRECT_SOFTWARE_DESIGN` it then
ends the turn; empty text is valid and all model prose is ignored while the server builds the
authoritative snapshot from user inputs and persisted answers. In `FULL_PACKAGE_DESIGN` it
still returns a complete Markdown requirement predesign in the same model turn. Follow-up
requirement messages repeat the selected contract without starting Decomposer. In default `DIRECT_SOFTWARE_DESIGN`, the explicit
requirement-confirm API freezes a numbered revision and the server creates `WP-1`
directly; there is no Decomposer transport, prompt, Session, or role message. In
explicitly enabled `FULL_PACKAGE_DESIGN`, confirmation supplies the frozen revision
and read-only project context to Task Decomposer. On a ready managed generation,
its built-ins are limited to `read/glob/grep` and its only MCP grant is the exact
private `submit_candidate`; user MCP is intentionally absent from this candidate
role. The tool result, not final assistant text, is authoritative. `REJECTED`
returns all bounded deterministic problems and the next submission revision so
the same Session can repair and resubmit; `ACCEPTED` atomically freezes the
canonical plan; `WAITING_INPUT` stops the loop. Rejected raw candidates are not
persisted. A compatibility Session still routes its final text through the same
policy and accepted writer in process. It cannot write, execute commands, ask a
model-side question, or create a Task.

The 0.2.84 packaged-JAR acceptance used OpenCode 1.18.23 with the then-current
`opencode/ling-3.0-flash-fin-free` model in an isolated repository. One real
Decomposer Session invoked the private tool once; V47 recorded one
`INTERNAL_MCP` attempt as `ACCEPTED`, compiled two ordered work packages, and
left the Task table empty. Because the first candidate passed, this is evidence
of real model tool adoption, not evidence that this model repaired a rejected
candidate. Same-Session rejection/correction, five-attempt exhaustion, raw-value
redaction and idempotency remain automated contract evidence until a separate
real-model run naturally exercises rejection.

For each large-task package in order, Loopper creates a scoped interactive read-only Designer
conversation. A healthy remote Session is reused for that package's human
revisions; after remote loss, a new Session receives the persisted requirement,
decisions, previous full snapshot, and package-scoped message. Initial design and
each human revision must ask its required questions before returning one complete Markdown
replacement; native-capable runtimes call `question`, while compatibility mode splits ordinary-text
questions and the complete design across two prompts in that same package Session. Designer is never
asked to populate LoopSpec fields. Direct WP-1 instead uses
the general read-only profile without `question`, enters `DESIGNING` immediately, and applies
the same no-question rule to feedback and redesign. Loopper then routes each candidate through
server acceptance resolution. Only a closed-set ambiguity or a frozen V6 large-task handoff creates one no-tools
Compiler Session with the configured model; a current V7 server-direct result has no remote Session to display
or poll. Historical frozen designs retain their own read-only Compiler and recovery contract.
Compiler cannot ask questions or create a Task.

For a new rolling software Task, that description applies to package 1 only before Task creation.
After package 1 is accepted, every later package Designer is rooted in the latest successful
`PackageFactSnapshot`, never the original repository baseline. A Git project exposes a managed,
read-only directory rebuilt from the checkpoint tree; a Direct project exposes the registered
directory only after its tree and manifest still match the prior fact point. The read-only profile
continues to permit only `read/glob/grep` (plus configured MCP prefixes); it cannot write, execute,
approve a design, or start implementation. The prompt contains bounded fact indexes—at most 4 KiB
per package and 24 KiB total—and labels Compiler/handoff prose as navigation rather than evidence.
Full evidence remains available through the fact and evidence APIs. A model response cannot create
or mutate a checkpoint, proven fact, accepted contract, Stage, or cumulative TaskSpec.

Remaining-plan changes are a separate human-confirmed boundary. The user may edit the suffix or
explicitly start a `DECOMPOSER_READ_ONLY` AI suggestion rooted in the verified latest checkpoint.
The asynchronous suggestion persists its remote Session, base Task/package/checkpoint versions,
transport state, output and typed failure; after restart the dedicated monitor resumes polling.
If the process stops in the prompt-submission uncertainty window, Loopper first confirms the old
Session has stopped before creating a replacement. The server validates marker JSON source links
and dependencies, then computes additions, removals, order, dependency, split, and merge impact.
No active plan changes until local confirmation. This proposal path is read-only with respect to
the checkout and cannot supersede a frozen package. Package design
approval and package execution start remain separate local actions even when Designer full-auto is
enabled; full-auto may only advance Router, initial decomposition, and read-only design generation.

An initial historical semantic Compiler candidate may read the repository, but every bounded format or semantic
repair aborts that Session and starts a fresh `COMPILER_REPAIR_NO_TOOLS` Session. Its wildcard
deny profile re-allows no built-in tools but still allows discovered MCP server prefixes; the prompt contains only the current compact object,
deterministic issues and bounded frozen-design evidence. Format repair uses the full compact
Compiler Schema. Semantic repair uses the separate `AI_SEMANTIC_PATCH_V1` Schema whose root is
`patches`, so the transport contract matches the requested JSON Patch envelope.
The semantic-repair prompt names the compact `evidence` field and repeats the Java Stage gate:
every `JAVA_PRODUCTION` Stage needs a focused Maven/Gradle TEST, including Judge-only wiring or
demo work; `FULL_TEST` and `BUILD` are supplemental only. A uniquely reversible final-DTO
`/stages/<n>/verifiers...` pointer is normalized to compact `evidence`, and `replace` of a missing
model-owned object leaf is normalized to `add`; neither normalization bypasses full validation.

Session creation is role-scoped. Except for the zero-tool Router, the adapter reads
`GET /mcp?directory=<canonical-root>`, validates the bounded server-name object, and appends
one `<server>_*` allow rule per connected user server to eligible ordinary role profiles. Discovery failure is
the explicit `OPENCODE_MCP_DISCOVERY_FAILED` Session error before any prompt is sent; Loopper
never edits the user's OpenCode configuration. Candidate roles remove those wildcard user-MCP
grants and re-allow only the exact private tool. Decomposer compatibility, Compiler, Judge, and general
read-only roles start from a wildcard deny and allow only the built-in `read`, `glob`, and
`grep`; the interactive Designer additionally allows the built-in `question` when the runtime
actually registers it. All read-only roles deny
`.env`/`.env.*`, re-allow only `.env.example`, and deny external directories.
Implementation retains the existing mutation and command boundary and explicitly
allows `todowrite`. MCP prefix rules do not relax built-in file-write, Bash, Git,
external-directory, destructive-operation, or Loopper human-authorization boundaries. These
permission profiles are sent when the Session is created; prompts cannot supply an ad-hoc
tool list that weakens them.

The local UI reads `GET /api/designer-sessions/{id}/activity` every 1.2 seconds. Interactive
Designer activity may project bounded `THINKING`, `OUTPUT`, and `TOOL` parts from the active
remote Session. Router uses the same bounded part types in its dedicated modal, but any marker,
JSON-like object, or task-profile field is replaced by **正在整理任务设置识别结果** before it
crosses the API. Decomposer, Compiler, Reviewer, repair, and finalizer expose only tool parts plus
the persisted authoritative workflow step; raw structured planning JSON is never returned as an
activity fragment. Tool names, status, bounded arguments, and bounded
output are presentation data only and cannot advance lifecycle state. The endpoint returns at
most the latest safe fragment. The UI replaces the body of the current-role card in the message
timeline, uses normal Markdown rendering, and does not build a separate activity panel or
client-side activity history; reconnect keeps only the last observed fragment.

The activity message response is also parsed for provider-reported token fields, so the active
remote never requires a second message-list request merely to refresh its numeric token window.
Loopper persists a monotonic aggregate per external Session and sums those rows within the
Designer or Task scope. One non-current incomplete remote may be reconciled per poll to retain
repair, finalizer, previous Attempt, and parallel Judge usage without creating an unbounded
request fan-out. Missing provider token fields remain unknown; elapsed time, output length, and
cost are never converted into synthetic tokens.

Every persisted task-profile Router run has one in-process owner at a time. The synchronous
request path claims a new `PENDING` run before it becomes visible to the monitor, and recovery
polling uses the same claim boundary. A remote Session created by a caller that loses the
optimistic row update is aborted immediately. Reroute and profile workflow replacement require
an acknowledged abort of the previous remote Session before superseding state or creating the
next Session; an abort failure leaves the current authoritative profile and Session unchanged.
The Designer DTO projects the latest Router run ID, local/remote state, error, timestamps,
deadline, and retry eligibility. `POST /api/designer-sessions/{id}/task-profile/reroute` requires
the expected terminal run ID and current unresolved provisional profile version and uses only the
persisted requirement snapshot; a confirmed profile is not retryable, and stale or duplicate
requests return 409 without creating concurrent runs.
`POST /api/designer-sessions/{id}/task-profile/cancel` requires the expected active run ID. It claims
the same monitor boundary, aborts the remote Session, and only then persists `SUPERSEDED` with
`ROUTER_USER_CANCELLED` plus a provisional manual-selection profile. Abort failure leaves the run
active; stale, duplicate, or concurrently owned requests return 409. The cancellation evidence blocks
full-auto adoption until the user saves an explicit override.
`POST /api/designer-sessions/{id}/task-profile/preview` is read-only and reports whether a
versioned selection changes the profile, requires an update, changes the workflow, and which
workflow would be selected. The UI must obtain this preview before saving. A workflow-changing
write is sent only after the user confirms **停止当前设计并重新开始**; an already confirmed
exact-match write is a server-side no-op and cannot abort or replace a remote Session.

`POST /api/designer-sessions/{id}/stop` is a local-UI-only, idempotent cancellation boundary.
It moves the Designer Session to `STOPPING` before external calls, disables further auto-mode
dispatch, then aborts every deduplicated active Router, requirement/package Designer,
Decomposer, Compiler, repair/finalizer, and Reviewer Session. Any failed abort leaves the
workspace in `STOPPING`, unarchived, and retryable. Only complete success records child
terminal projections, transitions to `CANCELLED`, and archives the design; a missing remote
Session on abort is treated as already stopped.

The Runtime capability projection discovers `/agent` with a 30-second cache and
records structured-output observations by endpoint, OpenCode version, provider,
and model. It may show that native `plan` exists, but this release does not
select that agent for Designer: Designer Markdown, Compiler JSON, and the
deterministic Validator remain separate artifacts and authority boundaries.
Loopper-managed runtimes define a private `loopper-structured` agent with at most 24 agentic steps
for Decomposer, Compiler, and Judge, plus a separate one-step `loopper-router` agent for immediate
zero-tool task classification. Interactive Markdown Designer and writable Implementation remain on
their normal agent behavior. Neither agent output becomes authoritative without server validation.

Those tools see the pre-execution repository baseline, not a simulated checkout
after earlier packages. For a package dependency already marked `APPROVED`,
Loopper supplies the predecessor's frozen objective, Compiler summary, and
handoff contract. Strict Task execution guarantees that predecessor's Stages run
first, so a currently absent predecessor-owned class or file is an
available-at-execution dependency rather than `MISSING_SCOPE`. Compiler may only
report a dependency-related semantic gap when neither the current design nor the
frozen predecessor contract defines the required behavior/API.

Decomposer submits one compact semantic object from a read-only Session and maps numbered requirements
to package/constraint indices. The persistent candidate run binds the exact owner/source revisions,
submission channel, external Session and runtime generation. It allows at most five unique submissions;
idempotency replays the same safe response, while key reuse with different content fails closed. Policy
evaluation is deterministic and accepted projection shares one short transaction with the terminal attempt.
Rejected payload values never appear in persisted problems or safe responses: problem codes, JSON Pointers,
and detail text are selected from server-owned static templates only.
For v6 acceptance, the server preserves the Designer's 1–6 direct Stages
or 1–3 Stages per large package; an optional one-turn Compiler fills only enumerated fact/capability holes.
Loopper validates and persists that binding, derives all mechanical fields and stable `DS-Lxxx` source
references, and directly compiles the final package envelope. It does not send a second final-JSON prompt,
and raw machine output is never a chat/SSE model message.
V7 mutation obligations never enter that Compiler request or Schema. The server binds only exact controlled
fact references, one uniquely covering existing Stage path rule, or an exact path added to the plan's only Stage;
real multi-Stage candidates remain a deterministic `DESIGN_INCOMPLETE` gap.
Current V7 direct, large-task, and active rolling packages skip the Compiler Session whenever all closed-set bindings
are server-resolved. A mutation ownership gap waits for targeted input instead of launching a whole-package Designer retry.

The compact Compiler contract asks only for observable business criteria. Loopper
deterministically treats untested code-style, source/annotation/assembly-shape,
build/test-result, and delivery-hygiene entries as frozen engineering metadata,
remaps remaining evidence, and can associate one unambiguous focused Java test with
otherwise unmapped business criteria in that Stage. It never invents test names or
chooses among multiple candidates. Semantic preflight returns all errors with JSON
Pointers in one repair prompt; source-text search is never executable behavior
evidence.

Compatibility work uses stable server-owned response Schemas for compact Decomposer,
v7 acceptance closed choice, frozen-v6 acceptance disambiguation, and final Judge. The managed
private-MCP path uses normal text prompting and the tool input schema instead of treating final text as a response.
Legacy Decomposer/Compiler final Schemas remain
registered only for historical rows without a semantic snapshot. A typed prompt may choose text or one of those
schemas and may set system/agent fields, but it never accepts caller-owned tools.
Schema mode uses OpenCode `format.type=json_schema` with provider retry count
zero so Loopper's persisted planning/final repair budgets remain authoritative.
An asynchronous HTTP 2xx proves only queue admission and never marks structured
output available. The adapter records support only after reading a real
`info.structured` object; it preserves legal JSON nulls and records explicit
transport/model failures separately.

Decomposer, Compiler, and final Judge steps select the configured provider/model
with `thinking=false` only while their persisted response mode is `JSON_SCHEMA`.
Ordinary `TEXT_MARKER` steps, including a fresh Session created after Schema fallback,
retain the configured thinking choice or the provider default when it is absent. The managed
Router is the deliberate exception: its marker prompt always selects the private non-thinking
variant for DeepSeek because classification must not spend time on design-like reasoning.
Interactive Markdown Designer and writable Implementation Sessions keep their
existing configured behavior. For a Loopper-managed DeepSeek runtime, startup injects a private
`loopper-no-thinking` model variant whose provider option is
`thinking.type=disabled`; JSON Schema prompts for those three machine-response roles and the
managed Router select that variant. Other marker prompts never attach it.
This avoids DeepSeek's incompatibility between Thinking and
OpenCode's required structured-output tool choice without weakening any role
permission or deterministic validation boundary. A reused external OpenCode
runtime remains operator-owned and must expose the same variant for its selected
DeepSeek model to get the direct schema path; otherwise the existing fresh-Session
marker fallback remains the safe compatibility path.

New Decomposer and current v7/frozen-v6 disambiguation records prefer JSON Schema unless capability is known
unavailable. OpenCode 1.18.12 through 1.18.18 are deterministically quarantined
to marker mode because both endpoints of that patch range were verified to
accept `prompt_async` and then reject their own stored Schema during message
decoding. Later versions return to normal capability probing. A rejected format,
typed structured-output error, or completed turn
without structured data consumes one ordinary model call and the existing one-time transport fallback allowance,
then creates one fresh read-only role Session and retries that step with the matching marker contract. V7 retains
the same `PACKAGE_ACCEPTANCE_CLOSED_CHOICE_V7` semantic closure in marker mode; this fallback does not create a
format/semantic repair pool or permit a second closed-choice answer. It never retries
in the failed transcript, never adds a provider-owned hidden retry pool, and
never bypasses the same deterministic semantic validation. Legacy active rows
default to marker mode. Judge records likewise persist response mode/schema;
their next explicitly scheduled ordinal uses marker mode after a structured
Session error, while the existing atomic Requirement/Risk prompt preflight is
unchanged.

OpenCode may accept `prompt_async` with `format.type=json_schema` but later reject
`GET /session/{id}/message` while decoding the stored format. Machine-response
polling therefore reads messages even while `/session/status` remains `busy`.
A 400/404/415/422
whose response identifies format or Schema incompatibility is the same explicit
transport-capability rejection and therefore enters the fresh marker Session
path above; it is not wrapped as a generic message failure. For every OpenCode
caller, status `RETRY` is transient provider self-recovery: the caller keeps
polling the same Session and does not create a replacement local execution row,
consume a Loopper retry budget, or persist a Session failure. Designer
additionally keeps its workflow `RUNNING`, and an authorized auto mode is not
blocked by that transient projection. Existing Designer, machine-role,
Implementation, Judge, project-convention, publication, and local-sync timeouts
remain authoritative. `RETRY` is not a terminal observation and cannot prove
that a mutating writer stopped. A
failed `StructuredOutput` tool part is the same explicit
structured-output failure. Loopper also counts assistant/step-start records after
the latest user turn and enforces its own 24-step hard limit even if OpenCode's
agent setting is ignored. The managed agent uses temperature zero and a fixed
instruction to stop repository exploration once evidence is sufficient and never
repeat or invent tool calls. Three consecutive calls with the same normalized
tool name and canonical arguments terminate that loop early. Loopper aborts the
original Session and, once per persisted role step, may start a built-in-tools-disabled
finalizer with bounded deduplicated evidence. That call counts against the
global model-call budget but not the format/semantic repair budgets; V28
prevents a restart from granting another finalizer. Implementation retains its existing
failure-escalation contract. Actual terminal failure, retry exhaustion, timeout, or transition to
human input always makes a best-effort abort call before Loopper reports the
structured role as stopped.

Decomposer and the historical semantic Compiler use a compact object containing either `COMPILED` semantic Stages or
`DESIGN_INCOMPLETE` with closed gap codes. Loopper resolves every `DS-Lxxx`
reference to exact frozen text, derives the complete package fragment, and runs
the same field, verifier, coverage, project, and draft-version validation used
by other entry points. Their extraction failures receive at most two full-object format
repairs; semantic/safety failures receive at most two restricted JSON Patch turns.
The counters are independent. Missing observable outcome,
exception semantics, scope, or acceptance intent requests at most one automatic
full replacement from the scoped Designer conversation for the current package. Format errors cannot be
relabelled as design gaps. Retry exhaustion or optimistic draft conflict leaves
the draft unchanged and exposes explicit manual decomposition/package recovery.
The initial Compiler prompt and format repair repeat the compact semantic Schema;
the model never has to emit `criterionIds`, `testTargets`, exact excerpts, stable
IDs or duplicated verifier mappings. Semantic repair returns at most 16
`add/replace/remove` operations against AI-owned fields, after which Loopper
re-runs complete normalization and authoritative validation. Loopper compiles the
temporary Stage contract and validates it with the authoritative v2
assessment before freezing it; direct argv, BEHAVIOR coverage, focused Java
tests and managed-runtime bindings therefore fail in the semantic repair pool
before accepting the server-compiled result.
Historical semantic records are parsed as that compact contract by default. Only a persisted historical
object with `evidenceMappings` enters the legacy planning parser; a semantic object that
omits `outcome` is a format mismatch, while v6 acceptance disambiguation intentionally has no outcome. A rejected
patch envelope or other incomplete object cannot replace the last valid semantic snapshot.
The compact transport accepts up to six Stages so direct software's 1–6 Stage product
contract is not narrowed to the large-package 1–3 limit. For non-Java packs, safe explicit
npm/pytest/unittest selectors are compiled by the shared test-policy registry rather than
the Java-only target extractor.
Stable criterion numbering, exact-source slicing, and duplicated TEST verifier
`criterionIds`/`testTargets` are server-owned encoding work: Loopper canonicalizes
them from the semantic evidence mappings before running that unchanged v2
assessment. The planning prompt separately lists bounded Designer lines containing
explicit focused Java tests, and marks every applicable named test as mandatory
evidence. Loopper may extract `testTargets` only from explicit safe Maven/Gradle
selectors and may copy or materialize the same focused verifier when the Stage
mapping is unique. It does not invent targets, infer tests from source prose, or
pick between multiple candidates. Exact-source recovery is allowed only for one
unique normalized match; ambiguity, missing business evidence, unsafe commands,
or genuinely missing focused tests still consume the normal planning repair path.
Each requirement revision permits at most 96 model calls across requirement
discussion, package discussion, and all machine roles, plus one fresh-Session
transport retry per role invocation. Question answers continue the already
counted model turn. After a package candidate passes, it remains in `REVIEWING`
until the exact revision is accepted. Only after all packages are `APPROVED`
does Loopper—not a model—concatenate fragments in package order and run complete
validation before an optimistic draft update. No branch, Task, or writable
Session is created beforehand.

Scoped REST mutations carry the expected discussion/design revision. Requirement
messages/confirm/reopen and package messages/approve/reopen reject stale clients
with 409; the legacy `/messages` path is accepted only before decomposition.
Reopening an approved package invalidates only its transitive dependents, while
reopening the requirement supersedes the current decomposition as an explicit
destructive boundary. Machine semantic/final JSON remains out of the chat
transcript; only persisted role summaries and the validated candidate projection
are returned to the browser.

Decomposer/Compiler/Judge output shares a bounded compatibility extractor.
Native structured payload and exact role markers are preferred; otherwise one
valid object may be taken from a `json`/untyped fence, short explanatory prose,
or the complete response. Equivalent candidates collapse to one answer;
conflicting valid objects, arrays, incomplete or non-standard JSON, oversize
content, and ambiguous field normalization remain errors. Safe deterministic
normalization does not consume a format repair and never bypasses the unchanged
typed, coverage, dependency, package-boundary, verifier, runtime, or frozen-plan
validation. Project Convention Markdown follows the analogous marker, unique
fence, then complete-response policy while retaining reserved-marker and size checks.

The v7 corpus/shadow evaluator is not an OpenCode role. It has no HTTP adapter,
permission profile, MCP discovery, response payload, or Session retry. A local
same-input shadow may count that the authoritative v6 path would need a model call
and that v7 would avoid it, but it cannot start either call or persist model output.
Its corpus report contains only versioned expectations and exact local JUnit guard
execution and is explicitly non-authoritative; the same-input production planning
pipeline produces an authoritative measurement, key guards publish bounded actual call and
safety counts through a test-only registry, and the complete gate checks those values before
joining them with every exact production guard. Neither operation creates an OpenCode Session. It is
defined in `weak-model-compiler-v7-evaluation.md`.

The MCP `propose_loop_spec` tool uses the same session-bound update path, but is
rejected while an active decomposed design workflow has not completed. It no
longer creates an unrelated draft, so an external MCP client and the built-in
Designer workflow converge on the same Review Gate state. Failed or unavailable
Decomposer/Designer/Compiler handoffs remain on the Designer Session and never transition a Task.
For v2 drafts, `propose_loop_spec` and `validate_loop_spec` expose the same
server-computed criterion coverage and evidence categories as
`POST /api/loop-drafts/validate`. The model cannot self-declare a verifier as
behavior evidence. Compiler planning is also assessed with the runtime path
policy: malformed globs and an allowed rule entirely shadowed by a forbidden
rule enter the bounded planning-repair turn before any plan is frozen. The
server repeats this check on draft persistence and confirmation, while keeping
broad allow rules with narrower exclusions valid. A persisted v1 binding may continue to propose v1 updates,
but an unbound/new proposal cannot use v1 to bypass strict validation.

All OpenCode adapter requests and runtime health probes use the configured
connect and request timeouts. This bounds a stalled local server rather than
letting scheduler monitors wait indefinitely.

The HTTP adapter is deliberately a protocol facade. `OpenCodePermissionPolicy`
owns fail-closed role profiles; `OpenCodeResponseParser` owns common response and
error decoding; `OpenCodeTodoParser` owns bounded Todo normalization; and
`OpenCodeMachineResponseInspector` owns structured-output/tool-loop inspection.
The facade retains request ordering, timeout, and transport ownership. A parser
or policy must remain deterministic and must never persist lifecycle state,
create a retry, or make a second HTTP request.

A mutating implementation Session belongs to exactly one Attempt. After a
verification failure, Loopper persists a bounded `ATTEMPT_HANDOFF` and supplies
it to a newly created Session; it never continues the previous transcript.
`sessionPolicy.createFreshOnVerifierFailure=false` means automatic continuation
is disabled and the Task waits for an explicit local-UI retry. The legacy
`reuseHealthySession` field remains wire-compatible but does not authorize
cross-Attempt Session reuse. Explicit retry parses the persisted handoff before
changing Task state and renders the same bounded `nextAttemptPromptTemplate` as
automatic continuation; missing or malformed handoff evidence is fail-closed.

## Dynamic Task Session monitoring

Task detail exposes a read-only projection of its OpenCode Sessions:

- `GET /api/tasks/{taskId}/sessions` lists implementation and judge Sessions;
- `GET /api/tasks/{taskId}/sessions/{sessionKey}` returns current status plus
  bounded provider-exposed `THINKING`, `OUTPUT`, and `TOOL` parts, and for an
  implementation Session its Todo capability and current bounded Todo list.

The UI polls active Sessions every 1.2 seconds and terminal history every three
seconds; the background lifecycle monitor refreshes an available implementation
Todo projection at most every two seconds. Todo reads happen outside SQLite and
persist only changed snapshots. At most 64 items, 1 KiB per item, and 64 KiB
total content are retained, with stable content/occurrence-derived ids and an
explicit truncation marker. Todo failure or completion never changes Task,
Stage, Attempt, verifier, or Judge state. Monitoring never resumes, aborts, or
otherwise mutates a Session, and transport errors shown by the panel do not alter
Task state. Loopper displays only content returned by the OpenCode API; it does
not fabricate or expose private model reasoning.

## Designer acceptance handoff

The frozen Markdown design and executable LoopSpec remain separate artifacts.
New v2 drafts first declare each Stage's `implementationKind` and observable
criterion IDs, then map them to server-valid
behavior verifiers. `PROCESS` commands remain argv arrays: a `TEST` mapped to a
business criterion requires a recognized non-skipping test command and concrete
targets; a safe unmapped full-suite test can remain only as blocking supplemental
report evidence and cannot provide behavior coverage; `SELF_CHECK` requires
an explicit bounded-output marker; build commands cannot cover criteria.
`GIT_DIFF` remains an opt-in scope verifier and cannot establish functional
correctness. Network criteria additionally require the same Stage's managed
dynamic-port runtime; an already-running fixed-port service cannot make the new
code pass. Compiler synchronization, manual save, MCP validation, template
publication and human confirmation all call the same analyzer. A
`JAVA_PRODUCTION` Stage additionally requires an unskipped focused Maven/Gradle
test with `testTargets` mapped to every `MACHINE`/`BOTH` criterion. Runtime
verification independently compares the Stage-start production-Java baseline;
classification mismatch and missing successful focused tests are blocking
Attempt failures rather than model judgments.
High-confidence Maven arguments accidentally combined into one array item are
tokenized without a shell and persisted as canonical argv. Only a Maven command
whose token boundary cannot be parsed safely is rejected for Designer repair.

## Runtime ownership and permissions

`managed` mode is the default and always starts a fresh owned `opencode serve`
process on a random loopback port with random Basic credentials, a random
private-MCP name, a generation identifier and a generation-scoped Bearer kept
in memory. It never reuses port 4096. Availability requires both authenticated
`/global/health` and the exact private entry in `/mcp` to be connected. The
managed permission policy denies external directories, all commit/ref/branch and remote
baseline mutations (`commit`, `update-ref`, `branch`, `fetch`, `pull`, `push`,
and related Git operations), `git reset --hard`, and `rm -rf` in its managed
permission policy. Restart and shutdown may terminate
only that owned process; an operator-owned endpoint is never killed. `auto`
remains an explicit reuse-or-start compatibility mode. `http` is connect-only
and rejects non-loopback endpoints; external modes do not claim private-MCP
readiness and use the explicitly separated in-process candidate adapter.

Release startup scripts do not assume that OpenCode listens on port 4096.
Unless `OPENCODE_BASE_URL` is explicitly supplied, they inspect current
OpenCode process command lines and explicit `--port` values. On Linux they
also resolve listening TCP ports owned by those OpenCode PIDs, covering the
TUI and `opencode web` servers whose dynamically selected port may be absent
from the command line. If Linux hides socket ownership from an unprivileged
process, the launcher health-checks the bounded set of local TCP listeners
instead; it never accepts a port without the exact OpenCode health contract.
`0.0.0.0` and `[::]` addresses are normalized to loopback before connecting,
and `OPENCODE_SERVER_USERNAME`/`OPENCODE_SERVER_PASSWORD` are accepted as the
official OpenCode Basic Auth variables. Candidates are accepted only when the
authenticated or anonymous loopback `/global/health` response reports
`healthy=true`. The release scripts default to `managed`, skip external discovery,
and let Loopper start an isolated OpenCode process on a dynamically allocated
loopback port. Only an explicitly selected `auto` mode performs the compatibility
discovery above. Before Linux enters `managed` or needs to start under `auto`, the launcher resolves
`OPENCODE_EXECUTABLE` or `opencode` from `PATH` to a concrete executable path
and fails immediately when no executable is available. Discovery never treats
an unrelated listener as OpenCode and never stops an externally owned process.

Each managed launch records its newly allocated endpoint before the process is
created. If process creation, early exit, private-MCP connection, or the bounded health wait fails, the
Runtime API returns `OFFLINE`, `managed=false`, the actual attempted endpoint,
the running service's `loopperVersion`, and a sanitized `startupFailure`.
`loopperVersion` is distinct from the OpenCode CLI `version` and must be rendered
from the server response rather than a frontend constant. The API must not present the configured default
probe endpoint (commonly `127.0.0.1:4096`) as a listener. Internal requests fail
closed against an unused loopback endpoint until an explicit local-UI retry
succeeds. Ordinary Runtime reads and internal client lookups must not repeatedly
spawn processes after a recorded launch failure. `POST /api/runtime/opencode/start`
is the local-UI-only recovery boundary: it clears the prior failure and performs
one launch attempt under the configured mode. Managed mode returns `AVAILABLE`
only after both authenticated health and exact private-MCP readiness succeed; a
created process or health response alone is insufficient.
The Runtime response exposes only an eight-character generation display token plus the internal-MCP
`status`, `configured`, and sanitized detail; it never returns the full generation, private Server name,
Bearer, or private URL.
During managed startup, each health request is capped at one second or the
remaining startup budget, whichever is shorter. A general OpenCode request
timeout must never consume the whole startup budget before another health probe
can run.

The OpenCode execution permission boundary continues to deny `git push`.
Publication is a separate Spring service available only after persisted Task
success and an explicit local-UI confirmation. It revalidates the exact registered
checkout and current task branch, accepts only `#` plus four digits plus `_subject`,
uses argv-only Git calls, and never force-pushes. AI commit-subject generation
runs in a distinct read-only OpenCode session; the four-digit work item always
comes from the user.

The Spring AI Streamable HTTP initialize response advertises only the tools
capability, and `tools/list` exposes exactly six Loopper tools. Resources,
prompts and completions are disabled. Both `/api/mcp-streamable` and the
compatibility `/api/mcp` fail closed without the configured Bearer token.

## Read-only MCP inventory (0.3.27)

`OpenCodeToolInventory` reads project-scoped `/mcp` statuses and merged `/config` MCP definitions
from the selected loopback OpenCode runtime. `/experimental/tool` only enumerates built-in tools
and must not be presented as the MCP catalog. The System Tools page lists every configured or
reported server (bounded to 256); private generation server names are replaced by a stable alias.
Expanding a connected, enabled server uses its existing configuration for an independent
initialize/tools/list session. Local stdio uses the selected directory without a shell wrapper;
remote Streamable HTTP falls back to SSE. No tools/call, sampling or configuration mutation is
permitted. Credentials/configuration and stderr are never returned; raw exception messages are
replaced with a safe unavailable explanation. OAuth credentials absent from the merged config
are not harvested from private stores. Requests are bounded, pagination stops at 512 tools or
its deadline with an explicit partial result, clients close, and complete catalogs cache for
20 seconds with runtime/directory/config identity. Failures remain retryable and distinct from
an empty successful tools list. This inventory does not expand any model role's tool permissions.
