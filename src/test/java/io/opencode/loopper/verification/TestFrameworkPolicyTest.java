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
        assertThat(TestFrameworkPolicy.assess(List.of(
                "python3", "-m", "unittest", "discover", "-s", "tests", "-p", "test_*.py")))
                .satisfies(result -> {
                    assertThat(result.recognized()).isTrue();
                    assertThat(result.focused()).isFalse();
                    assertThat(result.targets()).isEmpty();
                });
    }

    @Test void npmRequiresAnExplicitTargetAfterTheArgumentSeparator() {
        assertThat(TestFrameworkPolicy.assess(List.of("npm", "test")))
                .satisfies(result -> {
                    assertThat(result.recognized()).isTrue();
                    assertThat(result.focused()).isFalse();
                });
        assertThat(TestFrameworkPolicy.explicitTargets(List.of("npm", "test", "--", "src/converter.spec.ts")))
                .containsExactly("src/converter.spec.ts");
    }

    @Test void preservesMavenGradleAndNpmSkipBypassRejection() {
        assertThat(TestFrameworkPolicy.assess(List.of(
                "mvn", "-Dtest=ConverterTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test")).skipped())
                .isTrue();
        assertThat(TestFrameworkPolicy.assess(List.of(
                "gradlew", "test", "--tests", "ConverterTest", "--exclude-task=test")).skipped())
                .isTrue();
        assertThat(TestFrameworkPolicy.assess(List.of(
                "npm", "run", "test:unit", "--if-present", "--", "src/converter.spec.ts")).skipped())
                .isTrue();
    }
}
