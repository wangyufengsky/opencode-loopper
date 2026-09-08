package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.runtime.GitWorktreeManager;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectInspectionCacheTest {
    @TempDir Path root;
    private ThreadPoolExecutor workers() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
    }

    @Test void sharesInFlightRefreshAndStartsTtlWhenInspectionCompletes() throws Exception {
        var git = mock(GitWorktreeManager.class);
        var now = new AtomicReference<>(Instant.parse("2026-09-08T00:00:00Z"));
        var clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var value = new GitWorktreeManager.RepositoryInspection(true, true, "main");
        when(git.inspect(root)).thenAnswer(call -> { started.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return value; });
        var cache = new ProjectInspectionCache(git, clock, workers());
        try {
            var first = cache.inspect(root.toString(), false);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cache.inspect(root.resolve(".").toString(), true)).isSameAs(first);
            now.set(now.get().plusSeconds(20));
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(value);
            now.set(now.get().plusSeconds(4));
            assertThat(cache.inspect(root.toString(), false).get(5, TimeUnit.SECONDS)).isEqualTo(value);
            verify(git, times(1)).inspect(root);
            now.set(now.get().plusSeconds(2));
            cache.inspect(root.toString(), false).get(5, TimeUnit.SECONDS);
            verify(git, times(2)).inspect(root);
        } finally { release.countDown(); cache.shutdown(); }
    }

    @Test void rejectsExcessWorkAndShutdownSettlesQueuedRequests() throws Exception {
        var git = mock(GitWorktreeManager.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(git.inspect(any())).thenAnswer(call -> { started.countDown(); release.await(); return new GitWorktreeManager.RepositoryInspection(true, false, null); });
        var cache = new ProjectInspectionCache(git, Clock.systemUTC(), workers());
        try {
            var first = cache.inspect(root.resolve("one").toString(), false);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var second = cache.inspect(root.resolve("two").toString(), false);
            var excess = cache.inspect(root.resolve("three").toString(), false);
            assertThatThrownBy(excess::join).hasCauseInstanceOf(ServiceUnavailableException.class);
            assertThat(cache.inspect(root.resolve("two").toString(), true)).isSameAs(second);
            cache.shutdown();
            assertThatThrownBy(second::join).hasCauseInstanceOf(ServiceUnavailableException.class);
            assertThatThrownBy(first::join).isInstanceOf(CompletionException.class);
            verify(git, times(1)).inspect(any());
        } finally { release.countDown(); cache.shutdown(); }
    }

    @Test void retriesAfterFailedInspectionInsteadOfCachingFailure() throws Exception {
        var git = mock(GitWorktreeManager.class);
        when(git.inspect(root)).thenThrow(new IllegalStateException("injected"))
                .thenReturn(new GitWorktreeManager.RepositoryInspection(true, true, "main"));
        var cache = new ProjectInspectionCache(git, Clock.systemUTC(), workers());
        try {
            assertThatThrownBy(() -> cache.inspect(root.toString(), false).join()).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(cache.inspect(root.toString(), false).get(5, TimeUnit.SECONDS).branch()).isEqualTo("main");
            verify(git, times(2)).inspect(root);
        } finally { cache.shutdown(); }
    }
}
