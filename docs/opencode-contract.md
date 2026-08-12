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
parameter and require the create response to confirm that exact canonical
directory. A missing or mismatched response is a Session failure before any
prompt is sent. A managed server must bind loopback and use Basic Auth.
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

The Designer REST workflow closes that transport gap deterministically. Each
new Designer Session is persisted with the exact `loop_draft_id` shown in the
Review Gate. The prompt includes that binding and the current full LoopSpec and
requires a final JSON payload between `LOOPSPEC_JSON_START` and
`LOOPSPEC_JSON_END`. After `sessionOutput`, Loopper parses and validates the
payload, updates the bound draft using optimistic locking, strips the machine
payload from the visible Markdown, and only then transitions the Session to
`COMPLETED`. Missing, invalid, mismatched, or concurrently changed payloads are
returned to the same read-only Designer for at most two document-correction
turns. Exhausted correction attempts produce `SESSION_ERROR`; rejected output
is never treated as a completed design and never creates a Task.

The MCP `propose_loop_spec` tool uses the same session-bound update path. It no
longer creates an unrelated draft, so an external MCP client and the built-in
Designer workflow converge on the same Review Gate state. Failed or unavailable
Designer handoffs remain on the Designer Session and never transition a Task.

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

The Markdown plan and executable LoopSpec share one acceptance contract.
Designer commands must be copied into stage `PROCESS` verifiers as argv arrays;
`outputContains` turns an advertised marker such as `PASS` into a deterministic
assertion over bounded process output. `GIT_DIFF` remains an opt-in scope
verifier and cannot be the only verifier of a confirmation-ready stage. Drafts
start without an implicit path verifier; Designer synchronization, manual save,
MCP validation, and human confirmation require functional acceptance checks.
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
