package io.opencode.loopper.runtime;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenCodeCapabilityServiceTest {
    @Test
    void exposesNativePlanWithoutSelectingItAndUsesObservedStructuredFallback() {
        OpenCodeClient client = mock(OpenCodeClient.class);
        OpenCodeCapabilityRegistry registry = new OpenCodeCapabilityRegistry();
        when(client.agents()).thenReturn(List.of(new OpenCodeClient.AgentInfo("build", "primary", null),
                new OpenCodeClient.AgentInfo("plan", "primary", null)));
        OpenCodeClient.OpenCodeModel model = new OpenCodeClient.OpenCodeModel("provider", "model", null);
        when(client.structuredOutputCapability(model)).thenAnswer(ignored -> registry.capability(
                URI.create("http://127.0.0.1:4096"), model));
        OpenCodeCapabilityService service = new OpenCodeCapabilityService(client, registry);
        OpenCodeRuntimeManager.RuntimeSnapshot runtime = new OpenCodeRuntimeManager.RuntimeSnapshot("AVAILABLE", "1.2.3",
                false, null, "http://127.0.0.1:4096", "provider/model", Instant.now(), null);

        var initial = service.capabilities(runtime);
        assertThat(initial.nativePlanAgent()).isTrue();
        assertThat(initial.defaultResponseMode()).isEqualTo("JSON_SCHEMA");
        assertThat(initial.extensionPolicy()).isEqualTo("TRUSTED_ALLOWED");

        registry.transportUnsupported(URI.create(runtime.endpoint()), model, "format rejected");
        var fallback = service.capabilities(runtime);
        assertThat(fallback.structuredOutputTransport()).isEqualTo("UNAVAILABLE");
        assertThat(fallback.defaultResponseMode()).isEqualTo("TEXT_MARKER");
    }

    @Test
    void defaultsKnownStoredSchemaDecoderVersionsToMarkerWithoutProviderProbe() {
        OpenCodeClient client = mock(OpenCodeClient.class);
        OpenCodeCapabilityRegistry registry = new OpenCodeCapabilityRegistry();
        OpenCodeClient.OpenCodeModel model = new OpenCodeClient.OpenCodeModel(
                "deepseek", "deepseek-v4-flash", null);
        URI endpoint = URI.create("http://127.0.0.1:4096");
        when(client.agents()).thenReturn(List.of());
        when(client.structuredOutputCapability(model)).thenAnswer(ignored -> registry.capability(endpoint, model));
        OpenCodeCapabilityService service = new OpenCodeCapabilityService(client, registry);
        OpenCodeRuntimeManager.RuntimeSnapshot runtime = new OpenCodeRuntimeManager.RuntimeSnapshot(
                "AVAILABLE", "1.18.18", false, null, endpoint.toString(),
                "deepseek/deepseek-v4-flash", Instant.now(), null);

        var capabilities = service.capabilities(runtime);

        assertThat(capabilities.structuredOutputTransport()).isEqualTo("UNAVAILABLE");
        assertThat(capabilities.defaultResponseMode()).isEqualTo("TEXT_MARKER");
        assertThat(capabilities.detail()).contains("1.18.18", "marker compatibility mode");
    }
}
