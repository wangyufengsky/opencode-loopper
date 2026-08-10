# OpenCode Loopper design contract

The UI is a desktop-first developer console. Figma is the visual source of
truth; Vue components must expose the same states and terminology as the Figma
component variants.

Figma source: [OpenCode Loopper Control Plane](https://www.figma.com/design/dEUMnufilqNivuyK3vgyIT)

The file contains 43 variables in four collections, six text styles, three
effects, 18 reusable components, six desktop screens, and dedicated Session
warning / Task-terminal boards. Vue uses Element Plus for mature interaction
primitives and keeps the Figma tokens and error hierarchy in local components.

## Typography

- Product UI: `Inter`, with the platform sans-serif stack as fallback.
- Logs and code: `JetBrains Mono`, with the platform monospace stack as fallback.
- Main frames: 1440 px and 1280 px. Mobile is outside v1 acceptance.

## Dark-first tokens

| Token | Value | Purpose |
| --- | --- | --- |
| `--color-bg-canvas` | `#070B14` | application background |
| `--color-bg-surface` | `#0D1424` | navigation and base cards |
| `--color-bg-elevated` | `#121C30` | dialogs and selected panels |
| `--color-border-default` | `#21304B` | structural border |
| `--color-text-primary` | `#E6EDF8` | primary content |
| `--color-text-secondary` | `#9AA8BD` | secondary content |
| `--color-action-primary` | `#3B82F6` | primary action |
| `--color-accent-cyan` | `#22D3EE` | active execution and streams |
| `--color-accent-ai` | `#8B5CF6` | model and AI metadata |
| `--color-success` | `#22C55E` | deterministic success |
| `--color-session-warning` | `#F59E0B` | recoverable Session failure |
| `--color-task-danger` | `#EF4444` | terminal Task failure only |

Spacing follows a 4 px base grid. Use radii 6/10/14 px for controls, cards and
dialogs. Glows are reserved for active execution and must not reduce text
contrast.

## Required components

- App navigation and page header
- Button, input, select, tabs, dialog and status badge
- Project card and Task card
- Stage rail and Attempt timeline
- Session error card (`recoverable`, amber)
- Task fatal banner (`terminal`, red)
- Log viewer, Diff viewer and evidence panel
- Approval dialog and empty/loading states

## Error presentation invariant

1. Field errors remain inline and do not change runtime state.
2. Verification failures are evidence for the next Attempt.
3. Session failures close only the current Session and visibly announce that a
   fresh Session will continue the Loop.
4. Task failures stop scheduling and use the red terminal treatment.

Designer handoff states are also explicit: `PENDING_HANDOFF`, `RUNNING`,
`COMPLETED`, and `SESSION_ERROR`. The console polls an active read-only Session
and persists/displays an ASSISTANT message only after real OpenCode output is
available; it never renders a queue notice as model output. A transient browser
GET failure keeps the Session in a bounded-backoff reconnect loop, and the
current Designer Session/draft pair is restored after a same-tab page refresh.
The Session API returns its bound draft on every poll. A validated Designer
reply therefore replaces the right-hand LoopSpec editor automatically while
preserving any locally dirty editor text for explicit human resolution. Model
responses render as sanitized Markdown; fenced `mermaid` blocks render as SVG,
and the machine-only LoopSpec payload is not shown in the conversation.

Confirming a draft is an idempotent handoff. After the server returns the
persisted Task id, the client loads that Task into the live store and navigates
to its detail page even when execution-workspace preparation ended in a
terminal Task error. Projects without a usable Git HEAD fall back to direct
execution in the registered directory. A `READY` Task
exposes an explicit **Start execution** action on its detail page.

Each Task detail page also exposes a read-only **Model output / Thinking**
monitor. The operator can select any implementation or judge Session belonging
to that Task. While a Session is active, the panel polls frequently and follows
the newest provider-exposed `THINKING`, `OUTPUT`, and `TOOL` parts; terminal
Sessions remain selectable as history and refresh at a slower interval. Before
the first model text arrives, an animated thinking indicator makes the active
handoff distinguishable from an error or empty state.

Designer acceptance criteria are not advisory prose. Every validation command
shown in the Markdown proposal must also be represented in the corresponding
LoopSpec stage as a direct-argv `PROCESS` verifier; expected success markers use
`outputContains`. The Review Gate renders these machine checks separately and
rejects saving or confirming a stage whose only verifier is `GIT_DIFF`, because
that verifier proves change scope but not functional correctness.

Maven verifier input is tolerant only where the argv boundary is deterministic.
For example, `["mvn", "test -Dtest=FooTest -pl module"]` is normalized and stored
as five direct argv items without a Designer retry or shell execution. Ambiguous
input such as an unclosed quote remains a field validation error and enters the
same bounded, read-only LoopSpec correction flow as other invalid Designer
output. A rejected correction never mutates the draft or creates a Task.
