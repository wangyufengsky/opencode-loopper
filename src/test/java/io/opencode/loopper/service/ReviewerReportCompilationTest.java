package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.runtime.OpenCodeStructuredSchemas;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReviewerReportCompilationTest {
    private final ReviewerReportCompilation compilation =
            new DeterministicReviewerReportCompilation(new ObjectMapper());

    @Test
    void compilesEveryFindingAgainstImmutableSourceFactsAndProducesStableHashes() {
        ReviewerReportCompilation.Candidate candidate = candidate(List.of(
                finding("src/Main.java", 2), finding("src/Name`WithTick.java", 1)));
        ReviewerReportCompilation.Input input = new ReviewerReportCompilation.Input(candidate, List.of(
                source("src/Main.java", 4, "class Main {\n}\n"),
                source("src/Name`WithTick.java", 1, "record Name() {}\n")));

        ReviewerReportCompilation.Result first = compilation.compile(input);
        ReviewerReportCompilation.Result second = compilation.compile(input);

        assertThat(first.accepted()).isTrue();
        assertThat(first.evidence()).hasSize(2);
        assertThat(first.markdown()).contains("证据：``src/Name`WithTick.java:1``");
        assertThat(first.contentSha256()).hasSize(64);
        assertThat(first.sourceSnapshotSha256()).hasSize(64);
        assertThat(first.canonicalResultSha256()).hasSize(64);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void compilesAnEmptyFindingSetWithoutInventingEvidence() {
        ReviewerReportCompilation.Result result = compilation.compile(new ReviewerReportCompilation.Input(
                candidate(List.of()), List.of()));

        assertThat(result.accepted()).isTrue();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.markdown()).contains("## 已确认发现\n\n无。");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) OpenCodeStructuredSchemas
                .schema(OpenCodeStructuredSchemas.REVIEWER_REPORT_V1).get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> findings = (Map<String, Object>) properties.get("findings");
        assertThat(findings).containsEntry("maxItems", 128).doesNotContainKey("minItems");
    }

    @Test
    void rejectsTheWholeCandidateWhenOneFindingIsMissingFromTheSourceManifest() {
        ReviewerReportCompilation.Result result = compilation.compile(new ReviewerReportCompilation.Input(
                candidate(List.of(finding("src/Main.java", 1), finding("missing.java", 1))),
                List.of(source("src/Main.java", 2, "class Main {}\n"))));

        assertThat(result.accepted()).isFalse();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("REVIEWER_EVIDENCE_PATH_MISSING");
            assertThat(problem.pointer()).isEqualTo("/findings/1/path");
            assertThat(problem.problemClass()).isEqualTo(ReviewerReportCompilation.ProblemClass.MECHANICAL);
        });
        assertThat(result.retryable()).isTrue();
    }

    @Test
    void boundsMissingPathAllowedValuesForLargePreIoManifest() {
        List<ReviewerReportCompilation.SourceFile> manifest = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            manifest.add(source("src/File%02d.java".formatted(index), 1, "line\n"));
        }

        ReviewerReportCompilation.Result result = compilation.compile(new ReviewerReportCompilation.Input(
                candidate(List.of(finding("missing.java", 1))), manifest));

        assertThat(result.accepted()).isFalse();
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("REVIEWER_EVIDENCE_PATH_MISSING");
            assertThat(problem.allowedValues()).hasSize(32)
                    .containsExactlyElementsOf(manifest.stream().limit(32)
                            .map(ReviewerReportCompilation.SourceFile::path).toList());
        });
    }

    @Test
    void rejectsTraversalAsANonRetryableSecurityProblem() {
        ReviewerReportCompilation.Result result = compilation.compile(new ReviewerReportCompilation.Input(
                candidate(List.of(finding("../secret.txt", 1))), List.of()));

        assertThat(result.accepted()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("REVIEWER_EVIDENCE_PATH_UNSAFE");
            assertThat(problem.problemClass()).isEqualTo(ReviewerReportCompilation.ProblemClass.SECURITY);
        });
    }

    @Test
    void rejectsProtectedEnvironmentEvidenceAsANonRetryableSecurityProblem() {
        ReviewerReportCompilation.Result result = compilation.compile(new ReviewerReportCompilation.Input(
                candidate(List.of(finding("config/.env.production", 1))),
                List.of(source("config/.env.production", 1, "SECRET=value\n"))));

        assertThat(result.accepted()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo("REVIEWER_EVIDENCE_PATH_UNSAFE");
            assertThat(problem.problemClass()).isEqualTo(ReviewerReportCompilation.ProblemClass.SECURITY);
        });
    }

    @Test
    void rejectsAnOutOfRangeLineWithoutRetainingPartialEvidence() {
        ReviewerReportCompilation.Result result = compilation.compile(new ReviewerReportCompilation.Input(
                candidate(List.of(finding("src/Main.java", 3))),
                List.of(source("src/Main.java", 2, "line one\nline two\n"))));

        assertThat(result.accepted()).isFalse();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.problems()).singleElement()
                .extracting(ReviewerReportCompilation.Problem::code)
                .isEqualTo("REVIEWER_EVIDENCE_LINE_INVALID");
    }

    @Test
    void rejectsAServiceRenderedReportAboveTheMarkdownBoundary() {
        List<ReviewerReportCompilation.Finding> findings = new ArrayList<>();
        for (int index = 0; index < 128; index++) {
            findings.add(new ReviewerReportCompilation.Finding("INFO", "Finding " + index,
                    "x".repeat(4_000), "src/Main.java", 1, "y".repeat(4_000)));
        }
        ReviewerReportCompilation.Result result = compilation.compile(new ReviewerReportCompilation.Input(
                candidate(findings), List.of(source("src/Main.java", 1, "line\n"))));

        assertThat(result.accepted()).isFalse();
        assertThat(result.problems()).singleElement()
                .extracting(ReviewerReportCompilation.Problem::code)
                .isEqualTo("REVIEWER_REPORT_CONTENT_INVALID");
    }

    private static ReviewerReportCompilation.Candidate candidate(
            List<ReviewerReportCompilation.Finding> findings) {
        return new ReviewerReportCompilation.Candidate("Reviewer report", "Deterministic summary.",
                findings, List.of("Only managed files were inspected."));
    }

    private static ReviewerReportCompilation.Finding finding(String path, int line) {
        return new ReviewerReportCompilation.Finding("INFO", "Finding", "Detail", path, line,
                "Recommendation");
    }

    private static ReviewerReportCompilation.SourceFile source(String path, long lineCount, String content) {
        return new ReviewerReportCompilation.SourceFile(path,
                content.getBytes(StandardCharsets.UTF_8).length, lineCount, sha256(content));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
