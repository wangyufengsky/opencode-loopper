package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ModelTokenUsageRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** Persists provider-reported token totals for the compact live UI without changing budget evidence. */
@Service
public final class ModelTokenUsageProjectionService {
    private final LoopperMapper mapper;
    private final OpenCodeClient openCode;

    public ModelTokenUsageProjectionService(LoopperMapper mapper, OpenCodeClient openCode) {
        this.mapper = mapper;
        this.openCode = openCode;
    }

    public UsageView observeDesigner(String sessionId, Path root, String externalSessionId,
                                     List<OpenCodeClient.UsageRecord> records, boolean complete) {
        observe(designerRow(sessionId, externalSessionId, records, complete));
        List<ModelTokenUsageRow> rows = mapper.listDesignerModelTokenUsage(sessionId);
        reconcileDesignerHistory(rows, root, externalSessionId);
        return aggregate(rows);
    }

    public UsageView designerUsage(String sessionId) {
        return aggregate(mapper.listDesignerModelTokenUsage(sessionId));
    }

    public UsageView observeTask(String taskId, Path root, String externalSessionId,
                                 List<OpenCodeClient.UsageRecord> records, boolean complete) {
        Set<String> activeRemotes = Set.copyOf(mapper.listActiveTaskModelRemoteIds(taskId));
        observe(taskRow(taskId, externalSessionId, records, complete));
        List<ModelTokenUsageRow> rows = mapper.listTaskModelTokenUsage(taskId);
        reconcileTaskRemote(rows, root, externalSessionId, activeRemotes);
        return aggregate(rows);
    }

    public UsageView taskUsage(String taskId) {
        return aggregate(mapper.listTaskModelTokenUsage(taskId));
    }

    private void reconcileDesignerHistory(List<ModelTokenUsageRow> rows, Path root, String activeRemoteId) {
        rows.stream()
                .filter(row -> !row.complete() && !row.externalSessionId().equals(activeRemoteId))
                .findFirst()
                .ifPresent(row -> reconcile(row, root, true));
    }

    private void reconcileTaskRemote(List<ModelTokenUsageRow> rows, Path root, String activeRemoteId,
                                     Set<String> activeRemotes) {
        rows.stream()
                .filter(row -> !row.complete() && !row.externalSessionId().equals(activeRemoteId))
                .sorted(Comparator.comparing(row -> !activeRemotes.contains(row.externalSessionId())))
                .findFirst()
                .ifPresent(row -> reconcile(row, root, !activeRemotes.contains(row.externalSessionId())));
    }

    private void reconcile(ModelTokenUsageRow row, Path root, boolean complete) {
        try {
            List<OpenCodeClient.UsageRecord> records = openCode.sessionUsage(
                    new OpenCodeClient.OpenCodeSession(row.externalSessionId(), root));
            observe(row.designerSessionId() == null
                    ? taskRow(row.taskId(), row.externalSessionId(), records, complete)
                    : designerRow(row.designerSessionId(), row.externalSessionId(), records, complete));
        } catch (RuntimeException unavailable) { /* keep incomplete so a later poll can recover the total */ }
    }

    private void observe(ModelTokenUsageRow row) {
        if (row == null || blank(row.externalSessionId())) return;
        mapper.upsertModelTokenUsage(row);
    }

    private ModelTokenUsageRow designerRow(String sessionId, String externalSessionId,
                                            List<OpenCodeClient.UsageRecord> records, boolean complete) {
        Snapshot snapshot = snapshot(records);
        return new ModelTokenUsageRow(UUID.randomUUID().toString(), sessionId, null, externalSessionId,
                snapshot.inputTokens(), snapshot.outputTokens(), snapshot.totalTokens(), snapshot.reliable(),
                complete, Instant.now().toString());
    }

    private ModelTokenUsageRow taskRow(String taskId, String externalSessionId,
                                        List<OpenCodeClient.UsageRecord> records, boolean complete) {
        Snapshot snapshot = snapshot(records);
        return new ModelTokenUsageRow(UUID.randomUUID().toString(), null, taskId, externalSessionId,
                snapshot.inputTokens(), snapshot.outputTokens(), snapshot.totalTokens(), snapshot.reliable(),
                complete, Instant.now().toString());
    }

    static Snapshot snapshot(List<OpenCodeClient.UsageRecord> records) {
        List<OpenCodeClient.UsageRecord> safe = records == null ? List.of() : records;
        Long input = sum(safe, OpenCodeClient.UsageRecord::inputTokens);
        Long output = sum(safe, OpenCodeClient.UsageRecord::outputTokens);
        Long total = sum(safe, ModelTokenUsageProjectionService::total);
        return new Snapshot(input, output, total, total != null);
    }

    private UsageView aggregate(List<ModelTokenUsageRow> rows) {
        Long total = sum(rows, ModelTokenUsageRow::totalTokens);
        long unknown = rows.stream().filter(row -> !row.reliable()).count();
        return new UsageView(total, unknown, Instant.now().toString());
    }

    private static Long total(OpenCodeClient.UsageRecord record) {
        if (record.totalTokens() != null) return record.totalTokens();
        if (record.inputTokens() == null || record.outputTokens() == null) return null;
        try {
            return Math.addExact(record.inputTokens(), record.outputTokens());
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    private static <T> Long sum(List<T> values, Function<T, Long> extractor) {
        long result = 0;
        boolean known = false;
        try {
            for (T value : values) {
                Long amount = extractor.apply(value);
                if (amount == null) continue;
                result = Math.addExact(result, amount);
                known = true;
            }
        } catch (ArithmeticException overflow) {
            return null;
        }
        return known ? result : null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record UsageView(Long totalTokens, long unknownUsageCount, String observedAt) { }
    record Snapshot(Long inputTokens, Long outputTokens, Long totalTokens, boolean reliable) { }
}
