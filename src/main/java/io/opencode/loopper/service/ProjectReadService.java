package io.opencode.loopper.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.opencode.loopper.persistence.ProjectSummaryRow;
import io.opencode.loopper.persistence.ReadModelMapper;
import io.opencode.loopper.runtime.GitWorktreeManager;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

/** One-query project counters plus bounded/cached Git inspection for list pages. */
@Service
public class ProjectReadService {
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);
    private final ReadModelMapper mapper;
    private final GitWorktreeManager worktrees;
    private final MeterRegistry metrics;
    private final ExecutorService inspectionPool = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "project-git-inspection");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, CachedInspection> cache = new ConcurrentHashMap<>();

    public ProjectReadService(ReadModelMapper mapper, GitWorktreeManager worktrees, MeterRegistry metrics) {
        this.mapper = mapper;
        this.worktrees = worktrees;
        this.metrics = metrics;
    }

    public List<ProjectSummary> summaries(boolean refresh) {
        return metrics.timer("loopper.read_model.duration", "model", "project.summaries").record(() -> {
            List<ProjectSummaryRow> rows = mapper.projectSummaries();
            List<CompletableFuture<ProjectSummary>> futures = rows.stream()
                    .map(row -> CompletableFuture.supplyAsync(() -> summary(row, refresh), inspectionPool)).toList();
            List<ProjectSummary> result = futures.stream().map(CompletableFuture::join).toList();
            metrics.summary("loopper.read_model.rows", "model", "project.summaries").record(result.size());
            return result;
        });
    }

    private ProjectSummary summary(ProjectSummaryRow row, boolean refresh) {
        GitWorktreeManager.RepositoryInspection inspection = inspection(row.rootPath(), refresh);
        String status = !inspection.pathAvailable() ? "INVALID" : inspection.isolatedWorktree() ? "READY" : "NEEDS_GIT";
        String executionMode = inspection.isolatedWorktree()
                ? "WORKTREE" : inspection.pathAvailable() ? "DIRECT" : "UNAVAILABLE";
        return new ProjectSummary(row.id(), row.name(), row.rootPath(), status, row.description(), inspection.branch(),
                executionMode, row.updatedAt(), row.taskCount(), row.openDesignerSessionCount());
    }

    private GitWorktreeManager.RepositoryInspection inspection(String rootPath, boolean refresh) {
        CachedInspection cached = cache.get(rootPath);
        Instant now = Instant.now();
        if (!refresh && cached != null && now.isBefore(cached.expiresAt())) return cached.inspection();
        GitWorktreeManager.RepositoryInspection inspected = worktrees.inspect(Path.of(rootPath));
        cache.put(rootPath, new CachedInspection(inspected, now.plus(CACHE_TTL)));
        return inspected;
    }

    @PreDestroy
    void shutdown() {
        inspectionPool.shutdown();
    }

    private record CachedInspection(GitWorktreeManager.RepositoryInspection inspection, Instant expiresAt) { }

    public record ProjectSummary(String id, String name, String rootPath, String status, String description,
                                 String branch, String executionMode, String updatedAt, int taskCount,
                                 int openDesignerSessionCount) { }
}
