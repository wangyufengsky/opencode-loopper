package io.opencode.loopper.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import tools.jackson.databind.ObjectMapper;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.VerificationState;
import io.opencode.loopper.persistence.BinaryArtifactRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BinaryArtifactPersistenceServiceTest {
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final BinaryArtifactPersistenceService service = new BinaryArtifactPersistenceService(mapper, new ObjectMapper());

    @Test
    void storesOnlyBrowserReferenceMetadataWithEveryAuditForeignKey() {
        VerifierOutcome browser = new VerifierOutcome("BROWSER", VerificationState.PASS, "ok", Map.of("artifacts", List.of(
                reference("BROWSER_SCREENSHOT", "image/png", "artifacts/proof.png"),
                reference("BROWSER_TRACE", "application/zip", "artifacts/proof.zip"))));

        service.persistBrowserArtifacts("task-1", "attempt-1", "session-1", "verification-1", browser);

        ArgumentCaptor<BinaryArtifactRow> rows = ArgumentCaptor.forClass(BinaryArtifactRow.class);
        verify(mapper, times(2)).insertBinaryArtifact(rows.capture());
        assertThat(rows.getAllValues()).extracting(BinaryArtifactRow::kind)
                .containsExactly("BROWSER_SCREENSHOT", "BROWSER_TRACE");
        assertThat(rows.getAllValues()).allSatisfy(row -> {
            assertThat(row.taskId()).isEqualTo("task-1");
            assertThat(row.attemptId()).isEqualTo("attempt-1");
            assertThat(row.executionSessionId()).isEqualTo("session-1");
            assertThat(row.verificationResultId()).isEqualTo("verification-1");
            assertThat(row.relativePath()).startsWith("artifacts/");
            assertThat(row.metadataJson()).contains("127.0.0.1").doesNotContain("proof.png");
        });
    }

    @Test
    void rejectsNonRelativeArtifactPathsBeforeAnyDatabaseWrite() {
        VerifierOutcome browser = new VerifierOutcome("BROWSER", VerificationState.PASS, "ok", Map.of("artifacts", List.of(
                reference("BROWSER_SCREENSHOT", "image/png", "/tmp/proof.png"),
                reference("BROWSER_TRACE", "application/zip", "artifacts/proof.zip"))));

        assertThatThrownBy(() -> service.persistBrowserArtifacts("task", "attempt", "session", "verification", browser))
                .isInstanceOf(TaskFailure.class).hasMessageContaining("stay below artifacts");
        verify(mapper, times(0)).insertBinaryArtifact(any());
    }

    @Test
    void validatesEveryReferenceBeforeWritingTheFirstArtifact() {
        VerifierOutcome browser = new VerifierOutcome("BROWSER", VerificationState.PASS, "ok", Map.of("artifacts", List.of(
                reference("BROWSER_SCREENSHOT", "image/png", "artifacts/proof.png"),
                reference("BROWSER_TRACE", "application/zip", "../outside.zip"))));

        assertThatThrownBy(() -> service.persistBrowserArtifacts("task", "attempt", "session", "verification", browser))
                .isInstanceOf(TaskFailure.class).hasMessageContaining("stay below artifacts");
        verify(mapper, times(0)).insertBinaryArtifact(any());
    }

    private Map<String, Object> reference(String kind, String mediaType, String relativePath) {
        return Map.of("kind", kind, "mediaType", mediaType, "relativePath", relativePath,
                "sha256", "a".repeat(64), "sizeBytes", 42L, "metadata", Map.of("url", "http://127.0.0.1:49152/proof"));
    }
}
