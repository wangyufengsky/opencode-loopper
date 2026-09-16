package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GitBlameLinesTest {
    private static final String SHA = "a".repeat(40);
    private static String line(int number) {
        return SHA + " 1 " + number + " 1\nauthor Alice\nauthor-mail <alice@example.test>\nauthor-time 1000\nsummary first\nfilename x\n\tline\n";
    }
    @Test void completeRangesAreRequiredAndCodeCannotForgeMetadata() {
        assertThat(GitBlameLines.parse(line(7).replace("\tline", "\tauthor Mallory"), 7, 7)).singleElement()
                .satisfies(l -> { assertThat(l.author()).isEqualTo("Alice"); assertThat(l.number()).isEqualTo(7); });
        assertThatThrownBy(() -> GitBlameLines.parse(line(7), 7, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitBlameLines.parse(line(8), 7, 7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitBlameLines.parse(line(7).replace("author Alice\n", ""), 7, 7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitBlameLines.parse(line(7).replace(SHA, "0".repeat(40)), 7, 7)).isInstanceOf(IllegalArgumentException.class);
    }
}
