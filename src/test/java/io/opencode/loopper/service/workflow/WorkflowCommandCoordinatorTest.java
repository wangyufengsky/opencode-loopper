package io.opencode.loopper.service.workflow;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.WorkflowCommandRunMapper;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
class WorkflowCommandCoordinatorTest {
    @Test void repeatedPollsCannotOverlapOneJobOrQueueMoreThanFourExternalChecks() throws Exception {
        var mapper=mock(WorkflowCommandRunMapper.class);var execution=mock(WorkflowCommandExecution.class);
        when(mapper.active(anyString(),eq(32))).thenReturn(List.of("a","b","c","d","e"));
        var entered=new CountDownLatch(4);var release=new CountDownLatch(1);var exited=new CountDownLatch(4);
        doAnswer(call->{entered.countDown();try{assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();return null;}finally{exited.countDown();}}).when(execution).advance(anyString());
        var coordinator=new WorkflowCommandCoordinator(mapper,execution);
        try {
            coordinator.tick();assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<6;i++)coordinator.tick();
            verify(mapper,times(1)).active(anyString(),eq(32));for(String id:List.of("a","b","c","d"))verify(execution,times(1)).advance(id);
            verify(execution,never()).advance("e");release.countDown();assertThat(exited.await(3,TimeUnit.SECONDS)).isTrue();
        } finally {release.countDown();coordinator.destroy();}
    }
}
