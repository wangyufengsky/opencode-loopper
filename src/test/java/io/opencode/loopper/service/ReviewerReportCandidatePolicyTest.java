package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.LoopperMachineCandidateMapper;
import io.opencode.loopper.persistence.ReviewerReportAcceptedResultRow;
import io.opencode.loopper.persistence.ReviewerReportSourceSnapshotRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class ReviewerReportCandidatePolicyTest {
    @Test
    void wrongLimitationsTypePointsAtTheActualFieldWithoutInvitingAuthorityFields() throws Exception {
        var result = policy.evaluate(context(), candidateJson().replace("\"limitations\":[]", "\"limitations\":\"not tested\""));
        assertThat(result.retryable()).isTrue();
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.pointer()).isEqualTo("/limitations");
            assertThat(problem.detail()).contains("字符串数组", "[", "]").doesNotContain("缺少");
        });
        assertThat(policy.evaluate(context(), candidateJson().replace("\"limitations\":[]",
                "\"limitations\":[\"not tested\"]")).accepted()).isTrue();
        assertThat(policy.evaluate(context(), candidateJson().replace("\"limitations\":[]",
                "\"limitations\":[],\"contractVersion\":\"REVIEWER_REPORT_V1\"")).retryable()).isFalse();
    }
    private final ObjectMapper json = new ObjectMapper();
    private final ReviewerReportCompilation compilation =
            new DeterministicReviewerReportCompilation(json);
    private final ReviewerReportCandidateCodec codec = new ReviewerReportCandidateCodec(json);
    private final ReviewerReportCompilation.Input compilationInput =
            new ReviewerReportCompilation.Input(candidateDocument(), List.of(source()));
    private final ReviewerReportCompilationInputLoader inputs =
            (ignored, candidate) -> new ReviewerReportCompilation.Input(candidate, compilationInput.sourceFiles());
    private final ReviewerReportCandidatePolicy policy =
            new ReviewerReportCandidatePolicy(codec, inputs, compilation);

    @Test
    void retriesOnlyClosedMechanicalEvidenceCorrectionsAndNeverFallsBack() throws Exception {
        CandidatePolicy.Decision decision = policy.evaluate(context(), candidateJson()
                .replace("src/Main.java", "src/Missing.java"));

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.retryable()).isTrue();
        assertThat(decision.fallbackEligible()).isFalse();
        assertThat(decision.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("REVIEWER_EVIDENCE_PATH_MISSING");
            assertThat(problem.allowedValues()).containsExactly("src/Main.java");
        });
    }

    @Test
    void securityAndAuthorityFieldsFailClosedWithoutRetryOrFallback() throws Exception {
        CandidatePolicy.Decision traversal = policy.evaluate(context(), candidateJson()
                .replace("src/Main.java", "../secret.txt"));
        CandidatePolicy.Decision authority = policy.evaluate(context(), candidateJson()
                .replace("\"limitations\":[]", "\"limitations\":[],\"state\":\"READY\""));

        assertThat(traversal.accepted()).isFalse();
        assertThat(traversal.retryable()).isFalse();
        assertThat(traversal.fallbackEligible()).isFalse();
        assertThat(traversal.problems()).singleElement()
                .extracting(MachineCandidateSubmission.Problem::code)
                .isEqualTo("REVIEWER_EVIDENCE_PATH_UNSAFE");
        assertThat(authority.accepted()).isFalse();
        assertThat(authority.retryable()).isFalse();
        assertThat(authority.fallbackEligible()).isFalse();
        assertThat(authority.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("REVIEWER_AUTHORITY_FIELD_FORBIDDEN");
            assertThat(problem.detail()).doesNotContain("READY");
        });
    }

    @Test
    void acceptsCanonicalCandidateAndWriterRecompilesFullReviewerResult() throws Exception {
        CandidatePolicy.Decision decision = policy.evaluate(context(), candidateJson());
        LoopperMachineCandidateMapper mapper = mock(LoopperMachineCandidateMapper.class);
        when(mapper.insertReviewerReportAcceptedResult(any())).thenReturn(1);
        ReviewerReportAcceptedCandidateWriter writer = new ReviewerReportAcceptedCandidateWriter(
                mapper, json, codec, inputs, compilation);
        String candidateSha = sha256(decision.canonicalCandidateJson());

        writer.write(context(), decision.canonicalCandidateJson(), candidateSha);

        assertThat(decision.accepted()).isTrue();
        ArgumentCaptor<ReviewerReportAcceptedResultRow> row =
                ArgumentCaptor.forClass(ReviewerReportAcceptedResultRow.class);
        verify(mapper).insertReviewerReportAcceptedResult(row.capture());
        assertThat(row.getValue()).satisfies(result -> {
            assertThat(result.candidateRunId()).isEqualTo("reviewer-run");
            assertThat(result.analysisReportId()).isEqualTo("report");
            assertThat(result.candidatePayloadSha256()).isEqualTo(candidateSha);
            assertThat(result.canonicalResultSha256()).isNotEqualTo(candidateSha);
            assertThat(result.canonicalFindingsJson()).contains("src/Main.java");
            assertThat(result.evidenceJson()).contains(sha256("line\n"));
            assertThat(result.settledAnalysisReportId()).isNull();
            assertThat(result.version()).isZero();
        });
    }

    @Test
    void sourceSnapshotStoreCanonicalizesOnlyManifestMetadata() {
        LoopperMachineCandidateMapper mapper = mock(LoopperMachineCandidateMapper.class);
        when(mapper.insertReviewerReportSourceSnapshot(any())).thenReturn(1);
        ReviewerReportSourceSnapshotStore store =
                new ReviewerReportSourceSnapshotStore(mapper, codec);

        store.freeze(context(), List.of(source()));

        ArgumentCaptor<ReviewerReportSourceSnapshotRow> row =
                ArgumentCaptor.forClass(ReviewerReportSourceSnapshotRow.class);
        verify(mapper).insertReviewerReportSourceSnapshot(row.capture());
        assertThat(row.getValue().canonicalSourceManifestJson())
                .isEqualTo("[{\"path\":\"src/Main.java\",\"sizeBytes\":5,\"lineCount\":1,"
                        + "\"sha256\":\"" + sha256("line\n") + "\"}]");
        assertThat(row.getValue().canonicalSourceManifestJson()).doesNotContain("line\\n");
        assertThat(row.getValue().sourceManifestSha256())
                .isEqualTo(sha256(row.getValue().canonicalSourceManifestJson()));
    }

    @Test
    void acceptedReaderRecompilesStoredResultAndSettlementIsOwnerBound() throws Exception {
        CandidatePolicy.Decision decision = policy.evaluate(context(), candidateJson());
        String candidateSha = sha256(decision.canonicalCandidateJson());
        ReviewerReportCompilation.Result compiled = compilation.compile(new ReviewerReportCompilation.Input(
                codec.requireCandidate(decision.canonicalCandidateJson()), List.of(source())));
        String manifest = codec.canonicalSourceManifest(List.of(source()));
        ReviewerReportSourceSnapshotRow snapshot = new ReviewerReportSourceSnapshotRow(
                "reviewer-run", "report", 7, 0, "REVIEWER_REPORT_V1", manifest,
                sha256(manifest), "now");
        ReviewerReportAcceptedResultRow unsettled = new ReviewerReportAcceptedResultRow(
                "reviewer-run", "report", 7, 1, "REVIEWER_REPORT_V1",
                compiled.canonicalCandidateJson(), compiled.canonicalFindingsJson(), compiled.markdown(),
                json.writeValueAsString(compiled.evidence()), compiled.contentSha256(),
                compiled.sourceSnapshotSha256(), candidateSha, compiled.canonicalResultSha256(),
                null, "now", "now", 0);
        ReviewerReportAcceptedResultRow settled = new ReviewerReportAcceptedResultRow(
                unsettled.candidateRunId(), unsettled.analysisReportId(), unsettled.sourceRevision(),
                unsettled.ownerVersion(), unsettled.contractVersion(), unsettled.canonicalCandidateJson(),
                unsettled.canonicalFindingsJson(), unsettled.markdown(), unsettled.evidenceJson(),
                unsettled.contentSha256(), unsettled.sourceSnapshotSha256(),
                unsettled.candidatePayloadSha256(), unsettled.canonicalResultSha256(), "report",
                unsettled.createdAt(), "later", 1);
        LoopperMachineCandidateMapper mapper = mock(LoopperMachineCandidateMapper.class);
        when(mapper.findReviewerReportSourceSnapshot("reviewer-run")).thenReturn(Optional.of(snapshot));
        when(mapper.findReviewerReportAcceptedResult("reviewer-run"))
                .thenReturn(Optional.of(unsettled), Optional.of(unsettled),
                        Optional.of(unsettled), Optional.of(settled));
        when(mapper.settleReviewerReportAcceptedResult("reviewer-run", 0, "report", "later"))
                .thenReturn(1);
        ReviewerReportAcceptedResultStore store =
                new ReviewerReportAcceptedResultStore(mapper, codec, compilation);

        assertThat(store.find("reviewer-run")).hasValueSatisfying(accepted ->
                assertThat(accepted.compiled().canonicalResultSha256())
                        .isEqualTo(compiled.canonicalResultSha256()));
        assertThatThrownBy(() -> store.settle("reviewer-run", 0, "report-other", "later"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("owner");
        assertThat(store.settle("reviewer-run", 0, "report", "later").row()
                .settledAnalysisReportId()).isEqualTo("report");
    }

    private CandidatePolicy.Context context() {
        return new CandidatePolicy.Context("reviewer-run",
                MachineCandidateSubmission.CandidateScope.designerSession("s"),
                MachineCandidateSubmission.CandidateOwnerRef.analysisReport("report"),
                MachineCandidateKind.REVIEWER_REPORT_V1,
                ReviewerReportCandidatePolicy.WORKFLOW_STEP, 7, 1,
                ReviewerReportCandidatePolicy.CONTRACT_VERSION,
                ReviewerReportCandidatePolicy.MAX_ATTEMPTS, 0);
    }

    private String candidateJson() throws Exception {
        return json.writeValueAsString(candidateDocument());
    }

    private ReviewerReportCompilation.Candidate candidateDocument() {
        return new ReviewerReportCompilation.Candidate("Reviewer report", "Summary", List.of(
                new ReviewerReportCompilation.Finding("INFO", "Finding", "Detail",
                        "src/Main.java", 1, "Recommendation")), List.of());
    }

    private ReviewerReportCompilation.SourceFile source() {
        return new ReviewerReportCompilation.SourceFile(
                "src/Main.java", 5, 1, sha256("line\n"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
