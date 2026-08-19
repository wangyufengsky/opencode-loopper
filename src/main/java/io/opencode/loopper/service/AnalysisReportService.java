package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.persistence.AnalysisReportRow;
import io.opencode.loopper.persistence.DesignDiscussionRevisionRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.DesignerTaskProfileRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class AnalysisReportService {
    private static final Pattern LOCATION = Pattern.compile("(?m)(?<![A-Za-z0-9_./-])([A-Za-z0-9_./-]+\\.[A-Za-z0-9_-]{1,12}):(\\d{1,7})");
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final ObjectMapper json;
    private final OpenCodeClient openCode;
    private final LoopperProperties properties;
    private final RolePromptComposer prompts;

    public AnalysisReportService(LoopperMapper mapper, ProjectService projects, ObjectMapper json,
                                 OpenCodeClient openCode, LoopperProperties properties,
                                 RolePromptComposer prompts) {
        this.mapper = mapper; this.projects = projects; this.json = json; this.openCode = openCode;
        this.properties = properties; this.prompts = prompts;
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
        String now = Instant.now().toString();
        AnalysisReportRow row = new AnalysisReportRow(UUID.randomUUID().toString(), sessionId, profile.id(),
                "RUNNING", "只读分析报告", "", "[]", null, null, null, null, now, now, 0,
                null, "PENDING", discussion.snapshotMarkdown(), profile.rolePackId(), profile.rolePackVersion());
        if (mapper.insertAnalysisReport(row) != 1) throw new ConflictException("REPORT_CREATE_CONFLICT", "报告无法持久化");
        try {
            OpenCodeClient.OpenCodeSession remote = openCode.createSession(root,
                    "OpenCode Loopper Independent Reviewer (READ_ONLY)", configuredModel(),
                    OpenCodeClient.SessionProfile.REVIEWER_READ_ONLY);
            openCode.promptAsync(remote, reviewerPrompt(profile, root, discussion.snapshotMarkdown()));
            return view(session, update(row, "RUNNING", "只读分析报告", "", List.of(), null, null,
                    null, null, remote.id(), "RUNNING"));
        } catch (RuntimeException failure) {
            update(row, "FAILED", "只读分析报告", "", List.of(), null, null,
                    "REVIEWER_START_FAILED", safeMessage(failure.getMessage()), null, "FAILED");
            throw new ServiceUnavailableException("REVIEWER_START_FAILED", safeMessage(failure.getMessage()));
        }
    }

    public View generateFromDesignerSnapshot(String sessionId) { return startReviewer(sessionId); }

    public List<PollResult> pollActive() {
        List<PollResult> results = new ArrayList<>();
        for (AnalysisReportRow row : mapper.activeAnalysisReports()) {
            try { PollResult result = poll(row); if (result != null) results.add(result); }
            catch (RuntimeException failure) {
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
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(row.externalSessionId(), root);
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
        String markdown = openCode.sessionOutput(remote);
        if (markdown == null || markdown.isBlank() || markdown.getBytes(StandardCharsets.UTF_8).length > 65_536) {
            update(validating, "FAILED", row.title(), "", List.of(), null, null,
                    "REPORT_CONTENT_INVALID", "Reviewer report must be 1-64 KiB Markdown", remote.id(), status.state());
            return new PollResult(row.designerSessionId(), row.id(), false,
                    "REPORT_CONTENT_INVALID", "Reviewer report must be 1-64 KiB Markdown");
        }
        List<Evidence> evidence = evidence(root, markdown);
        if (evidence.isEmpty()) {
            update(validating, "FAILED", reportTitle(markdown), markdown, evidence, null, null,
                    "REPORT_EVIDENCE_REQUIRED", "Reviewer report contains no valid managed path:line evidence",
                    remote.id(), status.state());
            return new PollResult(row.designerSessionId(), row.id(), false, "REPORT_EVIDENCE_REQUIRED",
                    "Reviewer report contains no valid managed path:line evidence");
        }
        String contentHash = sha256(markdown.getBytes(StandardCharsets.UTF_8));
        String sourceHash = sha256(evidence.stream().map(item -> item.path() + ":" + item.sha256())
                .sorted().reduce("", (left, right) -> left + "\n" + right).getBytes(StandardCharsets.UTF_8));
        update(validating, "READY", reportTitle(markdown), markdown, evidence, contentHash, sourceHash,
                null, null, remote.id(), status.state());
        return new PollResult(row.designerSessionId(), row.id(), true, null, null);
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
                row.rolePackId(), row.rolePackVersion());
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
                row.errorCode(), row.errorDetail(), row.createdAt(), row.updatedAt());
    }

    private List<Evidence> evidence(Path root, String markdown) {
        List<Evidence> result = new ArrayList<>(); Matcher matcher = LOCATION.matcher(markdown);
        while (matcher.find() && result.size() < 128) {
            try {
                String relative = matcher.group(1); Path file = safe(root, relative);
                if (!Files.isRegularFile(file) || Files.size(file) > 16_000_000) continue;
                int line = Integer.parseInt(matcher.group(2)); long lineCount;
                try (var lines = Files.lines(file, StandardCharsets.UTF_8)) { lineCount = lines.limit(1_000_001).count(); }
                if (line < 1 || line > lineCount) continue;
                result.add(new Evidence(relative.replace('\\','/'), line, sha256(Files.readAllBytes(file))));
            } catch (Exception ignored) { }
        }
        return List.copyOf(result);
    }
    private static Path safe(Path root, String relative) throws Exception {
        Path input = Path.of(relative); if (input.isAbsolute()) throw new IllegalArgumentException();
        Path file = root.resolve(input).normalize(); if (!file.startsWith(root) || Files.isSymbolicLink(file)) throw new IllegalArgumentException();
        Path parent = Files.exists(file) ? file.toRealPath() : file.getParent().toRealPath();
        if (!parent.startsWith(root.toRealPath())) throw new IllegalArgumentException(); return file;
    }
    private String reviewerPrompt(TaskProfileService.View profile, Path root, String requirement) {
        return prompts.reviewerInstructions(profile) + "\n\nProject root: " + root + "\nFrozen review requirement:\n"
                + requirement + "\n\nReturn one complete Simplified-Chinese Markdown report. Every finding cites path:line.";
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
        return new TaskProfileService.View(row.id(), row.state(), TaskIntent.valueOf(row.intent()),
                io.opencode.loopper.domain.WorkflowTemplate.valueOf(row.workflowTemplate()),
                io.opencode.loopper.domain.MutationMode.valueOf(row.mutationMode()), artifacts, technologies,
                io.opencode.loopper.domain.TestPolicy.valueOf(row.testPolicy()),
                io.opencode.loopper.domain.ExecutionStrategy.valueOf(row.executionStrategy()), row.rolePackId(),
                row.rolePackVersion(), row.confidence(), List.of(), row.resolutionSource(), row.decisionRequired()==1, row.version());
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception failure) { throw new IllegalStateException(failure); } }
    private List<Evidence> readEvidence(String value) { try { return json.readValue(value, new TypeReference<>() { }); } catch (Exception ignored) { return List.of(); } }
    private DesignerSessionRow session(String id) { return mapper.findDesignerSession(id).orElseThrow(() -> new NotFoundException("Designer session not found: " + id)); }
    private static String reportTitle(String content) { return content.lines().map(String::strip).filter(line -> !line.isBlank()).findFirst().orElse("只读分析报告").replaceFirst("^#+\\s*", ""); }
    private static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception failure) { throw new IllegalStateException(failure); } }
    private static String safeMessage(String value) { return value == null || value.isBlank() ? "unknown Reviewer failure" : value.substring(0, Math.min(2000, value.length())); }

    public record PollResult(String designerSessionId, String reportId, boolean ready, String errorCode, String errorDetail) { }
    public record Evidence(String path, int line, String sha256) { }
    public record EvidenceView(String path, int line, String sha256, boolean stale) { }
    public record View(String id, String state, String title, String markdown, String contentSha256,
                       String sourceSnapshotSha256, List<EvidenceView> evidence, boolean stale,
                       String errorCode, String errorDetail, String createdAt, String updatedAt) { }
    public record Summary(String id, String state, String title, String contentSha256, boolean stale, String updatedAt) { }
}
