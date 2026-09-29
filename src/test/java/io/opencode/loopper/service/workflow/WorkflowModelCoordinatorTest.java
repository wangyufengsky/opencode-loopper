package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.WorkflowModelMapper;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkflowModelCoordinatorTest {
    @Test void recoveryPreservesTheActionableWorkspaceFailureCode() throws Exception {
        var mapper=mock(WorkflowModelMapper.class);var execution=mock(WorkflowModelExecution.class);var store=mock(WorkflowModelStore.class);
        when(mapper.active(anyString(),eq(32))).thenReturn(List.of("attempt"));
        doThrow(new io.opencode.loopper.service.ConflictException("WORKFLOW_WORKSPACE_CHANGED","工作区有未预期的修改")).when(execution).advance("attempt");
        var saved=new CountDownLatch(1);
        doAnswer(call->{saved.countDown();return null;}).when(store).suspend("attempt","WORKFLOW_WORKSPACE_CHANGED");
        var coordinator=new WorkflowModelCoordinator(mapper,execution,store);
        try {coordinator.tick();assertThat(saved.await(3,TimeUnit.SECONDS)).isTrue();}
        finally {coordinator.destroy();}
        assertThat(WorkflowFailures.safe("path / private content")).isEqualTo("WORKFLOW_MODEL_RECOVERY_REQUIRED");
    }
    @Test void repeatedTicksNeverOverlapOneAttemptsIoAndARecoverableFailureIsSaved() throws Exception {
        var mapper=mock(WorkflowModelMapper.class); var execution=mock(WorkflowModelExecution.class); var store=mock(WorkflowModelStore.class);
        when(mapper.active(anyString(),eq(32))).thenReturn(List.of("attempt"));
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var suspended=new CountDownLatch(1);
        doAnswer(call->{ entered.countDown(); assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();
            throw new SessionFailure("OPENCODE_PROMPT_FAILED","暂时无法确定投递结果"); }).when(execution).advance("attempt");
        doAnswer(call->{ suspended.countDown(); return null; }).when(store).suspend("attempt","OPENCODE_PROMPT_FAILED");
        var coordinator=new WorkflowModelCoordinator(mapper,execution,store);
        try {
            coordinator.tick(); assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            for (int i=0;i<8;i++) coordinator.tick();
            verify(execution,times(1)).advance("attempt");
            release.countDown(); assertThat(suspended.await(3,TimeUnit.SECONDS)).isTrue();
            verify(store,times(1)).suspend("attempt","OPENCODE_PROMPT_FAILED");
        } finally { release.countDown(); coordinator.destroy(); }
    }
    @Test void blockedSessionsCannotCreateAnUnboundedIoQueue() throws Exception {
        var mapper=mock(WorkflowModelMapper.class); var execution=mock(WorkflowModelExecution.class); var store=mock(WorkflowModelStore.class);
        when(mapper.active(anyString(),eq(32))).thenReturn(List.of("a","b","c","d","e"));
        var entered=new CountDownLatch(4); var release=new CountDownLatch(1); var exited=new CountDownLatch(4);
        var concurrent=new AtomicInteger(); var maximum=new AtomicInteger();
        doAnswer(call->{
            maximum.accumulateAndGet(concurrent.incrementAndGet(),Math::max); entered.countDown();
            try { assertThat(release.await(3,TimeUnit.SECONDS)).isTrue(); return null; }
            finally { concurrent.decrementAndGet(); exited.countDown(); }
        }).when(execution).advance(anyString());
        var coordinator=new WorkflowModelCoordinator(mapper,execution,store);
        try {
            coordinator.tick(); assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            coordinator.tick(); verify(mapper,times(1)).active(anyString(),eq(32));
            verify(execution,never()).advance("e"); assertThat(maximum.get()).isEqualTo(4);
            release.countDown(); assertThat(exited.await(3,TimeUnit.SECONDS)).isTrue();
            verifyNoInteractions(store);
        } finally { release.countDown(); coordinator.destroy(); }
    }
}
