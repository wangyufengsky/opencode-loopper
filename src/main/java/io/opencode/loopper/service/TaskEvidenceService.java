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
import io.opencode.loopper.persistence.StageRow;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.persistence.VerificationResultRow;
import io.opencode.loopper.verification.VerifierEngine;
import io.opencode.loopper.verification.VerifierOutcome;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Owns immutable Task audit artifacts and the bounded evidence projection consumed by final Judges. */
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

    private void captureVerificationSummary(TaskRow task, AttemptRow attempt) {
        if (mapper.listTaskArtifacts(task.id()).stream()
                .anyMatch(artifact -> "VERIFICATION_SUMMARY".equals(artifact.kind())
                        && attempt.id().equals(artifact.attemptId())
                        && artifact.content().contains("\"schemaVersion\":\"v2\""))) return;
        List<Map<String, Object>> stageEvidence = new ArrayList<>();
        int resultCount = 0;
        for (StageRow stage : mapper.listStages(task.id())) {
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
        String objectives = mapper.listStages(task.id()).stream()
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
}
