package io.opencode.loopper.template;

import java.util.List;

/** Structured model candidates contain claims only, never final artifacts, scores or task state. */
public final class TemplateAnalysis {
    private TemplateAnalysis() { }
    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW }
    public enum Side { BEFORE, AFTER }
    public record SourceLine(Side side, int line) { }
    public record Unit(String id, String commitSha, String evidenceId, String path, String disposition,
                       String patch, List<SourceLine> lines) { }
    public record Finding(Severity severity, Side side, int line, String title, String detail, String recommendation) { }
    public record UnitReview(String unitId, String summary, List<Finding> findings, List<String> limitations) { }
    public record BatchCandidate(List<UnitReview> reviews) { }
    public record ContributorCandidate(String identity, String summary, ContributionScore.Assessment value,
                                       ContributionScore.Assessment difficulty, ContributionScore.Assessment quality,
                                       ContributionScore.Assessment maintenance) { }
    public record Accepted(List<UnitReview> reviews, List<ContributorCandidate> contributors) {
        public Accepted { reviews = List.copyOf(reviews); contributors = List.copyOf(contributors); }
    }
}
