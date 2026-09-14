package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.*;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Completion is an execution fact: a successful current cycle, same-batch dual PASS and every declared verifier. */
@Service
public final class DocumentDevelopmentCompletion {
    private final LoopperMapper mapper;
    private final TaskReadService reads;
    private final ObjectMapper json;
    private final DocumentOriginalReadCoverage originalReads;
    public DocumentDevelopmentCompletion(LoopperMapper mapper, TaskReadService reads, ObjectMapper json, DocumentOriginalReadCoverage originalReads) {
        this.mapper = mapper; this.reads = reads; this.json = json; this.originalReads = originalReads;
    }
    public Proof require(String taskId) {
        var task = mapper.findTask(taskId).orElseThrow(DocumentDevelopmentCompletion::incomplete);
        if (!Set.of("AWAITING_DECISION", "COMPLETED").contains(task.state()) || "TEMPLATE_REPORT".equals(task.executionMode())) throw incomplete();
        var cycle = mapper.latestTaskExecutionCycle(task.id()).orElseThrow(DocumentDevelopmentCompletion::incomplete);
        if (!cycle.state().equals("SUCCEEDED")) throw incomplete();
        var batch = mapper.listJudgeReviewBatches(task.id()).stream()
                .max(Comparator.comparingInt(JudgeReviewBatchRow::generation)).orElseThrow(DocumentDevelopmentCompletion::incomplete);
        if (!batch.executionCycleId().equals(cycle.id()) || !batch.state().equals("COMPLETED")) throw incomplete();
        var judges = new ArrayList<Judge>();
        for (String role : List.of("REQUIREMENT", "RISK")) {
            var judge = mapper.latestJudgeRunForBatchRole(batch.id(), role).orElseThrow(DocumentDevelopmentCompletion::incomplete);
            if (!judge.state().equals("COMPLETED") || !"PASS".equals(judge.verdict())
                    || !Objects.equals(judge.attemptId(), batch.finalAttemptId())) throw incomplete();
            if (!originalReads.sessionComplete(taskId, judge.externalSessionId())) throw incomplete();
            judges.add(new Judge(judge.id(), role, judge.verdict(), judge.reason(), batch.id(), judge.sourceRevision()));
        }
        if (!Objects.equals(judges.get(0).sourceRevision(), judges.get(1).sourceRevision())) throw incomplete();
        var overview = reads.overview(task.id()); var stages = new ArrayList<Stage>();
        Set<String> acceptedStages = acceptedStages(task);
        for (var summary : overview.stages()) {
            if (acceptedStages != null && !acceptedStages.contains(summary.id())) continue;
            var row = mapper.findStage(summary.id()).orElseThrow(DocumentDevelopmentCompletion::incomplete);
            if (!row.taskId().equals(task.id()) || !row.state().equals("SUCCEEDED")) throw incomplete();
            var attempt = mapper.latestAttempt(row.id()).orElseThrow(DocumentDevelopmentCompletion::incomplete);
            if (!attempt.state().equals("SUCCEEDED") || !attempt.taskId().equals(task.id())) throw incomplete();
            var specs = json.readValue(row.verifiersJson(), LoopSpec.VerifierSpec[].class);
            var evidence = mapper.listVerifications(attempt.id()); var tests = new ArrayList<TestEvidence>();
            for (int index = 0; index < specs.length; index++) {
                int selected = index; var verifier = specs[index];
                var matching = evidence.stream().filter(item -> item.verifierIndex() == selected).toList();
                if (matching.size() != 1 || !matching.getFirst().state().equals("PASS")
                        || !matching.getFirst().type().equals(verifier.type())) throw incomplete();
                if (verifier.type().equals("PROCESS") && "TEST".equals(verifier.processPurpose())) {
                    var result = matching.getFirst();
                    tests.add(new TestEvidence(result.id(), result.state(), verifier.testTargets(), result.summary(), result.createdAt()));
                }
            }
            if (tests.isEmpty()) throw incomplete();
            stages.add(new Stage(row.id(), row.ordinal(), row.workPackageId(), row.packageRunId(), row.objective(), attempt.id(), List.copyOf(tests)));
        }
        if (stages.isEmpty() || acceptedStages != null && stages.size() != acceptedStages.size()) throw incomplete();
        var latest = mapper.findTask(task.id()).orElseThrow(DocumentDevelopmentCompletion::incomplete);
        if (latest.version() != task.version() || mapper.latestTaskExecutionCycle(task.id()).orElseThrow().version() != cycle.version()) throw incomplete();
        return new Proof(task.id(), task.version(), task.state(), cycle.id(), cycle.version(), batch.id(), List.copyOf(judges), List.copyOf(stages));
    }
    private Set<String> acceptedStages(TaskRow task) {
        if (!"ROLLING_PACKAGES".equals(task.executionMode())) return null;
        var result = new HashSet<String>();
        for (var fact : mapper.listPackageFactSnapshots(task.id())) {
            for (var stage : json.readTree(fact.acceptedContractJson()).path("stages")) {
                String id = stage.path("id").asText();
                if (id.isBlank() || !result.add(id)) throw incomplete();
            }
        }
        if (result.isEmpty()) throw incomplete();
        return result;
    }
    private static ConflictException incomplete() {
        return new ConflictException("DOCUMENT_DEVELOPMENT_EVIDENCE_INCOMPLETE", "当前执行尚不具备同批双评审通过及完整测试证据，不能完成需求开发验收");
    }
    public record Proof(String taskId, long taskVersion, String taskState, String cycleId, long cycleVersion,
                        String reviewBatchId, List<Judge> judges, List<Stage> stages) { }
    public record Judge(String id, String role, String verdict, String reason, String batchId, Long sourceRevision) { }
    public record Stage(String id, int ordinal, String workPackageId, String packageRunId, String objective,
                        String attemptId, List<TestEvidence> tests) { }
    public record TestEvidence(String id, String state, List<String> targets, String summary, String at) { }
}
