package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.domain.ProjectConventionState;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectConventionDraftRow;
import io.opencode.loopper.persistence.ProjectConventionRuntimeRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Generates a hash-guarded project context proposal through one deterministic authority. */
@Service
public class ProjectConventionService {
    public static final String START_MARKER = ProjectConventionDocumentStore.START_MARKER;
    public static final String END_MARKER = ProjectConventionDocumentStore.END_MARKER;
    private static final int MAX_PROJECT_CONTEXT_REPAIR_ATTEMPTS = 2;
    private static final String PROJECT_CONTEXT_REPAIR_STATE = "REPAIRING_PROJECT_CONTEXT_";
    private static final String STOP_USER_CANCEL = "USER_CANCEL";
    private static final String STOP_POLL_FAILED = "POLL_FAILED";
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ProjectService projects;
    private final ProjectStackProfileService stackProfiles;
    private final ProjectConventionStackPolicy stackPolicy;
    private final OpenCodeClient openCode;
    private final LoopperProperties properties;
    private final ProjectConventionLegacyAdapter legacyAdapter;
    private final ProjectConventionCandidateWorkflow candidateWorkflow;
    private final ProjectConventionCandidateDraftCreator candidateDrafts;
    private final AiOutputAuditService aiOutputAudit;
    private final ProjectConventionDocumentStore documents;

    public ProjectConventionService(LoopperMapper mapper, LifecycleTransitionService lifecycle,
                                    ProjectService projects, ProjectStackProfileService stackProfiles,
                                    ProjectConventionStackPolicy stackPolicy,
                                    OpenCodeClient openCode,
                                    LoopperProperties properties, ProjectConventionLegacyAdapter legacyAdapter,
                                    ProjectConventionCandidateWorkflow candidateWorkflow,
                                    ProjectConventionCandidateDraftCreator candidateDrafts,
                                    AiOutputAuditService aiOutputAudit,
                                    ProjectConventionDocumentStore documents) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.projects = projects;
        this.stackProfiles = stackProfiles;
        this.stackPolicy = stackPolicy;
        this.openCode = openCode;
        this.properties = properties;
        this.legacyAdapter = legacyAdapter;
        this.candidateWorkflow = candidateWorkflow;
        this.candidateDrafts = candidateDrafts;
        this.aiOutputAudit = aiOutputAudit;
        this.documents = documents;
    }

    public synchronized ProjectConventionDraftRow generate(String projectId) {
        ProjectRow project = projects.get(projectId);
        ProjectConventionDraftRow active = mapper.activeProjectConventionDraft(projectId).orElse(null);
        if (active != null) {
            ProjectConventionDraftRow recovered = reconcileActiveGeneration(active);
            if (!ProjectConventionState.FAILED.name().equals(recovered.state())
                    && !ProjectConventionState.CANCELLED.name().equals(recovered.state())) return recovered;
        }
        ProjectStackSnapshot stackProfile = stackProfiles.forceRefresh(projectId);
        if (!stackProfile.usable()) {
            throw new ConflictException("PROJECT_STACK_ANALYSIS_FAILED",
                    "项目技术栈分析失败；请检查项目目录读取权限后重试");
        }
        if (!openCode.healthy()) {
            throw new ServiceUnavailableException("OPENCODE_UNAVAILABLE",
                    "OpenCode is unavailable; AGENTS.md generation requires a real read-only AI session");
        }
        ProjectConventionDocumentStore.SourceSnapshot source = documents.read(project);
        if (properties.getInternalCandidate().isProjectConventionV1Enabled()) {
            return startCandidate(project, source, stackProfile);
        }
        OpenCodeClient.OpenCodeSession remote;
        try {
            remote = openCode.createSession(Path.of(project.rootPath()),
                    "OpenCode Loopper AGENTS.md Designer (READ_ONLY)", configuredModel(),
                    OpenCodeClient.SessionProfile.PROJECT_CONVENTION_READ_ONLY);
        } catch (RuntimeException failure) {
            throw new ServiceUnavailableException("PROJECT_CONVENTION_SESSION_FAILED", safeMessage(failure));
        }
        String now = now();
        ProjectConventionDraftRow created = new ProjectConventionDraftRow(UUID.randomUUID().toString(), project.id(),
                ProjectConventionState.RUNNING.name(),
                remote.id(), "CREATED", source.exists() ? 1 : 0, source.sha256(), source.content(), null,
                stackProfile.state() == io.opencode.loopper.domain.ProjectStackProfileState.PARTIAL
                        ? "项目技术栈画像证据不完整；请重点复核技术栈与模块章节" : null,
                null, now, now, 0, stackProfile.id(), stackProfile.manifestFingerprint());
        lifecycle.create(subject(created), created.state(), java.util.Map.of(),
                () -> mapper.insertProjectConventionDraft(created),
                () -> new ConflictException("PROJECT_CONVENTION_CREATE_CONFLICT", "AGENTS.md proposal could not be created"));
        try {
            mapper.insertProjectConventionRuntime(new ProjectConventionRuntimeRow(created.id(), now,
                    "CREATED:" + remote.id(), null, null, now, now, 0));
        } catch (RuntimeException runtimeFailure) {
            try { openCode.abort(remote); } catch (RuntimeException ignored) { }
            return transition(created, ProjectConventionState.FAILED, "RUNTIME_CREATE_FAILED", null,
                    "AGENTS.md generation runtime state could not be persisted");
        }
        ProjectConventionDraftRow row = transition(created, ProjectConventionState.RUNNING, "RUNNING", null, null);
        try {
            openCode.promptAsync(remote, stackPolicy.prompt(project, source.exists(), source.content(), stackProfile));
            return row;
        } catch (SessionFailure failure) {
            return requestStop(row, STOP_POLL_FAILED, safeMessage(failure));
        } catch (RuntimeException failure) {
            return requestStop(row, STOP_POLL_FAILED, safeMessage(failure));
        }
    }
    public ProjectConventionDraftRow get(String projectId, String draftId) {
        projects.get(projectId);
        ProjectConventionDraftRow row = mapper.findProjectConventionDraft(draftId)
                .orElseThrow(() -> new NotFoundException("AGENTS.md proposal not found: " + draftId));
        if (!projectId.equals(row.projectId())) {
            throw new NotFoundException("AGENTS.md proposal not found: " + draftId);
        }
        return row;
    }
    public CurrentConvention current(String projectId) {
        ProjectRow project = projects.get(projectId);
        ProjectConventionDocumentStore.SourceSnapshot source = documents.read(project);
        boolean loopperManaged = source.content().contains(START_MARKER) && source.content().contains(END_MARKER);
        return new CurrentConvention(project.id(), source.exists(), loopperManaged, source.content());
    }
    public void pollActiveGenerations() {
        for (ProjectConventionDraftRow row : mapper.activeProjectConventionDrafts()) {
            reconcileActiveGeneration(row);
        }
    }
    private ProjectConventionDraftRow reconcileActiveGeneration(ProjectConventionDraftRow row) {
        try {
            if (ProjectConventionState.APPLYING.name().equals(row.state())) return recoverApplying(row);
            if (candidateWorkflow.owns(row)) {
                ProjectConventionCandidateWorkflow.Result result = candidateWorkflow.advance(
                        candidateContext(row, false));
                if (result.action() == ProjectConventionCandidateWorkflow.Action.LEGACY_FALLBACK) {
                    return startLegacyReplacement(get(row.projectId(), row.id()));
                }
                return get(row.projectId(), row.id());
            }
            if (ProjectConventionCandidateWorkflow.RESPONSE_MODE.equals(row.responseMode())
                    && row.externalSessionId() == null
                    && ProjectConventionState.RUNNING.name().equals(row.state())) {
                return startLegacyReplacement(row);
            }
            if (ProjectConventionState.STOPPING.name().equals(row.state())) {
                pollStopping(row);
                return get(row.projectId(), row.id());
            }
            poll(row);
        }
        catch (RuntimeException failure) {
            try {
                ProjectConventionDraftRow current = get(row.projectId(), row.id());
                if (candidateWorkflow.owns(current)) return current;
                if (ProjectConventionState.RUNNING.name().equals(current.state())) {
                    return requestStop(current, STOP_POLL_FAILED,
                            "项目公约会话轮询失败，已请求停止：" + safeMessage(failure));
                }
            }
            catch (RuntimeException ignoredConcurrentTransition) { }
        }
        return get(row.projectId(), row.id());
    }
    public ProjectConventionDraftRow apply(String projectId, String draftId) {
        ProjectConventionDraftRow row = get(projectId, draftId);
        if (!ProjectConventionState.READY.name().equals(row.state()) || row.proposedContent() == null) {
            throw new ConflictException("PROJECT_CONVENTION_NOT_READY",
                    "The AGENTS.md proposal must finish successfully before it can be applied");
        }
        ProjectRow project = projects.get(projectId);
        ProjectConventionDocumentStore.SourceSnapshot current = documents.read(project);
        if (current.exists() != (row.sourceExists() == 1) || !current.sha256().equals(row.sourceSha256())) {
            throw new ConflictException("AGENTS_MD_CHANGED",
                    "AGENTS.md changed after generation started; generate a fresh proposal before applying");
        }
        stackPolicy.requireCurrentFingerprint(row);
        ProjectConventionDraftRow applying = transition(row, ProjectConventionState.APPLYING,
                "APPLYING", row.proposedContent(), null);
        try {
            documents.write(project, applying.proposedContent());
        } catch (RuntimeException failure) {
            ProjectConventionDraftRow latest = get(projectId, draftId);
            if (ProjectConventionState.APPLYING.name().equals(latest.state())) {
                transition(latest, ProjectConventionState.FAILED, "APPLY_FAILED", latest.proposedContent(),
                        safeMessage(failure));
            }
            throw failure;
        }
        return completeApplying(applying);
    }
    public ProjectConventionDraftRow cancel(String projectId, String draftId) {
        ProjectConventionDraftRow row = get(projectId, draftId);
        if (ProjectConventionState.RUNNING.name().equals(row.state())) {
            if (candidateWorkflow.owns(row)) {
                candidateWorkflow.advance(candidateContext(row, true));
                return get(projectId, draftId);
            }
            return requestStop(row, STOP_USER_CANCEL, "用户取消了项目公约生成");
        }
        if (ProjectConventionState.STOPPING.name().equals(row.state())) {
            pollStopping(row);
            return get(projectId, draftId);
        }
        return row;
    }
    private ProjectConventionDraftRow recoverApplying(ProjectConventionDraftRow input) {
        ProjectConventionDraftRow row = get(input.projectId(), input.id());
        if (!ProjectConventionState.APPLYING.name().equals(row.state())) return row;
        if (row.proposedContent() == null) {
            return transition(row, ProjectConventionState.FAILED, "APPLY_RECOVERY_INVALID", null,
                    "Persisted APPLYING proposal has no AGENTS.md content");
        }
        ProjectRow project = projects.get(row.projectId());
        ProjectConventionDocumentStore.SourceSnapshot current = documents.read(project);
        String proposedSha = sha256(row.proposedContent().getBytes(StandardCharsets.UTF_8));
        if (current.sha256().equals(proposedSha)) return completeApplying(row);
        if (current.exists() != (row.sourceExists() == 1) || !current.sha256().equals(row.sourceSha256())) {
            return transition(row, ProjectConventionState.FAILED, "AGENTS_MD_APPLY_RECOVERY_CONFLICT",
                    row.proposedContent(), "AGENTS.md changed while an interrupted apply was being recovered");
        }
        try { stackPolicy.requireCurrentFingerprint(row); }
        catch (RuntimeException staleProfile) {
            return transition(row, ProjectConventionState.FAILED, "AGENTS_MD_STACK_PROFILE_CONFLICT",
                    row.proposedContent(), safeMessage(staleProfile));
        }
        try {
            documents.write(project, row.proposedContent());
            return completeApplying(row);
        } catch (RuntimeException failure) {
            ProjectConventionDraftRow latest = get(row.projectId(), row.id());
            if (!ProjectConventionState.APPLYING.name().equals(latest.state())) return latest;
            return transition(latest, ProjectConventionState.FAILED, "AGENTS_MD_APPLY_RECOVERY_FAILED",
                    latest.proposedContent(), safeMessage(failure));
        }
    }
    private ProjectConventionDraftRow completeApplying(ProjectConventionDraftRow input) {
        ProjectConventionDraftRow current = get(input.projectId(), input.id());
        if (ProjectConventionState.APPLIED.name().equals(current.state())) return current;
        if (!ProjectConventionState.APPLYING.name().equals(current.state())) {
            throw new ConflictException("PROJECT_CONVENTION_APPLY_INTERRUPTED",
                    "AGENTS.md apply state changed before completion could be recorded");
        }
        return transition(current, ProjectConventionState.APPLIED, "APPLIED", current.proposedContent(), null);
    }
    private ProjectConventionDraftRow startCandidate(
            ProjectRow project, ProjectConventionDocumentStore.SourceSnapshot source,
            ProjectStackSnapshot stackProfile) {
        ProjectConventionDraftRow created = candidateDrafts.create(project, source, stackProfile);
        if (!ProjectConventionState.RUNNING.name().equals(created.state())) return created;
        ProjectConventionCandidateWorkflow.Result result = candidateWorkflow.advance(
                new ProjectConventionCandidateWorkflow.Context(created,
                        Path.of(project.rootPath()).toAbsolutePath().normalize(), stackProfile,
                        configuredModel(), false));
        return result.action() == ProjectConventionCandidateWorkflow.Action.LEGACY_FALLBACK
                ? startLegacyReplacement(get(project.id(), created.id()))
                : get(project.id(), created.id());
    }
    private ProjectConventionDraftRow startLegacyReplacement(ProjectConventionDraftRow input) {
        ProjectConventionDraftRow row = get(input.projectId(), input.id());
        ProjectRow project = projects.get(row.projectId());
        ProjectStackSnapshot snapshot = stackPolicy.snapshot(row);
        OpenCodeClient.OpenCodeSession remote;
        try {
            remote = openCode.createSession(Path.of(project.rootPath()),
                    "OpenCode Loopper AGENTS.md Designer (READ_ONLY)", configuredModel(),
                    OpenCodeClient.SessionProfile.PROJECT_CONVENTION_READ_ONLY);
        } catch (RuntimeException failure) {
            return transition(row, ProjectConventionState.FAILED, "LEGACY_SESSION_FAILED", null,
                    safeMessage(failure));
        }
        row = replaceRemote(row, remote.id(), "CREATED",
                "结构化候选能力在派发前不可用，已切换只读兼容会话");
        try {
            openCode.promptAsync(remote, stackPolicy.prompt(project, row.sourceExists() == 1,
                    row.sourceContent(), snapshot));
            return row;
        } catch (RuntimeException failure) {
            return requestStop(row, STOP_POLL_FAILED, safeMessage(failure));
        }
    }
    private ProjectConventionCandidateWorkflow.Context candidateContext(
            ProjectConventionDraftRow row, boolean ownerStopping) {
        ProjectConventionDraftRow current = get(row.projectId(), row.id());
        ProjectRow project = projects.get(row.projectId());
        return new ProjectConventionCandidateWorkflow.Context(current,
                Path.of(project.rootPath()).toAbsolutePath().normalize(), stackPolicy.snapshot(current),
                configuredModel(), ownerStopping);
    }

    private void poll(ProjectConventionDraftRow row) {
        if (!ProjectConventionState.RUNNING.name().equals(row.state())) return;
        OpenCodeClient.SessionStatus status;
        try {
            status = openCode.sessionStatus(session(row));
        } catch (SessionFailure failure) {
            if (recoverToolLoop(row, failure)) return;
            throw failure;
        }
        OpenCodeClient.SessionTranscript transcript = null;
        if (!status.failed()) {
            try {
                transcript = openCode.sessionTranscript(session(row));
                observeProgress(row, status, transcript);
                row = get(row.projectId(), row.id());
            } catch (RuntimeException ignoredActivityFailure) {
                // Status remains authoritative; a later monitor pass can recover activity.
            }
        }
        if (status.retrying()) {
            if (!status.state().equalsIgnoreCase(row.externalSessionState())) {
                transition(row, ProjectConventionState.RUNNING, status.state(), row.proposedContent(), row.errorMessage());
            }
            return;
        }
        if (status.failed()) {
            transition(row, ProjectConventionState.FAILED, safeState(status.state()), null,
                    status.detail() == null || status.detail().isBlank()
                            ? "OpenCode AGENTS.md generation failed: " + safeState(status.state())
                            : safeMessage(status.detail()));
            return;
        }
        if (!status.completed()) return;
        String output = openCode.sessionOutput(session(row));
        ProjectConventionLegacyAdapter.Adapted adapted;
        try {
            adapted = legacyAdapter.adapt(output, row.sourceContent(), stackPolicy.snapshot(row));
        } catch (BadRequestException failure) {
            if (requestProjectContextRepair(row, failure.getMessage())) return;
            throw failure;
        }
        AiOutputExtractor.TextExtractionResult extracted = adapted.extraction();
        String proposed = adapted.compilation().proposedContent();
        String notice = extracted.normalized()
                ? "AI 输出已自动规范化：" + String.join("、", extracted.normalizations())
                : row.normalizationNotice();
        if (extracted.normalized() && row.normalizationNotice() != null) {
            notice = row.normalizationNotice() + "；" + notice;
        }
        if (extracted.normalized()) {
            aiOutputAudit.recordNormalization("PROJECT_CONVENTION", row.id(), "PROJECT_CONVENTION",
                    "GENERATE", extracted.normalizations(), adapted.compilation().projectContextMarkdown());
        }
        transition(row, ProjectConventionState.READY, safeState(status.state()), proposed, null, notice);
    }

    private boolean recoverToolLoop(ProjectConventionDraftRow row, SessionFailure failure) {
        if (!"OPENCODE_MACHINE_TOOL_LOOP".equals(failure.code())
                || !aiOutputAudit.claimToolLoopRecovery("PROJECT_CONVENTION", row.id(),
                "PROJECT_CONVENTION", "GENERATE", failure.getMessage())) return false;
        ProjectRow project = projects.get(row.projectId());
        OpenCodeClient.OpenCodeSession failed = session(row);
        String evidence = boundedToolEvidence(failed);
        try {
            openCode.abortWithConfirmation(failed);
            OpenCodeClient.OpenCodeSession finalizer = openCode.createSession(Path.of(project.rootPath()),
                    "OpenCode Loopper AGENTS.md Designer Finalizer (MCP_ONLY)", configuredModel(),
                    OpenCodeClient.SessionProfile.MACHINE_FINALIZER_NO_TOOLS);
            ProjectConventionDraftRow updated = replaceRemote(row, finalizer.id(), "FINALIZER_RUNNING",
                    "检测到重复工具调用，已启动一次 MCP-only 收口会话");
            ProjectConventionDocumentStore.SourceSnapshot source = new ProjectConventionDocumentStore.SourceSnapshot(updated.sourceExists() == 1, updated.sourceContent(),
                    updated.sourceSha256());
            openCode.promptAsync(finalizer, stackPolicy.prompt(project, source.exists(), source.content(),
                    stackPolicy.snapshot(updated))
                    + "\n\nFINALIZER RECOVERY: Do not call built-in tools. Configured MCP tools remain allowed; return the requested Markdown now."
                    + evidence);
            return true;
        } catch (RuntimeException recoveryFailure) {
            return false;
        }
    }

    private ProjectConventionDraftRow replaceRemote(ProjectConventionDraftRow row, String externalSessionId,
                                                     String externalState, String notice) {
        ProjectConventionDraftRow updated = new ProjectConventionDraftRow(row.id(), row.projectId(), row.state(),
                externalSessionId, externalState, row.sourceExists(), row.sourceSha256(), row.sourceContent(),
                row.proposedContent(), notice, row.errorMessage(), row.createdAt(), now(), row.version(),
                row.projectStackProfileId(), row.stackFingerprint(), row.responseMode(), row.sourceRevision());
        lifecycle.mutateWithoutTransition(() -> mapper.updateProjectConventionProjection(updated),
                () -> new ConflictException("PROJECT_CONVENTION_VERSION_CONFLICT",
                        "AGENTS.md proposal was updated concurrently"));
        ProjectConventionDraftRow replaced = get(row.projectId(), row.id());
        resetRuntime(replaced, "REMOTE:" + externalSessionId);
        return replaced;
    }

    private ProjectConventionDraftRow requestStop(ProjectConventionDraftRow row, String reason, String detail) {
        markStop(row, reason, detail);
        ProjectConventionDraftRow stopping = ProjectConventionState.STOPPING.name().equals(row.state()) ? row
                : transition(row, ProjectConventionState.STOPPING, "STOPPING", row.proposedContent(), null);
        pollStopping(stopping);
        return get(row.projectId(), row.id());
    }

    private void pollStopping(ProjectConventionDraftRow row) {
        if (!ProjectConventionState.STOPPING.name().equals(row.state())) return;
        OpenCodeClient.AbortConfirmation confirmation;
        try {
            confirmation = openCode.abortWithConfirmation(session(row));
        } catch (RuntimeException unconfirmed) {
            return;
        }
        String stoppedState = confirmation == OpenCodeClient.AbortConfirmation.ALREADY_ABSENT
                ? "ALREADY_ABSENT" : "ABORTED";
        ProjectConventionRuntimeRow runtime = ensureRuntime(row);
        if (STOP_USER_CANCEL.equals(runtime.stopReason())) {
            transition(get(row.projectId(), row.id()), ProjectConventionState.CANCELLED,
                    stoppedState, null, runtime.stopDetail());
        } else {
            transition(get(row.projectId(), row.id()), ProjectConventionState.FAILED,
                    stoppedState, null,
                    runtime.stopDetail() == null ? "项目公约生成已停止" : runtime.stopDetail());
        }
    }

    private void observeProgress(ProjectConventionDraftRow row, OpenCodeClient.SessionStatus status,
                                 OpenCodeClient.SessionTranscript transcript) {
        ProjectConventionRuntimeRow runtime = ensureRuntime(row);
        String fingerprint = progressFingerprint(status, transcript);
        if (fingerprint.equals(runtime.progressFingerprint())) return;
        String observed = now();
        ProjectConventionRuntimeRow updated = new ProjectConventionRuntimeRow(runtime.draftId(), observed,
                fingerprint, runtime.stopReason(), runtime.stopDetail(), runtime.createdAt(), observed,
                runtime.version());
        mapper.updateProjectConventionRuntime(updated);
    }

    private String progressFingerprint(OpenCodeClient.SessionStatus status,
                                       OpenCodeClient.SessionTranscript transcript) {
        StringBuilder value = new StringBuilder(safeState(status.state())).append('|')
                .append(transcript.parts().size()).append('|');
        if (!transcript.parts().isEmpty()) {
            OpenCodeClient.SessionPart latest = transcript.parts().getLast();
            value.append(latest.id()).append('|').append(latest.type()).append('|')
                    .append(latest.status()).append('|').append(latest.content());
        }
        ModelTokenUsageProjectionService.Snapshot usage =
                ModelTokenUsageProjectionService.snapshot(transcript.usage());
        value.append('|').append(usage.totalTokens());
        return sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private ProjectConventionRuntimeRow ensureRuntime(ProjectConventionDraftRow row) {
        ProjectConventionRuntimeRow runtime = mapper.findProjectConventionRuntime(row.id()).orElse(null);
        if (runtime != null) return runtime;
        ProjectConventionRuntimeRow created = new ProjectConventionRuntimeRow(row.id(), row.updatedAt(),
                "RECOVERED:" + (row.externalSessionId() == null ? "NONE" : row.externalSessionId()),
                null, null, row.createdAt(), now(), 0);
        try { mapper.insertProjectConventionRuntime(created); }
        catch (RuntimeException ignoredConcurrentInsert) { }
        return mapper.findProjectConventionRuntime(row.id()).orElse(created);
    }

    private void markStop(ProjectConventionDraftRow row, String reason, String detail) {
        ProjectConventionRuntimeRow runtime = ensureRuntime(row);
        ProjectConventionRuntimeRow updated = new ProjectConventionRuntimeRow(runtime.draftId(),
                runtime.lastProgressAt(), runtime.progressFingerprint(), reason, safeMessage(detail),
                runtime.createdAt(), now(), runtime.version());
        if (mapper.updateProjectConventionRuntime(updated) != 1) {
            throw new ConflictException("PROJECT_CONVENTION_RUNTIME_CONFLICT",
                    "项目公约停止状态被并发更新，请重试");
        }
    }

    private void resetRuntime(ProjectConventionDraftRow row, String fingerprint) {
        ProjectConventionRuntimeRow runtime = ensureRuntime(row);
        String observed = now();
        mapper.updateProjectConventionRuntime(new ProjectConventionRuntimeRow(runtime.draftId(), observed,
                fingerprint, null, null, runtime.createdAt(), observed, runtime.version()));
    }

    private String boundedToolEvidence(OpenCodeClient.OpenCodeSession remote) {
        java.util.LinkedHashSet<String> evidence = new java.util.LinkedHashSet<>();
        try {
            for (OpenCodeClient.SessionPart part : openCode.sessionTranscript(remote).parts()) {
                if (!"TOOL".equals(part.type())) continue;
                String content = part.content() == null ? "completed" : part.content();
                evidence.add("- " + (part.label() == null ? "tool" : part.label()) + ": "
                        + content.substring(0, Math.min(content.length(), 800)));
                if (evidence.size() >= 12) break;
            }
        } catch (RuntimeException ignored) { }
        return "\nBounded prior tool evidence:\n" + (evidence.isEmpty()
                ? "- No reusable tool evidence was available." : String.join("\n", evidence));
    }

    private boolean requestProjectContextRepair(ProjectConventionDraftRow row, String validationError) {
        int attempt = projectContextRepairAttempt(row.externalSessionState()) + 1;
        if (attempt > MAX_PROJECT_CONTEXT_REPAIR_ATTEMPTS) return false;
        transition(row, ProjectConventionState.RUNNING, PROJECT_CONTEXT_REPAIR_STATE + attempt, null, null);
        openCode.promptAsync(session(row), projectContextRepairPrompt(validationError));
        return true;
    }

    private int projectContextRepairAttempt(String externalState) {
        if (externalState == null || !externalState.startsWith(PROJECT_CONTEXT_REPAIR_STATE)) return 0;
        try { return Integer.parseInt(externalState.substring(PROJECT_CONTEXT_REPAIR_STATE.length())); }
        catch (NumberFormatException ignored) { return MAX_PROJECT_CONTEXT_REPAIR_ATTEMPTS; }
    }

    private String projectContextRepairPrompt(String validationError) {
        return """
                Your previous response was rejected by the Loopper project-context validator.
                This is a protocol-repair turn only. Do not inspect more files, call repository tools, edit files, run commands, create tasks, or discuss implementation.

                Reformat the evidence-backed project context already collected in this read-only session. Do not add unverified facts, generic safety rules, secrets, credentials, personal data, or large source excerpts.

                Validation error:
                %s

                Prefer concise Chinese Markdown between exactly one pair of these markers. A single Markdown fence or a non-empty plain Markdown response is also accepted:
                <!-- LOOPPER_PROJECT_CONTEXT_START -->
                ## 技术栈与模块
                ...
                ## 构建与测试
                ...
                ## 目录与边界
                ...
                <!-- LOOPPER_PROJECT_CONTEXT_END -->
                """.formatted(safeMessage(validationError));
    }

    private OpenCodeClient.OpenCodeSession session(ProjectConventionDraftRow row) {
        ProjectRow project = projects.get(row.projectId());
        return new OpenCodeClient.OpenCodeSession(row.externalSessionId(), Path.of(project.rootPath()));
    }

    private ProjectConventionDraftRow transition(ProjectConventionDraftRow row, ProjectConventionState state, String externalState,
                                                 String proposedContent, String errorMessage) {
        return transition(row, state, externalState, proposedContent, errorMessage, row.normalizationNotice());
    }

    private ProjectConventionDraftRow transition(ProjectConventionDraftRow row, ProjectConventionState state,
                                                  String externalState, String proposedContent, String errorMessage,
                                                  String normalizationNotice) {
        ProjectConventionDraftRow updated = new ProjectConventionDraftRow(row.id(), row.projectId(), state.name(),
                row.externalSessionId(), externalState, row.sourceExists(), row.sourceSha256(), row.sourceContent(),
                proposedContent, normalizationNotice, errorMessage, row.createdAt(), now(), row.version(),
                row.projectStackProfileId(), row.stackFingerprint(), row.responseMode(), row.sourceRevision());
        if (row.state().equals(updated.state())) {
            lifecycle.mutateWithoutTransition(() -> mapper.updateProjectConventionProjection(updated),
                    () -> new ConflictException("PROJECT_CONVENTION_VERSION_CONFLICT", "AGENTS.md proposal was updated concurrently"));
        } else {
            lifecycle.transition(subject(updated), row.state(), updated.state(), null, java.util.Map.of(),
                    () -> mapper.updateProjectConventionDraft(updated),
                    () -> new ConflictException("PROJECT_CONVENTION_VERSION_CONFLICT", "AGENTS.md proposal was updated concurrently"));
        }
        return get(row.projectId(), row.id());
    }

    private LifecycleTransitionService.Subject subject(ProjectConventionDraftRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.PROJECT_CONVENTION, row.id(),
                LifecycleScopeType.PROJECT, row.projectId());
    }

    private OpenCodeClient.OpenCodeModel configuredModel() {
        String configured = properties.getOpenCode().getModel();
        if (configured == null) return null;
        String value = configured.trim();
        int separator = value.indexOf('/');
        if (separator <= 0 || separator >= value.length() - 1) return null;
        String provider = value.substring(0, separator).trim();
        String model = value.substring(separator + 1).trim();
        return provider.isEmpty() || model.isEmpty() ? null : new OpenCodeClient.OpenCodeModel(provider, model, null);
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    private static String safeState(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String safeMessage(Throwable failure) { return safeMessage(failure.getMessage()); }
    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "Unknown AGENTS.md generation failure";
        String normalized = message.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 500));
    }
    private static String now() { return Instant.now().toString(); }
    public record CurrentConvention(String projectId, boolean exists, boolean loopperManaged, String content) { }
}
