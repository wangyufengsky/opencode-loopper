package io.opencode.loopper.service;

import io.opencode.loopper.domain.AttemptState;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.StageState;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.VerificationState;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.PackageFactSnapshotRow;
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskPackageRunRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.TaskWorkspaceCheckpointRow;
import io.opencode.loopper.persistence.VerificationResultRow;
import io.opencode.loopper.verification.VerifierEngine;
import io.opencode.loopper.verification.VerifierOutcome;
import io.opencode.loopper.runtime.DirectWorkspaceBaselineManager;
import io.opencode.loopper.runtime.GitWorktreeManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Owns immutable Task audit artifacts and the bounded evidence projection consumed by final Judges. */
@Service
final class TaskEvidenceService {
    private static final String DESIGN_CONTEXT = "DESIGN_CONTEXT";
    private static final String REQUIREMENT_CONTEXT = "REQUIREMENT_CONTEXT";
    private static final String DECOMPOSITION_CONTEXT = "DECOMPOSITION_CONTEXT";
    private static final String PACKAGE_DESIGN = "WORK_PACKAGE_DESIGN";
    private static final String PACKAGE_COMPILATION_SUMMARY = "WORK_PACKAGE_COMPILATION_SUMMARY";

    private final LoopperMapper mapper;
    private final ObjectMapper json;
    private final VerifierEngine verifiers;

    TaskEvidenceService(LoopperMapper mapper, ObjectMapper json, VerifierEngine verifiers) {
        this.mapper = mapper;
        this.json = json;
        this.verifiers = verifiers;
    }

    VerifierEngine.DiffPreview previewDiff(TaskRow task, String path, TaskWorkspaceCheckpointRow checkpoint) {
        if (path == null || path.isBlank()) {
            throw new BadRequestException("DIFF_PATH_INVALID", "Diff preview requires a file path");
        }
        boolean verified = false;
        boolean untracked = false;
        TaskArtifactRow snapshot = mapper.listTaskArtifacts(task.id()).stream()
                .filter(artifact -> "GIT_DIFF".equals(artifact.kind())).findFirst().orElse(null);
        if (snapshot != null) {
            try {
                var evidence = json.readTree(snapshot.metadataJson());
                verified = contains(evidence.path("changedPaths"), path);
                untracked = verified && contains(evidence.path("untrackedPaths"), path);
            } catch (RuntimeException ignored) {
                // Historical session-diff artifacts did not contain structured path metadata.
            }
        }
        List<AttemptRow> attempts = mapper.listAttempts(task.id());
        for (int attemptIndex = attempts.size() - 1; attemptIndex >= 0 && !verified; attemptIndex--) {
            for (VerificationResultRow row : mapper.listVerifications(attempts.get(attemptIndex).id())) {
                if (!"GIT_DIFF".equalsIgnoreCase(row.type())) continue;
                try {
                    var evidence = json.readTree(row.evidenceJson());
                    verified = contains(evidence.path("changedPaths"), path);
                    untracked = verified && contains(evidence.path("untrackedPaths"), path);
                } catch (RuntimeException ignored) {
                    // Unreadable historical evidence cannot authorize a file preview.
                }
            }
        }
        if (!verified) {
            throw new BadRequestException("DIFF_PATH_NOT_VERIFIED",
                    "The requested file is not present in persisted GIT_DIFF evidence");
        }
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw new BadRequestException("WORKTREE_UNAVAILABLE", "Task worktree is unavailable");
        }
        try {
            if (checkpoint != null && checkpoint.checkpointTree() != null && !checkpoint.checkpointTree().isBlank()) {
                return verifiers.previewDiffAtRef(Path.of(task.worktreePath()), task.baselineCommit(),
                        checkpoint.checkpointTree(), path, untracked, Duration.ofSeconds(10));
            }
            return verifiers.previewDiff(Path.of(task.worktreePath()), task.baselineCommit(), task.branchName(),
                    path, untracked, Duration.ofSeconds(10));
        } catch (TaskFailure failure) {
            throw new BadRequestException(failure.code(), failure.getMessage());
        }
    }

    private boolean contains(tools.jackson.databind.JsonNode values, String expected) {
        if (!values.isArray()) return false;
        for (var item : values) if (expected.equals(item.asText())) return true;
        return false;
    }

    void captureFinalEvidence(TaskRow task, AttemptRow attempt) {
        captureVerificationSummary(task, attempt);
        if (hasArtifact(task.id(), attempt.id(), "GIT_DIFF")) return;
        VerifierOutcome snapshot = verifiers.verify(Path.of(requireWorktree(task)), task.baselineCommit(),
                new LoopSpec.VerifierSpec("GIT_DIFF", null, null, false, List.of(), List.of(), false),
                Duration.ofSeconds(10));
        if (snapshot.state() != VerificationState.PASS) {
            throw new TaskFailure("TASK_DIFF_CAPTURE_FAILED", snapshot.summary());
        }
        Map<String, Object> metadata = new LinkedHashMap<>(snapshot.evidence());
        metadata.put("source", "deterministic-task-baseline-diff");
        metadata.put("taskBranch", task.branchName());
        metadata.put("attemptId", attempt.id());
        persist(task, attempt.id(), null, "GIT_DIFF", "task-diff.json", "application/json",
                write(metadata), metadata);
    }

    RollingPackageService.FactEvidence capturePackageFactEvidence(TaskRow task, TaskPackageRunRow run,
                                                                  AttemptRow successfulAttempt) {
        List<Map<String, Object>> stages = new ArrayList<>();
        List<StageRow> acceptedStages = packageFactStages(task, run);
        for (StageRow stage : acceptedStages) {
            AttemptRow attempt = mapper.latestAttempt(stage.id()).orElseThrow(() ->
                    new TaskFailure("PACKAGE_FACT_ATTEMPT_MISSING", "工作包 Stage 缺少成功 Attempt"));
            if (!StageState.SUCCEEDED.name().equals(stage.state())
                    || !AttemptState.SUCCEEDED.name().equals(attempt.state())) {
                throw new TaskFailure("PACKAGE_FACT_VERIFICATION_INCOMPLETE", "工作包尚未完成全部机器验收");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stageId", stage.id());
            item.put("ordinal", stage.ordinal());
            item.put("attemptId", attempt.id());
            item.put("verificationResults", verificationResults(attempt));
            stages.add(item);
        }
        if (stages.isEmpty()) throw new TaskFailure("PACKAGE_FACT_STAGE_MISSING", "工作包没有可冻结的 Stage");

        PackageFactSnapshotRow previous = mapper.listPackageFactSnapshots(task.id()).stream()
                .reduce((left, right) -> right).orElse(null);
        String inputTree = previous == null ? task.baselineCommit() : previous.outputTree();
        String verifierBaseline = inputTree;
        if (GitWorktreeManager.DIRECT_BRANCH.equals(task.branchName())
                && inputTree != null && !inputTree.startsWith(DirectWorkspaceBaselineManager.PREFIX)) {
            verifierBaseline = DirectWorkspaceBaselineManager.PREFIX + task.id() + ":" + inputTree;
        }
        VerifierOutcome diff = verifiers.verify(Path.of(requireWorktree(task)), verifierBaseline,
                new LoopSpec.VerifierSpec("GIT_DIFF", null, null, false, List.of(), List.of(), false),
                Duration.ofSeconds(10));
        if (diff.state() != VerificationState.PASS) {
            throw new TaskFailure("PACKAGE_DIFF_CAPTURE_FAILED", diff.summary());
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", "package-fact-v1");
        evidence.put("taskId", task.id());
        evidence.put("packageRunId", run.id());
        evidence.put("successfulAttemptId", successfulAttempt.id());
        evidence.put("stages", stages);
        String evidenceJson = write(evidence);
        String diffJson = write(diff.evidence());
        var workPackage = mapper.findDesignWorkPackage(run.designWorkPackageId()).orElse(null);
        String navigation = workPackage == null ? ""
                : String.join("\n", List.of(nullToEmpty(workPackage.compilerSummary()),
                nullToEmpty(workPackage.handoffSummary()))).strip();
        String diffArtifactId = persistOnce(task, successfulAttempt.id(), "PACKAGE_FACT_DIFF",
                run.packageKey() + "-fact-diff.json", "application/json", diffJson,
                Map.of("packageRunId", run.id(), "diffSha256", sha256(diffJson)));
        String evidenceArtifactId = persistOnce(task, successfulAttempt.id(), "PACKAGE_FACT_EVIDENCE",
                run.packageKey() + "-fact-evidence.json", "application/json", evidenceJson,
                Map.of("packageRunId", run.id(), "diffSha256", sha256(diffJson),
                        "evidenceSha256", sha256(evidenceJson)));
        return new RollingPackageService.FactEvidence(inputTree, sha256(diffJson),
                sha256(evidenceJson), diffArtifactId, evidenceArtifactId,
                acceptedStages.stream().map(StageRow::id).toList(), navigation);
    }

    private void captureVerificationSummary(TaskRow task, AttemptRow attempt) {
        if (mapper.listTaskArtifacts(task.id()).stream()
                .anyMatch(artifact -> "VERIFICATION_SUMMARY".equals(artifact.kind())
                        && attempt.id().equals(artifact.attemptId())
                        && artifact.content().contains("\"schemaVersion\":\"v2\""))) return;
        List<Map<String, Object>> stageEvidence = new ArrayList<>();
        int resultCount = 0;
        for (StageRow stage : effectiveStages(task)) {
            AttemptRow stageAttempt = mapper.latestAttempt(stage.id()).orElseThrow(() ->
                    new TaskFailure("JUDGE_STAGE_EVIDENCE_MISSING",
                            "Stage " + stage.ordinal() + " has no deterministic attempt evidence"));
            if (!StageState.SUCCEEDED.name().equals(stage.state())
                    || !AttemptState.SUCCEEDED.name().equals(stageAttempt.state())) {
                throw new TaskFailure("JUDGE_STAGE_EVIDENCE_INCOMPLETE",
                        "Stage " + stage.ordinal() + " has not completed deterministic acceptance");
            }
            List<Map<String, Object>> results = verificationResults(stageAttempt);
            resultCount += results.size();
            Map<String, Object> stageResult = new LinkedHashMap<>();
            stageResult.put("stageId", stage.id());
            stageResult.put("ordinal", stage.ordinal());
            stageResult.put("objective", stage.objective());
            stageResult.put("attemptId", stageAttempt.id());
            stageResult.put("attemptOrdinal", stageAttempt.ordinal());
            stageResult.put("results", results);
            stageEvidence.add(stageResult);
        }
        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("schemaVersion", "v2");
        aggregate.put("taskId", task.id());
        aggregate.put("finalAttemptId", attempt.id());
        aggregate.put("allPassed", stageEvidence.stream().flatMap(stage -> ((List<?>) stage.get("results")).stream())
                .map(result -> (Map<?, ?>) result)
                .allMatch(result -> VerificationState.PASS.name().equals(result.get("state"))));
        aggregate.put("stages", stageEvidence);
        persist(task, attempt.id(), null, "VERIFICATION_SUMMARY", "verification-summary-v2.json",
                "application/json", write(aggregate), Map.of(
                        "source", "deterministic-verifier-aggregate", "stageCount", stageEvidence.size(),
                        "resultCount", resultCount, "schemaVersion", "v2"));
    }

    private List<Map<String, Object>> verificationResults(AttemptRow attempt) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (VerificationResultRow row : mapper.listVerifications(attempt.id())) {
            Map<String, Object> result = new LinkedHashMap<>();
            String evidence = row.evidenceJson() == null ? "" : row.evidenceJson();
            result.put("verifierIndex", row.verifierIndex());
            result.put("type", row.type());
            result.put("state", row.state());
            result.put("summary", row.summary());
            result.put("evidenceExcerpt", JudgePromptPolicy.evidenceExcerpt(evidence));
            result.put("evidenceTruncated", JudgePromptPolicy.utf8Bytes(evidence)
                    > JudgePromptPolicy.MAX_EVIDENCE_EXCERPT_UTF8_BYTES);
            result.put("evidenceSha256", sha256(evidence));
            results.add(result);
        }
        return results;
    }

    void persistConfirmedDesignContext(TaskRow task, LoopDraftRow draft) {
        var session = mapper.findLatestDesignerSessionByDraft(draft.id()).orElse(null);
        if (session == null || session.currentRequirementRevision() == null) {
            mapper.findLatestPersistedDesignerMessageByDraft(draft.id()).ifPresent(message ->
                    persistLegacyDesign(task, draft, message));
            return;
        }
        var revision = mapper.findCurrentDesignRequirementRevision(session.id()).orElse(null);
        if (revision == null || !"COMPLETED".equals(revision.state())) return;
        persist(task, null, null, REQUIREMENT_CONTEXT, "requirement-r" + revision.revision() + ".md",
                "text/markdown", revision.requirementText(), Map.of(
                        "designerSessionId", session.id(), "requirementRevision", revision.revision(),
                        "sourceMessageId", revision.sourceMessageId()));
        mapper.findTaskDecompositionByRevision(revision.id()).ifPresent(decomposition ->
                persist(task, null, null, DECOMPOSITION_CONTEXT,
                        "decomposition-r" + revision.revision() + ".json", "application/json",
                        decomposition.planJson(), Map.of("designerSessionId", session.id(),
                                "requirementRevision", revision.revision(), "decompositionId", decomposition.id(),
                                "resultType", decomposition.resultType() == null ? "" : decomposition.resultType())));
        StringBuilder combined = new StringBuilder("# 已确认分包设计\n\n");
        for (var workPackage : mapper.listDesignWorkPackages(revision.id())) {
            DesignerMessageRow design = mapper.listDesignerMessages(session.id()).stream()
                    .filter(message -> message.id().equals(workPackage.designMessageId())).findFirst().orElse(null);
            if (design != null) {
                persist(task, null, null, PACKAGE_DESIGN, workPackage.packageId() + "-design.md", "text/markdown",
                        design.content(), Map.of("workPackageId", workPackage.packageId(),
                                "ordinal", workPackage.ordinal(), "designerMessageId", design.id(),
                                "requirementRevision", revision.revision()));
                combined.append("## ").append(workPackage.packageId()).append(" · ")
                        .append(workPackage.title()).append("\n\n").append(design.content()).append("\n\n");
            }
            String summary = workPackage.compilerSummary() == null ? "" : workPackage.compilerSummary();
            String handoff = workPackage.handoffSummary() == null ? "" : workPackage.handoffSummary();
            persist(task, null, null, PACKAGE_COMPILATION_SUMMARY,
                    workPackage.packageId() + "-compilation-summary.md", "text/markdown",
                    summary + (handoff.isBlank() ? "" : "\n\n依赖交接：\n" + handoff),
                    Map.of("workPackageId", workPackage.packageId(), "ordinal", workPackage.ordinal(),
                            "handoffSummary", handoff, "requirementRevision", revision.revision()));
        }
        persist(task, null, null, DESIGN_CONTEXT, "confirmed-combined-design.md", "text/markdown",
                combined.toString(), Map.of("draftId", draft.id(), "designerSessionId", session.id(),
                        "requirementRevision", revision.revision(), "composite", true));
    }

    private void persistLegacyDesign(TaskRow task, LoopDraftRow draft, DesignerMessageRow message) {
        persist(task, null, null, DESIGN_CONTEXT, "confirmed-designer-design.md", "text/markdown",
                message.content(), Map.of("draftId", draft.id(), "designerSessionId", message.designerSessionId(),
                        "designerMessageId", message.id(), "deliveryState", message.deliveryState()));
    }

    String judgePrompt(TaskRow task, AttemptRow attempt, String role, LoopSpec loopSpec) {
        String objectives = effectiveStages(task).stream()
                .filter(stage -> StageState.SUCCEEDED.name().equals(stage.state()))
                .map(stage -> "- 阶段 " + (stage.ordinal() + 1) + "：" + stage.objective())
                .collect(java.util.stream.Collectors.joining("\n"));
        String verification = artifact(task.id(), attempt.id(), "VERIFICATION_SUMMARY",
                content -> content.contains("\"schemaVersion\":\"v2\""),
                "No verification summary was persisted.");
        String diff = artifact(task.id(), attempt.id(), "GIT_DIFF", content -> true,
                "No diff artifact was persisted.");
        return JudgePromptPolicy.prompt(loopSpec, role, objectives, verification, diff, attempt.id());
    }

    private String persistOnce(TaskRow task, String attemptId, String kind, String name,
                               String contentType, String content, Map<String, ?> metadata) {
        TaskArtifactRow existing = mapper.listTaskArtifacts(task.id()).stream()
                .filter(row -> kind.equals(row.kind()) && name.equals(row.name())
                        && java.util.Objects.equals(attemptId, row.attemptId())).findFirst().orElse(null);
        if (existing != null) return existing.id();
        String id = UUID.randomUUID().toString();
        mapper.insertTaskArtifact(new TaskArtifactRow(id, task.id(), attemptId,
                null, kind, name, contentType, content == null ? "" : content, write(metadata), now()));
        return id;
    }

    void persist(TaskRow task, String attemptId, String judgeRunId, String kind, String name,
                 String contentType, String content, Map<String, ?> metadata) {
        mapper.insertTaskArtifact(new TaskArtifactRow(UUID.randomUUID().toString(), task.id(), attemptId,
                judgeRunId, kind, name, contentType, content == null ? "" : content, write(metadata), now()));
    }

    private String artifact(String taskId, String attemptId, String kind,
                            java.util.function.Predicate<String> contentFilter, String fallback) {
        return mapper.listTaskArtifacts(taskId).stream()
                .filter(item -> attemptId.equals(item.attemptId()) && kind.equals(item.kind()))
                .map(TaskArtifactRow::content).filter(contentFilter).findFirst().orElse(fallback);
    }

    private boolean hasArtifact(String taskId, String attemptId, String kind) {
        return mapper.listTaskArtifacts(taskId).stream()
                .anyMatch(artifact -> kind.equals(artifact.kind()) && attemptId.equals(artifact.attemptId()));
    }

    private List<StageRow> packageFactStages(TaskRow task, TaskPackageRunRow run) {
        var current = mapper.latestTaskSpecRevision(task.id()).orElseThrow(() ->
                new TaskFailure("TASK_SPEC_REVISION_MISSING", "工作包缺少当前累计执行规范"));
        if (!run.id().equals(current.packageRunId())) {
            throw new TaskFailure("PACKAGE_TASK_SPEC_MISMATCH", "当前累计执行规范不属于待冻结工作包");
        }
        int acceptedBaseCount = mapper.listPackageFactSnapshots(task.id()).stream().reduce((left, right) -> right)
                .flatMap(fact -> mapper.listTaskSpecRevisions(task.id()).stream()
                        .filter(revision -> fact.taskSpecSha256().equals(revision.specSha256())).findFirst())
                .map(io.opencode.loopper.persistence.TaskSpecRevisionRow::stageCount).orElse(0);
        int acceptedCount = current.stageCount() - acceptedBaseCount;
        List<StageRow> candidates = mapper.listStages(task.id()).stream()
                .filter(stage -> run.id().equals(stage.packageRunId()))
                .sorted(java.util.Comparator.comparingInt(StageRow::ordinal)).toList();
        if (acceptedCount <= 0 || candidates.size() < acceptedCount) {
            throw new TaskFailure("PACKAGE_FACT_STAGE_MISMATCH", "当前工作包的已接受 Stage 与累计规范不一致");
        }
        return candidates.subList(candidates.size() - acceptedCount, candidates.size());
    }

    private List<StageRow> effectiveStages(TaskRow task) {
        if (!"ROLLING_PACKAGES".equals(task.executionMode())) return mapper.listStages(task.id());
        java.util.Set<String> acceptedIds = new java.util.LinkedHashSet<>();
        try {
            for (PackageFactSnapshotRow fact : mapper.listPackageFactSnapshots(task.id())) {
                for (var stage : json.readTree(fact.acceptedContractJson()).path("stages")) {
                    String id = stage.path("id").asText();
                    if (!id.isBlank()) acceptedIds.add(id);
                }
            }
        } catch (JacksonException unreadable) {
            throw new TaskFailure("PACKAGE_FACT_CONTRACT_INVALID", "已冻结工作包合同无法读取");
        }
        return mapper.listStages(task.id()).stream().filter(stage -> acceptedIds.contains(stage.id())).toList();
    }

    private String requireWorktree(TaskRow task) {
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw new TaskFailure("WORKTREE_MISSING", "Task has no prepared execution workspace");
        }
        return task.worktreePath();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String now() {
        return Instant.now().toString();
    }

    private String nullToEmpty(String value) { return value == null ? "" : value; }
}
