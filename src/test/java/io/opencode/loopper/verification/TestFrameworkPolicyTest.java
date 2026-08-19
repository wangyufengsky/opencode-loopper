package io.opencode.loopper.verification;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestFrameworkPolicyTest {
    @Test void recognizesFocusedPytestAndUnittestTargets() {
        assertThat(ProcessCommandPolicy.assessTestCommand(
                List.of("python3", "-m", "pytest", "tests/test_converter.py")).recognized()).isTrue();
        assertThat(TestFrameworkPolicy.explicitTargets(
                List.of("python3", "-m", "pytest", "tests/test_converter.py")))
                .containsExactly("tests/test_converter.py");
        assertThat(TestFrameworkPolicy.explicitTargets(
                List.of("python", "-m", "unittest", "tests.test_converter.ConverterTest")))
                .containsExactly("tests.test_converter.ConverterTest");
    }

    @Test void rejectsSkipAndMissingTargetAsFocusedEvidence() {
        assertThat(TestFrameworkPolicy.assess(List.of("pytest", "--ignore", "tests/slow", "tests")))
                .satisfies(result -> { assertThat(result.recognized()).isTrue(); assertThat(result.skipped()).isTrue(); });
        assertThat(TestFrameworkPolicy.explicitTargets(List.of("python3", "-m", "pytest"))).isEmpty();
    }
}
