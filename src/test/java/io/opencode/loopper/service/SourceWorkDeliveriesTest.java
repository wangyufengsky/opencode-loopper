package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.SourceArtifactMapper;
import io.opencode.loopper.persistence.SourceTemplateRunRow;
import io.opencode.loopper.runtime.ImmutableContentStore;
import io.opencode.loopper.service.workflow.WorkflowEncoding;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class SourceWorkDeliveriesTest {
    @TempDir Path directory;
    private final SourceTemplateAdmission admission = mock(SourceTemplateAdmission.class);
    private final SourceArtifactMapper artifacts = mock(SourceArtifactMapper.class);
    private final SourceTemplateRunRow run = mock(SourceTemplateRunRow.class);
    private final WorkflowEncoding encoding = new WorkflowEncoding(new ObjectMapper());
    private final String text = "# 发布的设计\n";
    private final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    private final String hash = ImmutableContentStore.hash(bytes);
    private final SourceArtifactMapper.Artifact artifact = new SourceArtifactMapper.Artifact(
            "artifact-1", "run-1", "overview.md", "DETAILED_DESIGN", text, hash, "2026-09-28T00:00:00Z");
    private LoopperProperties properties;
    private SourceWorkDeliveries deliveries;
    @BeforeEach void prepare() {
        properties = new LoopperProperties(); properties.setDataDir(directory);
        deliveries = new SourceWorkDeliveries(admission, artifacts, encoding, properties);
        when(admission.require("run-1")).thenReturn(run);
        when(run.projectId()).thenReturn("project-1"); when(run.state()).thenReturn("COMPLETED");
        when(artifacts.manifest("run-1")).thenReturn(List.of(new SourceArtifactMapper.Metadata(
                artifact.id(), artifact.name(), artifact.kind(), hash, bytes.length)));
        when(artifacts.find("run-1", artifact.id())).thenReturn(java.util.Optional.of(artifact));
        when(artifacts.all("run-1")).thenReturn(List.of(artifact));
    }
    @Test void manifestDoesNotReadBodiesAndStableHashSurvivesRestart() {
        var manifest = deliveries.manifest("project-1", "run-1");
        verify(artifacts, never()).all(anyString()); verify(artifacts, never()).find(anyString(), anyString());
        assertThat(directory.resolve("work-files")).doesNotExist();
        var restarted = new SourceWorkDeliveries(admission, artifacts, encoding, properties);
        assertThat(restarted.manifest("project-1", "run-1")).isEqualTo(manifest);
        assertThat(restarted.read("project-1", "run-1", manifest.sha256(), artifact.id())).isEqualTo(bytes);
        assertThat(directory.resolve("work-files/run-1/objects/" + hash)).hasBinaryContent(bytes);
    }
    @Test void scopesAndPartialPublicationNeverPermitConsumption() {
        assertThatThrownBy(() -> deliveries.manifest("project-2", "run-1")).isInstanceOf(NotFoundException.class);
        when(run.state()).thenReturn("REPORTING");
        deliveries.freeze("run-1");
        assertThatThrownBy(() -> deliveries.manifest("project-1", "run-1")).isInstanceOf(ConflictException.class);
        verify(artifacts, never()).manifest(anyString());
        assertThat(directory.resolve("work-files/run-1/objects/" + hash)).hasBinaryContent(bytes);
    }
    @Test void changedManifestOrForeignFileCannotReplacePinnedInput() {
        var manifest = deliveries.manifest("project-1", "run-1");
        assertThatThrownBy(() -> deliveries.read("project-1", "run-1", manifest.sha256(), "foreign-file"))
                .isInstanceOf(NotFoundException.class);
        when(artifacts.manifest("run-1")).thenReturn(List.of(new SourceArtifactMapper.Metadata(
                "new-file", artifact.name(), artifact.kind(), hash, bytes.length)));
        assertThatThrownBy(() -> deliveries.read("project-1", "run-1", manifest.sha256(), artifact.id()))
                .isInstanceOf(ConflictException.class);
        verify(artifacts, never()).find(anyString(), anyString());
    }
    @Test void nativeBodyAndMetadataMustAgreeBeforePublishingAnyNewBytes() {
        var manifest = deliveries.manifest("project-1", "run-1");
        when(artifacts.find("run-1", artifact.id())).thenReturn(java.util.Optional.of(new SourceArtifactMapper.Artifact(
                artifact.id(), artifact.runId(), artifact.name(), artifact.kind(), "changed", hash, artifact.createdAt())));
        assertThatThrownBy(() -> deliveries.read("project-1", "run-1", manifest.sha256(), artifact.id()))
                .isInstanceOf(ConflictException.class);
        assertThat(directory.resolve("work-files")).doesNotExist();
    }
    @Test void damagedManagedObjectIsRetainedEvenWhenDatabaseCanRecreateItsOriginalBytes() throws Exception {
        var manifest = deliveries.manifest("project-1", "run-1");
        deliveries.read("project-1", "run-1", manifest.sha256(), artifact.id());
        Path object = directory.resolve("work-files/run-1/objects/" + hash);
        Files.writeString(object, "changed by another writer");
        assertThatThrownBy(() -> deliveries.read("project-1", "run-1", manifest.sha256(), artifact.id()))
                .isInstanceOf(ConflictException.class);
        assertThat(Files.readString(object)).isEqualTo("changed by another writer");
    }
}
