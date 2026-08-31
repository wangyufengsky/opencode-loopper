package io.opencode.loopper.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.runtime.OpenCodeSessionRuntimeBindings;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PersistentOpenCodeSessionRuntimeBindingsTest {
    @Test
    void persistsAndReloadsTheNonSecretManagedIdentity() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        when(mapper.findOpenCodeSessionRuntimeBinding("remote-1")).thenReturn(Optional.empty());
        when(mapper.insertOpenCodeSessionRuntimeBinding(any())).thenReturn(1);
        PersistentOpenCodeSessionRuntimeBindings bindings =
                new PersistentOpenCodeSessionRuntimeBindings(mapper);
        OpenCodeSessionRuntimeBindings.Binding desired = new OpenCodeSessionRuntimeBindings.Binding(
                "remote-1", "generation-1", OpenCodeSessionRuntimeBindings.OwnershipMode.MANAGED,
                "a".repeat(64), "loopper_internal_generation1");

        bindings.register(desired);

        ArgumentCaptor<OpenCodeSessionRuntimeBindingRow> row =
                ArgumentCaptor.forClass(OpenCodeSessionRuntimeBindingRow.class);
        verify(mapper).insertOpenCodeSessionRuntimeBinding(row.capture());
        assertThat(row.getValue().internalMcpServer()).isEqualTo("loopper_internal_generation1");
        assertThat(row.getValue().endpointFingerprint()).hasSize(64);
        when(mapper.findOpenCodeSessionRuntimeBinding("remote-1"))
                .thenReturn(Optional.of(row.getValue()));
        assertThat(bindings.find("remote-1")).contains(desired);
    }

    @Test
    void refusesToRebindOneRemoteIdToAnotherGeneration() {
        LoopperMapper mapper = mock(LoopperMapper.class);
        when(mapper.findOpenCodeSessionRuntimeBinding("remote-1")).thenReturn(Optional.of(
                new OpenCodeSessionRuntimeBindingRow("remote-1", "generation-old", "MANAGED",
                        "a".repeat(64), "loopper_internal_old", "now")));
        PersistentOpenCodeSessionRuntimeBindings bindings =
                new PersistentOpenCodeSessionRuntimeBindings(mapper);

        assertThatThrownBy(() -> bindings.register(new OpenCodeSessionRuntimeBindings.Binding(
                "remote-1", "generation-new", OpenCodeSessionRuntimeBindings.OwnershipMode.MANAGED,
                "a".repeat(64), "loopper_internal_new")))
                .isInstanceOfSatisfying(SessionFailure.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("OPENCODE_SESSION_RUNTIME_BINDING_FAILED"));
    }
}
