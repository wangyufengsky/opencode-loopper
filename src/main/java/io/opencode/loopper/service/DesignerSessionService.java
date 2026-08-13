package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.DesignWorkflowPhase;
import io.opencode.loopper.domain.DesignerActor;
import io.opencode.loopper.domain.DesignerSessionState;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.LoopSpecCompilationState;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Coordinates three strictly separated read-only roles. Designer produces only
 * Markdown, Compiler produces only a marked machine envelope, and the server
 * validator is the sole authority allowed to synchronize the bound draft.
 */
@Service
public class DesignerSessionService {
    public static final String READ_ONLY = "READ_ONLY";
    private static final int MAX_MESSAGE_LENGTH = 12_000;
    private static final int MAX_FROZEN_DESIGN_LENGTH = 48_000;
    private static final int MAX_COMPILER_REPAIRS = 2;
    private static final int MAX_AUTOMATIC_REDESIGNS = 1;
    private static final Pattern COMPILATION_PAYLOAD = Pattern.compile(
            "<!--\\s*LOOPSPEC_COMPILATION_JSON_START\\s*-->(.*?)<!--\\s*LOOPSPEC_COMPILATION_JSON_END\\s*-->",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** Compatibility sanitization only: a Designer payload is never consumed as LoopSpec. */
    private static final Pattern LEGACY_DESIGNER_PAYLOAD = Pattern.compile(
            "<!--\\s*LOOPSPEC_JSON_START\\s*-->.*?<!--\\s*LOOPSPEC_JSON_END\\s*-->",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Set<DesignGapCode> ALLOWED_DESIGN_GAPS = EnumSet.allOf(DesignGapCode.class);

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

    public DesignerSessionRow create(String projectId, String initialMessage) {
        return create(projectId, null, initialMessage);
    }

    public DesignerSessionRow create(String projectId, String loopDraftId, String initialMessage) {
        projects.get(projectId);
        if (loopDraftId != null) {
            LoopDraftRow draft = drafts.get(loopDraftId);
            if (!projectId.equals(draft.projectId())) {
                throw new BadRequestException("DESIGNER_DRAFT_PROJECT_MISMATCH",
                        "Designer session and LoopSpec draft must belong to the same project");
            }
        }
        String now = now();
        DesignerSessionRow session = new DesignerSessionRow(UUID.randomUUID().toString(), projectId,
                DesignerSessionState.PENDING_HANDOFF.name(), READ_ONLY, now, now, 0,
                null, "PENDING", loopDraftId, DesignWorkflowPhase.DESIGNING.name(), 0, 0);
        lifecycle.create(designerSubject(session), session.state(), java.util.Map.of(),
                () -> mapper.insertDesignerSession(session),
                () -> new ConflictException("DESIGNER_SESSION_CREATE_CONFLICT",
                        "Designer session could not be created"));
        appendMessage(session.id(), DesignerActor.SYSTEM,
                "设计会话已创建。设计师只生成 Markdown 设计稿，规范编译器将在独立只读会话中生成 LoopSpec。",
                DesignerSessionState.PENDING_HANDOFF.name());
        if (initialMessage != null && !initialMessage.isBlank()) appendUserMessage(session.id(), initialMessage);
        return get(session.id());
    }

    public DesignerSessionRow get(String sessionId) {
        return mapper.findDesignerSession(sessionId)
                .orElseThrow(() -> new NotFoundException("Designer session not found: " + sessionId));
    }

    public ProjectRow project(String sessionId) { return projects.get(get(sessionId).projectId()); }

    public List<DesignerMessageRow> messages(String sessionId) {
        get(sessionId);
        return mapper.listDesignerMessages(sessionId);
    }

    public CompilerStatus compilerStatus(String sessionId) {
        get(sessionId);
        return mapper.findLatestLoopSpecCompilation(sessionId)
                .map(row -> new CompilerStatus(row.id(), row.state(), row.externalSessionId(),
                        row.externalSessionState(), row.repairCount(), row.designRevision(),
                        row.lastErrorCode(), row.lastErrorDetail()))
                .orElse(null);
    }

    public String activeActor(DesignerSessionRow session) {
        return switch (DesignWorkflowPhase.valueOf(session.workflowPhase())) {
            case DESIGNING, REDESIGNING -> DesignerActor.DESIGNER.name();
            case COMPILING -> DesignerActor.COMPILER.name();
            case VALIDATING -> DesignerActor.VALIDATOR.name();
            case COMPLETED, FAILED -> DesignerActor.SYSTEM.name();
        };
    }

    public List<PendingQuestion> pendingQuestions(String sessionId) {
        DesignerSessionRow session = get(sessionId);
        if (!DesignerSessionState.RUNNING.name().equals(session.state())
                || !Set.of(DesignWorkflowPhase.DESIGNING.name(), DesignWorkflowPhase.REDESIGNING.name())
                .contains(session.workflowPhase())
                || blank(session.externalSessionId())) return List.of();
        try {
            return openCode.pendingQuestions(designerRemote(session)).stream().map(this::question).toList();
        } catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safeMessage(failure.getMessage()));
        }
    }

    public void replyQuestion(String sessionId, String questionId, List<List<String>> answers) {
        DesignerSessionRow session = requireRunningDesigner(sessionId);
        OpenCodeClient.OpenCodeSession remote = designerRemote(session);
        OpenCodeClient.PendingQuestion pending = pending(remote, questionId);
        try {
            openCode.replyQuestion(remote, pending.id(), validateAnswers(pending, answers));
        } catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safeMessage(failure.getMessage()));
        }
        updateDesignerProjection(get(sessionId), DesignerSessionState.RUNNING,
                DesignWorkflowPhase.valueOf(get(sessionId).workflowPhase()), remote.id(), "RUNNING",
                get(sessionId).designRevision(), get(sessionId).redesignCount());
    }

    public void rejectQuestion(String sessionId, String questionId) {
        DesignerSessionRow session = requireRunningDesigner(sessionId);
        OpenCodeClient.OpenCodeSession remote = designerRemote(session);
        OpenCodeClient.PendingQuestion pending = pending(remote, questionId);
        try { openCode.rejectQuestion(remote, pending.id()); }
        catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safeMessage(failure.getMessage()));
        }
        updateDesignerProjection(get(sessionId), DesignerSessionState.RUNNING,
                DesignWorkflowPhase.valueOf(get(sessionId).workflowPhase()), remote.id(), "RUNNING",
                get(sessionId).designRevision(), get(sessionId).redesignCount());
    }

    public LoopDraftRow draft(String sessionId) {
        DesignerSessionRow session = get(sessionId);
        return blank(session.loopDraftId()) ? null : drafts.get(session.loopDraftId());
    }

    /** MCP/manual compatibility boundary; the same deterministic contract still applies. */
    @Transactional
    public LoopDraftRow syncLoopSpec(String sessionId, LoopSpec spec) {
        DesignerSessionRow session = get(sessionId);
        requireBoundDraft(session);
        requireProject(session, spec);
        return drafts.update(session.loopDraftId(), spec);
    }

    public List<DesignerMessageRow> appendUserMessage(String sessionId, String content) {
        DesignerSessionRow session = get(sessionId);
        if (DesignerSessionState.RUNNING.name().equals(session.state())) {
            throw new ConflictException("DESIGNER_SESSION_BUSY",
                    "The Designer, Compiler, or Validator is still processing the previous request");
        }
        DesignerMessageRow user = appendMessage(session.id(), DesignerActor.USER,
                normalizeMessage(content), "PERSISTED");
        DesignerMessageRow notice = dispatchDesigner(session, user.content(), DesignWorkflowPhase.DESIGNING,
                session.redesignCount());
        return List.of(user, notice);
    }

    /** Explicit recovery: compile the latest frozen design in a brand-new read-only Session. */
    public void retryCompilation(String sessionId) {
        DesignerSessionRow session = get(sessionId);
        if (DesignerSessionState.RUNNING.name().equals(session.state())) {
            throw new ConflictException("DESIGN_WORKFLOW_BUSY", "The design workflow is still running");
        }
        DesignerMessageRow source = latestDesign(session.id());
        DesignerSessionRow running = updateDesignerProjection(session, DesignerSessionState.RUNNING,
                DesignWorkflowPhase.COMPILING, session.externalSessionId(), session.externalSessionState(),
                session.designRevision(), session.redesignCount());
        startCompilation(running, source);
    }

    /** Explicit recovery: ask Designer for a complete replacement, not a patch. */
    public void requestRedesign(String sessionId) {
        DesignerSessionRow session = get(sessionId);
        if (DesignerSessionState.RUNNING.name().equals(session.state())) {
            throw new ConflictException("DESIGN_WORKFLOW_BUSY", "The design workflow is still running");
        }
        dispatchDesigner(session, redesignPrompt("人工要求重新设计当前完整方案"),
                DesignWorkflowPhase.REDESIGNING, session.redesignCount() + 1);
    }

    /** External model calls are deliberately outside a surrounding database transaction. */
    public void pollActiveHandoffs() {
        for (DesignerSessionRow session : mapper.activeDesignerHandoffs()) {
            try { pollDesigner(session); }
            catch (RuntimeException ignoredConcurrentTransition) { }
        }
        for (LoopSpecCompilationRow compilation : mapper.activeLoopSpecCompilations()) {
            try { pollCompiler(compilation); }
            catch (RuntimeException ignoredConcurrentTransition) { }
        }
    }

    private DesignerMessageRow dispatchDesigner(DesignerSessionRow input, String request,
                                                  DesignWorkflowPhase phase, int redesignCount) {
        if (!openCode.healthy()) {
            DesignerSessionRow pending = updateDesignerProjection(input, DesignerSessionState.PENDING_HANDOFF,
                    phase, null, "UNAVAILABLE", input.designRevision(), redesignCount);
            DesignerMessageRow message = appendMessage(pending.id(), DesignerActor.SYSTEM,
                    "SYSTEM_ERROR[SESSION] OPENCODE_DESIGNER_UNAVAILABLE: OpenCode Designer runtime is unavailable; "
                            + "the request remains pending and no task was changed.", "PENDING_HANDOFF");
            publish(pending, "ERROR", DesignerActor.SYSTEM, false, "", message.content());
            return message;
        }
        ProjectRow project = projects.get(input.projectId());
        DesignerSessionRow current = input;
        try {
            OpenCodeClient.OpenCodeSession remote = reusableDesigner(input)
                    ? designerRemote(input)
                    : openCode.createReadOnlySession(Path.of(project.rootPath()),
                    "OpenCode Loopper Designer (READ_ONLY)", configuredModel());
            current = updateDesignerProjection(input, DesignerSessionState.RUNNING, phase,
                    remote.id(), "CREATED", input.designRevision(), redesignCount);
            publish(current, "STATUS", DesignerActor.DESIGNER, true, "",
                    phase == DesignWorkflowPhase.REDESIGNING ? "设计师正在生成完整替代稿" : "设计师正在生成 Markdown 设计稿");
            openCode.promptAsync(remote, phase == DesignWorkflowPhase.REDESIGNING
                    ? request : designerPrompt(current, project, request));
            current = updateDesignerProjection(current, DesignerSessionState.RUNNING, phase,
                    remote.id(), "RUNNING", current.designRevision(), redesignCount);
            return appendMessage(current.id(), DesignerActor.SYSTEM,
                    "请求已交给只读设计师；只有模型的实际 Markdown 输出会作为设计稿保存。",
                    "PENDING_HANDOFF");
        } catch (SessionFailure failure) {
            return failWorkflow(current, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return failWorkflow(current, "OPENCODE_DESIGNER_HANDOFF_FAILED", failure.getMessage());
        }
    }

    private void pollDesigner(DesignerSessionRow session) {
        OpenCodeClient.OpenCodeSession remote = designerRemote(session);
        try {
            List<OpenCodeClient.PendingQuestion> pending = openCode.pendingQuestions(remote);
            if (!pending.isEmpty()) {
                DesignerSessionRow current = same(session.externalSessionState(), "WAITING_INPUT") ? session
                        : updateDesignerProjection(session, DesignerSessionState.RUNNING,
                        DesignWorkflowPhase.valueOf(session.workflowPhase()), remote.id(), "WAITING_INPUT",
                        session.designRevision(), session.redesignCount());
                publish(current, "STATUS", DesignerActor.DESIGNER, true,
                        openCode.sessionLiveOutput(remote), "设计师正在等待你的回答");
                return;
            }
            if (timedOut(session.updatedAt())) {
                try { openCode.abort(remote); } catch (RuntimeException ignored) { }
                failWorkflow(session, "OPENCODE_DESIGNER_TIMEOUT", "Designer exceeded " + defaults.getDesignerTimeout());
                return;
            }
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.failed()) {
                failWorkflow(session, "OPENCODE_DESIGNER_" + safeState(status.state()), statusDetail(status));
            } else if (status.completed()) {
                String output = openCode.sessionOutput(remote);
                String markdown = designerMarkdown(output);
                if (markdown.isBlank()) {
                    failWorkflow(session, "DESIGN_OUTPUT_MISSING", "Designer completed without a Markdown design");
                    return;
                }
                DesignerMessageRow source = appendMessage(session.id(), DesignerActor.DESIGNER, markdown, "PERSISTED");
                DesignerSessionRow compiling = updateDesignerProjection(get(session.id()), DesignerSessionState.RUNNING,
                        DesignWorkflowPhase.COMPILING, remote.id(), "COMPLETED",
                        session.designRevision() + 1, session.redesignCount());
                publish(compiling, "STATUS", DesignerActor.COMPILER, true, "",
                        "设计稿已冻结，正在启动独立规范编译器");
                startCompilation(compiling, source);
            } else {
                DesignerSessionRow current = same(session.externalSessionState(), status.state()) ? session
                        : updateDesignerProjection(session, DesignerSessionState.RUNNING,
                        DesignWorkflowPhase.valueOf(session.workflowPhase()), remote.id(), status.state(),
                        session.designRevision(), session.redesignCount());
                publish(current, "PARTIAL", DesignerActor.DESIGNER, true,
                        designerMarkdown(openCode.sessionLiveOutput(remote)), "正在接收设计师 Markdown");
            }
        } catch (SessionFailure failure) {
            failWorkflow(session, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            failWorkflow(session, "OPENCODE_DESIGNER_STATUS_FAILED", failure.getMessage());
        }
    }

    private void startCompilation(DesignerSessionRow session, DesignerMessageRow source) {
        requireBoundDraft(session);
        LoopDraftRow draft = drafts.get(session.loopDraftId());
        if (source == null || source.actor() == null || !DesignerActor.DESIGNER.name().equals(source.actor())) {
            failWorkflow(session, "DESIGN_SOURCE_MISSING", "No frozen Designer message is available for compilation");
            return;
        }
        String now = now();
        LoopSpecCompilationRow pending = new LoopSpecCompilationRow(UUID.randomUUID().toString(), session.id(),
                session.designRevision(), LoopSpecCompilationState.PENDING_HANDOFF.name(), null, "PENDING", 0,
                source.id(), draft.version(), null, null, now, now, 0);
        lifecycle.create(compilationSubject(pending, session.projectId()), pending.state(), java.util.Map.of(),
                () -> mapper.insertLoopSpecCompilation(pending),
                () -> new ConflictException("LOOPSPEC_COMPILATION_CREATE_CONFLICT",
                        "LoopSpec compilation could not be created"));
        try {
            ProjectRow project = projects.get(session.projectId());
            OpenCodeClient.OpenCodeSession remote = openCode.createReadOnlySession(Path.of(project.rootPath()),
                    "OpenCode Loopper LoopSpec Compiler (READ_ONLY)", configuredModel());
            LoopSpecCompilationRow running = updateCompilation(pending, LoopSpecCompilationState.RUNNING,
                    remote.id(), "RUNNING", 0, null, null, session.projectId());
            openCode.promptAsync(remote, compilerPrompt(session, project, draft, source.content()));
            publish(session, "STATUS", DesignerActor.COMPILER, true, "",
                    "规范编译器已连接；原始 JSON 只会进入 Review Gate");
        } catch (SessionFailure failure) {
            failCompilation(pending, session, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            failCompilation(pending, session, "OPENCODE_COMPILER_HANDOFF_FAILED", failure.getMessage());
        }
    }

    private void pollCompiler(LoopSpecCompilationRow compilation) {
        DesignerSessionRow session = get(compilation.designerSessionId());
        ProjectRow project = projects.get(session.projectId());
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                compilation.externalSessionId(), Path.of(project.rootPath()));
        try {
            List<OpenCodeClient.PendingQuestion> questions = openCode.pendingQuestions(remote);
            if (!questions.isEmpty()) {
                for (OpenCodeClient.PendingQuestion question : questions) {
                    try { openCode.rejectQuestion(remote, question.id()); } catch (RuntimeException ignored) { }
                }
                compilerRejected(compilation, session, remote, "COMPILER_INTERACTION_FORBIDDEN",
                        "LoopSpec Compiler must resolve the frozen design without asking questions");
                return;
            }
            if (timedOut(compilation.updatedAt())) {
                try { openCode.abort(remote); } catch (RuntimeException ignored) { }
                failCompilation(compilation, session, "OPENCODE_COMPILER_TIMEOUT",
                        "LoopSpec Compiler exceeded " + defaults.getDesignerTimeout());
                return;
            }
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            if (status.failed()) {
                failCompilation(compilation, session, "OPENCODE_COMPILER_" + safeState(status.state()), statusDetail(status));
            } else if (status.completed()) {
                handleCompilerOutput(compilation, session, remote, openCode.sessionOutput(remote));
            } else if (!same(compilation.externalSessionState(), status.state())) {
                updateCompilation(compilation, LoopSpecCompilationState.RUNNING, remote.id(), status.state(),
                        compilation.repairCount(), compilation.lastErrorCode(), compilation.lastErrorDetail(),
                        session.projectId());
                publish(session, "STATUS", DesignerActor.COMPILER, true, "", "规范编译器正在生成结构化结果");
            }
        } catch (SessionFailure failure) {
            failCompilation(compilation, session, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            failCompilation(compilation, session, "OPENCODE_COMPILER_STATUS_FAILED", failure.getMessage());
        }
    }

    private void handleCompilerOutput(LoopSpecCompilationRow compilation, DesignerSessionRow session,
                                      OpenCodeClient.OpenCodeSession remote, String output) {
        CompilationEnvelope envelope;
        try {
            envelope = parseCompilation(output);
        } catch (BadRequestException invalid) {
            compilerRejected(compilation, session, remote, invalid.code(), invalid.getMessage());
            return;
        }
        if ("DESIGN_INCOMPLETE".equals(envelope.status())) {
            List<DesignGap> gaps;
            try { gaps = validateDesignGaps(envelope.designGaps()); }
            catch (BadRequestException invalid) {
                compilerRejected(compilation, session, remote, invalid.code(), invalid.getMessage());
                return;
            }
            LoopSpecCompilationRow incomplete = updateCompilation(compilation,
                    LoopSpecCompilationState.DESIGN_INCOMPLETE, remote.id(), "COMPLETED",
                    compilation.repairCount(), "DESIGN_INCOMPLETE", summarizeGaps(gaps), session.projectId());
            appendMessage(session.id(), DesignerActor.COMPILER,
                    "设计稿暂不可编译：\n" + summarizeGaps(gaps), "DESIGN_INCOMPLETE");
            if (session.redesignCount() < MAX_AUTOMATIC_REDESIGNS) {
                dispatchDesigner(get(session.id()), redesignPrompt(summarizeGaps(gaps)),
                        DesignWorkflowPhase.REDESIGNING, session.redesignCount() + 1);
            } else {
                failWorkflow(get(session.id()), "DESIGN_RETRY_EXHAUSTED",
                        "Designer automatic redesign limit was reached: " + incomplete.lastErrorDetail());
            }
            return;
        }
        if (!"COMPILED".equals(envelope.status())) {
            compilerRejected(compilation, session, remote, "COMPILER_STATUS_INVALID",
                    "Compiler status must be COMPILED or DESIGN_INCOMPLETE");
            return;
        }
        try {
            DesignerSessionRow validating = updateDesignerProjection(get(session.id()), DesignerSessionState.RUNNING,
                    DesignWorkflowPhase.VALIDATING, session.externalSessionId(), session.externalSessionState(),
                    session.designRevision(), session.redesignCount());
            DesignerMessageRow source = mapper.listDesignerMessages(session.id()).stream()
                    .filter(message -> message.id().equals(compilation.sourceDesignMessageId())).findFirst()
                    .orElseThrow(() -> new ConflictException("DESIGN_SOURCE_MISSING",
                            "The frozen Designer source message no longer exists"));
            requireProject(validating, envelope.loopSpec());
            validateTraceability(source.content(), envelope.loopSpec(), envelope.criterionSources());
            drafts.updateAtVersion(validating.loopDraftId(), envelope.loopSpec(), compilation.sourceDraftVersion());
            String summary = blank(envelope.summary()) ? "LoopSpec 已从当前冻结设计编译完成。"
                    : bounded(envelope.summary(), 1_000);
            appendMessage(session.id(), DesignerActor.COMPILER, summary, "COMPILED");
            appendMessage(session.id(), DesignerActor.VALIDATOR,
                    "确定性校验通过：字段、验证器、验收覆盖、来源追踪及 Java 单元测试规则均满足。",
                    "PASS");
            updateCompilation(getCompilation(compilation.id()), LoopSpecCompilationState.COMPLETED,
                    remote.id(), "COMPLETED", compilation.repairCount(), null, null, session.projectId());
            DesignerSessionRow completed = updateDesignerProjection(get(session.id()), DesignerSessionState.COMPLETED,
                    DesignWorkflowPhase.COMPLETED, session.externalSessionId(), session.externalSessionState(),
                    session.designRevision(), session.redesignCount());
            publish(completed, "COMPLETED", DesignerActor.VALIDATOR, true, "",
                    "LoopSpec 已通过确定性校验并同步到 Review Gate");
        } catch (BadRequestException invalid) {
            compilerRejected(getCompilation(compilation.id()), get(session.id()), remote, invalid.code(), invalid.getMessage());
        } catch (ConflictException stale) {
            failCompilation(getCompilation(compilation.id()), get(session.id()), stale.code(), stale.getMessage());
        }
    }

    private void compilerRejected(LoopSpecCompilationRow input, DesignerSessionRow session,
                                  OpenCodeClient.OpenCodeSession remote, String code, String detail) {
        LoopSpecCompilationRow compilation = getCompilation(input.id());
        appendMessage(session.id(), DesignerActor.VALIDATOR,
                "确定性校验未通过（" + code + "）：" + safeMessage(detail), "RETRYABLE_ERROR");
        if (compilation.repairCount() >= MAX_COMPILER_REPAIRS) {
            failCompilation(compilation, session, "COMPILER_RETRY_EXHAUSTED", detail);
            return;
        }
        int repair = compilation.repairCount() + 1;
        LoopSpecCompilationRow repairing = updateCompilation(compilation, LoopSpecCompilationState.RUNNING,
                remote.id(), "REPAIRING_" + repair, repair, code, safeMessage(detail), session.projectId());
        DesignerSessionRow compiling = updateDesignerProjection(get(session.id()), DesignerSessionState.RUNNING,
                DesignWorkflowPhase.COMPILING, session.externalSessionId(), session.externalSessionState(),
                session.designRevision(), session.redesignCount());
        try {
            openCode.promptAsync(remote, compilerRepairPrompt(repairing, code, detail));
            publish(compiling, "STATUS", DesignerActor.COMPILER, true, "",
                    "规范编译器正在进行第 " + repair + "/" + MAX_COMPILER_REPAIRS + " 次修复");
        } catch (RuntimeException failure) {
            failCompilation(repairing, compiling, "OPENCODE_COMPILER_REPAIR_FAILED", failure.getMessage());
        }
    }

    private void failCompilation(LoopSpecCompilationRow input, DesignerSessionRow session,
                                 String code, String detail) {
        LoopSpecCompilationRow current = mapper.findLoopSpecCompilation(input.id()).orElse(input);
        if (!LoopSpecCompilationState.SESSION_ERROR.name().equals(current.state())
                && !LoopSpecCompilationState.COMPLETED.name().equals(current.state())
                && !LoopSpecCompilationState.DESIGN_INCOMPLETE.name().equals(current.state())) {
            updateCompilation(current, LoopSpecCompilationState.SESSION_ERROR,
                    current.externalSessionId(), "FAILED", current.repairCount(), code,
                    safeMessage(detail), session.projectId());
        }
        failWorkflow(get(session.id()), code, detail);
    }

    private DesignerMessageRow failWorkflow(DesignerSessionRow input, String code, String detail) {
        DesignerSessionRow current = get(input.id());
        DesignerSessionRow failed = current;
        if (!DesignerSessionState.SESSION_ERROR.name().equals(current.state())) {
            failed = updateDesignerProjection(current, DesignerSessionState.SESSION_ERROR,
                    DesignWorkflowPhase.FAILED, current.externalSessionId(), "FAILED",
                    current.designRevision(), current.redesignCount());
        }
        DesignerMessageRow message = appendMessage(failed.id(), DesignerActor.VALIDATOR,
                "工作流已停止（" + code + "）：" + safeMessage(detail)
                        + "。设计稿未同步，也没有创建或修改 Task。",
                "TERMINAL_ERROR");
        publish(failed, "ERROR", DesignerActor.VALIDATOR, false, "", message.content());
        return message;
    }

    private CompilationEnvelope parseCompilation(String output) {
        if (blank(output)) throw new BadRequestException("COMPILER_OUTPUT_MISSING",
                "LoopSpec Compiler completed without output");
        Matcher matcher = COMPILATION_PAYLOAD.matcher(output);
        if (!matcher.find()) throw new BadRequestException("COMPILER_OUTPUT_MARKERS_MISSING",
                "Compiler output did not contain the required compilation markers");
        String payload = matcher.group(1);
        int start = payload.indexOf('{');
        int end = payload.lastIndexOf('}');
        if (start < 0 || end <= start) throw new BadRequestException("COMPILER_OUTPUT_INVALID",
                "Compiler payload is not one JSON object");
        try {
            CompilationEnvelope envelope = json.readValue(payload.substring(start, end + 1), CompilationEnvelope.class);
            if (envelope == null || blank(envelope.status())) {
                throw new BadRequestException("COMPILER_STATUS_MISSING", "Compiler status is required");
            }
            return envelope.normalized();
        } catch (BadRequestException failure) {
            throw failure;
        } catch (JacksonException failure) {
            throw new BadRequestException("COMPILER_OUTPUT_INVALID",
                    "Compiler JSON cannot be read: " + failure.getMessage());
        }
    }

    private List<DesignGap> validateDesignGaps(List<DesignGap> input) {
        if (input == null || input.isEmpty()) throw new BadRequestException("DESIGN_GAPS_REQUIRED",
                "DESIGN_INCOMPLETE requires at least one concrete design gap");
        List<DesignGap> result = new ArrayList<>();
        for (DesignGap gap : input) {
            if (gap == null || gap.code() == null || !ALLOWED_DESIGN_GAPS.contains(gap.code()) || blank(gap.detail())) {
                throw new BadRequestException("DESIGN_GAP_INVALID",
                        "Design gaps must use a closed semantic gap code and a concrete detail");
            }
            result.add(new DesignGap(gap.code(), bounded(gap.detail().trim(), 1_000)));
        }
        return List.copyOf(result);
    }

    private void validateTraceability(String design, LoopSpec spec, List<CriterionSource> sources) {
        if (spec == null) throw new BadRequestException("COMPILED_LOOPSPEC_MISSING", "COMPILED requires loopSpec");
        List<CriterionSource> trace = sources == null ? List.of() : sources;
        Set<String> seen = new HashSet<>();
        for (int stageIndex = 0; stageIndex < spec.stages().size(); stageIndex++) {
            int currentStageIndex = stageIndex;
            for (LoopSpec.AcceptanceCriterion criterion : spec.stages().get(stageIndex).acceptanceCriteria()) {
                if (criterion == null || blank(criterion.id())) {
                    throw new BadRequestException("CRITERION_SOURCE_INVALID",
                            "Every compiled acceptance criterion must have a non-blank id before source mapping");
                }
                String key = stageIndex + ":" + criterion.id();
                CriterionSource source = trace.stream()
                        .filter(item -> item != null && item.stageIndex() == currentStageIndex
                                && criterion.id().equals(item.criterionId())).findFirst()
                        .orElseThrow(() -> new BadRequestException("CRITERION_SOURCE_MISSING",
                                "No Designer source excerpt was supplied for " + key));
                if (blank(source.excerpt()) || !design.contains(source.excerpt())) {
                    throw new BadRequestException("CRITERION_SOURCE_NOT_IN_DESIGN",
                            "Criterion source excerpt is not an exact substring of the frozen design: " + key);
                }
                if (!seen.add(key)) throw new BadRequestException("CRITERION_SOURCE_DUPLICATE",
                        "Criterion source was duplicated: " + key);
            }
        }
        if (trace.size() != seen.size()) throw new BadRequestException("CRITERION_SOURCE_EXTRA",
                "Compiler supplied criterion source entries that do not belong to the compiled LoopSpec");
    }

    private String designerPrompt(DesignerSessionRow session, ProjectRow project, String message) {
        return """
                You are OpenCode Loopper Designer / 设计师 in strictly read-only advisory mode.
                You may use read, glob, and grep to inspect the registered project. Do not edit or write files,
                run commands, create tasks, or claim implementation has happened.

                Registered project root: %s
                Designer session id: %s
                Bound draft id: %s

                Produce one complete, replacement-quality Markdown design in Simplified Chinese. Do not emit
                LoopSpec JSON, schema fields, hidden markers, or a machine payload. Include implementation scope,
                observable business results, exception semantics, affected modules/files, dependency-ordered stages,
                acceptance intent and exact validation commands when evidenced. Non-trivial work should normally use
                2-6 independently deliverable stages; an atomic change may use one stage with a stated reason.
                Every stage must be coherent and immediately verifiable. Do not postpone all behavior checks to a
                final test stage. If a stage adds or changes production Java, put its focused Maven/Gradle unit test
                in the same stage and describe which business acceptance behavior that test proves. A statement such
                as 'all tests pass' is evidence, not a standalone business acceptance item. Include Mermaid for
                multi-step workflows. Preserve identifiers, commands, paths, and enum literals exactly.

                User request:
                %s
                """.formatted(project.rootPath(), session.id(), session.loopDraftId(), message);
    }

    private String compilerPrompt(DesignerSessionRow session, ProjectRow project, LoopDraftRow draft, String design) {
        return """
                You are OpenCode Loopper LoopSpec Compiler / 规范编译器 in a new strictly read-only Session.
                You compile a frozen Designer Markdown document into machine LoopSpec; you do not redesign it.
                You may use read, glob, and grep to verify build files and test conventions. Do not edit/write files,
                execute commands, ask questions, create tasks, or add business requirements absent from the design.

                Project root: %s
                Required projectId: %s
                Draft schema/version context (read-only):
                %s

                Return exactly one JSON object between the exact markers below and no raw LoopSpec elsewhere.
                Status COMPILED requires loopSpec, a short summary, and one criterionSources entry for every
                stage acceptance criterion. Each entry has stageIndex, criterionId, and excerpt; excerpt must be an
                exact non-empty substring of the frozen design. Status DESIGN_INCOMPLETE is allowed only when the
                design lacks business semantics and requires designGaps using only these codes:
                MISSING_OBSERVABLE_OUTCOME, MISSING_EXCEPTION_SEMANTICS, MISSING_SCOPE, MISSING_ACCEPTANCE_INTENT.
                Never use DESIGN_INCOMPLETE for malformed JSON, schema uncertainty, invalid validators, or coverage errors.

                For v2 every stage must set implementationKind to JAVA_PRODUCTION, JAVA_TEST_ONLY, or NON_JAVA.
                JAVA_PRODUCTION requires a non-skipped focused Maven/Gradle PROCESS TEST with concrete testTargets,
                and every MACHINE/BOTH business criterion must be mapped to that focused test through criterionIds.
                Tests are evidence for business criteria, never a separate 'tests pass' criterion. PROCESS is direct
                argv, never shell. Every v2 PROCESS declares processPurpose. Every stage has at least one blocking
                deterministic verifier. GIT_DIFF is scope only; FILE_EXISTS is advisory; build/lint/typecheck are not
                behavior. Use JUDGE only when deterministic proof is genuinely unreliable and explain why.

                Required envelope shape:
                <!-- LOOPSPEC_COMPILATION_JSON_START -->
                ```json
                {"status":"COMPILED","summary":"...","loopSpec":{"schemaVersion":"v2","projectId":"%s","goal":"...","context":"...","stages":[{"objective":"...","allowedPaths":[],"forbiddenPaths":[],"deliverables":[],"implementationKind":"NON_JAVA","acceptanceCriteria":[{"id":"AC-1","description":"...","verificationMode":"MACHINE"}],"verifiers":[]}],"limits":{}},"criterionSources":[{"stageIndex":0,"criterionId":"AC-1","excerpt":"exact Designer text"}],"designGaps":[]}
                ```
                <!-- LOOPSPEC_COMPILATION_JSON_END -->

                Frozen Designer Markdown revision %d:
                %s
                """.formatted(project.rootPath(), session.projectId(), draft.specJson(),
                session.projectId(), session.designRevision(), design);
    }

    private String compilerRepairPrompt(LoopSpecCompilationRow compilation, String code, String detail) {
        return """
                The deterministic server validator rejected the previous compiler envelope.
                Repair the complete compilation envelope using only the same frozen Designer document and prior
                read-only evidence. Do not redesign, ask questions, inspect additional scope, execute commands, or
                return DESIGN_INCOMPLETE to escape JSON/schema/verifier/coverage errors.

                Repair %d/%d
                Error code: %s
                Error detail: %s

                Return one complete replacement JSON object between
                <!-- LOOPSPEC_COMPILATION_JSON_START --> and <!-- LOOPSPEC_COMPILATION_JSON_END -->.
                """.formatted(compilation.repairCount(), MAX_COMPILER_REPAIRS, code, safeMessage(detail));
    }

    private String redesignPrompt(String gaps) {
        return """
                The independent LoopSpec Compiler could not compile the previous frozen design because required
                business semantics were missing. Produce a complete replacement Markdown design, not a patch or
                commentary about the old design. Do not emit LoopSpec JSON or hidden machine markers. Preserve the
                original user goal, but explicitly fill every listed gap with observable results, exception semantics,
                scope, and acceptance intent. Production Java changes must include focused Maven/Gradle unit-test
                evidence mapped to the business behavior in the same stage.

                Design gaps:
                %s
                """.formatted(gaps);
    }

    private String designerMarkdown(String output) {
        if (blank(output)) return "";
        return LEGACY_DESIGNER_PAYLOAD.matcher(output).replaceAll("").trim();
    }

    private DesignerMessageRow latestDesign(String sessionId) {
        return mapper.listDesignerMessages(sessionId).stream()
                .filter(message -> DesignerActor.DESIGNER.name().equals(message.actor()))
                .filter(message -> "PERSISTED".equals(message.deliveryState()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new ConflictException("DESIGN_SOURCE_MISSING",
                        "No frozen Designer Markdown is available"));
    }

    private void requireBoundDraft(DesignerSessionRow session) {
        if (blank(session.loopDraftId())) throw new ConflictException("DESIGNER_DRAFT_NOT_BOUND",
                "Designer session is not bound to a LoopSpec draft");
    }

    private void requireProject(DesignerSessionRow session, LoopSpec spec) {
        if (spec == null || !session.projectId().equals(spec.projectId())) {
            throw new BadRequestException("LOOPSPEC_PROJECT_MISMATCH",
                    "LoopSpec projectId must match the Designer session projectId");
        }
    }

    private DesignerSessionRow requireRunningDesigner(String sessionId) {
        DesignerSessionRow session = get(sessionId);
        if (!DesignerSessionState.RUNNING.name().equals(session.state())
                || !Set.of(DesignWorkflowPhase.DESIGNING.name(), DesignWorkflowPhase.REDESIGNING.name())
                .contains(session.workflowPhase()) || blank(session.externalSessionId())) {
            throw new ConflictException("DESIGNER_QUESTION_UNAVAILABLE",
                    "Designer session has no running Designer question to answer");
        }
        return session;
    }

    private OpenCodeClient.OpenCodeSession designerRemote(DesignerSessionRow session) {
        return new OpenCodeClient.OpenCodeSession(session.externalSessionId(),
                Path.of(projects.get(session.projectId()).rootPath()));
    }

    private boolean reusableDesigner(DesignerSessionRow session) {
        return !blank(session.externalSessionId())
                && !DesignerSessionState.SESSION_ERROR.name().equals(session.state());
    }

    private OpenCodeClient.PendingQuestion pending(OpenCodeClient.OpenCodeSession remote, String questionId) {
        if (blank(questionId)) throw new BadRequestException("QUESTION_ID_REQUIRED", "Question id is required");
        try {
            return openCode.pendingQuestions(remote).stream().filter(question -> questionId.equals(question.id()))
                    .findFirst().orElseThrow(() -> new NotFoundException(
                            "Pending question not found for this Designer Session: " + questionId));
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
            throw new BadRequestException("QUESTION_ANSWERS_INVALID",
                    "Answers must contain one entry for every question");
        }
        List<List<String>> result = new ArrayList<>();
        for (int index = 0; index < pending.questions().size(); index++) {
            OpenCodeClient.QuestionPrompt prompt = pending.questions().get(index);
            List<String> answer = answers.get(index) == null ? List.of() : answers.get(index);
            List<String> normalized = answer.stream().filter(value -> value != null && !value.isBlank())
                    .map(String::trim).distinct().toList();
            if (normalized.isEmpty()) throw new BadRequestException("QUESTION_ANSWER_REQUIRED",
                    "Every question requires an answer");
            if (!prompt.multiple() && normalized.size() > 1) {
                throw new BadRequestException("QUESTION_ANSWER_MULTIPLE_FORBIDDEN",
                        "This question accepts only one answer");
            }
            if (!prompt.custom()) {
                List<String> labels = prompt.options().stream().map(OpenCodeClient.QuestionOption::label).toList();
                if (!labels.containsAll(normalized)) throw new BadRequestException("QUESTION_CUSTOM_ANSWER_FORBIDDEN",
                        "This question only accepts listed options");
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private DesignerSessionRow updateDesignerProjection(DesignerSessionRow session, DesignerSessionState state,
                                                         DesignWorkflowPhase phase, String externalSessionId,
                                                         String externalSessionState, int revision, int redesignCount) {
        DesignerSessionRow updated = new DesignerSessionRow(session.id(), session.projectId(), state.name(),
                session.accessMode(), session.createdAt(), now(), session.version(), externalSessionId,
                externalSessionState, session.loopDraftId(), phase.name(), revision, redesignCount);
        if (session.state().equals(updated.state())) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateDesignerSessionProjection(updated),
                    () -> new ConflictException("DESIGNER_SESSION_VERSION_CONFLICT",
                            "Designer session was updated concurrently"));
        } else {
            lifecycle.transition(designerSubject(updated), session.state(), updated.state(), null, java.util.Map.of(),
                    () -> mapper.updateDesignerSession(updated),
                    () -> new ConflictException("DESIGNER_SESSION_VERSION_CONFLICT",
                            "Designer session was updated concurrently"));
        }
        return get(session.id());
    }

    private LoopSpecCompilationRow updateCompilation(LoopSpecCompilationRow row,
                                                     LoopSpecCompilationState state,
                                                     String externalSessionId, String externalSessionState,
                                                     int repairCount, String errorCode, String errorDetail,
                                                     String projectId) {
        LoopSpecCompilationRow updated = new LoopSpecCompilationRow(row.id(), row.designerSessionId(),
                row.designRevision(), state.name(), externalSessionId, externalSessionState, repairCount,
                row.sourceDesignMessageId(), row.sourceDraftVersion(), errorCode, errorDetail,
                row.createdAt(), now(), row.version());
        if (row.state().equals(updated.state())) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateLoopSpecCompilation(updated),
                    () -> new ConflictException("LOOPSPEC_COMPILATION_VERSION_CONFLICT",
                            "LoopSpec compilation was updated concurrently"));
        } else {
            lifecycle.transition(compilationSubject(updated, projectId), row.state(), updated.state(), null,
                    java.util.Map.of(), () -> mapper.updateLoopSpecCompilation(updated),
                    () -> new ConflictException("LOOPSPEC_COMPILATION_VERSION_CONFLICT",
                            "LoopSpec compilation was updated concurrently"));
        }
        return getCompilation(row.id());
    }

    private LoopSpecCompilationRow getCompilation(String id) {
        return mapper.findLoopSpecCompilation(id)
                .orElseThrow(() -> new NotFoundException("LoopSpec compilation not found: " + id));
    }

    private LifecycleTransitionService.Subject designerSubject(DesignerSessionRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.DESIGNER_SESSION, row.id(),
                LifecycleScopeType.PROJECT, row.projectId());
    }

    private LifecycleTransitionService.Subject compilationSubject(LoopSpecCompilationRow row, String projectId) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.LOOPSPEC_COMPILATION, row.id(),
                LifecycleScopeType.PROJECT, projectId);
    }

    private DesignerMessageRow appendMessage(String sessionId, DesignerActor actor,
                                             String content, String deliveryState) {
        String role = actor == DesignerActor.USER ? "USER"
                : Set.of(DesignerActor.DESIGNER, DesignerActor.COMPILER).contains(actor) ? "ASSISTANT" : "SYSTEM";
        int contentLimit = actor == DesignerActor.DESIGNER ? MAX_FROZEN_DESIGN_LENGTH : MAX_MESSAGE_LENGTH;
        DesignerMessageRow message = new DesignerMessageRow(UUID.randomUUID().toString(), sessionId,
                mapper.nextDesignerMessageOrdinal(sessionId), role, bounded(content, contentLimit),
                deliveryState, now(), actor.name());
        mapper.insertDesignerMessage(message);
        return message;
    }

    private void publish(DesignerSessionRow session, String type, DesignerActor actor,
                         boolean connected, String content, String detail) {
        CompilerStatus compiler = compilerStatus(session.id());
        String remoteState = actor == DesignerActor.COMPILER && compiler != null
                ? compiler.externalSessionState() : session.externalSessionState();
        events.publish(session.id(), type, session.state(), session.workflowPhase(), actor.name(),
                remoteState, connected, actor == DesignerActor.COMPILER ? "" : designerMarkdown(content), detail);
    }

    private OpenCodeClient.OpenCodeModel configuredModel() {
        String configured = defaults.getOpenCode().getModel();
        if (configured == null) return null;
        String value = configured.trim();
        int separator = value.indexOf('/');
        if (separator <= 0 || separator >= value.length() - 1) return null;
        String provider = value.substring(0, separator).trim();
        String model = value.substring(separator + 1).trim();
        return provider.isEmpty() || model.isEmpty() ? null
                : new OpenCodeClient.OpenCodeModel(provider, model, null);
    }

    private boolean timedOut(String updatedAt) {
        Duration timeout = defaults.getDesignerTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) return false;
        try { return Duration.between(Instant.parse(updatedAt), Instant.now()).compareTo(timeout) > 0; }
        catch (RuntimeException invalidTimestamp) { return false; }
    }

    private String normalizeMessage(String content) {
        if (blank(content)) throw new BadRequestException("DESIGNER_MESSAGE_REQUIRED",
                "Designer message content is required");
        String normalized = content.trim();
        if (normalized.length() > MAX_MESSAGE_LENGTH) throw new BadRequestException("DESIGNER_MESSAGE_TOO_LONG",
                "Designer message must be at most " + MAX_MESSAGE_LENGTH + " characters");
        return normalized;
    }

    private String summarizeGaps(List<DesignGap> gaps) {
        return gaps.stream().map(gap -> "- " + gap.code().name() + "：" + gap.detail())
                .reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }
    private static String statusDetail(OpenCodeClient.SessionStatus status) {
        return blank(status.detail()) ? "OpenCode session ended in " + status.state() : status.detail();
    }
    private static String safeState(String state) {
        return blank(state) ? "UNKNOWN" : state.replaceAll("[^A-Za-z0-9_-]", "_");
    }
    private static String safeMessage(String message) {
        if (blank(message)) return "OpenCode read-only workflow failed";
        return bounded(message.replaceAll("[\\r\\n]+", " ").trim(), 500);
    }
    private static String bounded(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
    private String now() { return Instant.now().toString(); }

    public record PendingQuestion(String id, List<QuestionPrompt> questions) { }
    public record QuestionPrompt(String question, String header, List<QuestionOption> options,
                                 boolean multiple, boolean custom) { }
    public record QuestionOption(String label, String description) { }
    public record CompilerStatus(String id, String state, String externalSessionId,
                                 String externalSessionState, int repairCount,
                                 int designRevision, String lastErrorCode, String lastErrorDetail) { }
    public record CriterionSource(int stageIndex, String criterionId, String excerpt) { }
    public record DesignGap(DesignGapCode code, String detail) { }
    public enum DesignGapCode {
        MISSING_OBSERVABLE_OUTCOME, MISSING_EXCEPTION_SEMANTICS, MISSING_SCOPE, MISSING_ACCEPTANCE_INTENT
    }
    public record CompilationEnvelope(String status, String summary, LoopSpec loopSpec,
                                      List<CriterionSource> criterionSources, List<DesignGap> designGaps) {
        CompilationEnvelope normalized() {
            return new CompilationEnvelope(status == null ? null : status.trim().toUpperCase(), summary,
                    loopSpec, criterionSources == null ? List.of() : List.copyOf(criterionSources),
                    designGaps == null ? List.of() : List.copyOf(designGaps));
        }
    }
}
