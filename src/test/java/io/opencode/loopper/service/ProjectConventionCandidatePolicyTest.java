package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import io.opencode.loopper.persistence.ProjectConventionCandidateAcceptedResultRow;
import io.opencode.loopper.persistence.ProjectConventionCandidateSourceSnapshotRow;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class ProjectConventionCandidatePolicyTest {
    private static final String SOURCE = "# Human rule\n";
    private final ObjectMapper json = new ObjectMapper();
    private final ProjectConventionCandidateCodec codec = new ProjectConventionCandidateCodec(json);
    private final ProjectConventionCompilation compilation =
            new DeterministicProjectConventionCompilation(json, new ProjectConventionDocumentStore());
    private final ProjectConventionCompilation.Input input =
            new ProjectConventionCompilation.Input(SOURCE, evidence());
    private final ProjectConventionCompilationInputLoader inputs = ignored -> input;
    private final ProjectConventionCandidatePolicy policy =
            new ProjectConventionCandidatePolicy(inputs, compilation);

    @Test
    void retriesOnlyClosedMechanicalProblemsAtThreeAttemptContractAndNeverFallsBack() {
        CandidatePolicy.Decision mechanical = policy.evaluate(context(), candidateJson()
                .replace("root:test", "missing-command"));
        CandidatePolicy.Decision security = policy.evaluate(context(), candidateJson()
                .replace("\"pathIds\"", "\"permission\":\"write\",\"pathIds\""));

        assertThat(mechanical.accepted()).isFalse();
        assertThat(mechanical.retryable()).isTrue();
        assertThat(mechanical.fallbackEligible()).isFalse();
        assertThat(mechanical.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("PROJECT_CONVENTION_COMMAND_UNVERIFIED");
            assertThat(problem.allowedValues()).containsExactly("root:test");
        });
        assertThat(security.accepted()).isFalse();
        assertThat(security.retryable()).isFalse();
        assertThat(security.fallbackEligible()).isFalse();
        assertThat(security.problems()).singleElement()
                .extracting(MachineCandidateSubmission.Problem::code)
                .isEqualTo("PROJECT_CONVENTION_AUTHORITY_FIELD_FORBIDDEN");
    }

    @Test
    void sourceStoreCanonicalizesAndHashesTheCompleteEvidenceObjectBeforeRemoteIo() {
        LoopperMachineCandidateMapper mapper = mock(LoopperMachineCandidateMapper.class);
        when(mapper.insertProjectConventionCandidateSourceSnapshot(any())).thenReturn(1);
        ProjectConventionCandidateSourceSnapshotStore store =
                new ProjectConventionCandidateSourceSnapshotStore(mapper, codec, compilation);

        store.freeze(context(), true, codec.sha256(SOURCE), SOURCE,
                "stack", "a".repeat(64), evidence());

        ArgumentCaptor<ProjectConventionCandidateSourceSnapshotRow> captured =
                ArgumentCaptor.forClass(ProjectConventionCandidateSourceSnapshotRow.class);
        verify(mapper).insertProjectConventionCandidateSourceSnapshot(captured.capture());
        assertThat(captured.getValue()).satisfies(row -> {
            assertThat(row.candidateRunId()).isEqualTo("run");
            assertThat(row.projectId()).isEqualTo("p");
            assertThat(row.projectConventionDraftId()).isEqualTo("draft");
            assertThat(row.preparedOwnerVersion()).isZero();
            assertThat(row.sourceAgentsSha256()).isEqualTo(codec.sha256(SOURCE));
            assertThat(row.sourceContentSha256()).isEqualTo(codec.sha256(SOURCE));
            assertThat(row.canonicalEvidenceJson()).isEqualTo(codec.canonicalEvidence(evidence()));
            assertThat(row.evidenceSha256()).isEqualTo(codec.sha256(row.canonicalEvidenceJson()));
        });
    }

    @Test
    void inputLoaderReadsOnlyV62SnapshotAndRejectsAnyHashOrOwnerDrift() {
        LoopperMachineCandidateMapper mapper = mock(LoopperMachineCandidateMapper.class);
        ProjectConventionCandidateSourceSnapshotRow snapshot = snapshot();
        when(mapper.findProjectConventionCandidateSourceSnapshot("run"))
                .thenReturn(Optional.of(snapshot));
        ProjectConventionCompilationInputLoader loader =
                new ProjectConventionCompilationInputLoader.MapperLoader(mapper, codec);

        ProjectConventionCompilation.Input loaded = loader.load(context());
        assertThat(loaded.sourceContent()).isEqualTo(SOURCE);
        assertThat(loaded.evidenceCatalog())
                .isEqualTo(codec.requireEvidence(
                        snapshot.canonicalEvidenceJson(), snapshot.evidenceSha256()));
        verify(mapper).findProjectConventionCandidateSourceSnapshot("run");
        verifyNoMoreInteractions(mapper);

        ProjectConventionCandidateSourceSnapshotRow corruptEvidence = new ProjectConventionCandidateSourceSnapshotRow(
                snapshot.candidateRunId(), snapshot.projectId(), snapshot.projectConventionDraftId(),
                snapshot.sourceRevision(), snapshot.preparedOwnerVersion(), snapshot.contractVersion(),
                snapshot.sourceExists(), snapshot.sourceAgentsSha256(), snapshot.sourceContent(),
                snapshot.sourceContentSha256(), snapshot.projectStackProfileId(), snapshot.stackFingerprint(),
                snapshot.canonicalEvidenceJson(), "f".repeat(64), snapshot.createdAt());
        ProjectConventionCandidateSourceSnapshotRow corruptAgentsHash = copySnapshot(
                snapshot, "e".repeat(64), snapshot.sourceContentSha256(), snapshot.evidenceSha256());
        ProjectConventionCandidateSourceSnapshotRow corruptContentHash = copySnapshot(
                snapshot, snapshot.sourceAgentsSha256(), "e".repeat(64), snapshot.evidenceSha256());
        for (ProjectConventionCandidateSourceSnapshotRow corrupt : List.of(
                corruptEvidence, corruptAgentsHash, corruptContentHash)) {
            when(mapper.findProjectConventionCandidateSourceSnapshot("run"))
                    .thenReturn(Optional.of(corrupt));
            assertThatThrownBy(() -> loader.load(context()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("snapshot");
        }
    }

    @Test
    void acceptedWriterRecompilesFromSnapshotAndStoreRevalidatesEveryPersistedHash() {
        CandidatePolicy.Decision decision = policy.evaluate(context(), candidateJson());
        LoopperMachineCandidateMapper writerMapper = mock(LoopperMachineCandidateMapper.class);
        when(writerMapper.insertProjectConventionCandidateAcceptedResult(any())).thenReturn(1);
        ProjectConventionAcceptedCandidateWriter writer = new ProjectConventionAcceptedCandidateWriter(
                writerMapper, codec, inputs, compilation);
        String payloadSha = codec.sha256(decision.canonicalCandidateJson());

        writer.write(context(), decision.canonicalCandidateJson(), payloadSha);

        ArgumentCaptor<ProjectConventionCandidateAcceptedResultRow> captured =
                ArgumentCaptor.forClass(ProjectConventionCandidateAcceptedResultRow.class);
        verify(writerMapper).insertProjectConventionCandidateAcceptedResult(captured.capture());
        ProjectConventionCandidateAcceptedResultRow row = captured.getValue();
        ProjectConventionCompilation.Result compiled = compilation.compileCandidate(
                input, decision.canonicalCandidateJson());
        assertThat(row.candidatePayloadSha256()).isEqualTo(payloadSha);
        assertThat(row.canonicalResultSha256()).isEqualTo(compiled.canonicalResultSha256());
        assertThat(row.proposedContent()).isEqualTo(compiled.proposedContent());
        assertThat(row.proposedContentSha256()).isEqualTo(compiled.contentSha256());
        assertThat(row.settledDraftId()).isNull();

        LoopperMachineCandidateMapper recoveryMapper = mock(LoopperMachineCandidateMapper.class);
        when(recoveryMapper.findProjectConventionCandidateSourceSnapshot("run"))
                .thenReturn(Optional.of(snapshot()));
        when(recoveryMapper.findProjectConventionCandidateAcceptedResult("run"))
                .thenReturn(Optional.of(row));
        ProjectConventionAcceptedResultStore accepted = new ProjectConventionAcceptedResultStore(
                recoveryMapper, codec,
                new ProjectConventionCompilationInputLoader.MapperLoader(recoveryMapper, codec), compilation);
        assertThat(accepted.find("run")).hasValueSatisfying(result ->
                assertThat(result.compiled().canonicalResultSha256())
                        .isEqualTo(compiled.canonicalResultSha256()));

        for (ProjectConventionCandidateAcceptedResultRow corrupt : List.of(
                copyAccepted(row, "0".repeat(64), row.canonicalResultSha256(),
                        row.proposedContent(), row.proposedContentSha256()),
                copyAccepted(row, row.candidatePayloadSha256(), "0".repeat(64),
                        row.proposedContent(), row.proposedContentSha256()),
                copyAccepted(row, row.candidatePayloadSha256(), row.canonicalResultSha256(),
                        row.proposedContent() + "tampered", row.proposedContentSha256()),
                copyAccepted(row, row.candidatePayloadSha256(), row.canonicalResultSha256(),
                        row.proposedContent(), "0".repeat(64)))) {
            when(recoveryMapper.findProjectConventionCandidateAcceptedResult("run"))
                    .thenReturn(Optional.of(corrupt));
            assertThatThrownBy(() -> accepted.find("run"))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("deterministic compilation");
        }
    }

    private CandidatePolicy.Context context() {
        return new CandidatePolicy.Context("run",
                MachineCandidateSubmission.CandidateScope.project("p"),
                MachineCandidateSubmission.CandidateOwnerRef.projectConventionDraft("draft"),
                MachineCandidateKind.PROJECT_CONVENTION_V1,
                ProjectConventionCandidatePolicy.WORKFLOW_STEP, 7, 1,
                ProjectConventionCandidatePolicy.CONTRACT_VERSION,
                ProjectConventionCandidatePolicy.MAX_ATTEMPTS, 0);
    }

    private String candidateJson() {
        return """
                {"contractVersion":"PROJECT_CONVENTION_V1","componentKeys":["root"],
                 "commandIds":["root:test"],"pathIds":["root:manifest:pom.xml"]}
                """;
    }

    private ProjectConventionCandidateSourceSnapshotRow snapshot() {
        String canonicalEvidence = codec.canonicalEvidence(evidence());
        return new ProjectConventionCandidateSourceSnapshotRow(
                "run", "p", "draft", 7, 0, "PROJECT_CONVENTION_V1", 1,
                codec.sha256(SOURCE), SOURCE, codec.sha256(SOURCE), "stack", "a".repeat(64),
                canonicalEvidence, codec.sha256(canonicalEvidence), "now");
    }

    private static ProjectConventionCandidateSourceSnapshotRow copySnapshot(
            ProjectConventionCandidateSourceSnapshotRow row,
            String sourceAgentsSha256,
            String sourceContentSha256,
            String evidenceSha256) {
        return new ProjectConventionCandidateSourceSnapshotRow(
                row.candidateRunId(), row.projectId(), row.projectConventionDraftId(),
                row.sourceRevision(), row.preparedOwnerVersion(), row.contractVersion(), row.sourceExists(),
                sourceAgentsSha256, row.sourceContent(), sourceContentSha256, row.projectStackProfileId(),
                row.stackFingerprint(), row.canonicalEvidenceJson(), evidenceSha256, row.createdAt());
    }

    private static ProjectConventionCandidateAcceptedResultRow copyAccepted(
            ProjectConventionCandidateAcceptedResultRow row,
            String candidatePayloadSha256,
            String canonicalResultSha256,
            String proposedContent,
            String proposedContentSha256) {
        return new ProjectConventionCandidateAcceptedResultRow(
                row.candidateRunId(), row.projectId(), row.projectConventionDraftId(), row.sourceRevision(),
                row.ownerVersion(), row.contractVersion(), row.canonicalCandidateJson(),
                candidatePayloadSha256, canonicalResultSha256, proposedContent, proposedContentSha256,
                row.settledDraftId(), row.createdAt(), row.updatedAt(), row.version());
    }

    private static ProjectConventionCompilation.EvidenceCatalog evidence() {
        return new ProjectConventionCompilation.EvidenceCatalog("a".repeat(64), List.of(
                new ProjectConventionCompilation.ComponentEvidence(
                        "root", ".", List.of("java"), List.of("maven"), List.of("junit"))), List.of(
                new ProjectConventionCompilation.CommandEvidence(
                        "root:test", "root", List.of("mvn", "test"))), List.of(
                new ProjectConventionCompilation.PathEvidence(
                        "root:root", "root", ".", ProjectConventionCompilation.PathKind.COMPONENT_ROOT),
                new ProjectConventionCompilation.PathEvidence(
                        "root:manifest:pom.xml", "root", "pom.xml",
                        ProjectConventionCompilation.PathKind.MANIFEST)));
    }
}
