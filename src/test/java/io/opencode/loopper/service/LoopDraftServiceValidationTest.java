package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.LoopperMapper;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                mock(ProjectService.class), mock(ObjectMapper.class), mock(TaskService.class), validator,
                new LoopSpecAcceptanceService());
    }

    @Test
    void acceptsCollapsedMavenArgumentsThatCanBeParsedSafely() {
        List<String> errors = drafts.validationErrors(spec(List.of(
                "mvn", "test -Dtest=Base64FieldTest -pl upfs-common")), true);

        assertThat(errors).isEmpty();
    }

    @Test
    void acceptsAWholeMavenCommandThatCanBeParsedSafely() {
        assertThat(drafts.validationErrors(spec(List.of("/usr/bin/mvn test -DskipTests")), true))
                .isEmpty();
    }

    @Test
    void rejectsCollapsedMavenArgumentsThatCannotBeParsedSafely() {
        assertThat(drafts.validationErrors(spec(List.of(
                "mvn", "test -Dtest='Base64FieldTest")), true))
                .singleElement().asString()
                .contains("stages[0].verifiers[0].command[1]")
                .contains("cannot be parsed safely", "unclosed quote");
    }

    @Test
    void acceptsSeparatedMavenArgumentsAndLegitimateSpaceContainingValues() {
        assertThat(drafts.validationErrors(spec(List.of(
                "mvn", "test", "-Dtest=Base64FieldTest", "-pl", "upfs-common")), true)).isEmpty();
        assertThat(drafts.validationErrors(spec(List.of(
                "mvn", "-DargLine=-Xmx512m -XX:+UseSerialGC", "test")), true)).isEmpty();
    }

    @Test
    void selectsThePlatformSpecificMavenWrapperName() {
        Path root = Path.of("project");
        assertThat(io.opencode.loopper.verification.ProcessCommandPolicy.platformMavenWrapper(root, "Linux"))
                .isEqualTo(root.resolve("mvnw"));
        assertThat(io.opencode.loopper.verification.ProcessCommandPolicy.platformMavenWrapper(root, "Windows 10"))
                .isEqualTo(root.resolve("mvnw.cmd"));
    }

    @Test
    void rejectsStageAndGitDiffAllowRulesEntirelyShadowedByForbiddenParents() {
        LoopSpec.VerifierSpec scope = new LoopSpec.VerifierSpec(
                "GIT_DIFF", List.of(), null, true,
                List.of("src/main/java/com/spdb/upfs/event/bridge/**"),
                List.of("src/main/java/com/spdb/upfs/event/**"), true);
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec(
                "Bridge events into the state machine",
                List.of("src\\main\\java\\com\\spdb\\upfs\\event\\bridge\\**"),
                List.of("src/main/java/com/spdb/upfs/event/**"),
                List.of("bridge implementation"), List.of(scope),
                List.of(new LoopSpec.AcceptanceCriterion("AC-1", "bridge is implemented", "JUDGE",
                        "Review the bridge implementation", "No reliable runtime is available")),
                null, io.opencode.loopper.domain.ImplementationKind.NON_JAVA);
        LoopSpec spec = new LoopSpec("v2", "project", "Prevent contradictory path policy", "",
                List.of(stage), LoopSpec.Limits.defaults(), null, null, null);

        assertThat(drafts.validationErrors(spec, false))
                .anySatisfy(error -> assertThat(error)
                        .contains("stages[0].allowedPaths[0]")
                        .contains("entirely shadowed")
                        .contains("event/bridge/**", "event/**"))
                .anySatisfy(error -> assertThat(error)
                        .contains("stages[0].verifiers[0].allowedPaths[0]")
                        .contains("no changed path accepted by this allow rule can satisfy the path policy"));
        assertThatThrownBy(() -> drafts.validateExecutionContract(spec))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("entirely shadowed");
    }

    @Test
    void acceptsAllowWithNarrowForbiddenExclusion() {
        LoopSpec.VerifierSpec scope = new LoopSpec.VerifierSpec(
                "GIT_DIFF", List.of(), null, true, List.of("src/**"),
                List.of("src/generated/**"), true);
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec(
                "Scoped change", List.of("src/**"), List.of("src/generated/**"),
                List.of("implementation"), List.of(scope),
                List.of(new LoopSpec.AcceptanceCriterion("AC-1", "scope is reviewed", "JUDGE",
                        "Review the scoped change", "No deterministic behavior is required")),
                null, io.opencode.loopper.domain.ImplementationKind.NON_JAVA);
        LoopSpec spec = new LoopSpec("v2", "project", "Keep exclusions valid", "",
                List.of(stage), LoopSpec.Limits.defaults(), null, null, null);

        assertThat(drafts.validationErrors(spec, false))
                .noneMatch(error -> error.contains("entirely shadowed"));
    }

    @Test
    void rejectsMalformedGlobBeforeTaskExecution() {
        LoopSpec.VerifierSpec scope = new LoopSpec.VerifierSpec(
                "GIT_DIFF", List.of(), null, true, List.of("src/[invalid"), List.of(), true);
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec(
                "Invalid scope", List.of("src/[invalid"), List.of(), List.of("implementation"), List.of(scope),
                List.of(new LoopSpec.AcceptanceCriterion("AC-1", "scope is reviewed", "JUDGE",
                        "Review the scoped change", "No deterministic behavior is required")),
                null, io.opencode.loopper.domain.ImplementationKind.NON_JAVA);
        LoopSpec spec = new LoopSpec("v2", "project", "Reject invalid glob", "", List.of(stage),
                LoopSpec.Limits.defaults(), null, null, null);

        assertThat(drafts.validationErrors(spec, false))
                .anySatisfy(error -> assertThat(error).contains("invalid path pattern", "src/[invalid"));
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
