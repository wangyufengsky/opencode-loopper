package io.opencode.loopper.service;

import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.MutationMode;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.WorkflowTemplate;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.TaskProfileRouterRunRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskProfileServiceTest {

    @Test
    void monitorDoesNotStartTheSynchronouslyOwnedPendingRouterRunInParallel() throws Exception {
        LoopperMapper mapper = mock(LoopperMapper.class);
        ProjectService projects = mock(ProjectService.class);
        TaskProfileRouter router = mock(TaskProfileRouter.class);
        TaskSemanticRouter semanticRouter = mock(TaskSemanticRouter.class);
        AtomicReference<TaskProfileRouterRunRow> stored = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        DesignerSessionRow session = session();
        when(mapper.findDesignerSession(session.id())).thenReturn(Optional.of(session));
        when(mapper.findCurrentDesignerTaskProfile(session.id())).thenReturn(Optional.empty());
        when(mapper.findLatestTaskProfileRouterRun(session.id())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(mapper.listActiveTaskProfileRouterRuns()).thenAnswer(invocation ->
                stored.get() == null ? List.of() : List.of(stored.get()));
        when(mapper.insertTaskProfileRouterRun(any())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
        when(mapper.updateTaskProfileRouterRun(any())).thenAnswer(invocation -> {
            TaskProfileRouterRunRow row = invocation.getArgument(0);
            stored.set(new TaskProfileRouterRunRow(row.id(), row.designerSessionId(), row.state(),
                    row.requirementSnapshot(), row.repositoryEvidenceJson(), row.externalSessionId(),
                    row.externalSessionState(), row.responseMode(), row.semanticLabelsJson(), row.errorCode(),
                    row.errorDetail(), row.createdAt(), row.updatedAt(), row.version() + 1));
            return 1;
        });
        when(projects.get(session.projectId())).thenReturn(project());
        when(router.route(any(), anyString())).thenReturn(decision());
        when(semanticRouter.start(any(), anyString(), any())).thenAnswer(invocation -> {
            starts.incrementAndGet();
            startEntered.countDown();
            assertThat(releaseStart.await(5, TimeUnit.SECONDS)).isTrue();
            return new TaskSemanticRouter.StartResult("router-remote", "TEXT_MARKER", null, null);
        });
        TaskProfileService service = service(mapper, projects, router, semanticRouter);

        CompletableFuture<TaskProfileService.View> initialization = CompletableFuture.supplyAsync(() ->
                service.initialize(session.id(), "修改 Java 缓存刷新逻辑"));
        assertThat(startEntered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(service.pollActive()).isEmpty();
        assertThat(starts).hasValue(1);

        releaseStart.countDown();
        assertThat(initialization.get(5, TimeUnit.SECONDS).decisionState()).isEqualTo("ROUTING");
        assertThat(starts).hasValue(1);
    }

    @Test
    void losingPendingRunUpdateAbortsTheJustCreatedRemoteSession() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        ProjectService projects = mock(ProjectService.class);
        TaskProfileRouter router = mock(TaskProfileRouter.class);
        TaskSemanticRouter semanticRouter = mock(TaskSemanticRouter.class);
        DesignerSessionRow session = session();
        String now = Instant.now().toString();
        TaskProfileRouterRunRow pending = new TaskProfileRouterRunRow("run-1", session.id(), "PENDING", "需求",
                "[]", null, null, null, null, null, null,
                now, now, 0);
        when(mapper.listActiveTaskProfileRouterRuns()).thenReturn(List.of(pending));
        when(mapper.findDesignerSession(session.id())).thenReturn(Optional.of(session));
        when(projects.get(session.projectId())).thenReturn(project());
        when(semanticRouter.start(any(), anyString(), any())).thenReturn(
                new TaskSemanticRouter.StartResult("orphan-candidate", "TEXT_MARKER", null, null));
        when(mapper.updateTaskProfileRouterRun(any())).thenReturn(0);
        TaskProfileService service = service(mapper, projects, router, semanticRouter);

        assertThat(service.pollActive()).isEmpty();

        verify(semanticRouter).abortQuietly(any(), org.mockito.ArgumentMatchers.eq("orphan-candidate"));
        verify(semanticRouter, never()).poll(any(), anyString(), anyString());
    }

    private TaskProfileService service(LoopperMapper mapper, ProjectService projects, TaskProfileRouter router,
                                       TaskSemanticRouter semanticRouter) {
        return new TaskProfileService(mapper, projects, router, semanticRouter, new RolePackRegistry(),
                new ObjectMapper(), mock(PlatformTransactionManager.class));
    }

    private DesignerSessionRow session() {
        return new DesignerSessionRow("designer-1", "project-1", "PENDING_HANDOFF", "READ_ONLY",
                "2026-08-20T08:00:00Z", "2026-08-20T08:00:00Z", 0,
                null, "PENDING", "draft-1", "ROUTING", 0, 0);
    }

    private ProjectRow project() {
        return new ProjectRow("project-1", "Project", "/tmp", "", "now", "now", 1, 0);
    }

    private TaskProfileRouter.Decision decision() {
        return new TaskProfileRouter.Decision(TaskIntent.SOFTWARE_CHANGE,
                WorkflowTemplate.DIRECT_SOFTWARE_DESIGN, MutationMode.WRITE_CODE,
                List.of(ArtifactKind.SOURCE_CODE), List.of("java"), 95, false, List.of("test"));
    }
}
