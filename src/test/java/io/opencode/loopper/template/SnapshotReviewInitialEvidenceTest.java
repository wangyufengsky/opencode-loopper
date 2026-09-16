package io.opencode.loopper.template;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.template.SnapshotReview.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SnapshotReviewInitialEvidenceTest {
    private static final File BEFORE = new File("base", "old.java", "before-blob", "100644", 20, null);
    private static final File AFTER = new File("tip", "new.java", "after-blob", "100644", 20, null);
    @Test void mapsRenamedDiffAndDeletedLinesToBothVersionsWithoutDiffPrefixes() {
        String diff = "diff --git a/old.java b/new.java\n--- a/old.java\n+++ b/new.java\n@@ -1,3 +1,3 @@\n first\n-old\n+new\n last\n\\ No newline at end of file\n";
        var units = SnapshotReviewInitialEvidence.compile(SnapshotReviewUnits.compact("id", new String[]{"R", "old.java", "new.java"}, diff, null), diff, BEFORE, AFTER);
        assertThat(units.getFirst().initialEvidence()).containsExactly(
                new Reference("base", "old.java", "before-blob", 1, 3, "first\nold\nlast"),
                new Reference("tip", "new.java", "after-blob", 1, 3, "first\nnew\nlast"));
        assertThat(SnapshotReviewInitialEvidence.verified(units, Map.of("before-blob", "first\nold\nlast", "after-blob", "first\nnew\nlast")).getFirst().initialEvidence()).hasSize(2);
        assertThat(SnapshotReviewInitialEvidence.verified(units, Map.of("after-blob", "different content")).getFirst().initialEvidence()).isEmpty();
    }
    @Test void splitWithinHunkRetainsOriginalLineNumbersAndDoesNotGrantWholeFile() {
        String diff = "@@ -100,0 +101,200 @@\n" + "+".concat("a".repeat(199)).concat("\n").repeat(200);
        var units = SnapshotReviewInitialEvidence.compile(SnapshotReviewUnits.compact("id", new String[]{"M", "x", "x"}, diff, null), diff, null, AFTER);
        assertThat(units).hasSizeGreaterThan(1);
        var refs = units.stream().flatMap(u -> u.initialEvidence().stream()).toList();
        assertThat(refs.getFirst().startLine()).isEqualTo(101); assertThat(refs.getLast().endLine()).isEqualTo(300);
        for (int i = 1; i < refs.size(); i++) assertThat(refs.get(i).startLine()).isEqualTo(refs.get(i - 1).endLine() + 1);
    }
    @Test void fullFileDoesNotGrantPhantomFinalLineAndLongLineFragmentsDoNotGrantUnseenText() {
        String source = "one\ntwo\n";
        var units = SnapshotReviewInitialEvidence.compile(SnapshotReviewUnits.compact("id", new String[]{"FULL", "x", "x"}, source, null), source, null, AFTER);
        assertThat(units.getFirst().initialEvidence()).containsExactly(new Reference("tip", "new.java", "after-blob", 1, 2, "one\ntwo"));
        String longLine = "x".repeat(48000);
        var split = SnapshotReviewInitialEvidence.compile(SnapshotReviewUnits.compact("id", new String[]{"FULL", "x", "x"}, longLine, null), longLine, null, AFTER);
        assertThat(split).allMatch(u -> u.initialEvidence().isEmpty());
    }
}
