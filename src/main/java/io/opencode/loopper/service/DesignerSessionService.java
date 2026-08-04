package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
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
    public static final String PENDING_HANDOFF = "PENDING_HANDOFF";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String SESSION_ERROR = "SESSION_ERROR";
    private static final int MAX_MESSAGE_LENGTH = 12_000;
    private static final Pattern LOOP_SPEC_PAYLOAD = Pattern.compile(
            "<!--\\s*LOOPSPEC_JSON_START\\s*-->(.*?)<!--\\s*LOOPSPEC_JSON_END\\s*-->",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final OpenCodeClient openCode;
    private final LoopperProperties defaults;
    private final LoopDraftService drafts;
    private final ObjectMapper json;

    public DesignerSessionService(LoopperMapper mapper, ProjectService projects, OpenCodeClient openCode,
                                  LoopperProperties defaults, LoopDraftService drafts, ObjectMapper json) {
        this.mapper = mapper;
        this.projects = projects;
        this.openCode = openCode;
        this.defaults = defaults;
        this.drafts = drafts;
        this.json = json;
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
        DesignerSessionRow session = new DesignerSessionRow(UUID.randomUUID().toString(), projectId, PENDING_HANDOFF,
                READ_ONLY, now, now, 0, null, "PENDING", loopDraftId);
        mapper.insertDesignerSession(session);
        appendSystem(session, "Designer session created in read-only mode. OpenCode Designer handoff is pending; no model response has been generated.",
                PENDING_HANDOFF);
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
        if (RUNNING.equals(session.state())) {
            throw new ConflictException("DESIGNER_SESSION_BUSY",
                    "OpenCode Designer is still processing the previous message; wait for its actual assistant response before sending another prompt");
        }
        String normalized = normalizeMessage(content);
        DesignerMessageRow user = appendMessage(session.id(), "USER", normalized, "PERSISTED");
        DesignerMessageRow notice = dispatch(session, normalized);
        return List.of(user, notice);
    }

    /** Polls only Designer sessions. This has no reference to TaskService or task tables. */
    @Transactional
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
            DesignerSessionRow pending = transition(session, PENDING_HANDOFF, null, "UNAVAILABLE");
            return appendSystem(pending,
                    "SYSTEM_ERROR[SESSION]: OpenCode Designer runtime is unavailable. Message remains pending handoff; no assistant reply was fabricated.",
                    PENDING_HANDOFF);
        }
        ProjectRow project = projects.get(session.projectId());
        DesignerSessionRow current = session;
        try {
            OpenCodeClient.OpenCodeSession remote = reusable(session)
                    ? new OpenCodeClient.OpenCodeSession(session.externalSessionId(), Path.of(project.rootPath()))
                    : openCode.createReadOnlySession(Path.of(project.rootPath()), "OpenCode Loopper Designer (READ_ONLY)", configuredModel());
            current = transition(session, RUNNING, remote.id(), "CREATED");
            openCode.promptAsync(remote, designerPrompt(current, project, userMessage));
            current = transition(current, RUNNING, remote.id(), "RUNNING");
            return appendSystem(current,
                    "Message was handed to the read-only OpenCode Designer. Waiting to persist the actual assistant response.",
                    PENDING_HANDOFF);
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
                    appendMessage(session.id(), "ASSISTANT", visibleDesignerOutput(output), "PERSISTED");
                    sessionError(get(session.id()), "LOOPSPEC_SYNC_FAILED", failure.getMessage());
                    return;
                }
                appendMessage(session.id(), "ASSISTANT", parsed.markdown(), "PERSISTED");
                transition(session, COMPLETED, session.externalSessionId(), "COMPLETED");
            } else if (!same(session.externalSessionState(), status.state())) {
                transition(session, RUNNING, session.externalSessionId(), status.state());
            }
        } catch (SessionFailure failure) {
            sessionError(session, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            sessionError(session, "OPENCODE_DESIGNER_STATUS_FAILED", failure.getMessage());
        }
    }

    private DesignerMessageRow sessionError(DesignerSessionRow session, String code, String detail) {
        transition(session, SESSION_ERROR, session.externalSessionId(), "FAILED");
        return appendSystem(get(session.id()), "SYSTEM_ERROR[SESSION:" + code + "]: " + safeMessage(detail)
                + ". This affected only the read-only Designer handoff; no task was changed.", SESSION_ERROR);
    }

    private boolean reusable(DesignerSessionRow session) {
        return session.externalSessionId() != null && !session.externalSessionId().isBlank() && !SESSION_ERROR.equals(session.state());
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
                - Whenever you describe a workflow, state transition, component interaction, dependency flow, or multi-step execution path, include a fenced `mermaid` diagram. Never draw flows with ASCII art.
                - Keep identifiers, commands, file paths, and code in their original form. Use the same natural language as the user for explanatory prose.
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
        if (output == null || output.isBlank()) return "Designer returned no readable Markdown document.";
        Matcher matcher = LOOP_SPEC_PAYLOAD.matcher(output);
        return matcher.find() ? (output.substring(0, matcher.start()) + output.substring(matcher.end())).trim() : output;
    }

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

    private DesignerSessionRow transition(DesignerSessionRow session, String state, String externalSessionId, String externalSessionState) {
        DesignerSessionRow updated = new DesignerSessionRow(session.id(), session.projectId(), state, session.accessMode(),
                session.createdAt(), now(), session.version(), externalSessionId, externalSessionState, session.loopDraftId());
        if (mapper.updateDesignerSession(updated) != 1) {
            throw new ConflictException("DESIGNER_SESSION_VERSION_CONFLICT", "Designer session was updated concurrently");
        }
        return get(session.id());
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
