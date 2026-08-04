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
parameter. A managed server must bind loopback and use Basic Auth. Passwords
remain in process memory/environment and are never logged or persisted.

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
`COMPLETED`. Missing, invalid, mismatched, or concurrently changed payloads
produce `SESSION_ERROR`; they are never treated as a completed design.

The MCP `propose_loop_spec` tool uses the same session-bound update path. It no
longer creates an unrelated draft, so an external MCP client and the built-in
Designer workflow converge on the same Review Gate state. Failed or unavailable
Designer handoffs remain on the Designer Session and never transition a Task.

All OpenCode adapter requests and runtime health probes use the configured
connect and request timeouts. This bounds a stalled local server rather than
letting scheduler monitors wait indefinitely.

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
assertion over bounded process output. `GIT_DIFF` remains a scope verifier and
cannot be the only verifier of a confirmation-ready stage. Draft creation may
start with that placeholder, but Designer synchronization, manual save, MCP
validation, and human confirmation all reject the weak contract.

## Runtime ownership and permissions

`auto` mode first reuses a healthy loopback endpoint. Otherwise Loopper starts
`opencode serve` on a random loopback port with random Basic credentials kept
in memory and denies external directories, `git push`, `git reset --hard`, and
`rm -rf` in its managed permission policy. Restart and shutdown may terminate
only that owned process; an operator-owned endpoint is never killed. `http`
mode is connect-only and rejects non-loopback endpoints.

The Spring AI Streamable HTTP initialize response advertises only the tools
capability, and `tools/list` exposes exactly six Loopper tools. Resources,
prompts and completions are disabled. Both `/api/mcp-streamable` and the
compatibility `/api/mcp` fail closed without the configured Bearer token.
