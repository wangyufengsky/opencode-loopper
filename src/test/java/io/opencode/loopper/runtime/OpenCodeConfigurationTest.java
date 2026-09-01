package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.config.LoopperProperties;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenCodeConfigurationTest {
    @TempDir Path worktree;

    @Test
    void productionHttpClientWiresTheNoIoRuntimeIdentitySeparatelyFromLiveConnectionResolution() {
        LoopperProperties properties = new LoopperProperties();
        properties.getOpenCode().setMode("managed");
        OpenCodeRuntimeManager runtimeManager = mock(OpenCodeRuntimeManager.class);
        when(runtimeManager.currentIdentityNoIo()).thenReturn(new OpenCodeRuntimeManager.RuntimeIdentity(
                URI.create("http://127.0.0.1:19999"), true,
                "generation-configured", "loopper-internal-configured"));
        OpenCodeClient client = new OpenCodeConfiguration().openCodeClient(properties, runtimeManager,
                new OpenCodeCapabilityRegistry(), OpenCodeSessionRuntimeBindings.untracked());

        OpenCodeClient.SessionCreationPlan plan = client.prepareCandidateSessionCreationLocally(
                worktree, "Acceptance configured", null,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                "0123456789abcdefghijklmnopqrstuvwxyz_ABCD12");

        assertThat(plan.runtimeGenerationId()).isEqualTo("generation-configured");
        assertThat(plan.internalMcpServer()).isEqualTo("loopper-internal-configured");
        verify(runtimeManager).currentIdentityNoIo();
        verify(runtimeManager, never()).connectionForClient();
    }
}
