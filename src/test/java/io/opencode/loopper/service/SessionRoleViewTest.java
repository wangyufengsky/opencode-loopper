package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RoleConfigurationService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionRoleViewTest {
    @Test void missingHistoricalSnapshotIsExplicitAndAnotherOwnerCannotReadAFrozenSummary() {
        var roles = mock(RoleConfigurationService.class);
        var view = new SessionRoleView(roles);
        when(roles.sessionSnapshot("old")).thenReturn(Optional.empty());
        assertThat(view.read("TASK", "task", "old").configured()).isFalse();
        var frozen = mock(RoleConfigurationService.SessionSnapshot.class);
        when(frozen.context()).thenReturn(new RoleConfigurationService.RoleContext(new RoleConfigurationService.OwnerRef("TASK", "another"), "IMPLEMENTATION"));
        when(roles.sessionSnapshot("foreign")).thenReturn(Optional.of(frozen));
        assertThatThrownBy(() -> view.read("TASK", "task", "foreign")).isInstanceOf(ConflictException.class);
        verify(roles, never()).resolveFrozen(any(), any());
    }
}
