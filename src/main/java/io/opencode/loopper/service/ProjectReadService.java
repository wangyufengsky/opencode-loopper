package io.opencode.loopper.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.opencode.loopper.persistence.ProjectSummaryRow;
import io.opencode.loopper.persistence.ReadModelMapper;
import io.opencode.loopper.runtime.GitWorktreeManager;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** One-query project counters plus bounded/cached Git inspection for list pages. */
@Service
public class ProjectReadService {
    private final ReadModelMapper mapper;
    private final ProjectInspectionCache inspections;
    private final MeterRegistry metrics;
    private final ObjectMapper json;
    public ProjectReadService(ReadModelMapper mapper, ProjectInspectionCache inspections, MeterRegistry metrics,
                              ObjectMapper json) {
        this.mapper = mapper;
        this.inspections = inspections;
        this.metrics = metrics;
        this.json = json;
    }

    public List<ProjectSummary> summaries(boolean refresh) {
        return metrics.timer("loopper.read_model.duration", "model", "project.summaries").record(() -> {
            List<ProjectSummaryRow> rows = mapper.projectSummaries();
            List<CompletableFuture<ProjectSummary>> futures = rows.stream()
                    .map(row -> inspections.inspect(row.rootPath(), refresh).thenApply(inspection -> summary(row, inspection))).toList();
            List<ProjectSummary> result;
            try { result = futures.stream().map(CompletableFuture::join).toList(); }
            catch (java.util.concurrent.CompletionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                throw failure;
            }
            metrics.summary("loopper.read_model.rows", "model", "project.summaries").record(result.size());
            return result;
        });
    }

    private ProjectSummary summary(ProjectSummaryRow row, GitWorktreeManager.RepositoryInspection inspection) {
        String status = !inspection.pathAvailable() ? "INVALID" : inspection.isolatedWorktree() ? "READY" : "NEEDS_GIT";
        String executionMode = inspection.isolatedWorktree()
                ? "WORKTREE" : inspection.pathAvailable() ? "DIRECT" : "UNAVAILABLE";
        return new ProjectSummary(row.id(), row.name(), row.rootPath(), status, row.description(), inspection.branch(),
                executionMode, row.updatedAt(), row.taskCount(), row.openDesignerSessionCount(),
                row.stackProfileState(), strings(row.stackTechnologyFamiliesJson()), row.stackComponentCount(),
                row.stackAnalyzedAt(), row.documentPath(), row.version());
    }

    private List<String> strings(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (Exception ignored) { return List.of(); }
    }

    public record ProjectSummary(String id, String name, String rootPath, String status, String description,
                                 String branch, String executionMode, String updatedAt, int taskCount,
                                 int openDesignerSessionCount, String stackProfileState,
                                 List<String> stackTechnologyFamilies, int stackComponentCount,
                                 String stackAnalyzedAt, String documentPath, long version) { }
}
