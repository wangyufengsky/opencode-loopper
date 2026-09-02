package io.opencode.loopper.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.opencode.loopper.persistence.GlobalUsageRow;
import io.opencode.loopper.persistence.InsightPageMapper;
import io.opencode.loopper.domain.InsightFilter;
import io.opencode.loopper.persistence.TaskInsightRow;
import io.opencode.loopper.persistence.UsageCostRow;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class InsightReadService {
    private final InsightPageMapper mapper;
    private final MeterRegistry metrics;

    public InsightReadService(InsightPageMapper mapper, MeterRegistry metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
    }

    public InsightPage page(String cursor, Integer requestedLimit) {
        return page(InsightFilter.all(), cursor, requestedLimit);
    }

    public InsightPage page(InsightFilter filter, String cursor, Integer requestedLimit) {
        return metrics.timer("loopper.read_model.duration", "model", "insight.page").record(() -> {
            int limit = PageCursor.limit(requestedLimit);
            PageCursor decoded = PageCursor.decode(cursor);
            List<TaskInsightRow> rows = mapper.page(filter, decoded == null ? null : decoded.value(),
                    decoded == null ? null : decoded.id(), limit + 1);
            boolean more = rows.size() > limit;
            List<TaskInsightRow> pageRows = more ? rows.subList(0, limit) : rows;
            List<String> ids = pageRows.stream().map(TaskInsightRow::id).toList();
            Map<String, Map<String, String>> costs = costs(ids.isEmpty() ? List.of() : mapper.costs(filter, ids));
            List<TaskInsight> items = pageRows.stream().map(row -> insight(row, costs.getOrDefault(row.id(), Map.of()))).toList();
            GlobalUsageRow global = mapper.usage(filter);
            Map<String, String> globalCosts = mergeCosts(mapper.costs(filter, null));
            String next = more ? new PageCursor(pageRows.getLast().updatedAt(), pageRows.getLast().id()).encode() : null;
            metrics.summary("loopper.read_model.rows", "model", "insight.page").record(items.size());
            return new InsightPage(items, next, usage(global, globalCosts), Instant.now().toString());
        });
    }

    private TaskInsight insight(TaskInsightRow row, Map<String, String> costs) {
        boolean deterministic = row.verificationCount() > 0
                && row.verificationPassedCount() == row.verificationCount();
        boolean requirement = row.requirementJudgePassed() == 1;
        boolean risk = row.riskJudgePassed() == 1;
        String qualityState = !deterministic ? "PENDING" : (requirement && risk || row.humanApproved() == 1) ? "PASS" : "REVIEW_REQUIRED";
        long retryCount = Math.max(0, row.attemptCount() - row.attemptedStageCount())
                + Math.max(0, row.judgeCount() - row.judgedRoleCount());
        return new TaskInsight(row.id(), row.title(), row.state(), duration(row.createdAt(), row.updatedAt()), retryCount,
                new Usage(row.inputTokens(), row.outputTokens(), row.totalTokens(), costs, row.unknownUsageCount()),
                new Quality(deterministic, row.verificationCount(), row.verificationPassedCount(),
                        requirement, risk, qualityState, row.humanApproved() == 1));
    }

    private Usage usage(GlobalUsageRow row, Map<String, String> costs) {
        return new Usage(row == null ? null : row.inputTokens(), row == null ? null : row.outputTokens(),
                row == null ? null : row.totalTokens(), costs, row == null ? 0 : row.unknownUsageCount());
    }

    private Map<String, Map<String, String>> costs(List<UsageCostRow> rows) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (UsageCostRow row : rows) result.computeIfAbsent(row.taskId(), ignored -> new LinkedHashMap<>())
                .put(row.currency(), row.amount());
        return result;
    }

    private Map<String, String> mergeCosts(List<UsageCostRow> rows) {
        Map<String, java.math.BigDecimal> totals = new LinkedHashMap<>();
        for (UsageCostRow row : rows) {
            try { totals.merge(row.currency(), new java.math.BigDecimal(row.amount()), java.math.BigDecimal::add); }
            catch (NumberFormatException ignored) { }
        }
        Map<String, String> result = new LinkedHashMap<>();
        totals.forEach((currency, amount) -> result.put(currency, amount.toPlainString()));
        return result;
    }

    private long duration(String start, String end) {
        try { return Math.max(0, Duration.between(Instant.parse(start), Instant.parse(end)).toMillis()); }
        catch (RuntimeException invalid) { return 0; }
    }

    public record InsightPage(List<TaskInsight> items, String nextCursor, Usage usage, String generatedAt) { }
    public record TaskInsight(String taskId, String title, String state, long durationMs, long retryCount,
                              Usage usage, Quality quality) { }
    public record Usage(Long inputTokens, Long outputTokens, Long totalTokens,
                        Map<String, String> costByCurrency, long unknownUsageCount) { }
    public record Quality(boolean deterministicPassed, int verificationCount, int verificationPassedCount,
                          boolean requirementJudgePassed, boolean riskJudgePassed, String state, boolean humanApproved) { }
}
