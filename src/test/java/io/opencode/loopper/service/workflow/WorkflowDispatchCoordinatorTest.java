package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.WorkflowControlMapper;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class WorkflowDispatchCoordinatorTest {
    @Test void persistedActiveControlsAreReadAgainAfterRestartAndOneRequirementNeverOverlaps() throws Exception {
        var mapper=mock(WorkflowControlMapper.class);var execution=mock(WorkflowDispatchExecution.class);
        when(mapper.active(anyString(),eq(32))).thenReturn(List.of("requirement"));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();try {assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();return null;}
            finally {finished.countDown();}}).when(execution).advance("requirement");
        var coordinator=new WorkflowDispatchCoordinator(mapper,execution);
        try {
            coordinator.tick();assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<8;i++)coordinator.tick();
            verify(execution,times(1)).advance("requirement");
            release.countDown();assertThat(finished.await(3,TimeUnit.SECONDS)).isTrue();
        } finally {release.countDown();coordinator.destroy();}
        var recovered=new CountDownLatch(1);doAnswer(call->{recovered.countDown();return null;}).when(execution).advance("requirement");
        var restarted=new WorkflowDispatchCoordinator(mapper,execution);
        try {restarted.tick();assertThat(recovered.await(3,TimeUnit.SECONDS)).isTrue();}
        finally {restarted.destroy();}
        verify(execution,times(2)).advance("requirement");
    }
    @Test void slowPreflightHasABoundedQueueAndDoesNotBlockOtherRequirements() throws Exception {
        var mapper=mock(WorkflowControlMapper.class);var execution=mock(WorkflowDispatchExecution.class);
        when(mapper.active(anyString(),eq(32))).thenReturn(List.of("a","b","c","d","e"));
        var entered=new CountDownLatch(4);var release=new CountDownLatch(1);var finished=new CountDownLatch(4);
        doAnswer(call->{entered.countDown();try {assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();return null;}
            finally {finished.countDown();}}).when(execution).advance(anyString());
        var coordinator=new WorkflowDispatchCoordinator(mapper,execution);
        try {
            coordinator.tick();assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            coordinator.tick();verify(mapper,times(1)).active(anyString(),eq(32));verify(execution,never()).advance("e");
            release.countDown();assertThat(finished.await(3,TimeUnit.SECONDS)).isTrue();
        } finally {release.countDown();coordinator.destroy();}
    }
}
