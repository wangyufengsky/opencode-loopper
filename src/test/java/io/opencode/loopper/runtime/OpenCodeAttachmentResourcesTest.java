package io.opencode.loopper.runtime;

import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenCodeAttachmentResourcesTest {
    @TempDir Path temp;
    private final InternalMcpRuntimeAccess access = new InternalMcpRuntimeAccess();
    private final InternalMcpCredentialProvider.Credentials credentials = new InternalMcpCredentialProvider(() -> 18083).issue();
    private final OpenCodeAttachmentResources resources = new OpenCodeAttachmentResources(access);

    @Test
    void nativeResourceDescriptorReadsFullImmutableTextAndRequiresAReceipt() throws Exception {
        access.activate(credentials);
        String text = "x".repeat(128 * 1024);
        var file = file(text.getBytes(StandardCharsets.UTF_8), "text/plain");
        var parts = resources.prepare("ses-one", credentials.generation(), credentials.serverName(), List.of(file));
        String uri = (String) parts.getFirst().get("url");
        assertThat(uri).startsWith("loopper-attachment://snapshot/").doesNotContain(temp.toString());
        assertThat(parts.getFirst().get("source")).isInstanceOf(java.util.Map.class);
        assertThat(parts.toString()).doesNotContain("file://", "data:", text);
        assertThatThrownBy(() -> resources.requireRead("ses-one")).hasMessageContaining("not been read");
        Files.writeString(Path.of(file.managedUri()), "changed after immutable grant");
        var read = resources.read(uri);
        assertThat(((McpSchema.TextResourceContents) read.contents().getFirst()).text()).isEqualTo(text);
        resources.awaitRead("ses-one", Duration.ofMillis(10));
        resources.requireRead("ses-one");
    }

    @Test
    void refusesUnknownRevokedAndPriorGenerationGrantsWithoutLeakingPaths() throws Exception {
        access.activate(credentials);
        var file = file("test".getBytes(StandardCharsets.UTF_8), "text/plain");
        String first = (String) resources.prepare("ses-one", credentials.generation(), credentials.serverName(), List.of(file)).getFirst().get("url");
        String second = (String) resources.prepare("ses-two", credentials.generation(), credentials.serverName(), List.of(file)).getFirst().get("url");
        assertThat(first).isNotEqualTo(second);
        assertThatThrownBy(() -> resources.read("loopper-attachment://snapshot/unknown")).hasMessageNotContaining(temp.toString());
        resources.revoke("ses-one");
        assertThatThrownBy(() -> resources.read(first)).hasMessageContaining("unavailable");
        resources.read(second);
        access.activate(new InternalMcpCredentialProvider(() -> 18083).issue());
        assertThatThrownBy(() -> resources.read(second)).hasMessageContaining("unavailable");
        assertThatThrownBy(() -> resources.prepare("ses-three", credentials.generation(), credentials.serverName(), List.of(file)))
                .hasMessageContaining("managed MCP");
    }

    @Test
    void unreadResourceFailsClosedAndCannotBeReplacedByAnotherDispatch() throws Exception {
        access.activate(credentials);
        var file = file("test".getBytes(StandardCharsets.UTF_8), "text/plain");
        resources.prepare("ses-one", credentials.generation(), credentials.serverName(), List.of(file));
        assertThatThrownBy(() -> resources.awaitRead("ses-one", Duration.ofMillis(1))).hasMessageContaining("not been read");
        assertThatThrownBy(() -> resources.prepare("ses-one", credentials.generation(), credentials.serverName(), List.of(file)))
                .hasMessageContaining("not been read");
        resources.requireRead("unattached-session");
    }

    @Test
    void rejectsOversizedUnsupportedInvalidUtf8AndChangedSnapshotsBeforeGranting() throws Exception {
        access.activate(credentials);
        for (var file : List.of(file(new byte[128 * 1024 + 1], "text/plain"),
                file(new byte[]{(byte) 0xc3, (byte) 0x28}, "application/json"),
                file(new byte[]{1}, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                file(new byte[10 * 1024 * 1024 + 1], "image/png"))) {
            assertThatThrownBy(() -> resources.prepare("ses-one", credentials.generation(), credentials.serverName(), List.of(file)))
                    .isInstanceOf(io.opencode.loopper.domain.SessionFailure.class);
        }
        var modified = file("original".getBytes(StandardCharsets.UTF_8), "text/plain");
        Files.writeString(Path.of(modified.managedUri()), "tampered");
        assertThatThrownBy(() -> resources.prepare("ses-one", credentials.generation(), credentials.serverName(), List.of(modified)))
                .hasMessageContaining("SHA-256");
    }

    @Test
    void preservesSupportedImageAndPdfBlobs() throws Exception {
        access.activate(credentials);
        for (String mime : List.of("image/png", "application/pdf")) {
            var file = file(new byte[]{1, 2, 3}, mime);
            String uri = (String) resources.prepare(mime, credentials.generation(), credentials.serverName(), List.of(file)).getFirst().get("url");
            var content = (McpSchema.BlobResourceContents) resources.read(uri).contents().getFirst();
            assertThat(content.mimeType()).isEqualTo(mime);
            assertThat(content.blob()).isEqualTo("AQID");
        }
    }

    @Test
    void expirationAndOversizedManifestFailClosedWithoutWaitingInTheTest() throws Exception {
        access.activate(credentials);
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var expiring = new OpenCodeAttachmentResources(access, clock::get);
        var file = file("test".getBytes(StandardCharsets.UTF_8), "text/plain");
        assertThatThrownBy(() -> expiring.prepare("too-many", credentials.generation(), credentials.serverName(), java.util.Collections.nCopies(25, file)))
                .hasMessageContaining("capacity");
        String uri = (String) expiring.prepare("expires", credentials.generation(), credentials.serverName(), List.of(file)).getFirst().get("url");
        clock.addAndGet(Duration.ofMinutes(16).toNanos());
        assertThatThrownBy(() -> expiring.read(uri)).hasMessageContaining("unavailable");
        assertThatThrownBy(() -> expiring.requireRead("expires")).hasMessageContaining("not been read");
    }

    private OpenCodeClient.FilePart file(byte[] bytes, String mime) throws Exception {
        Path path = Files.write(temp.resolve(java.util.UUID.randomUUID() + ".bin"), bytes);
        return new OpenCodeClient.FilePart("reference", mime, path.toUri(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
}
