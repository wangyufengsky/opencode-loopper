package io.opencode.loopper.verification;

import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.VerificationState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaUnitTestGatePolicyTest {
    private final LoopSpec.VerifierSpec focused = process(List.of("mvn", "-Dtest=CacheTest", "test"),
            List.of("CacheTest"));

    @Test
    void rejectsProductionJavaInNonProductionStages() {
        for (ImplementationKind kind : List.of(ImplementationKind.NON_JAVA, ImplementationKind.JAVA_TEST_ONLY)) {
            assertThat(JavaUnitTestGatePolicy.evaluate(kind, List.of(focused), Map.of(0, VerificationState.PASS)))
                    .extracting(JavaUnitTestGatePolicy.Decision::code,
                            JavaUnitTestGatePolicy.Decision::passed)
                    .containsExactly("JAVA_CHANGE_CLASSIFICATION_MISMATCH", false);
        }
    }

    @Test
    void requiresAnActuallyPassingFocusedMavenOrGradleTest() {
        LoopSpec.VerifierSpec broad = process(List.of("mvn", "test"), List.of("all"));
        assertThat(JavaUnitTestGatePolicy.evaluate(ImplementationKind.JAVA_PRODUCTION,
                List.of(broad), Map.of(0, VerificationState.PASS)).code())
                .isEqualTo("JAVA_UNIT_TEST_ACCEPTANCE_REQUIRED");
        assertThat(JavaUnitTestGatePolicy.evaluate(ImplementationKind.JAVA_PRODUCTION,
                List.of(focused), Map.of(0, VerificationState.FAIL)).code())
                .isEqualTo("JAVA_UNIT_TEST_ACCEPTANCE_REQUIRED");

        var passed = JavaUnitTestGatePolicy.evaluate(ImplementationKind.JAVA_PRODUCTION,
                List.of(focused), Map.of(0, VerificationState.PASS));
        assertThat(passed.code()).isEqualTo("JAVA_UNIT_TEST_ACCEPTANCE_SATISFIED");
        assertThat(passed.passed()).isTrue();
        assertThat(passed.focusedVerifierIndexes()).containsExactly(0);
        assertThat(passed.passedVerifierIndexes()).containsExactly(0);
    }

    private LoopSpec.VerifierSpec process(List<String> command, List<String> targets) {
        return new LoopSpec.VerifierSpec("PROCESS", command, null, null, List.of(), List.of(), false,
                null, null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of("AC-1"), "TEST", targets);
    }
}
