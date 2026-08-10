package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.LoopperMapper;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class LoopDraftServiceValidationTest {
    private LoopDraftService drafts;

    @BeforeEach
    void setUp() {
        Validator validator = mock(Validator.class);
        doReturn(Set.of()).when(validator).validate(any());
        drafts = new LoopDraftService(mock(LoopperMapper.class), mock(LifecycleTransitionService.class),
                mock(ProjectService.class), mock(ObjectMapper.class), mock(TaskService.class), validator);
    }

    @Test
    void rejectsCollapsedMavenArgumentsBeforeConfirmation() {
        List<String> errors = drafts.validationErrors(spec(List.of(
                "mvn", "test -Dtest=Base64FieldTest -pl upfs-common")), true);

        assertThat(errors).singleElement().asString()
                .contains("stages[0].verifiers[0].command[1]")
                .contains("multiple argv tokens");
    }

    @Test
    void rejectsAWholeMavenCommandStoredAsTheExecutable() {
        assertThat(drafts.validationErrors(spec(List.of("/usr/bin/mvn test -DskipTests")), true))
                .singleElement().asString()
                .contains("stages[0].verifiers[0].command[0]")
                .contains("multiple argv tokens");
    }

    @Test
    void acceptsSeparatedMavenArgumentsAndLegitimateSpaceContainingValues() {
        assertThat(drafts.validationErrors(spec(List.of(
                "mvn", "test", "-Dtest=Base64FieldTest", "-pl", "upfs-common")), true)).isEmpty();
        assertThat(drafts.validationErrors(spec(List.of(
                "mvn", "-DargLine=-Xmx512m -XX:+UseSerialGC", "test")), true)).isEmpty();
    }

    private LoopSpec spec(List<String> command) {
        LoopSpec.VerifierSpec verifier = new LoopSpec.VerifierSpec(
                "PROCESS", command, null, null, null, null, null, "BUILD SUCCESS");
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec(
                "Run focused tests", List.of(), List.of(), List.of("test evidence"), List.of(verifier));
        return new LoopSpec("v1", "project", "Validate Maven command", "", List.of(stage),
                LoopSpec.Limits.defaults(), new LoopSpec.ModelSpec(null, null, null),
                LoopSpec.SessionPolicy.defaults(), null);
    }
}
