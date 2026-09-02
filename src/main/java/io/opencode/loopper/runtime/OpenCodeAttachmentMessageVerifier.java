package io.opencode.loopper.runtime;

import io.opencode.loopper.domain.SessionFailure;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Exact recovery for OpenCode's native resources/read expansion, not model-generated acknowledgements. */
final class OpenCodeAttachmentMessageVerifier {
    private OpenCodeAttachmentMessageVerifier() { }

    static boolean isResourceExpansion(JsonNode parts) {
        return parts != null && parts.isArray() && parts.size() > 1
                && parts.get(1).path("text").asText("").startsWith("Reading MCP resource: ");
    }

    static void verify(JsonNode parts, List<OpenCodeClient.FilePart> files) {
        int index = 1;
        for (var expected : files) {
            JsonNode banner = parts.path(index++);
            String prefix = "Reading MCP resource: " + expected.filename() + " (";
            String text = banner.path("text").asText("");
            if (!synthetic(banner) || !text.startsWith(prefix) || !text.endsWith(")")) throw invalid();
            String uri = text.substring(prefix.length(), text.length() - 1);
            if (!uri.matches("loopper-attachment://snapshot/[0-9a-f-]{36}")) throw invalid();
            JsonNode value = parts.path(index++);
            byte[] bytes;
            if (expected.mediaType().startsWith("image/") || expected.mediaType().equals("application/pdf")) {
                if (!synthetic(value) || !value.path("text").asText("").equals(
                        "[Binary MCP resource attached: " + uri + " (" + expected.mediaType() + ")]")) throw invalid();
                JsonNode blob = parts.path(index++);
                String dataPrefix = "data:" + expected.mediaType() + ";base64,";
                String url = blob.path("url").asText("");
                if (!blob.path("type").asText("").equals("file") || !blob.path("mime").asText("").equals(expected.mediaType())
                        || !blob.path("filename").asText("").equals(uri) || !url.startsWith(dataPrefix)
                        || url.length() > 14 * 1024 * 1024) throw invalid();
                try { bytes = Base64.getDecoder().decode(url.substring(dataPrefix.length())); }
                catch (IllegalArgumentException malformed) { throw invalid(); }
            } else {
                if (!synthetic(value) || !value.path("text").isTextual()) throw invalid();
                bytes = value.path("text").asText().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 128 * 1024) throw invalid();
            }
            if (!OpenCodeAttachmentResources.sha256(bytes).equals(expected.sha256())) throw invalid();
        }
        if (index != parts.size()) throw invalid();
    }

    private static boolean synthetic(JsonNode part) {
        return part.path("type").asText("").equals("text") && part.path("synthetic").asBoolean(false);
    }

    private static SessionFailure invalid() {
        return new SessionFailure("ATTACHMENT_MCP_CONTENT_UNVERIFIED", "ATTACHMENT_MCP_CONTENT_UNVERIFIED: OpenCode did not preserve the exact MCP attachment content");
    }
}
