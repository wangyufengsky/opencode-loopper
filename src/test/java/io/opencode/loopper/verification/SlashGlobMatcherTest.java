package io.opencode.loopper.verification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlashGlobMatcherTest {
    @Test
    void doubleStarCrossesDirectoriesAndSupportsZeroDirectorySegments() {
        assertThat(SlashGlobMatcher.matches("src/**", "src/main/App.java")).isTrue();
        assertThat(SlashGlobMatcher.matches("src/**/App.java", "src/App.java")).isTrue();
        assertThat(SlashGlobMatcher.matches("src/**/App.java", "src/main/java/App.java")).isTrue();
        assertThat(SlashGlobMatcher.matches("**/README.md", "README.md")).isTrue();
    }

    @Test
    void singleStarAndQuestionMarkNeverCrossASeparator() {
        assertThat(SlashGlobMatcher.matches("src/*.java", "src/App.java")).isTrue();
        assertThat(SlashGlobMatcher.matches("src/*.java", "src/main/App.java")).isFalse();
        assertThat(SlashGlobMatcher.matches("src/?.java", "src/A.java")).isTrue();
        assertThat(SlashGlobMatcher.matches("src/?.java", "src/AB.java")).isFalse();
    }

    @Test
    void characterClassesAndAlternativeGroupsRetainGlobSemantics() {
        assertThat(SlashGlobMatcher.matches("{src,test}/[A-Z]*.java", "src/App.java")).isTrue();
        assertThat(SlashGlobMatcher.matches("{src,test}/[A-Z]*.java", "test/Build.java")).isTrue();
        assertThat(SlashGlobMatcher.matches("{src,test}/[!A-Z]*.java", "src/app.java")).isTrue();
        assertThat(SlashGlobMatcher.matches("{src,test}/[A-Z]*.java", "docs/App.java")).isFalse();
    }

    @Test
    void windowsSeparatorsAreNormalizedBeforeMatching() {
        assertThat(SlashGlobMatcher.matches("src\\**", "src\\main\\App.java")).isTrue();
    }

    @Test
    void malformedPatternsAreRejected() {
        assertThatThrownBy(() -> SlashGlobMatcher.matches("src/[abc", "src/a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlashGlobMatcher.matches("{src,{test,docs}}/**", "src/App.java"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pathologicalWildcardSequencesUseBoundedDynamicProgramming() {
        String pathological = "**a".repeat(100) + "b";
        assertThat(SlashGlobMatcher.matches(pathological, "a".repeat(300))).isFalse();
        assertThatThrownBy(() -> SlashGlobMatcher.matches("x".repeat(513), "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
        assertThatThrownBy(() -> SlashGlobMatcher.matches("{a,b}".repeat(6), "aaaaaa"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many alternatives");
        var sharedBudget = new SlashGlobMatcher.WorkBudget(20);
        assertThatThrownBy(() -> SlashGlobMatcher.matches("**", "a".repeat(100), sharedBudget))
                .isInstanceOf(SlashGlobMatcher.WorkLimitExceeded.class)
                .hasMessageContaining("budget exceeded");
    }
}
