package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SourceExistingTestsTest {
    private final String before = "class ExistingTest { @Test void normal() { assertEquals(3, service.value()); } }";
    @Test void acceptsAdditionalJavaTestsWhilePreservingTheExistingMethod() {
        assertThat(SourceExistingTests.preserved("ExistingTest.java", before,
                before.substring(0, before.length() - 1) + " @Test void boundary() { assertThrows(Exception.class, () -> service.fail()); } }")).isTrue();
    }
    @Test void rejectsCommentWrappingEarlyReturnAndClassLevelDisable() {
        assertThat(SourceExistingTests.preserved("ExistingTest.java", before, "/*" + before + "*/ class ExistingTest {} ")).isFalse();
        assertThat(SourceExistingTests.preserved("ExistingTest.java", before,
                before.replace("assertEquals", "if (true) return; assertEquals"))).isFalse();
        assertThat(SourceExistingTests.preserved("ExistingTest.java", before, "@Disabled " + before)).isFalse();
        assertThat(SourceExistingTests.preserved("ExistingTest.java", before,
                before.substring(0, before.length() - 1) + " @BeforeEach void skip() { assumeTrue(false); } }")).isFalse();
    }
    @Test void malformedOrUnsupportedSyntaxCannotPassAsPreserved() {
        assertThat(SourceExistingTests.preserved("ExistingTest.java", before, before + " invalid" )).isFalse();
        assertThat(SourceExistingTests.preserved("existing.spec.ts", "test('old', () => expect(true).toBe(true))",
                "/*test('old', () => expect(true).toBe(true))*/")).isFalse();
    }
}
