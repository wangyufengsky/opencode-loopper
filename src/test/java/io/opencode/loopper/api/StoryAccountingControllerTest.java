package io.opencode.loopper.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.service.BadRequestException;
import io.opencode.loopper.service.StoryAccountingActivityService;
import io.opencode.loopper.service.StoryAccountingCoordinator;
import org.junit.jupiter.api.Test;

class StoryAccountingControllerTest {
    @Test void retryRequiresLocalUiAndReturnsTheNewCallWithoutWaitingForModelOutput() {
        var activity = mock(StoryAccountingActivityService.class);
        var coordinator = mock(StoryAccountingCoordinator.class);
        var controller = new StoryAccountingController(activity, coordinator);
        assertThatThrownBy(() -> controller.retry("old", null)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(coordinator, activity);
        when(coordinator.retry("old")).thenReturn("new");
        var view = mock(StoryAccountingActivityService.CallView.class);
        when(activity.snapshot("new")).thenReturn(view);
        assertThat(controller.retry("old", "1")).isSameAs(view);
        verify(coordinator).retry("old");
        verify(activity, never()).get(anyString());
    }
}
