# OpenCode Loopper

OpenCode Loopper is a local control plane for turning a natural-language coding
request into a versioned, auditable execution loop. Spring Boot owns state,
verification and recovery; local OpenCode sessions use isolated Git worktrees
when HEAD is available and otherwise edit the registered directory directly;
Vue provides a dark-first operator console.

## What v1 provides

- Register local project directories and inspect their baseline safely.
- Plan through a real read-only OpenCode Designer Session, then draft and
  validate a staged LoopSpec before human confirmation.
- Run Git projects in `loopper/<taskId>` and a dedicated worktree; fall back to
  the registered directory when the project has no usable Git HEAD.
- Generate or update a registered project's root `AGENTS.md` from the Projects
  page. OpenCode inspects the project in a read-only Session, the UI previews
  the complete result, and Loopper writes only after explicit confirmation.
  Existing human content is preserved outside the managed
  `<!-- LOOPPER:START -->` block and a source hash prevents stale overwrites.
- Persist Tasks, Stages, Attempts, Sessions, layered errors, Judge runs and
  immutable evidence artifacts in SQLite.
- Pause, resume and cancel Tasks.
- Continue the Loop with a fresh OpenCode Session after a Session-level error.
- Recover a restart-disconnected Session by aborting the old remote Session
  best-effort and automatically creating a fresh Attempt/Session within budget.
- Stop the Task only for Task-level failures or exhausted budgets.
- Stream the persisted timeline to the browser over SSE.
- Verify outcomes with direct-process, file and Git-diff evidence, then require
  independent read-only Requirement and Risk Judge approval.
- Expose six bearer-protected Loopper tools over Spring AI Streamable HTTP MCP.

## Requirements

- JDK 21
- Git
- OpenCode CLI 1.18.x or a compatible local OpenCode server
- Node.js/npm only for frontend hot development; the Maven production build
  prepares the pinned frontend toolchain.

On macOS, select JDK 21 explicitly because a newer system JDK may be the
default:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
```

## Build and run

```bash
./mvnw clean verify
java -jar target/opencode-loopper-0.1.1-SNAPSHOT.jar
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080). Runtime data is written
to `./data` unless `LOOPPER_DATA_DIR` is set.

### Run the packaged JAR on Linux

Copy only `target/opencode-loopper-0.1.1-SNAPSHOT.jar` to the Linux machine,
then run it as a regular (non-root) user from a writable directory:

```bash
java -version # must report Java 21 or newer
mkdir -p "$PWD/data"
LOOPPER_DATA_DIR="$PWD/data" java -jar opencode-loopper-0.1.1-SNAPSHOT.jar
```

For an offline or intranet Linux host, copy the packaged JAR together with
`scripts/start-linux.sh`. The script runs the JAR directly, so it does not
invoke Maven or download Node/npm. Its default JDK directory is `/opt/jdk-21`;
edit `DEFAULT_JAVA_HOME` in the script or override it without changing the file:

```bash
chmod +x start-linux.sh
LOOPPER_JAVA_HOME=/opt/java/jdk-21 ./start-linux.sh
```

JDK selection is deterministic: `LOOPPER_JAVA_HOME` wins, otherwise the script
uses `DEFAULT_JAVA_HOME`. An inherited system `JAVA_HOME` is deliberately
ignored so a legacy JDK 8 configuration cannot override the selected runtime.

Running `sh start-linux.sh` is also accepted: the script detects a POSIX `sh`
such as `dash` and re-executes itself with Bash before using Bash-only options.

The default OpenCode endpoint is `http://127.0.0.1:4096`. Override credentials,
model, port, or endpoint through the existing environment variables. When a
Linux desktop and `xdg-open` are available, the script opens the console after
the Actuator health endpoint reports `UP`; on a headless server it prints the
URL and keeps Spring Boot in the foreground.

The JAR is architecture-independent Java bytecode and contains the Linux
x86_64/aarch64 SQLite native libraries. Git and a compatible OpenCode CLI must
be installed on the Linux host for real Task execution. For the optional
`BROWSER` verifier, install Google Chrome or Chromium; set
`LOOPPER_CHROME_EXECUTABLE=/absolute/path/to/chrome` when it is not on `PATH`
or in a standard system location. A headless Linux server can register project
paths by typing them in the UI; the native folder-picker button additionally
requires `zenity`, `kdialog`, or `yad` and a graphical session.

The Maven build downloads its own fixed Node.js `v22.14.0` and npm `10.9.2`,
runs `npm ci`, type checking, Vitest and Vite, then copies `frontend/dist` to
`target/classes/static` before the executable JAR is created. `mvn clean`
removes that toolchain and static directory first, so a clean package cannot
serve an earlier frontend bundle. The lockfile and `frontend/.npmrc` use the
official npm registry.

To inspect the packaged SPA without starting the application:

```bash
jar tf target/opencode-loopper-0.1.1-SNAPSHOT.jar | rg 'BOOT-INF/classes/static/(index.html|assets/)'
```

For hot development:

```bash
./scripts/dev.sh
```

On Windows PowerShell:

```powershell
.\scripts\dev.ps1
```

### IntelliJ IDEA

The shared `Loopper Full Stack` run configuration follows the same embedded
frontend model as `Java-OpenCode-CLI`. Before Spring Boot starts, IDEA runs the
Maven `process-resources` phase, which installs the pinned Node/npm toolchain
when necessary, builds Vue, and copies the current bundle into
`target/classes/static`. Spring Boot then serves the frontend and API together
from [http://127.0.0.1:8080](http://127.0.0.1:8080); no orphaned Vite process is
left behind when the backend stops.

Select `Loopper Full Stack` in the IDEA run widget and use Run or Debug. The
shared configuration contains the Maven before-launch task, so it does not rely
on user-specific `workspace.xml` state. Use `scripts/dev.sh` or
`scripts/dev.ps1` instead when Vite hot-module replacement is required.

## Configuration

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `LOOPPER_DATA_DIR` | `./data` | SQLite, artifacts, Task worktrees and direct-execution baselines |
| `SERVER_PORT` | `8080` | loopback HTTP port |
| `LOOPPER_OPENCODE_MODE` | `auto` | `auto` reuses a healthy loopback server or starts an owned one; `http` only connects; `fake` is test-only |
| `OPENCODE_BASE_URL` | `http://127.0.0.1:4096` | loopback OpenCode endpoint to probe/reuse |
| `OPENCODE_USERNAME` | empty | Basic Auth username |
| `OPENCODE_PASSWORD` | empty | Basic Auth password; never persisted |
| `OPENCODE_EXECUTABLE` | PATH lookup | explicit OpenCode executable for managed `auto` mode |
| `OPENCODE_MODEL` | OpenCode default | optional `provider/model` for Designer and Task Sessions |
| `LOOPPER_CHROME_EXECUTABLE` | auto-detected | explicit Google Chrome/Chromium executable used by the optional `BROWSER` verifier |
| `LOOPPER_MCP_BEARER_TOKEN` | random in-memory token | token for `/api/mcp-streamable` and compatibility `/api/mcp`; set it explicitly for an external MCP client |

## Error semantics

The error layer is a public API and persistence invariant:

| Layer | Effect |
| --- | --- |
| `FIELD` | Reject or annotate user input; no runtime transition |
| `VERIFICATION` | Finish the Attempt and prepare the next Loop iteration |
| `SESSION` | Close the failed Session and continue with a fresh Session |
| `TASK` | Abort child work and transition the Task to `FAILED` |

Session failures cannot directly set a Task to `FAILED`. They end the current
Session and Attempt and automatically continue with a fresh pair. Only the
orchestrator may promote exhausted Session/Attempt budgets to a Task failure.
At the Task-fatal boundary, every active child Session, Judge and Attempt is
closed before the Task enters `FAILED`. If remote termination cannot be
confirmed, the Task still exits but the Session remains visibly `DISCONNECTED`
instead of being mislabeled `ABORTED`.

Session recovery has one fail-closed exception: if the old mutating Session
cannot be aborted or independently observed terminal, including after an app
restart, Loopper raises
`TASK/SESSION_ABORT_UNCONFIRMED` instead of risking two Sessions writing the
same worktree. A persisted monitor then makes at most three cleanup attempts
(configurable with `loopper.abort-cleanup-attempts`); it marks the Session
`ABORTED` only after abort or an independent terminal status is confirmed, and
records `SESSION_ABORT_CLEANUP_EXHAUSTED` if the bound is reached.

## Safety boundaries

- HTTP servers bind to loopback.
- Project and execution-workspace paths are canonicalized and containment-checked.
- External paths, destructive commands and `git push` are not silently
  approved.
- Commands are executed as argument arrays, without shell interpolation.
- Stage path rules are advisory Agent guidance and do not create an implicit
  acceptance gate. Path/delete policy is enforced only by an explicit
  `GIT_DIFF` verifier proposed in the confirmed LoopSpec.
- Deterministic verifiers run on a bounded worker pool so monitor polling stays
  responsive; HTTP and process work has explicit timeouts.
- Verification is gated by a second authoritative OpenCode status read and is
  rejected while the implementation Session is still running.
- Loopper never automatically pushes, merges or deletes a completed worktree.
- Secrets are accepted through the process environment and are not written to
  SQLite, logs or artifacts.

See [architecture](docs/architecture.md), [Figma-backed design contract](docs/design-contract.md)
and the locally verified [OpenCode contract](docs/opencode-contract.md).

## Verification

```bash
./scripts/verify.sh
```

Mock and contract success are reported separately from a real OpenCode/provider
end-to-end run.
