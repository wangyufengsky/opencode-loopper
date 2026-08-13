package io.opencode.loopper.verification;

import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.VerificationState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure policy for the runtime production-Java unit-test gate. */
public final class JavaUnitTestGatePolicy {
    private JavaUnitTestGatePolicy() { }

    public static Decision evaluate(ImplementationKind kind, List<LoopSpec.VerifierSpec> verifiers,
                                    Map<Integer, VerificationState> completed) {
        if (kind != ImplementationKind.JAVA_PRODUCTION) {
            return new Decision("JAVA_CHANGE_CLASSIFICATION_MISMATCH", false, List.of(), List.of());
        }
        List<Integer> focused = new ArrayList<>();
        for (int index = 0; index < verifiers.size(); index++) {
            LoopSpec.VerifierSpec verifier = verifiers.get(index);
            if ("PROCESS".equals(verifier.type()) && "TEST".equals(verifier.processPurpose())
                    && !verifier.testTargets().isEmpty()
                    && ProcessCommandPolicy.isFocusedJavaTestCommand(verifier.command())) {
                focused.add(index);
            }
        }
        List<Integer> passed = focused.stream()
                .filter(index -> completed.get(index) == VerificationState.PASS).toList();
        return passed.isEmpty()
                ? new Decision("JAVA_UNIT_TEST_ACCEPTANCE_REQUIRED", false, focused, passed)
                : new Decision("JAVA_UNIT_TEST_ACCEPTANCE_SATISFIED", true, focused, passed);
    }

    public record Decision(String code, boolean passed, List<Integer> focusedVerifierIndexes,
                           List<Integer> passedVerifierIndexes) { }
}
