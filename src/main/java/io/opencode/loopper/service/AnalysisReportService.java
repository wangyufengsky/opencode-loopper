package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.persistence.AnalysisReportRow;
import io.opencode.loopper.persistence.DesignDiscussionRevisionRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.DesignerTaskProfileRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeStructuredSchemas;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class AnalysisReportService {
    private static final String REVIEWER_CONTRACT = "REVIEWER_REPORT_V1";
    private static final String REVIEWER_START = "<!-- REVIEWER_REPORT_JSON_START -->";
    private static final String REVIEWER_END = "<!-- REVIEWER_REPORT_JSON_END -->";
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final ObjectMapper json;
    private final OpenCodeClient openCode;
    private final LoopperProperties properties;
    private final RolePromptComposer prompts;
    private final DesignerAttachmentContext attachmentContext;
    private final ReviewerReportCompilation compilation;
    private final ReviewerReportLiveSourceAdapter liveSources;
    private final ReviewerReportCandidateWorkflow candidateWorkflow;

    public AnalysisReportService(LoopperMapper mapper, ProjectService projects, ObjectMapper json,
                                 OpenCodeClient openCode, LoopperProperties properties,
                                 RolePromptComposer prompts, DesignerAttachmentContext attachmentContext,
                                 ReviewerReportCompilation compilation,
                                 ReviewerReportLiveSourceAdapter liveSources,
                                 ReviewerReportCandidateWorkflow candidateWorkflow) {
        this.mapper = mapper; this.projects = projects; this.json = json; this.openCode = openCode;
        this.properties = properties; this.prompts = prompts; this.attachmentContext = attachmentContext;
        this.compilation = compilation; this.liveSources = liveSources;
        this.candidateWorkflow = candidateWorkflow;
    }

    /** Starts an independent Reviewer. It creates no Task, branch, lease, Attempt, or writable Session. */
    public View startReviewer(String sessionId) {
        DesignerSessionRow session = session(sessionId);
        DesignerTaskProfileRow profileRow = mapper.findCurrentDesignerTaskProfile(sessionId)
                .orElseThrow(() -> new ConflictException("TASK_PROFILE_MISSING", "只读报告缺少冻结任务画像"));
        TaskProfileService.View profile = profile(profileRow);
        if (!"FROZEN".equals(profile.state()) || !"READ_ONLY_REPORT".equals(profile.executionStrategy().name())) {
            throw new ConflictException("REPORT_PROFILE_INVALID", "当前任务画像不是已冻结的只读报告流程");
        }
        DesignDiscussionRevisionRow discussion = mapper.findLatestDesignDiscussionRevision(sessionId, "REQUIREMENT")
                .orElseThrow(() -> new ConflictException("REPORT_CONTENT_MISSING", "只读评审需求稿尚未形成"));
        if (discussion.snapshotMarkdown() == null || discussion.snapshotMarkdown().isBlank()) {
            throw new ConflictException("REPORT_CONTENT_MISSING", "只读评审需求稿尚未形成");
        }
        Path root = Path.of(projects.get(session.projectId()).rootPath()).toAbsolutePath().normalize();
        OpenCodeClient.OpenCodeModel model = configuredModel();
        boolean candidate = properties.getInternalCandidate().isReviewerReportV1Enabled();
        boolean schema = !candidate && legacySchema(model);
        String responseMode = candidate ? ReviewerReportCandidateWorkflow.RESPONSE_MODE
                : schema ? "JSON_SCHEMA" : "TEXT_MARKER";
        String now = Instant.now().toString();
        AnalysisReportRow row = new AnalysisReportRow(UUID.randomUUID().toString(), sessionId, profile.id(),
                "RUNNING", "只读分析报告", "", "[]", null, null, null, null, now, now, 0,
                null, "PENDING", discussion.snapshotMarkdown(), profile.rolePackId(), profile.rolePackVersion(),
                REVIEWER_CONTRACT, responseMode, "[]", Instant.now().plusSeconds(120).toString(),
                discussion.revision());
        if (mapper.insertAnalysisReport(row) != 1) throw new ConflictException("REPORT_CREATE_CONFLICT", "报告无法持久化");
        try {
            if (candidate) {
                ReviewerReportCandidateWorkflow.Result result = candidateWorkflow.advance(
                        candidateContext(row, session, profile, root, discussion.revision(), false));
                if (result.action() == ReviewerReportCandidateWorkflow.Action.LEGACY_FALLBACK) {
                    AnalysisReportRow legacy = changeResponseMode(row, legacySchema(model)
                            ? "JSON_SCHEMA" : "TEXT_MARKER");
                    return startLegacy(session, profile, root, model, legacy);
                }
                if (result.action() == ReviewerReportCandidateWorkflow.Action.FAILED) {
                    throw new ServiceUnavailableException(result.code(), result.detail());
                }
                return view(session, mapper.findAnalysisReport(session.id(), row.id()).orElseThrow());
            }
            return startLegacy(session, profile, root, model, row);
        } catch (RuntimeException failure) {
            AnalysisReportRow current = mapper.findAnalysisReport(session.id(), row.id()).orElse(row);
            if (candidateWorkflow.owns(current)
                    && Set.of("RUNNING", "VALIDATING").contains(current.state())) {
                return view(session, current);
            }
            if ("FAILED".equals(current.state())) throw failure;
            update(row, "FAILED", "只读分析报告", "", List.of(), null, null,
                    "REVIEWER_START_FAILED", safeMessage(failure.getMessage()), null, "FAILED");
            throw new ServiceUnavailableException("REVIEWER_START_FAILED", safeMessage(failure.getMessage()));
        }
    }

    public View generateFromDesignerSnapshot(String sessionId) { return startReviewer(sessionId); }

    public List<PollResult> pollActive() {
        List<PollResult> results = new ArrayList<>();
        for (AnalysisReportRow row : mapper.activeAnalysisReports()) {
            DesignerSessionRow owner = session(row.designerSessionId());
            boolean candidate = candidateWorkflow.owns(row);
            if (!candidate && ("STOPPING".equals(owner.state()) || "CANCELLED".equals(owner.state()))) continue;
            try { PollResult result = poll(row); if (result != null) results.add(result); }
            catch (RuntimeException failure) {
                if (candidate) continue;
                AnalysisReportRow latest = mapper.findAnalysisReport(row.designerSessionId(), row.id()).orElse(row);
                update(latest, "FAILED", latest.title(), latest.markdown(), readEvidence(latest.evidenceJson()),
                        latest.contentSha256(), latest.sourceSnapshotSha256(), "REVIEWER_POLL_FAILED",
                        safeMessage(failure.getMessage()), latest.externalSessionId(), "FAILED");
                results.add(new PollResult(row.designerSessionId(), row.id(), false,
                        "REVIEWER_POLL_FAILED", safeMessage(failure.getMessage())));
            }
        }
        return List.copyOf(results);
    }

    private PollResult poll(AnalysisReportRow row) {
        DesignerSessionRow session = session(row.designerSessionId());
        Path root = Path.of(projects.get(session.projectId()).rootPath()).toAbsolutePath().normalize();
        if (candidateWorkflow.owns(row)) {
            DesignerTaskProfileRow profileRow = mapper.findDesignerTaskProfile(row.taskProfileId())
                    .orElseThrow(() -> new ConflictException("TASK_PROFILE_MISSING",
                            "只读报告缺少冻结任务画像"));
            ReviewerReportCandidateWorkflow.Result result = candidateWorkflow.advance(
                    candidateContext(row, session, profile(profileRow), root,
                            requireSourceRevision(row),
                            "STOPPING".equals(session.state()) || "CANCELLED".equals(session.state())));
            if (result.action() == ReviewerReportCandidateWorkflow.Action.LEGACY_FALLBACK) {
                AnalysisReportRow legacy = changeResponseMode(
                        mapper.findAnalysisReport(row.designerSessionId(), row.id()).orElseThrow(),
                        legacySchema(configuredModel()) ? "JSON_SCHEMA" : "TEXT_MARKER");
                startLegacy(session, profile(profileRow), root, configuredModel(), legacy);
                return null;
            }
            if (result.action() == ReviewerReportCandidateWorkflow.Action.READY) {
                return new PollResult(row.designerSessionId(), row.id(), true, null, null);
            }
            if (result.action() == ReviewerReportCandidateWorkflow.Action.FAILED) {
                return new PollResult(row.designerSessionId(), row.id(), false,
                        result.code(), result.detail());
            }
            return null;
        }
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(row.externalSessionId(), root);
        if (row.deadlineAt() != null && StoryAccountingClock.sessionNow(mapper, row.externalSessionId(), row.createdAt()).isAfter(Instant.parse(row.deadlineAt()))) {
            try { openCode.abort(remote); } catch (Exception ignored) { }
            update(row, "FAILED", row.title(), row.markdown(), readEvidence(row.evidenceJson()), null, null,
                    "REVIEWER_TIMEOUT", "Independent Reviewer exceeded its 120 second boundary", remote.id(), "FAILED");
            return new PollResult(row.designerSessionId(), row.id(), false, "REVIEWER_TIMEOUT",
                    "Independent Reviewer exceeded its 120 second boundary");
        }
        OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
        if (status.retrying()) {
            update(row, row.state(), row.title(), row.markdown(), readEvidence(row.evidenceJson()),
                    row.contentSha256(), row.sourceSnapshotSha256(), null, null, remote.id(), status.state());
            return null;
        }
        if (status.failed()) {
            update(row, "FAILED", row.title(), row.markdown(), readEvidence(row.evidenceJson()), null, null,
                    "REVIEWER_SESSION_FAILED", safeMessage(status.detail()), remote.id(), status.state());
            return new PollResult(row.designerSessionId(), row.id(), false,
                    "REVIEWER_SESSION_FAILED", safeMessage(status.detail()));
        }
        if (!status.completed()) return null;
        AnalysisReportRow validating = update(row, "VALIDATING", row.title(), row.markdown(), List.of(), null, null,
                null, null, remote.id(), status.state());
        ReviewerReportCompilation.Candidate candidate;
        try { candidate = reviewerResult(row, remote); }
        catch (RuntimeException failure) {
            update(validating, "FAILED", row.title(), "", List.of(), null, null,
                    "REVIEWER_CONTRACT_INVALID", safeMessage(failure.getMessage()), remote.id(), status.state());
            return new PollResult(row.designerSessionId(), row.id(), false,
                    "REVIEWER_CONTRACT_INVALID", safeMessage(failure.getMessage()));
        }
        ReviewerReportCompilation.Result compiled = compilation.compile(new ReviewerReportCompilation.Input(
                candidate, liveSources.capture(root, candidate.findings())));
        if (!compiled.accepted()) {
            ReviewerReportCompilation.Problem problem = compiled.problems().getFirst();
            boolean evidenceFailure = compiled.problems().stream().anyMatch(item ->
                    item.code().contains("EVIDENCE") || item.code().contains("SOURCE_MANIFEST"));
            String code = evidenceFailure ? "REPORT_EVIDENCE_INVALID" : problem.code();
            update(validating, "FAILED", row.title(), "", List.of(), null, null,
                    code, safeMessage(problem.staticDetail()), remote.id(), status.state());
            return new PollResult(row.designerSessionId(), row.id(), false, code,
                    safeMessage(problem.staticDetail()));
        }
        List<Evidence> evidence = compiled.evidence().stream()
                .map(item -> new Evidence(item.path(), item.line(), item.sha256())).toList();
        updateReady(validating, candidate, compiled, evidence, remote.id(), status.state());
        return new PollResult(row.designerSessionId(), row.id(), true, null, null);
    }

    private ReviewerReportCompilation.Candidate reviewerResult(
            AnalysisReportRow row, OpenCodeClient.OpenCodeSession remote) {
        try {
            String value;
            if ("JSON_SCHEMA".equals(row.responseMode())) {
                OpenCodeClient.SessionResult result = openCode.sessionResult(remote);
                if (result.structuredRetryCount() != 0 || !result.hasStructured()) {
                    throw new IllegalArgumentException("Reviewer structured payload is missing: " + safeMessage(result.errorDetail()));
                }
                value = json.writeValueAsString(result.structured());
            } else {
                value = openCode.sessionOutput(remote);
                int start = value == null ? -1 : value.indexOf(REVIEWER_START);
                int end = value == null ? -1 : value.indexOf(REVIEWER_END);
                if (start < 0 || end <= start) throw new IllegalArgumentException("Reviewer marker payload is missing");
                value = value.substring(start + REVIEWER_START.length(), end).trim();
            }
            Map<String, Object> raw = json.readValue(value, new TypeReference<>() { });
            String title = bounded(raw.get("title"), 200, "title");
            String summary = bounded(raw.get("summary"), 8000, "summary");
            List<ReviewerReportCompilation.Finding> findings = new ArrayList<>();
            if (!(raw.get("findings") instanceof List<?> values) || values.size() > 128) {
                throw new IllegalArgumentException("Reviewer findings must contain 0-128 items");
            }
            for (Object item : values) {
                if (!(item instanceof Map<?, ?> finding)) throw new IllegalArgumentException("Reviewer finding must be an object");
                String severity = bounded(finding.get("severity"), 16, "severity");
                if (!List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO").contains(severity)) {
                    throw new IllegalArgumentException("Reviewer severity is invalid");
                }
                int line = finding.get("line") instanceof Number number ? number.intValue() : -1;
                if (line < 1 || line > 10_000_000) throw new IllegalArgumentException("Reviewer line is invalid");
                findings.add(new ReviewerReportCompilation.Finding(
                        severity, bounded(finding.get("title"), 300, "finding title"),
                        bounded(finding.get("detail"), 4000, "finding detail"),
                        bounded(finding.get("path"), 1024, "finding path"), line,
                        bounded(finding.get("recommendation"), 4000, "recommendation")));
            }
            List<String> limitations = strings(raw.get("limitations"), 32, 2000);
            return new ReviewerReportCompilation.Candidate(title, summary, List.copyOf(findings), limitations);
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException(failure.getMessage(), failure); }
    }

    public View get(String sessionId, String reportId) {
        return view(session(sessionId), mapper.findAnalysisReport(sessionId, reportId)
                .orElseThrow(() -> new NotFoundException("Analysis report not found: " + reportId)));
    }
    public List<Summary> list(String sessionId) {
        DesignerSessionRow session = session(sessionId);
        return mapper.listAnalysisReports(sessionId).stream().map(row -> {
            View view = view(session, row);
            return new Summary(row.id(), row.state(), row.title(), row.contentSha256(), view.stale(), row.updatedAt());
        }).toList();
    }

    private AnalysisReportRow update(AnalysisReportRow row, String state, String title, String markdown,
                                     List<Evidence> evidence, String contentHash, String sourceHash,
                                     String errorCode, String errorDetail, String externalId, String externalState) {
        AnalysisReportRow updated = new AnalysisReportRow(row.id(), row.designerSessionId(), row.taskProfileId(), state,
                title, markdown, write(evidence), contentHash, sourceHash, errorCode, errorDetail, row.createdAt(),
                Instant.now().toString(), row.version(), externalId, externalState, row.sourceRequirement(),
                row.rolePackId(), row.rolePackVersion(), row.reviewerContractVersion(), row.responseMode(),
                row.findingsJson(), row.deadlineAt(), row.sourceRequirementRevision());
        if (mapper.updateAnalysisReport(updated) != 1) throw new ConflictException("REPORT_VERSION_CONFLICT", "报告状态已并发变化");
        return mapper.findAnalysisReport(row.designerSessionId(), row.id()).orElseThrow();
    }

    private AnalysisReportRow updateReady(AnalysisReportRow row, ReviewerReportCompilation.Candidate candidate,
                                          ReviewerReportCompilation.Result compiled, List<Evidence> evidence,
                                          String externalId, String externalState) {
        AnalysisReportRow updated = new AnalysisReportRow(row.id(), row.designerSessionId(), row.taskProfileId(),
                "READY", candidate.title(), compiled.markdown(), write(evidence), compiled.contentSha256(),
                compiled.sourceSnapshotSha256(), null, null,
                row.createdAt(), Instant.now().toString(), row.version(), externalId, externalState,
                row.sourceRequirement(), row.rolePackId(), row.rolePackVersion(), row.reviewerContractVersion(),
                row.responseMode(), compiled.canonicalFindingsJson(), row.deadlineAt(),
                row.sourceRequirementRevision());
        if (mapper.updateAnalysisReport(updated) != 1) throw new ConflictException("REPORT_VERSION_CONFLICT", "报告状态已并发变化");
        return mapper.findAnalysisReport(row.designerSessionId(), row.id()).orElseThrow();
    }

    private View view(DesignerSessionRow session, AnalysisReportRow row) {
        List<Evidence> evidence = readEvidence(row.evidenceJson());
        Path root = Path.of(projects.get(session.projectId()).rootPath()).toAbsolutePath().normalize();
        List<EvidenceView> views = evidence.stream().map(item -> {
            boolean stale = true;
            try { Path file = safe(root, item.path()); stale = !Files.isRegularFile(file) || !sha256(Files.readAllBytes(file)).equals(item.sha256()); }
            catch (Exception ignored) { }
            return new EvidenceView(item.path(), item.line(), item.sha256(), stale);
        }).toList();
        return new View(row.id(), row.state(), row.title(), row.markdown(), row.contentSha256(),
                row.sourceSnapshotSha256(), views, views.stream().anyMatch(EvidenceView::stale),
                row.errorCode(), row.errorDetail(), row.createdAt(), row.updatedAt(), row.reviewerContractVersion(),
                readFindings(row.findingsJson()));
    }

    private static Path safe(Path root, String relative) throws Exception {
        Path input = Path.of(relative); if (input.isAbsolute()) throw new IllegalArgumentException();
        Path file = root.resolve(input).normalize(); if (!file.startsWith(root) || Files.isSymbolicLink(file)) throw new IllegalArgumentException();
        Path parent = Files.exists(file) ? file.toRealPath() : file.getParent().toRealPath();
        if (!parent.startsWith(root.toRealPath())) throw new IllegalArgumentException(); return file;
    }
    private String reviewerPrompt(TaskProfileService.View profile, Path root, String requirement, boolean schema) {
        return prompts.reviewerInstructions(profile) + "\n\nProject root: " + root + "\nFrozen review requirement:\n"
                + requirement + "\n\nREVIEWER_REPORT_JSON contract: return title, summary, 0-128 findings, and limitations. "
                + "Every finding contains severity, title, detail, managed relative path, exact line, and recommendation."
                + (schema ? "" : "\n" + REVIEWER_START + "\n{\"title\":\"...\",\"summary\":\"...\",\"findings\":[],\"limitations\":[]}\n" + REVIEWER_END);
    }

    private View startLegacy(DesignerSessionRow session, TaskProfileService.View profile, Path root,
                             OpenCodeClient.OpenCodeModel model, AnalysisReportRow input) {
        AnalysisReportRow row = mapper.findAnalysisReport(session.id(), input.id()).orElseThrow();
        boolean schema = "JSON_SCHEMA".equals(row.responseMode());
        OpenCodeClient.OpenCodeSession remote = openCode.createSession(root,
                "OpenCode Loopper Independent Reviewer (READ_ONLY)", model,
                OpenCodeClient.SessionProfile.REVIEWER_READ_ONLY);
        String prompt = reviewerPrompt(profile, root, row.sourceRequirement(), schema);
        OpenCodeClient.PromptRequest request = schema
                ? new OpenCodeClient.PromptRequest(prompt, null, null,
                OpenCodeStructuredSchemas.format(OpenCodeStructuredSchemas.REVIEWER_REPORT_V1))
                : OpenCodeClient.PromptRequest.text(prompt);
        request = attachmentContext.withContext(
                DesignerAttachmentContext.ContextUse.requirement(session.id()), request);
        openCode.promptAsync(remote, request);
        return view(session, update(row, "RUNNING", "只读分析报告", "", List.of(), null, null,
                null, null, remote.id(), "RUNNING"));
    }

    private AnalysisReportRow changeResponseMode(AnalysisReportRow row, String responseMode) {
        AnalysisReportRow updated = new AnalysisReportRow(
                row.id(), row.designerSessionId(), row.taskProfileId(), row.state(), row.title(),
                row.markdown(), row.evidenceJson(), row.contentSha256(), row.sourceSnapshotSha256(),
                row.errorCode(), row.errorDetail(), row.createdAt(), Instant.now().toString(), row.version(),
                row.externalSessionId(), row.externalSessionState(), row.sourceRequirement(), row.rolePackId(),
                row.rolePackVersion(), row.reviewerContractVersion(), responseMode, row.findingsJson(),
                row.deadlineAt(), row.sourceRequirementRevision());
        if (mapper.updateAnalysisReport(updated) != 1) {
            throw new ConflictException("REPORT_VERSION_CONFLICT", "报告状态已并发变化");
        }
        return mapper.findAnalysisReport(row.designerSessionId(), row.id()).orElseThrow();
    }

    private ReviewerReportCandidateWorkflow.Context candidateContext(
            AnalysisReportRow row, DesignerSessionRow session, TaskProfileService.View profile,
            Path root, long sourceRevision, boolean ownerStopping) {
        AnalysisReportRow current = mapper.findAnalysisReport(session.id(), row.id()).orElse(row);
        return new ReviewerReportCandidateWorkflow.Context(
                current, root, sourceRevision, configuredModel(), prompts.reviewerInstructions(profile),
                current.sourceRequirement(), ownerStopping);
    }

    private boolean legacySchema(OpenCodeClient.OpenCodeModel model) {
        OpenCodeClient.StructuredOutputCapability capability = openCode.structuredOutputCapability(model);
        return capability.transport() != OpenCodeClient.CapabilityState.UNAVAILABLE
                && capability.selectedModel() != OpenCodeClient.CapabilityState.UNAVAILABLE;
    }

    private static long requireSourceRevision(AnalysisReportRow row) {
        if (row.sourceRequirementRevision() == null || row.sourceRequirementRevision() < 0) {
            throw new ConflictException("REVIEWER_SOURCE_REVISION_MISSING",
                    "Reviewer candidate report lacks its frozen requirement revision");
        }
        return row.sourceRequirementRevision();
    }

    private static String bounded(Object value, int max, String field) {
        String text = value == null ? "" : String.valueOf(value).strip();
        if (text.isBlank() || text.length() > max) throw new IllegalArgumentException("Reviewer " + field + " is invalid");
        return text;
    }
    private static List<String> strings(Object raw, int maxItems, int maxLength) {
        if (!(raw instanceof List<?> values) || values.size() > maxItems) throw new IllegalArgumentException("Reviewer limitations are invalid");
        List<String> result = new ArrayList<>();
        for (Object value : values) result.add(bounded(value, maxLength, "limitation"));
        return List.copyOf(result);
    }
    private List<ReviewerFinding> readFindings(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (Exception ignored) { return List.of(); }
    }
    private OpenCodeClient.OpenCodeModel configuredModel() {
        String value = properties.getOpenCode().getModel(); if (value == null) return null;
        int split = value.indexOf('/'); if (split <= 0 || split == value.length()-1) return null;
        return new OpenCodeClient.OpenCodeModel(value.substring(0,split).trim(), value.substring(split+1).trim(), null);
    }
    private TaskProfileService.View profile(DesignerTaskProfileRow row) {
        List<io.opencode.loopper.domain.ArtifactKind> artifacts;
        List<String> technologies;
        try { artifacts = json.readValue(row.artifactKindsJson(), new TypeReference<>() { }); } catch (Exception e) { artifacts = List.of(); }
        try { technologies = json.readValue(row.technologiesJson(), new TypeReference<>() { }); } catch (Exception e) { technologies = List.of(); }
        String decisionState = "FROZEN".equals(row.state()) ? "FROZEN"
                : row.decisionRequired() == 1 ? "NEEDS_CONFIRMATION" : "CONFIRMED";
        return new TaskProfileService.View(row.id(), row.state(), decisionState,
                "FROZEN".equals(row.state()) || row.decisionRequired() == 0, TaskIntent.valueOf(row.intent()),
                io.opencode.loopper.domain.WorkflowTemplate.valueOf(row.workflowTemplate()),
                io.opencode.loopper.domain.MutationMode.valueOf(row.mutationMode()), artifacts, technologies,
                io.opencode.loopper.domain.TestPolicy.valueOf(row.testPolicy()),
                io.opencode.loopper.domain.ExecutionStrategy.valueOf(row.executionStrategy()), row.rolePackId(),
                row.rolePackVersion(), row.confidence(),
                TaskProfileConfidence.available(row.resolutionSource(), row.confidence(), List.of()),
                List.of(), row.resolutionSource(), row.decisionRequired()==1,
                TaskIntent.SOFTWARE_CHANGE.name().equals(row.intent())
                        && io.opencode.loopper.domain.WorkflowTemplate.FULL_PACKAGE_DESIGN.name().equals(row.workflowTemplate()),
                null, row.version(), row.projectStackProfileId(), row.stackFingerprint(),
                readStringList(row.componentKeysJson()), List.of(),
                row.projectStackProfileId() == null ? "UNANALYZED" : "FROZEN", false);
    }
    private List<String> readStringList(String value) { try { return json.readValue(value, new TypeReference<>() { }); } catch (Exception ignored) { return List.of(); } }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception failure) { throw new IllegalStateException(failure); } }
    private List<Evidence> readEvidence(String value) { try { return json.readValue(value, new TypeReference<>() { }); } catch (Exception ignored) { return List.of(); } }
    private DesignerSessionRow session(String id) { return mapper.findDesignerSession(id).orElseThrow(() -> new NotFoundException("Designer session not found: " + id)); }
    private static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception failure) { throw new IllegalStateException(failure); } }
    private static String safeMessage(String value) { return value == null || value.isBlank() ? "unknown Reviewer failure" : value.substring(0, Math.min(2000, value.length())); }

    public record PollResult(String designerSessionId, String reportId, boolean ready, String errorCode, String errorDetail) { }
    public record Evidence(String path, int line, String sha256) { }
    public record EvidenceView(String path, int line, String sha256, boolean stale) { }
    public record View(String id, String state, String title, String markdown, String contentSha256,
                       String sourceSnapshotSha256, List<EvidenceView> evidence, boolean stale,
                       String errorCode, String errorDetail, String createdAt, String updatedAt,
                       String reviewerContractVersion, List<ReviewerFinding> findings) { }
    public record ReviewerFinding(String severity, String title, String detail, String path, int line,
                                  String recommendation) { }
    public record Summary(String id, String state, String title, String contentSha256, boolean stale, String updatedAt) { }
}
