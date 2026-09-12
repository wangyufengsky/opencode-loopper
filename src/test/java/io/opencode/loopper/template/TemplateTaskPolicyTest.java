package io.opencode.loopper.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class TemplateTaskPolicyTest {
    @Test void onlyTheExplicitNewVersionSkipsDualReview() {
        assertThat(TemplateTaskDefinition.requiresDualReview("7")).isFalse();
        for (String version : List.of("1", "2", "3", "4", "5", "6", "unknown"))
            assertThat(TemplateTaskDefinition.requiresDualReview(version)).isTrue();
        assertThat(TemplateTaskDefinition.requiresDualReview(null)).isTrue();
    }

    @Test void dateDefaultsUseBeijingEvenWhenServerAndBrowserHaveAnotherDate() {
        var clock = Clock.fixed(Instant.parse("2026-09-10T16:30:00Z"), ZoneOffset.ofHours(-7));
        var range = TemplateDateRange.parse(null, null, clock);
        assertThat(range.startDate()).hasToString("2026-09-05");
        assertThat(range.endDate()).hasToString("2026-09-11");
        assertThat(range.startInclusive()).isEqualTo(Instant.parse("2026-09-04T16:00:00Z"));
        assertThat(range.endExclusive()).isEqualTo(Instant.parse("2026-09-11T16:00:00Z"));
    }

    @Test void civilDatesAreStrictAndBothBoundaryDaysAreIncluded() {
        var range = TemplateDateRange.parse("2026-09-11", "2026-09-11", Clock.systemUTC());
        assertThat(range.contains(Instant.parse("2026-09-10T16:00:00Z"))).isTrue();
        assertThat(range.contains(Instant.parse("2026-09-11T15:59:59Z"))).isTrue();
        assertThat(range.contains(Instant.parse("2026-09-11T16:00:00Z"))).isFalse();
        assertThatThrownBy(() -> TemplateDateRange.parse("2026-02-29", null, Clock.systemUTC())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> TemplateDateRange.parse("2026-09-12", "2026-09-11", Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("结束日期");
        assertThatThrownBy(() -> TemplateDateRange.parse("2026-9-01", null, Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void scoringIsDeterministicBoundedAndUsesCompetitionRanks() {
        var result = ContributionScore.rank(List.of(input("c", false, 1, 1), input("b", false, 100, 4),
                input("a", false, 100, 4), input("bot", true, 100000, 4), input("none", false, 0, 4)));
        assertThat(result).extracting(ContributionScore.Ranked::identity).containsExactly("a", "b", "c", "none", "bot");
        assertThat(result).extracting(ContributionScore.Ranked::rank).containsExactly(1, 1, 3, 4, null);
        assertThat(result.getFirst().total()).isEqualByComparingTo("100.00");
        assertThat(result.get(2).total()).isEqualByComparingTo("22.01");
        assertThat(result.get(3).total()).isEqualByComparingTo("0.00");
        assertThat(result.getLast().total()).isEqualByComparingTo("0.00");
        assertThat(ContributionScore.rank(List.of(input("none", false, 0, 0))).getFirst().quantity()).isZero();
    }

    @Test void fabricatedLevelsAndUnattributedScoresAreRejected() {
        assertThatThrownBy(() -> new ContributionScore.Assessment(5, "good", List.of("evidence"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContributionScore.Assessment(2, "", List.of("evidence"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContributionScore.Assessment(2, "good", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContributionScore.rank(List.of(input("a", false, 1, 2), input("a", false, 2, 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ContributionScore.Input input(String identity, boolean robot, double lines, int level) {
        var assessment = new ContributionScore.Assessment(level, "有提交证据", List.of("evidence"));
        return new ContributionScore.Input(identity, identity, robot, lines, assessment, assessment, assessment, assessment);
    }
}
