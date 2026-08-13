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
Review Gate. Task Decomposer first receives a frozen, numbered requirement
revision and read-only project context. It may use only `read`, `glob`, and
`grep`, and returns a marked `DIRECT_DESIGN`, `DECOMPOSED`, `NEEDS_INPUT`, or
`MULTI_TASK_REQUIRED` envelope. It cannot write, execute commands, ask a
model-side question, or create a Task. The server verifies complete requirement
coverage and dependency order before persisting packages.

For each package in order, Loopper creates a brand-new read-only Designer
Session. Designer receives the original requirement, frozen package plan,
global constraints, and bounded prerequisite handoffs and returns one complete
Markdown package design; it is never asked to populate LoopSpec fields. Loopper
then creates a brand-new read-only Compiler Session with the same configured
model. Compiler has the same `read`/`glob`/`grep`-only boundary and cannot ask
questions or create a Task.

Compiler returns one marked envelope with either `COMPILED` (a 1–3 Stage package
fragment, bounded summary/handoff, and an exact Designer excerpt for every criterion) or
`DESIGN_INCOMPLETE` (a closed semantic gap code plus concrete gaps). Loopper
checks each excerpt against the frozen design and then runs the same field,
verifier, coverage, project, and draft-version validation used by other entry
points. JSON/schema/verifier/coverage failures create another bounded repair
turn in the same Compiler Session, at most twice after the initial compile. Missing observable outcome,
exception semantics, scope, or acceptance intent requests at most one automatic
full replacement from a fresh Designer Session for the current package. Format errors cannot be
relabelled as design gaps. Retry exhaustion or optimistic draft conflict leaves
the draft unchanged and exposes explicit manual decomposition/package recovery.
Each requirement revision permits at most 24 model calls across all roles and
one fresh-Session transport retry per role invocation. After every package
passes, Loopper—not a model—concatenates fragments in package order and runs the
complete validation before an optimistic draft update. No branch, Task, or
writable Session is created beforehand.

The MCP `propose_loop_spec` tool uses the same session-bound update path, but is
rejected while an active decomposed design workflow has not completed. It no
longer creates an unrelated draft, so an external MCP client and the built-in
Designer workflow converge on the same Review Gate state. Failed or unavailable
Decomposer/Designer/Compiler handoffs remain on the Designer Session and never transition a Task.
For v2 drafts, `propose_loop_spec` and `validate_loop_spec` expose the same
server-computed criterion coverage and evidence categories as
`POST /api/loop-drafts/validate`. The model cannot self-declare a verifier as
behavior evidence. A persisted v1 binding may continue to propose v1 updates,
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
  bounded provider-exposed `THINKING`, `OUTPUT`, and `TOOL` parts.

The UI polls active Sessions every 1.2 seconds and terminal history every three
seconds. Monitoring never resumes, aborts, or otherwise mutates a Session, and
transport errors shown by the panel do not alter Task state. Loopper displays
only content returned by the OpenCode API; it does not fabricate or expose
private model reasoning.

## Designer acceptance handoff

The frozen Markdown design and executable LoopSpec remain separate artifacts.
New v2 drafts first declare each Stage's `implementationKind` and observable
criterion IDs, then map them to server-valid
behavior verifiers. `PROCESS` commands remain argv arrays: `TEST` requires a
recognized non-skipping test command and concrete targets; `SELF_CHECK` requires
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
and a sanitized `startupFailure`. It must not present the configured default
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
