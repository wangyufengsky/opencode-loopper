package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.JudgeRunState;
import io.opencode.loopper.domain.SessionState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.persistence.AttemptRow;
import io.opencode.loopper.persistence.ExecutionSessionRow;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.SessionUsageRow;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Provider usage is immutable evidence. Missing provider values remain unknown, never zero. */
@Service
public class UsageInsightsService {
    private final LoopperMapper mapper;
    private final OpenCodeClient openCode;

    public UsageInsightsService(LoopperMapper mapper, OpenCodeClient openCode) {
        this.mapper = mapper;
        this.openCode = openCode;
    }

    /** Samples terminal implementation and Judge sessions; V13's idempotency key prevents duplicate polls. */
    public void collectTaskUsage(String taskId) {
        TaskRow task = mapper.findTask(taskId).orElse(null);
        if (task == null || task.worktreePath() == null) return;
        for (ExecutionSessionRow session : mapper.listSessions(taskId)) {
            if (session.externalSessionId() == null || !terminal(session.state())) continue;
            collect(task, session.id(), null, session.externalSessionId(), "session");
        }
        for (JudgeRunRow judge : mapper.listJudgeRuns(taskId)) {
            if (judge.externalSessionId() == null || !terminalJudge(judge.state())) continue;
            collect(task, null, judge.id(), judge.externalSessionId(), "judge");
        }
    }

    /**
     * Captures provider-reported usage as soon as a final-review session reaches a terminal
     * state.  The judge run id is part of the immutable idempotency key, so this is safe to
     * invoke from every terminal transition as well as from later task-level reconciliation.
     */
    public void collectTerminalJudgeUsage(String taskId, String judgeRunId) {
        if (taskId == null || judgeRunId == null) return;
        TaskRow task = mapper.findTask(taskId).orElse(null);
        if (task == null || task.worktreePath() == null) return;
        JudgeRunRow judge = mapper.findJudgeRun(judgeRunId)
                .filter(row -> taskId.equals(row.taskId()))
                .orElse(null);
        if (judge == null || judge.externalSessionId() == null || !terminalJudge(judge.state())) return;
        collect(task, null, judge.id(), judge.externalSessionId(), "judge");
    }

    public BudgetDecision budget(TaskRow task, LoopSpec spec) {
        collectTaskUsage(task.id());
        Usage usage = usage(task.id());
        if (spec.budget().maxTotalTokens() != null && usage.totalTokens() != null
                && usage.totalTokens() >= spec.budget().maxTotalTokens()) {
            return new BudgetDecision(true, "BUDGET_TOKEN_LIMIT_REACHED", "可靠 token 用量已达到软上限，等待确认后才能继续调用模型。", usage);
        }
        String currency = normalizeCurrency(spec.budget().currency());
        if (spec.budget().maxCostAmount() != null && currency != null) {
            BigDecimal cap;
            try { cap = new BigDecimal(spec.budget().maxCostAmount()); }
            catch (NumberFormatException invalid) { return new BudgetDecision(false, null, null, usage); }
            BigDecimal spent = usage.costByCurrency().get(currency);
            if (spent != null && spent.compareTo(cap) >= 0) {
                return new BudgetDecision(true, "BUDGET_COST_LIMIT_REACHED", "可靠 " + currency + " 成本已达到软上限，等待确认后才能继续调用模型。", usage);
            }
        }
        return new BudgetDecision(false, null, null, usage);
    }

    public Usage usage(String taskId) { return aggregate(mapper.listTaskUsage(taskId)); }

    public Map<String, Object> insights() {
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (TaskRow task : mapper.listTasks()) tasks.add(taskInsight(task));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tasks", tasks);
        response.put("usage", usageMap(aggregate(mapper.listAllUsage())));
        response.put("generatedAt", Instant.now().toString());
        return response;
    }

    private Map<String, Object> taskInsight(TaskRow task) {
        Usage usage = usage(task.id());
        var attempts = mapper.listAttempts(task.id());
        var executionAttempts = attempts.stream()
                .filter(attempt -> !"SESSION_FORK_SNAPSHOT".equals(attempt.failureKind()))
                .toList();
        Map<String, AttemptRow> latestAttemptsByStage = new LinkedHashMap<>();
        for (var attempt : executionAttempts) {
            latestAttemptsByStage.merge(attempt.stageId(), attempt,
                    (current, candidate) -> candidate.ordinal() > current.ordinal() ? candidate : current);
        }
        // Failed retries remain available in the task audit trail, but only the final attempt for
        // each stage represents the task's current deterministic acceptance state.
        var verifications = latestAttemptsByStage.values().stream()
                .flatMap(attempt -> mapper.listVerifications(attempt.id()).stream())
                .toList();
        var judges = mapper.listJudgeRuns(task.id());
        long passed = verifications.stream().filter(row -> "PASS".equals(row.state())).count();
        boolean deterministicPassed = !verifications.isEmpty() && passed == verifications.size();
        Map<String, JudgeRunRow> latestJudgesByRole = new LinkedHashMap<>();
        for (var judge : judges) {
            latestJudgesByRole.merge(judge.role(), judge,
                    (current, candidate) -> candidate.ordinal() > current.ordinal() ? candidate : current);
        }
        boolean requirementPassed = "PASS".equals(java.util.Optional.ofNullable(latestJudgesByRole.get("REQUIREMENT"))
                .map(JudgeRunRow::verdict).orElse(null));
        boolean riskPassed = "PASS".equals(java.util.Optional.ofNullable(latestJudgesByRole.get("RISK"))
                .map(JudgeRunRow::verdict).orElse(null));
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("deterministicPassed", deterministicPassed);
        quality.put("verificationCount", verifications.size());
        quality.put("verificationPassedCount", passed);
        quality.put("requirementJudgePassed", requirementPassed);
        quality.put("riskJudgePassed", riskPassed);
        quality.put("state", !deterministicPassed ? "PENDING"
                : requirementPassed && riskPassed ? "PASS" : "REVIEW_REQUIRED");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.id()); result.put("title", task.title()); result.put("state", task.state());
        result.put("durationMs", duration(task.createdAt(), terminalTask(task.state()) ? task.updatedAt() : Instant.now().toString()));
        long attemptedStages = executionAttempts.stream().map(attempt -> attempt.stageId()).distinct().count();
        long judgedRoles = judges.stream().map(JudgeRunRow::role).distinct().count();
        result.put("retryCount", Math.max(0, executionAttempts.size() - attemptedStages) + Math.max(0, judges.size() - judgedRoles));
        result.put("usage", usageMap(usage)); result.put("quality", quality);
        return result;
    }

    private Usage aggregate(List<SessionUsageRow> rows) {
        List<SessionUsageRow> reliableRows = rows.stream().filter(SessionUsageRow::reliable).toList();
        Long input = sum(reliableRows, SessionUsageRow::inputTokens); Long output = sum(reliableRows, SessionUsageRow::outputTokens);
        Long total = sum(reliableRows, SessionUsageRow::totalTokens); long unknown = rows.stream().filter(row -> !row.reliable()).count();
        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        for (SessionUsageRow row : rows) {
            if (!row.reliable() || row.costAmount() == null || row.currency() == null) continue;
            try { costs.merge(row.currency(), new BigDecimal(row.costAmount()), BigDecimal::add); } catch (NumberFormatException ignored) { /* invalid provider value is not a cost */ }
        }
        return new Usage(input, output, total, Map.copyOf(costs), unknown);
    }

    private Long total(OpenCodeClient.UsageRecord row) {
        if (row.totalTokens() != null) return row.totalTokens();
        if (row.inputTokens() == null || row.outputTokens() == null) return null;
        try { return Math.addExact(row.inputTokens(), row.outputTokens()); } catch (ArithmeticException overflow) { return null; }
    }

    private Long sum(List<SessionUsageRow> rows, java.util.function.Function<SessionUsageRow, Long> value) {
        long sum = 0; boolean known = false;
        for (SessionUsageRow row : rows) { Long amount = value.apply(row); if (amount != null) { sum += amount; known = true; } }
        return known ? sum : null;
    }

    private Map<String, Object> usageMap(Usage usage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("inputTokens", usage.inputTokens()); map.put("outputTokens", usage.outputTokens()); map.put("totalTokens", usage.totalTokens());
        map.put("costByCurrency", usage.costByCurrency().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue().toPlainString(), (a, b) -> a, LinkedHashMap::new)));
        map.put("unknownUsageCount", usage.unknownUsageCount());
        return map;
    }

    private void collect(TaskRow task, String executionSessionId, String judgeRunId, String externalSessionId, String source) {
        List<OpenCodeClient.UsageRecord> records;
        try {
            records = openCode.sessionUsage(new OpenCodeClient.OpenCodeSession(externalSessionId, Path.of(task.worktreePath())));
        } catch (RuntimeException unavailable) {
            return;
        }
        if (records.isEmpty()) {
            insertUsage(task.id(), executionSessionId, judgeRunId, source, "__unknown__", null);
            return;
        }
        for (OpenCodeClient.UsageRecord record : records) {
            String messageId = record.messageId() == null || record.messageId().isBlank() ? "__unknown__" : record.messageId();
            insertUsage(task.id(), executionSessionId, judgeRunId, source, messageId, record);
        }
    }

    private void insertUsage(String taskId, String executionSessionId, String judgeRunId, String source,
                             String messageId, OpenCodeClient.UsageRecord record) {
        Long input = record == null ? null : record.inputTokens();
        Long output = record == null ? null : record.outputTokens();
        Long total = record == null ? null : total(record);
        BigDecimal cost = record == null ? null : record.costAmount();
        boolean reliable = record != null && record.reliable()
                && (input != null || output != null || total != null || cost != null);
        String localSessionId = executionSessionId == null ? judgeRunId : executionSessionId;
        mapper.insertSessionUsage(new SessionUsageRow(UUID.randomUUID().toString(), taskId, executionSessionId, judgeRunId,
                messageId, "usage:" + source + ":" + localSessionId + ":" + messageId,
                record == null ? null : record.providerId(), record == null ? null : record.modelId(), input, output, total,
                cost == null ? null : cost.toPlainString(), normalizeCurrency(record == null ? null : record.currency()), reliable,
                Instant.now().toString()));
    }
    private boolean terminal(String state) { return SessionState.valueOf(state).terminal(); }
    private boolean terminalJudge(String state) { return JudgeRunState.valueOf(state).terminal(); }
    private boolean terminalTask(String state) { return TaskState.valueOf(state).terminal(); }
    private String normalizeCurrency(String value) { return value == null || value.isBlank() ? null : value.toUpperCase(Locale.ROOT); }
    private long duration(String start, String end) { try { return Math.max(0, Duration.between(Instant.parse(start), Instant.parse(end)).toMillis()); } catch (RuntimeException invalid) { return 0; } }

    public record Usage(Long inputTokens, Long outputTokens, Long totalTokens, Map<String, BigDecimal> costByCurrency, long unknownUsageCount) { }
    public record BudgetDecision(boolean blocked, String code, String message, Usage usage) { }
}
