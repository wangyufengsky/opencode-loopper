package io.opencode.loopper.domain;

import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoopSpecValidationTest {
    @Test
    void rejectsOversizedPathPoliciesBeforeTheyCanBecomeTaskContracts() {
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec(
                "bounded policy",
                List.of("x".repeat(513)),
                java.util.stream.IntStream.range(0, 65).mapToObj(index -> "tmp/" + index).toList(),
                List.of(),
                List.of());
        LoopSpec spec = new LoopSpec("v1", "project", "goal", "", List.of(stage), null, null, null, null);
        LoopSpec timeoutSpec = new LoopSpec("v1", "project", "goal", "", List.of(
                new LoopSpec.StageSpec("bounded timeout", List.of(), List.of(), List.of(), List.of())),
                new LoopSpec.Limits(3, 12, 3, 2, 7_200L, 1_800L, 3_601L), null, null, null);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(spec);
            assertThat(violations).anySatisfy(violation ->
                    assertThat(violation.getPropertyPath().toString()).contains("allowedPaths"));
            assertThat(violations).anySatisfy(violation ->
                    assertThat(violation.getPropertyPath().toString()).contains("forbiddenPaths"));
            assertThat(factory.getValidator().validate(timeoutSpec)).anySatisfy(violation ->
                    assertThat(violation.getPropertyPath().toString()).contains("verifierTimeoutSeconds"));
        }
    }
}
