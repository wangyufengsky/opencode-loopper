# OpenCode 1.18.12 contract evidence

Verified locally on 2026-08-04 with `opencode serve --hostname 127.0.0.1
--port 4097` in an isolated temporary directory.

| Operation | Endpoint | Observed result |
| --- | --- | --- |
| health | `GET /global/health` | `healthy=true`, version `1.18.12` |
| MCP status | `GET /mcp` | JSON object |
| create Session | `POST /session?directory=...` | Session with canonical directory |
| Session status | `GET /session/status` | JSON map |
| Session messages | `GET /session/{id}/message` | user/assistant messages with completion metadata |
| Session diff | `GET /session/{id}/diff` | JSON array |
| Session Todo | `GET /session/{id}/todo?directory=...` | bounded implementation progress projection |
| built-in tool ids | `GET /experimental/tool/ids?directory=...` | workspace-scoped tool capability list |
| native agents | `GET /agent` | native agent metadata, including optional `plan` |
| abort | `POST /session/{id}/abort` | boolean |
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

## Designer and MCP boundary

The verified OpenCode session API has no safe, documented operation for
dynamically attaching Loopper's MCP server to an existing Session. Loopper does
not invent one: a Designer uses an OpenCode `createReadOnlySession` against the
registered project root, while Loopper's bearer-protected Spring AI MCP server
is independently exposed at `/api/mcp-streamable`.

The REST workflow uses three model roles plus a deterministic server validator.
Each overall Designer Session is bound to the exact `loop_draft_id` shown in
Review Gate. Before Task Decomposer runs, the interactive requirement Designer
must call `question` with 1–3 design choices and then return a complete Markdown
replacement in the same model turn. Follow-up requirement messages repeat that
contract without starting Decomposer. Only the explicit requirement-confirm API
freezes a numbered requirement revision and supplies it with read-only project
context to Task Decomposer. It may use only `read`, `glob`, and
`grep`, and returns a marked `DIRECT_DESIGN`, `DECOMPOSED`, `NEEDS_INPUT`, or
`MULTI_TASK_REQUIRED` envelope. It cannot write, execute commands, ask a
model-side question, or create a Task. The server verifies complete requirement
coverage and dependency order before persisting packages.

For each package in order, Loopper creates a scoped read-only Designer
conversation. A healthy remote Session is reused for that package's human
revisions; after remote loss, a new Session receives the persisted requirement,
decisions, previous full snapshot, and package-scoped message. Initial design and
each human revision must call `question` before returning one complete Markdown
replacement; Designer is never asked to populate LoopSpec fields. Loopper then
creates a brand-new read-only Compiler Session for each candidate with the same
configured model. Compiler has the same `read`/`glob`/`grep`-only boundary and
cannot ask questions or create a Task.

Session creation is role-scoped. Decomposer, Compiler, Judge, and general
read-only roles start from a wildcard deny and allow only `read`, `glob`, and
`grep`; Designer additionally allows `question`. All read-only roles deny
`.env`/`.env.*`, re-allow only `.env.example`, and deny external directories.
Implementation retains the existing mutation and command boundary and explicitly
allows `todowrite`. These permission profiles are sent when the Session is
created; prompts cannot supply an ad-hoc tool list that weakens them.

The Runtime capability projection discovers `/agent` with a 30-second cache and
records structured-output observations by endpoint, OpenCode version, provider,
and model. It may show that native `plan` exists, but this release does not
select that agent for Designer: Designer Markdown, Compiler JSON, and the
deterministic Validator remain separate artifacts and authority boundaries.
Loopper-managed runtimes additionally define a private `loopper-structured`
agent with at most 24 agentic steps. Only Decomposer, Compiler, and Judge select
it; interactive Markdown Designer and writable Implementation remain on their
normal agent behavior. The step bound limits repository exploration without
changing the per-Session permission profile or making agent output authoritative.

Those tools see the pre-execution repository baseline, not a simulated checkout
after earlier packages. For a package dependency already marked `APPROVED`,
Loopper supplies the predecessor's frozen objective, Compiler summary, and
handoff contract. Strict Task execution guarantees that predecessor's Stages run
first, so a currently absent predecessor-owned class or file is an
available-at-execution dependency rather than `MISSING_SCOPE`. Compiler may only
report a dependency-related semantic gap when neither the current design nor the
frozen predecessor contract defines the required behavior/API.

Decomposer and Compiler each return one compact semantic object from a read-only
Session. Decomposer maps numbered requirements to package/constraint indices;
Compiler maps 1–3 Stages and acceptance criteria to stable `DS-Lxxx` source refs
and closed evidence intentions. Loopper validates and persists the semantic
snapshot, derives all mechanical fields, and directly compiles the final legacy
envelope. It does not send a second final-JSON prompt, and raw semantic output is
never a chat/SSE model message.

The compact Compiler contract asks only for observable business criteria. Loopper
deterministically treats untested code-style, source/annotation/assembly-shape,
build/test-result, and delivery-hygiene entries as frozen engineering metadata,
remaps remaining evidence, and can associate one unambiguous focused Java test with
otherwise unmapped business criteria in that Stage. It never invents test names or
chooses among multiple candidates. Semantic preflight returns all errors with JSON
Pointers in one repair prompt; source-text search is never executable behavior
evidence.

New work uses three stable server-owned response Schemas: compact Decomposer,
compact Compiler, and final Judge. Legacy Decomposer/Compiler final Schemas remain
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
`TEXT_MARKER` steps, including a fresh Session created after Schema fallback,
retain the configured thinking choice or the provider default when it is absent.
Interactive Markdown Designer and writable Implementation Sessions keep their
existing configured behavior. For a Loopper-managed DeepSeek runtime, startup injects a private
`loopper-no-thinking` model variant whose provider option is
`thinking.type=disabled`, and only JSON Schema prompts for those three
machine-response roles select that variant. Plain marker prompts never attach it.
This avoids DeepSeek's incompatibility between Thinking and
OpenCode's required structured-output tool choice without weakening any role
permission or deterministic validation boundary. A reused external OpenCode
runtime remains operator-owned and must expose the same variant for its selected
DeepSeek model to get the direct schema path; otherwise the existing fresh-Session
marker fallback remains the safe compatibility path.

New Decomposer/Compiler records prefer JSON Schema unless capability is known
unavailable. OpenCode 1.18.12 through 1.18.18 are deterministically quarantined
to marker mode because both endpoints of that patch range were verified to
accept `prompt_async` and then reject their own stored Schema during message
decoding. Later versions return to normal capability probing. A rejected format,
typed structured-output error, or completed turn
without structured data consumes one ordinary model call and the matching
format-repair allowance, then creates one fresh read-only role
Session and retries that step with the legacy marker contract. It never retries
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
path above; it is not wrapped as a generic message failure. For interactive
Designer, Decomposer, and Compiler, OpenCode status `RETRY` is transient provider
self-recovery, so the design pipeline keeps polling the same Session and does
not consume its single transport retry. Designer keeps its workflow `RUNNING`,
and an authorized auto mode is not blocked by that transient projection. A
failed `StructuredOutput` tool part is the same explicit
structured-output failure. Loopper also counts assistant/step-start records after
the latest user turn and enforces its own 24-step hard limit even if OpenCode's
agent setting is ignored. The managed agent uses temperature zero and a fixed
instruction to stop repository exploration once evidence is sufficient and never
repeat or invent tool calls. Three consecutive calls with the same normalized
tool name and canonical arguments terminate that loop early. Loopper aborts the
original Session and, once per persisted role step, may start a no-tools
finalizer with bounded deduplicated evidence. That call counts against the
global model-call budget but not the format/semantic repair budgets; V28
prevents a restart from granting another finalizer. Implementation retains its existing
failure-escalation contract. Actual terminal failure, retry exhaustion, timeout, or transition to
human input always makes a best-effort abort call before Loopper reports the
structured role as stopped.

Compiler's compact object contains either `COMPILED` semantic Stages or
`DESIGN_INCOMPLETE` with closed gap codes. Loopper resolves every `DS-Lxxx`
reference to exact frozen text, derives the complete package fragment, and runs
the same field, verifier, coverage, project, and draft-version validation used
by other entry points. Extraction failures receive at most two full-object format
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

`auto` mode first reuses a healthy loopback endpoint. Otherwise Loopper starts
`opencode serve` on a random loopback port with random Basic credentials kept
in memory and denies external directories, all commit/ref/branch and remote
baseline mutations (`commit`, `update-ref`, `branch`, `fetch`, `pull`, `push`,
and related Git operations), `git reset --hard`, and `rm -rf` in its managed
permission policy. Restart and shutdown may terminate
only that owned process; an operator-owned endpoint is never killed. `http`
mode is connect-only and rejects non-loopback endpoints.

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
`healthy=true`. If no endpoint can be reused, the scripts select `auto` mode
so Loopper starts an owned OpenCode process on a dynamically allocated loopback
port. Before Linux enters managed `auto` mode, the launcher resolves
`OPENCODE_EXECUTABLE` or `opencode` from `PATH` to a concrete executable path
and fails immediately when no executable is available. Discovery never treats
an unrelated listener as OpenCode and never stops an externally owned process.

Each managed launch records its newly allocated endpoint before the process is
created. If process creation, early exit, or the bounded health wait fails, the
Runtime API returns `OFFLINE`, `managed=false`, the actual attempted endpoint,
the running service's `loopperVersion`, and a sanitized `startupFailure`.
`loopperVersion` is distinct from the OpenCode CLI `version` and must be rendered
from the server response rather than a frontend constant. The API must not present the configured default
probe endpoint (commonly `127.0.0.1:4096`) as a listener. Internal requests fail
closed against an unused loopback endpoint until an explicit local-UI retry
succeeds. Ordinary Runtime reads and internal client lookups must not repeatedly
spawn processes after a recorded launch failure. `POST /api/runtime/opencode/start`
is the local-UI-only recovery boundary: it clears the prior failure, performs one
Auto launch attempt, and returns `AVAILABLE` only after the authenticated
`/global/health` response reports `healthy=true`. A created process alone is not
a successful connection.
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
