package io.opencode.loopper.template;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.template.SnapshotReview.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SnapshotReviewLightweightPolicyTest {
    @Test void tinyHunksKeepHeaderAndCodeTogetherWithoutLosingDeletionEvidence() {
        String diff = "diff --git a/a.java b/a.java\n--- a/a.java\n+++ b/a.java\n@@ -1 +1 @@\n-old\n+new\n@@ -99 +99 @@\n-x\n+y\n";
        var compact = SnapshotReviewUnits.compact("u", new String[]{"M", "a.java", "a.java"}, diff, null);
        assertThat(compact).hasSize(1);
        assertThat(compact.getFirst().excerpt()).endsWith(diff);
        assertThat(SnapshotReviewUnits.compile("u", new String[]{"M", "a.java", "a.java"}, diff, null)).hasSize(3);
        var deleted = SnapshotReviewUnits.compact("d", new String[]{"D", "d.java", "d.java"}, diff, null);
        assertThat(SnapshotReviewLightweightPolicy.plan(deleted).groups()).hasSize(1);
    }

    @Test void oneHundredTwentyTinyUnitsUseTwoBatchesInsteadOfTenPlanningSessions() {
        var units = new ArrayList<Unit>();
        for (int i = 0; i < 120; i++) units.add(unit("u" + i, "file" + i, "x".repeat(100), null));
        var plan = SnapshotReviewLightweightPolicy.plan(units);
        assertThat(plan.groups()).hasSize(2);
        assertThat(plan.groups().stream().flatMap(g -> g.unitIds().stream())).containsExactlyInAnyOrderElementsOf(units.stream().map(Unit::id).toList());
        assertThat(plan.relations()).isEmpty();
        assertThat(plan).isEqualTo(SnapshotReviewLightweightPolicy.plan(units));
    }

    @Test void capacityAndExclusionsKeepReadableLongLinesAndExplicitLimitations() {
        var units = new ArrayList<Unit>();
        for (int i = 0; i < 5; i++) units.add(unit("u" + i, "a.java", "x".repeat(23000), null));
        units.add(unit("secret", ".env", "", "敏感文件未读取"));
        units.addAll(SnapshotReviewUnits.compact("long", new String[]{"FULL", "long.java", "long.java"}, "x".repeat(50000), null));
        var plan = SnapshotReviewLightweightPolicy.plan(units);
        assertThat(plan.groups().stream().flatMap(g -> g.unitIds().stream())).doesNotContain("secret").contains("long:0", "long:1", "long:2");
        for (var group : plan.groups()) {
            var input = SnapshotReviewLightweightPolicy.analysis(units, group);
            assertThat(input.units().stream().mapToInt(u -> u.excerpt().length()).sum()).isLessThanOrEqualTo(48000);
            assertThat(input.lightweight()).isTrue();
        }
    }

    @Test void reviewTargetsFindingLocationsRatherThanEveryFileInAnalysis() {
        var units = List.of(unit("a", "a.java", "a", null), unit("b", "b.java", "b", null));
        var plan = SnapshotReviewLightweightPolicy.plan(units);
        var input = SnapshotReviewLightweightPolicy.analysis(units, plan.groups().getFirst());
        var ref = new Reference("sha", "a.java", "blob", 1, 1, "a");
        var finding = new Finding("f", TemplateAnalysis.Severity.HIGH, "缺陷", "触发", "行为", "建议", Attribution.CHANGE_RELATED, List.of(ref));
        var review = SnapshotReviewLightweightPolicy.review("batch", input, new Analysis(List.of(), List.of(finding), List.of(), List.of()));
        assertThat(review.units()).extracting(Unit::id).containsExactly("a");
        assertThat(review.dependencies()).containsExactly("batch");
        assertThat(review.groups()).isEmpty();
    }

    private static Unit unit(String id, String path, String excerpt, String limitation) { return new Unit(id, path, path, "M", excerpt, limitation); }
}
