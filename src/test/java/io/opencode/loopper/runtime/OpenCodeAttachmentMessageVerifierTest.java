package io.opencode.loopper.runtime;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenCodeAttachmentMessageVerifierTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String uri = "loopper-attachment://snapshot/12345678-1234-1234-1234-123456789012";

    @Test
    void verifiesCompleteTextAndRejectsMissingChangedExtraOrNonSyntheticContent() {
        var expected = file("text/plain", "complete".getBytes(StandardCharsets.UTF_8));
        var banner = Map.of("type", "text", "synthetic", true, "text", "Reading MCP resource: reference (" + uri + ")");
        var content = Map.of("type", "text", "synthetic", true, "text", "complete");
        var base = Map.of("type", "text", "text", "user request");
        OpenCodeAttachmentMessageVerifier.verify(json.valueToTree(List.of(base, banner, content)), List.of(expected));
        for (var parts : List.of(List.of(base, banner), List.of(base, banner, content, content),
                List.of(base, banner, Map.of("type", "text", "synthetic", true, "text", "truncated")),
                List.of(base, banner, Map.of("type", "text", "text", "complete")))) {
            assertThatThrownBy(() -> OpenCodeAttachmentMessageVerifier.verify(json.valueToTree(parts), List.of(expected)))
                    .hasMessageContaining("ATTACHMENT_MCP_CONTENT_UNVERIFIED");
        }
    }

    @Test
    void verifiesBinaryExpansionAndRejectsTheOpenCodeOmissionMarker() {
        var file = file("application/pdf", new byte[]{1, 2, 3});
        var base = Map.of("type", "text", "text", "user request");
        var banner = Map.of("type", "text", "synthetic", true, "text", "Reading MCP resource: reference (" + uri + ")");
        var marker = Map.of("type", "text", "synthetic", true, "text", "[Binary MCP resource attached: " + uri + " (application/pdf)]");
        var blob = Map.of("type", "file", "mime", "application/pdf", "filename", uri, "url", "data:application/pdf;base64,AQID");
        OpenCodeAttachmentMessageVerifier.verify(json.valueToTree(List.of(base, banner, marker, blob)), List.of(file));
        assertThatThrownBy(() -> OpenCodeAttachmentMessageVerifier.verify(json.valueToTree(List.of(base, banner,
                Map.of("type", "text", "synthetic", true, "text", "[Binary MCP resource omitted]"))), List.of(file)))
                .hasMessageContaining("ATTACHMENT_MCP_CONTENT_UNVERIFIED");
    }

    private OpenCodeClient.FilePart file(String mime, byte[] bytes) {
        return new OpenCodeClient.FilePart("reference", mime, URI.create("file:///managed/reference.bin"), OpenCodeAttachmentResources.sha256(bytes));
    }
}
