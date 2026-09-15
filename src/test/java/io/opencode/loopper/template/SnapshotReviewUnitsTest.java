package io.opencode.loopper.template;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SnapshotReviewUnitsTest {
    @Test void hunkAndFunctionBoundariesRetainOriginalContentWithBoundedContinuationUnits() {
        String diff="diff --git a/a.java b/a.java\n--- a/a.java\n+++ b/a.java\n@@ -1,1 +1,1 @@\n-old\n+new\n@@ -99,1 +99,1 @@\n-x\n+y\n";
        var units=SnapshotReviewUnits.compile("u",new String[]{"M","a.java","a.java"},diff,null);
        assertThat(units).hasSize(3);
        assertThat(units.get(1).excerpt()).contains("@@ -1,1 +1,1 @@","+new");
        assertThat(units.get(2).excerpt()).contains("@@ -99,1 +99,1 @@","+y");
        var source="class A {\n"+"  // source\n".repeat(3000)+"}\nclass B {}\n";
        var full=SnapshotReviewUnits.compile("f",new String[]{"FULL","a.java","a.java"},source,null);
        assertThat(full).allMatch(u->u.excerpt().length()<=24000);
        String restored=full.stream().map(u->u.excerpt().substring(u.excerpt().indexOf('\n')+1)).reduce("",String::concat);
        assertThat(restored).isEqualTo(source);
        assertThat(full.getLast().excerpt()).contains("class B {}","目标文件行");
    }
}
