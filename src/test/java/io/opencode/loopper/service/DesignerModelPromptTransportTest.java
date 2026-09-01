package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DesignerModelPromptTransportTest {
    @Test
    void exactLookupAndPromptPostAreSeparateDurableSagaSteps() {
        OpenCodeClient openCode = mock(OpenCodeClient.class);
        DesignerModelPromptTransport transport = new DesignerModelPromptTransport(
                openCode, mock(DesignerAttachmentContext.class), mock(ObjectMapper.class));
        OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                "remote-1", Path.of("/tmp/project"));
        OpenCodeClient.PromptRequest request = OpenCodeClient.PromptRequest.text("repair");
        DesignerModelPromptTransport.PreparedPrompt prepared =
                new DesignerModelPromptTransport.PreparedPrompt(request, "sha-1");
        OpenCodeClient.MessageLookup lookup = new OpenCodeClient.MessageLookup(true, false, null);
        when(openCode.findPromptMessage(remote, request, "sha-1")).thenReturn(lookup);

        assertThat(transport.lookupPrompt(remote, prepared)).isSameAs(lookup);
        verify(openCode, never()).promptAsync(remote, request);

        transport.dispatchPrompt(remote, prepared);
        verify(openCode).promptAsync(remote, request);
    }
}
