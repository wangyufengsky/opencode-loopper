package io.opencode.loopper.service;

import io.opencode.loopper.domain.JudgeRunState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Settles a proven-completed legacy Judge response through the shared deterministic compiler. */
@Service
final class LegacyJudgeCompletionService {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final JudgeDecisionLegacyAdapter adapter;
    private final TaskJudgeDecisionParser parser;
    private final AiOutputAuditService audit;
    private final UsageInsightsService usage;
    private final TaskEvidenceService evidence;
    private final TaskEventService events;

    LegacyJudgeCompletionService(
            LoopperMapper mapper, LifecycleTransitionService lifecycle,
            JudgeDecisionLegacyAdapter adapter, AiOutputExtractor extractor,
            AiOutputAuditService audit, UsageInsightsService usage,
            TaskEvidenceService evidence, TaskEventService events) {
        this.mapper = mapper; this.lifecycle = lifecycle; this.adapter = adapter;
        this.parser = new TaskJudgeDecisionParser(extractor); this.audit = audit;
        this.usage = usage; this.evidence = evidence; this.events = events;
    }

    void complete(TaskRow task, JudgeRunRow input, String rawOutput) {
        JudgeRunRow judge = mapper.findJudgeRun(input.id()).orElse(input);
        if (!JudgeRunState.RUNNING.name().equals(judge.state())) return;
        var frozen = evidence.frozenLegacyJudgeSource(task, judge);
        JudgeDecisionCompilation.Result compiled = adapter.compile(
                new JudgeDecisionCompilation.Input(judge.role(), frozen.source().evidenceCatalog()), rawOutput);
        String verdict = compiled.accepted() ? compiled.candidate().verdict() : "UNPARSEABLE";
        String reason = compiled.accepted() ? compiled.deterministicReason() : problems(compiled.problems());
        JudgeRunRow completed = new JudgeRunRow(judge.id(), judge.taskId(), judge.attemptId(), judge.role(),
                judge.ordinal(), judge.externalSessionId(), JudgeRunState.COMPLETED.name(), verdict, reason,
                rawOutput, judge.createdAt(), Instant.now().toString(), judge.version(), judge.responseMode(),
                judge.responseSchemaId(), judge.reviewBatchId(), judge.sourceRevision());
        lifecycle.transition(new LifecycleTransitionService.Subject(
                        LifecycleMachineType.JUDGE_RUN, judge.id(), LifecycleScopeType.TASK, judge.taskId()),
                judge.state(), completed.state(), LifecycleEvent.COMPLETE, "LEGACY_JUDGE_COMPILED",
                Map.of("role", judge.role()), () -> mapper.updateJudgeRun(completed),
                () -> new ConflictException("JUDGE_VERSION_CONFLICT", "Judge run was updated concurrently"));
        TaskJudgeDecisionParser.Decision parsed = parser.parse(rawOutput);
        if (!parsed.normalizations().isEmpty()) {
            audit.recordNormalization("TASK", task.id(), judge.role(), "JUDGE_" + judge.ordinal(),
                    parsed.normalizations(), rawOutput);
            events.emit(task.id(), "AI_OUTPUT_NORMALIZED", Map.of(
                    "role", judge.role(), "judgeRunId", judge.id(),
                    "corrections", parsed.normalizations()));
        }
        usage.collectTerminalJudgeUsage(task.id(), completed.id());
        evidence.persist(task, judge.attemptId(), judge.id(), "JUDGE_RESULT",
                judge.role().toLowerCase() + "-judge-result.txt", "text/plain",
                rawOutput == null ? "" : rawOutput, Map.of(
                        "role", judge.role(), "verdict", verdict, "reason", reason,
                        "state", JudgeRunState.COMPLETED.name(), "authority", "SERVER_COMPILATION",
                        "sourceSnapshotSha256", frozen.sourceSha256()));
        events.emit(task.id(), "judge.completed", Map.of(
                "judgeRunId", judge.id(), "role", judge.role(), "verdict", verdict));
    }

    private static String problems(List<JudgeDecisionCompilation.Problem> problems) {
        if (problems == null || problems.isEmpty()) return "JUDGE_OUTPUT_INVALID";
        return problems.stream().map(problem -> problem.code() + ": " + problem.staticDetail())
                .collect(java.util.stream.Collectors.joining(" | "));
    }
}
