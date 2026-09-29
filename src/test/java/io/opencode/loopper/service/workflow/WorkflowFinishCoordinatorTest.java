package io.opencode.loopper.service.workflow;
import static org.mockito.Mockito.*;
import io.opencode.loopper.persistence.WorkflowFinishMapper;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
class WorkflowFinishCoordinatorTest {
    @Test void blockedFirstPageCannotStarveLaterStopsAndRestartRescansPersistedIntent() {
        var mapper=mock(WorkflowFinishMapper.class);var drain=mock(WorkflowFinishDrain.class);var finishes=mock(WorkflowFinishes.class);
        when(mapper.pending("",16)).thenReturn(List.of("req"));when(mapper.pending("req",16)).thenReturn(List.of());
        when(mapper.find("req")).thenReturn(Optional.of(new WorkflowFinishMapper.Intent("req","FAILED","原因",1,1,"now",null)));
        var first=IntStream.range(0,32).mapToObj(n->String.format("%02d",n)).toList();
        when(mapper.attempts("req","",32)).thenReturn(first);when(mapper.attempts("req","31",32)).thenReturn(List.of("32"));
        doThrow(new IllegalStateException("unknown adapter")).when(drain).stop("req","00");
        var coordinator=new WorkflowFinishCoordinator(mapper,drain,finishes);
        coordinator.tick();coordinator.tick();coordinator.tick();
        verify(drain).stop("req","32");verify(finishes,times(2)).finalizeReady("req");
        new WorkflowFinishCoordinator(mapper,drain,finishes).tick();verify(drain,times(2)).stop("req","00");
    }
    @Test void oneFailedRequirementDoesNotPreventAnotherFinalization() {
        var mapper=mock(WorkflowFinishMapper.class);var drain=mock(WorkflowFinishDrain.class);var finishes=mock(WorkflowFinishes.class);
        when(mapper.pending("",16)).thenReturn(List.of("a","b"));
        when(finishes.finalizeReady("a")).thenThrow(new IllegalStateException("rollback"));when(finishes.finalizeReady("b")).thenReturn(true);
        new WorkflowFinishCoordinator(mapper,drain,finishes).tick();verify(finishes).finalizeReady("b");
    }
}
