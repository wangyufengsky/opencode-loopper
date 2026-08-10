package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.DesignerSessionState;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only Designer handoff.  It deliberately has no TaskService dependency:
 * an OpenCode failure is persisted only on this DesignerSession and cannot stop
 * or mutate an execution task.
 */
@Service
public class DesignerSessionService {
    public static final String READ_ONLY = "READ_ONLY";
    private static final int MAX_MESSAGE_LENGTH = 12_000;
    private static final int MAX_LOOP_SPEC_REPAIR_ATTEMPTS = 2;
    private static final String LOOP_SPEC_REPAIR_MARKER = "SYSTEM_RECOVERY[LOOPSPEC_AUTO_REPAIR";
    private static final Pattern LOOP_SPEC_PAYLOAD = Pattern.compile(
            "<!--\\s*LOOPSPEC_JSON_START\\s*-->(.*?)<!--\\s*LOOPSPEC_JSON_END\\s*-->",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LOOP_SPEC_START = Pattern.compile(
            "<!--\\s*LOOPSPEC_JSON_START\\s*-->", Pattern.CASE_INSENSITIVE);

    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ProjectService projects;
    private final OpenCodeClient openCode;
    private final LoopperProperties defaults;
    private final LoopDraftService drafts;
    private final ObjectMapper json;
    private final DesignerEventHub events;

    public DesignerSessionService(LoopperMapper mapper, LifecycleTransitionService lifecycle,
                                  ProjectService projects, OpenCodeClient openCode,
                                  LoopperProperties defaults, LoopDraftService drafts, ObjectMapper json,
                                  DesignerEventHub events) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.projects = projects;
        this.openCode = openCode;
        this.defaults = defaults;
        this.drafts = drafts;
        this.json = json;
        this.events = events;
    }

    @Transactional
    public DesignerSessionRow create(String projectId, String initialMessage) {
        return create(projectId, null, initialMessage);
    }

    @Transactional
    public DesignerSessionRow create(String projectId, String loopDraftId, String initialMessage) {
        projects.get(projectId);
        if (loopDraftId != null) {
            LoopDraftRow draft = drafts.get(loopDraftId);
            if (!projectId.equals(draft.projectId())) {
                throw new BadRequestException("DESIGNER_DRAFT_PROJECT_MISMATCH", "Designer session and LoopSpec draft must belong to the same project");
            }
        }
        String now = now();
        DesignerSessionRow session = new DesignerSessionRow(UUID.randomUUID().toString(), projectId,
                DesignerSessionState.PENDING_HANDOFF.name(),
                READ_ONLY, now, now, 0, null, "PENDING", loopDraftId);
        lifecycle.create(subject(session), session.state(), java.util.Map.of(),
                () -> mapper.insertDesignerSession(session),
                () -> new ConflictException("DESIGNER_SESSION_CREATE_CONFLICT", "Designer session could not be created"));
        appendSystem(session, "Designer session created in read-only mode. OpenCode Designer handoff is pending; no model response has been generated.",
                DesignerSessionState.PENDING_HANDOFF.name());
        if (initialMessage != null && !initialMessage.isBlank()) appendUserMessage(session.id(), initialMessage);
        return get(session.id());
    }

    public DesignerSessionRow get(String sessionId) {
        return mapper.findDesignerSession(sessionId)
                .orElseThrow(() -> new NotFoundException("Designer session not found: " + sessionId));
    }

    public ProjectRow project(String sessionId) {
        return projects.get(get(sessionId).projectId());
    }

    public List<DesignerMessageRow> messages(String sessionId) {
        get(sessionId);
        return mapper.listDesignerMessages(sessionId);
    }

    public List<PendingQuestion> pendingQuestions(String sessionId) {
        DesignerSessionRow session = get(sessionId);
        if (!DesignerSessionState.RUNNING.name().equals(session.state())
                || session.externalSessionId() == null || session.externalSessionId().isBlank()) {
            return List.of();
        }
        OpenCodeClient.OpenCodeSession remote = remote(session);
        try {
            return openCode.pendingQuestions(remote).stream().map(this::question).toList();
        } catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safeMessage(failure.getMessage()));
        }
    }

    public void replyQuestion(String sessionId, String questionId, List<List<String>> answers) {
        DesignerSessionRow session = requireRunningRemote(sessionId);
        OpenCodeClient.OpenCodeSession remote = remote(session);
        OpenCodeClient.PendingQuestion pending = pending(remote, questionId);
        List<List<String>> normalized = validateAnswers(pending, answers);
        try {
            openCode.replyQuestion(remote, pending.id(), normalized);
        } catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safeMessage(failure.getMessage()));
        }
        questionResolved(sessionId, "问题回答已提交，OpenCode Designer 继续生成设计稿");
    }

    public void rejectQuestion(String sessionId, String questionId) {
        DesignerSessionRow session = requireRunningRemote(sessionId);
        OpenCodeClient.OpenCodeSession remote = remote(session);
        OpenCodeClient.PendingQuestion pending = pending(remote, questionId);
        try {
            openCode.rejectQuestion(remote, pending.id());
        } catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safeMessage(failure.getMessage()));
        }
        questionResolved(sessionId, "问题已拒绝，OpenCode Designer 将自行处理");
    }

    public LoopDraftRow draft(String sessionId) {
        DesignerSessionRow session = get(sessionId);
        return session.loopDraftId() == null ? null : drafts.get(session.loopDraftId());
    }

    @Transactional
    public LoopDraftRow syncLoopSpec(String sessionId, LoopSpec spec) {
        DesignerSessionRow session = get(sessionId);
        if (session.loopDraftId() == null || session.loopDraftId().isBlank()) {
            throw new ConflictException("DESIGNER_DRAFT_NOT_BOUND", "Designer session is not bound to a LoopSpec draft");
        }
        if (spec == null || !session.projectId().equals(spec.projectId())) {
            throw new BadRequestException("LOOPSPEC_PROJECT_MISMATCH", "LoopSpec projectId must match the Designer session projectId");
        }
        return drafts.update(session.loopDraftId(), spec);
    }

    /**
     * Records the human message before dispatching it.  A queue/error notice is
     * a SYSTEM record only; an ASSISTANT record is inserted exclusively by
     * {@link #pollActiveHandoffs()} after sessionOutput returns real text.
     */
    @Transactional
    public List<DesignerMessageRow> appendUserMessage(String sessionId, String content) {
        DesignerSessionRow session = get(sessionId);
        if (DesignerSessionState.RUNNING.name().equals(session.state())) {
            throw new ConflictException("DESIGNER_SESSION_BUSY",
                    "OpenCode Designer is still processing the previous message; wait for its actual assistant response before sending another prompt");
        }
        String normalized = normalizeMessage(content);
        DesignerMessageRow user = appendMessage(session.id(), "USER", normalized, "PERSISTED");
        DesignerMessageRow notice = dispatch(session, normalized);
        return List.of(user, notice);
    }

    /**
     * Polls only Designer sessions. This has no reference to TaskService or task tables.
     *
     * Do not wrap the batch in a transaction. A completed response can be rejected by
     * {@link LoopDraftService#update(String, LoopSpec)}; that service correctly rolls
     * back its own update, while this method must still persist the session-scoped error.
     * Joining both operations to one transaction would leave it rollback-only and make
     * the scheduler throw {@code UnexpectedRollbackException} on every poll.
     */
    public void pollActiveHandoffs() {
        for (DesignerSessionRow session : mapper.activeDesignerHandoffs()) {
            try {
                poll(session);
            } catch (RuntimeException ignoredConcurrentTransition) {
                // An operator message or retry can legitimately update this independent session first.
            }
        }
    }

    private DesignerMessageRow dispatch(DesignerSessionRow session, String userMessage) {
        if (!openCode.healthy()) {
            DesignerSessionRow pending = transition(session, DesignerSessionState.PENDING_HANDOFF, null, "UNAVAILABLE");
            DesignerMessageRow notice = appendSystem(pending,
                    "SYSTEM_ERROR[SESSION]: OpenCode Designer runtime is unavailable. Message remains pending handoff; no assistant reply was fabricated.",
                    DesignerSessionState.PENDING_HANDOFF.name());
            events.publish(pending.id(), "ERROR", pending.state(), pending.externalSessionState(), false, "", notice.content());
            return notice;
        }
        ProjectRow project = projects.get(session.projectId());
        DesignerSessionRow current = session;
        try {
            OpenCodeClient.OpenCodeSession remote = reusable(session)
                    ? new OpenCodeClient.OpenCodeSession(session.externalSessionId(), Path.of(project.rootPath()))
                    : openCode.createReadOnlySession(Path.of(project.rootPath()), "OpenCode Loopper Designer (READ_ONLY)", configuredModel());
            current = transition(session, DesignerSessionState.RUNNING, remote.id(), "CREATED");
            events.publish(current.id(), "STATUS", current.state(), current.externalSessionState(), true, "",
                    "OpenCode 已连接，只读 Designer Session 已创建");
            openCode.promptAsync(remote, designerPrompt(current, project, userMessage));
            current = transition(current, DesignerSessionState.RUNNING, remote.id(), "RUNNING");
            DesignerMessageRow notice = appendSystem(current,
                    "Message was handed to the read-only OpenCode Designer. Waiting to persist the actual assistant response.",
                    DesignerSessionState.PENDING_HANDOFF.name());
            events.publish(current.id(), "STATUS", current.state(), current.externalSessionState(), true, "",
                    "提示词已送达模型，等待首段回复");
            return notice;
        } catch (SessionFailure failure) {
            return sessionError(get(session.id()), failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return sessionError(get(session.id()), "OPENCODE_DESIGNER_HANDOFF_FAILED", failure.getMessage());
        }
    }

    private void poll(DesignerSessionRow session) {
        ProjectRow project = projects.get(session.projectId());
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(session.externalSessionId(), Path.of(project.rootPath()));
        try {
            List<OpenCodeClient.PendingQuestion> pending = openCode.pendingQuestions(remote);
            if (!pending.isEmpty()) {
                DesignerSessionRow current = !same(session.externalSessionState(), "WAITING_INPUT")
                        ? transition(session, DesignerSessionState.RUNNING, session.externalSessionId(), "WAITING_INPUT") : session;
                String liveOutput = visibleDesignerOutput(openCode.sessionLiveOutput(remote));
                events.publish(current.id(), liveOutput.isBlank() ? "STATUS" : "PARTIAL", current.state(), "WAITING_INPUT", true,
                        liveOutput, "OpenCode Designer 正在等待你的回答");
                return;
            }
            if (designerTimedOut(session)) {
                try { openCode.abort(remote); } catch (RuntimeException ignoredAbortFailure) { }
                sessionError(session, "OPENCODE_DESIGNER_TIMEOUT",
                        "OpenCode Designer exceeded the configured timeout of " + defaults.getDesignerTimeout());
                return;
            }
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.failed()) {
                sessionError(session, "OPENCODE_DESIGNER_" + safeState(status.state()), statusDetail(status, "OpenCode Designer session ended in " + status.state()));
            } else if (status.completed()) {
                String output = openCode.sessionOutput(remote);
                if (output == null || output.isBlank()) {
                    sessionError(session, "OPENCODE_OUTPUT_MISSING", "OpenCode Designer completed without assistant text");
                    return;
                }
                ParsedDesignerOutput parsed;
                try {
                    parsed = parseDesignerOutput(output);
                    syncLoopSpec(session.id(), parsed.spec());
                } catch (BadRequestException | ConflictException failure) {
                    String visibleOutput = visibleDesignerOutput(output);
                    if (!visibleOutput.isBlank()) {
                        appendMessage(session.id(), "ASSISTANT", visibleOutput, "REJECTED");
                    }
                    DesignerSessionRow current = get(session.id());
                    if (!requestLoopSpecRepair(current, remote, failure.getMessage())) {
                        sessionError(current, "LOOPSPEC_SYNC_FAILED", failure.getMessage());
                    }
                    return;
                }
                appendMessage(session.id(), "ASSISTANT", parsed.markdown(), "PERSISTED");
                DesignerSessionRow completed = transition(session, DesignerSessionState.COMPLETED,
                        session.externalSessionId(), "COMPLETED");
                events.publish(completed.id(), "COMPLETED", completed.state(), completed.externalSessionState(), true,
                        parsed.markdown(), "Designer 回复完成，LoopSpec 已同步");
            } else {
                DesignerSessionRow current = !same(session.externalSessionState(), status.state())
                        ? transition(session, DesignerSessionState.RUNNING, session.externalSessionId(), status.state()) : session;
                String liveOutput = visibleDesignerOutput(openCode.sessionLiveOutput(remote));
                events.publish(current.id(), liveOutput.isBlank() ? "STATUS" : "PARTIAL", current.state(), status.state(), true,
                        liveOutput, liveOutput.isBlank() ? "OpenCode 已连接，等待首段模型回复" : "正在接收模型回复");
            }
        } catch (SessionFailure failure) {
            sessionError(session, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            sessionError(session, "OPENCODE_DESIGNER_STATUS_FAILED", failure.getMessage());
        }
    }

    private boolean requestLoopSpecRepair(DesignerSessionRow session, OpenCodeClient.OpenCodeSession remote,
                                          String validationError) {
        if (session.loopDraftId() == null || session.loopDraftId().isBlank()) return false;
        int attempt = loopSpecRepairAttempts(session.id()) + 1;
        if (attempt > MAX_LOOP_SPEC_REPAIR_ATTEMPTS) return false;
        DesignerSessionRow repairing = transition(session, DesignerSessionState.RUNNING, session.externalSessionId(),
                "REPAIRING_LOOPSPEC_" + attempt);
        DesignerMessageRow notice = appendSystem(repairing,
                LOOP_SPEC_REPAIR_MARKER + " " + attempt + "/" + MAX_LOOP_SPEC_REPAIR_ATTEMPTS + "]: 上一次回复未通过 LoopSpec 校验，"
                        + "系统正把具体错误回送给只读 Designer 自动纠正；不会生成代码、修改项目或创建 Task。",
                "AUTO_REPAIR");
        events.publish(repairing.id(), "STATUS", repairing.state(), repairing.externalSessionState(), true, "", notice.content());
        try {
            openCode.promptAsync(remote, loopSpecRepairPrompt(repairing, validationError));
        } catch (SessionFailure failure) {
            sessionError(get(session.id()), failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            sessionError(get(session.id()), "OPENCODE_LOOPSPEC_REPAIR_FAILED", failure.getMessage());
        }
        return true;
    }

    private int loopSpecRepairAttempts(String sessionId) {
        return (int) mapper.listDesignerMessages(sessionId).stream()
                .filter(message -> "SYSTEM".equals(message.role()))
                .filter(message -> message.content().startsWith(LOOP_SPEC_REPAIR_MARKER))
                .count();
    }

    private String loopSpecRepairPrompt(DesignerSessionRow session, String validationError) {
        LoopDraftRow draft = drafts.get(session.loopDraftId());
        return """
                Your previous response was rejected by the Loopper LoopSpec validator.
                This is a protocol-repair turn only. Do not inspect more files, call repository tools, generate implementation code, edit files, run commands, create tasks, or discuss starting implementation.

                Correct the complete Markdown design and the complete machine LoopSpec using only the project evidence already collected in this read-only session.
                Address every validation error below. Every stage must contain at least one functional verifier in addition to any GIT_DIFF scope verifier. Normally use a PROCESS verifier with a direct `command` argv array and a command already supported by the inspected repository. Never assume Maven Wrapper is present: use `./mvnw` only when an executable `mvnw` is checked in and will be present inside an isolated Git worktree; otherwise choose the repository's real supported command, such as `mvn`, `./gradlew`, or an npm script. Do not rename `command` to `argv`, `args`, or `cmd`.
                Re-evaluate stage boundaries while repairing: prefer 2 to 6 dependency-ordered stages for non-trivial work, keep a single stage only for an atomic change, and split by independently deliverable behavior rather than by generic activities such as analysis, coding, and testing.
                Every stage must own functional acceptance that can run immediately after that stage and prove its stated deliverables. Do not defer all functional validation to the final stage. A final full-regression verifier may supplement, but never replace, the focused verifier of each earlier stage.
                The visible Markdown acceptance criteria and machine verifiers must describe the same checks.

                Validation errors:
                %s

                Current bound LoopSpec JSON (unchanged because the rejected response was never synchronized):
                %s

                Return the complete corrected Markdown design followed by exactly one complete JSON object between these markers:
                <!-- LOOPSPEC_JSON_START -->
                ```json
                { "schemaVersion": "v1", "projectId": "%s", "goal": "...", "context": "...", "stages": [], "limits": {} }
                ```
                <!-- LOOPSPEC_JSON_END -->
                """.formatted(safeMessage(validationError), draft.specJson(), session.projectId());
    }

    private DesignerMessageRow sessionError(DesignerSessionRow session, String code, String detail) {
        DesignerSessionRow failed = transition(session, DesignerSessionState.SESSION_ERROR,
                session.externalSessionId(), "FAILED");
        DesignerMessageRow message = appendSystem(failed, "SYSTEM_ERROR[SESSION:" + code + "]: " + safeMessage(detail)
                + ". This affected only the read-only Designer handoff; no task was changed.",
                DesignerSessionState.SESSION_ERROR.name());
        events.publish(failed.id(), "ERROR", failed.state(), failed.externalSessionState(), false, "", message.content());
        return message;
    }

    private boolean reusable(DesignerSessionRow session) {
        return session.externalSessionId() != null && !session.externalSessionId().isBlank()
                && !DesignerSessionState.SESSION_ERROR.name().equals(session.state());
    }

    /** A parseable provider/model is passed through; absent or malformed configuration lets OpenCode choose its runtime default. */
    private OpenCodeClient.OpenCodeModel configuredModel() {
        String configured = defaults.getOpenCode().getModel();
        if (configured == null) return null;
        String value = configured.trim();
        int separator = value.indexOf('/');
        if (separator <= 0 || separator >= value.length() - 1) return null;
        String provider = value.substring(0, separator).trim();
        String model = value.substring(separator + 1).trim();
        return provider.isEmpty() || model.isEmpty() ? null : new OpenCodeClient.OpenCodeModel(provider, model, null);
    }

    private String designerPrompt(DesignerSessionRow session, ProjectRow project, String message) {
        LoopDraftRow draft = session.loopDraftId() == null ? null : drafts.get(session.loopDraftId());
        String currentSpec = draft == null ? "{}" : draft.specJson();
        return """
                You are the OpenCode Loopper Designer. Work in read-only advisory mode only.
                You may inspect the registered project but must not edit files, run shell commands, create tasks, or claim an action completed without evidence.
                Registered project root: %s
                Designer session id: %s
                Bound LoopSpec draft id: %s

                Respond to the user's design request with an implementation-ready LoopSpec proposal or review. A human must still confirm any draft before task creation.

                Output contract:
                - Write the complete response as a well-structured Markdown document. Do not wrap the whole response in a code fence and do not emit raw HTML.
                - Use meaningful headings, short paragraphs, lists, tables, and fenced code blocks where they make the proposal easier to review.
                - Include concrete implementation boundaries, affected files or modules, validation commands, acceptance criteria, risks, and unresolved decisions when they are relevant.
                - Prefer 2 to 6 dependency-ordered stages for non-trivial work. Keep a single stage only when the requested change is genuinely atomic, and state the single-stage reason in the Markdown. Split by independently deliverable, independently verifiable behavior; do not split mechanically into analysis, coding, and testing phases.
                - Include a Markdown stage plan whose rows map one-to-one and in the same order to machine `stages`. For every stage show its observable result, affected scope, deliverables, dependency on earlier stages, and acceptance command or check.
                - Every stage must leave the project in a coherent, safe-to-stop state and must own functional acceptance that can run immediately after that stage. Use the narrowest reliable stage-specific verifier first. Do not defer all tests or functional validation to the final stage; a final full-regression verifier may supplement but never replace each earlier stage's focused acceptance.
                - The Markdown acceptance criteria and the machine LoopSpec MUST describe the same checks. Every validation command shown in Markdown must appear in the corresponding stage.verifiers as a PROCESS verifier whose JSON field is exactly `command`. Never leave a stage with GIT_DIFF as its only verifier: GIT_DIFF checks scope, not functional correctness.
                - PROCESS uses the schema { "type": "PROCESS", "command": ["program", "arg"] }. The `command` value is a direct argv array, never a shell snippet. Select `program` from commands actually supported by the inspected repository and its project conventions. Never assume Maven Wrapper is present or require a project to add it: use `./mvnw` only when an executable `mvnw` is checked in and will be present inside an isolated Git worktree; otherwise use another evidenced command such as `mvn`, `./gradlew`, or an npm script. Never rename this JSON field to `argv`, `args`, or `cmd`. When success requires stdout text such as PASS, set outputContains to that exact text. A PROCESS verifier must exit non-zero when its self-check fails.
                - Do not add FILE_EXISTS verifiers: generated artifacts and build output directories are not fixed-path hard gates. Prove required output with a PROCESS self-check that exits non-zero on failure and optionally requires an exact PASS marker. FILE_NOT_EXISTS remains available only for explicit safety invariants. Do not add GIT_DIFF merely because stage.allowedPaths or stage.forbiddenPaths is populated: those stage fields are advisory Agent guidance. Add a GIT_DIFF verifier only when you explicitly propose a path/delete acceptance check in the visible Markdown, and choose limits.verifierTimeoutSeconds large enough for the slowest validation command.
                - Whenever you describe a workflow, state transition, component interaction, dependency flow, or multi-step execution path, include a fenced `mermaid` diagram. Never draw flows with ASCII art.
                - Explanatory prose, conclusions, reviews, risks, acceptance criteria, and unresolved decisions default to Simplified Chinese unless the user explicitly requests another language.
                - Keep identifiers, commands, file paths, code, JSON field names, protocol enum values, and exact literal markers in their original form.
                - Your response MUST end with the complete updated LoopSpec JSON between the exact markers shown below. The JSON is machine-consumed and will be removed from the visible Markdown after validation.
                - Do not repeat or display the raw LoopSpec JSON anywhere else in the visible Markdown document; summarize its important decisions in prose, tables, and Mermaid diagrams instead.
                - Keep schemaVersion and projectId unchanged. Return every field, including stages, verifiers, limits, model, sessionPolicy, and nextAttemptPromptTemplate. Use numeric *Seconds fields exactly as in the current JSON.

                Current bound LoopSpec JSON:
                %s

                Required machine payload format:
                <!-- LOOPSPEC_JSON_START -->
                ```json
                { "schemaVersion": "v1", "projectId": "%s", "goal": "...", "context": "...", "stages": [], "limits": {} }
                ```
                <!-- LOOPSPEC_JSON_END -->

                User message:
                %s
                """.formatted(project.rootPath(), session.id(), session.loopDraftId(), currentSpec, project.id(), message);
    }

    private ParsedDesignerOutput parseDesignerOutput(String output) {
        Matcher matcher = LOOP_SPEC_PAYLOAD.matcher(output);
        if (!matcher.find()) {
            throw new BadRequestException("LOOPSPEC_OUTPUT_MISSING", "Designer response did not contain the required LoopSpec JSON payload");
        }
        String payload = matcher.group(1);
        int start = payload.indexOf('{');
        int end = payload.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new BadRequestException("LOOPSPEC_OUTPUT_INVALID", "Designer LoopSpec payload is not a JSON object");
        }
        try {
            LoopSpec spec = json.readValue(payload.substring(start, end + 1), LoopSpec.class);
            String markdown = (output.substring(0, matcher.start()) + output.substring(matcher.end())).trim();
            if (markdown.isBlank()) markdown = "## LoopSpec 已生成\n\n结构化设计已同步到右侧 Review Gate。";
            return new ParsedDesignerOutput(markdown, spec);
        } catch (JacksonException failure) {
            throw new BadRequestException("LOOPSPEC_OUTPUT_INVALID", "Designer LoopSpec JSON cannot be read: " + failure.getMessage());
        }
    }

    private String visibleDesignerOutput(String output) {
        if (output == null || output.isBlank()) return "";
        Matcher matcher = LOOP_SPEC_PAYLOAD.matcher(output);
        if (matcher.find()) return (output.substring(0, matcher.start()) + output.substring(matcher.end())).trim();
        Matcher start = LOOP_SPEC_START.matcher(output);
        return start.find() ? output.substring(0, start.start()).trim() : output;
    }

    private boolean designerTimedOut(DesignerSessionRow session) {
        Duration timeout = defaults.getDesignerTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) return false;
        String latestUserMessageAt = mapper.listDesignerMessages(session.id()).stream()
                .filter(message -> "USER".equals(message.role()))
                .reduce((first, second) -> second)
                .map(DesignerMessageRow::createdAt)
                .orElse(session.updatedAt());
        try {
            Instant messageAt = Instant.parse(latestUserMessageAt);
            Instant stateChangedAt = Instant.parse(session.updatedAt());
            return Duration.between(messageAt.isAfter(stateChangedAt) ? messageAt : stateChangedAt, Instant.now()).compareTo(timeout) > 0;
        }
        catch (RuntimeException invalidTimestamp) { return false; }
    }

    private DesignerSessionRow requireRunningRemote(String sessionId) {
        DesignerSessionRow session = get(sessionId);
        if (!DesignerSessionState.RUNNING.name().equals(session.state())
                || session.externalSessionId() == null || session.externalSessionId().isBlank()) {
            throw new ConflictException("DESIGNER_QUESTION_UNAVAILABLE", "Designer session has no running OpenCode question to answer");
        }
        return session;
    }

    private OpenCodeClient.OpenCodeSession remote(DesignerSessionRow session) {
        ProjectRow project = projects.get(session.projectId());
        return new OpenCodeClient.OpenCodeSession(session.externalSessionId(), Path.of(project.rootPath()));
    }

    private OpenCodeClient.PendingQuestion pending(OpenCodeClient.OpenCodeSession remote, String questionId) {
        if (questionId == null || questionId.isBlank()) {
            throw new BadRequestException("QUESTION_ID_REQUIRED", "Question id is required");
        }
        try {
            return openCode.pendingQuestions(remote).stream()
                    .filter(question -> questionId.equals(question.id()))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("Pending question not found for this Designer Session: " + questionId));
        } catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safeMessage(failure.getMessage()));
        }
    }

    private PendingQuestion question(OpenCodeClient.PendingQuestion pending) {
        return new PendingQuestion(pending.id(), pending.questions().stream().map(prompt -> new QuestionPrompt(
                prompt.question(), prompt.header(), prompt.options().stream()
                .map(option -> new QuestionOption(option.label(), option.description())).toList(),
                prompt.multiple(), prompt.custom())).toList());
    }

    private List<List<String>> validateAnswers(OpenCodeClient.PendingQuestion pending, List<List<String>> answers) {
        if (answers == null || answers.size() != pending.questions().size()) {
            throw new BadRequestException("QUESTION_ANSWERS_INVALID", "Answers must contain one entry for every question");
        }
        List<List<String>> result = new ArrayList<>();
        for (int index = 0; index < pending.questions().size(); index++) {
            OpenCodeClient.QuestionPrompt prompt = pending.questions().get(index);
            List<String> answer = answers.get(index);
            if (answer == null) answer = List.of();
            List<String> normalized = answer.stream().filter(value -> value != null && !value.isBlank())
                    .map(String::trim).distinct().toList();
            if (normalized.isEmpty()) {
                throw new BadRequestException("QUESTION_ANSWER_REQUIRED", "Every question requires an answer");
            }
            if (!prompt.multiple() && normalized.size() > 1) {
                throw new BadRequestException("QUESTION_ANSWER_MULTIPLE_FORBIDDEN", "This question accepts only one answer");
            }
            if (!prompt.custom()) {
                List<String> labels = prompt.options().stream().map(OpenCodeClient.QuestionOption::label).toList();
                if (!labels.containsAll(normalized)) {
                    throw new BadRequestException("QUESTION_CUSTOM_ANSWER_FORBIDDEN", "This question only accepts listed options");
                }
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private void questionResolved(String sessionId, String detail) {
        DesignerSessionRow current = get(sessionId);
        try {
            current = transition(current, DesignerSessionState.RUNNING, current.externalSessionId(), "RUNNING");
        } catch (ConflictException concurrentPoll) {
            current = get(sessionId);
        }
        events.publish(current.id(), "STATUS", current.state(), current.externalSessionState(), true, "", detail);
    }

    public record PendingQuestion(String id, List<QuestionPrompt> questions) { }
    public record QuestionPrompt(String question, String header, List<QuestionOption> options, boolean multiple, boolean custom) { }
    public record QuestionOption(String label, String description) { }

    private record ParsedDesignerOutput(String markdown, LoopSpec spec) { }

    private DesignerMessageRow appendSystem(DesignerSessionRow session, String content, String deliveryState) {
        return appendMessage(session.id(), "SYSTEM", content, deliveryState);
    }

    private DesignerMessageRow appendMessage(String sessionId, String role, String content, String deliveryState) {
        DesignerMessageRow message = new DesignerMessageRow(UUID.randomUUID().toString(), sessionId,
                mapper.nextDesignerMessageOrdinal(sessionId), role, content, deliveryState, now());
        mapper.insertDesignerMessage(message);
        return message;
    }

    private DesignerSessionRow transition(DesignerSessionRow session, DesignerSessionState state,
                                          String externalSessionId, String externalSessionState) {
        DesignerSessionRow updated = new DesignerSessionRow(session.id(), session.projectId(), state.name(), session.accessMode(),
                session.createdAt(), now(), session.version(), externalSessionId, externalSessionState, session.loopDraftId());
        if (session.state().equals(updated.state())) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateDesignerSessionProjection(updated),
                    () -> new ConflictException("DESIGNER_SESSION_VERSION_CONFLICT", "Designer session was updated concurrently"));
        } else {
            lifecycle.transition(subject(updated), session.state(), updated.state(), null, java.util.Map.of(),
                    () -> mapper.updateDesignerSession(updated),
                    () -> new ConflictException("DESIGNER_SESSION_VERSION_CONFLICT", "Designer session was updated concurrently"));
        }
        return get(session.id());
    }

    private LifecycleTransitionService.Subject subject(DesignerSessionRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.DESIGNER_SESSION, row.id(),
                LifecycleScopeType.PROJECT, row.projectId());
    }

    private String normalizeMessage(String content) {
        if (content == null || content.isBlank()) {
            throw new BadRequestException("DESIGNER_MESSAGE_REQUIRED", "Designer message content is required");
        }
        String normalized = content.trim();
        if (normalized.length() > MAX_MESSAGE_LENGTH) {
            throw new BadRequestException("DESIGNER_MESSAGE_TOO_LONG", "Designer message must be at most " + MAX_MESSAGE_LENGTH + " characters");
        }
        return normalized;
    }

    private static boolean same(String left, String right) { return left == null ? right == null : left.equalsIgnoreCase(right); }
    private static String statusDetail(OpenCodeClient.SessionStatus status, String fallback) {
        return status.detail() == null || status.detail().isBlank() ? fallback : status.detail();
    }
    private static String safeState(String state) { return state == null || state.isBlank() ? "UNKNOWN" : state.replaceAll("[^A-Za-z0-9_-]", "_"); }
    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "OpenCode Designer handoff failed";
        String normalized = message.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() > 500 ? normalized.substring(0, 500) : normalized;
    }
    private String now() { return Instant.now().toString(); }
}
