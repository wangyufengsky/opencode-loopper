package io.opencode.loopper.service;

import io.opencode.loopper.runtime.GitWorktreeManager;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Bounded Git inspection admission and per-root in-flight coalescing, outside DB transactions. */
@Component
public class ProjectInspectionCache {
    private static final Duration TTL = Duration.ofSeconds(5);
    private static final int MAX_CACHED_ROOTS = 512;
    private final GitWorktreeManager worktrees;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final Map<String, Cached> cached = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, CompletableFuture<GitWorktreeManager.RepositoryInspection>> pending = new LinkedHashMap<>();
    private boolean closed;

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectInspectionCache(GitWorktreeManager worktrees) {
        this(worktrees, Clock.systemUTC(), new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), Thread.ofPlatform().daemon().name("project-git-inspection-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()));
    }

    ProjectInspectionCache(GitWorktreeManager worktrees, Clock clock, ThreadPoolExecutor workers) {
        this.worktrees = worktrees;
        this.clock = clock;
        this.workers = workers;
    }

    public synchronized CompletableFuture<GitWorktreeManager.RepositoryInspection> inspect(String root, boolean refresh) {
        String key = Path.of(root).toAbsolutePath().normalize().toString();
        if (closed) return CompletableFuture.failedFuture(unavailable());
        var running = pending.get(key);
        if (running != null) return running;
        Cached previous = cached.get(key);
        if (!refresh && previous != null && clock.instant().isBefore(previous.expiresAt())) {
            return CompletableFuture.completedFuture(previous.inspection());
        }
        var result = new CompletableFuture<GitWorktreeManager.RepositoryInspection>();
        pending.put(key, result);
        try {
            workers.execute(() -> complete(key, result));
        } catch (RejectedExecutionException busy) {
            pending.remove(key, result);
            result.completeExceptionally(unavailable());
        }
        return result;
    }

    private void complete(String key, CompletableFuture<GitWorktreeManager.RepositoryInspection> result) {
        try {
            var inspection = worktrees.inspect(Path.of(key));
            synchronized (this) {
                if (!closed) {
                    cached.put(key, new Cached(inspection, clock.instant().plus(TTL)));
                    if (cached.size() > MAX_CACHED_ROOTS) cached.remove(cached.keySet().iterator().next());
                }
                pending.remove(key, result);
            }
            result.complete(inspection);
        } catch (RuntimeException failure) {
            synchronized (this) { pending.remove(key, result); }
            result.completeExceptionally(failure);
        }
    }

    private ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException("PROJECT_INSPECTION_BUSY", "项目检查繁忙，请稍后刷新。");
    }

    @PreDestroy
    public synchronized void shutdown() {
        closed = true;
        workers.shutdownNow();
        pending.values().forEach(result -> result.completeExceptionally(unavailable()));
        pending.clear();
        cached.clear();
    }

    private record Cached(GitWorktreeManager.RepositoryInspection inspection, Instant expiresAt) { }
}
