package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.LoopperMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class AcceptanceClosedChoiceCandidateConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AcceptanceClosedChoiceCandidateConfiguration.class)
            .withBean(MachineCandidateSubmission.class, () -> mock(MachineCandidateSubmission.class))
            .withBean(LoopperProperties.class, LoopperProperties::new)
            .withBean(LoopperMapper.class, () -> mock(LoopperMapper.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void safeDefaultOffStillKeepsAdaptersAvailableToSettlePersistedRuns() {
        context.run(application -> {
            assertThat(application).hasSingleBean(AcceptanceClosedChoiceCandidateCoordinator.class);
            assertThat(application).hasSingleBean(AcceptanceClosedChoiceCandidatePolicy.class);
            assertThat(application).hasSingleBean(AcceptanceClosedChoiceAcceptedCandidateWriter.class);
        });
    }

    @Test
    void explicitRollbackConfigurationStillRegistersExactlyOnePolicyAndWriter() {
        context.withPropertyValues(
                        "loopper.internal-candidate.acceptance-closed-choice-v7-enabled=false")
                .run(application -> {
                    assertThat(application).hasSingleBean(AcceptanceClosedChoiceCandidateCoordinator.class);
                    assertThat(application).hasSingleBean(AcceptanceClosedChoiceCandidatePolicy.class);
                    assertThat(application).hasSingleBean(AcceptanceClosedChoiceAcceptedCandidateWriter.class);
                });
    }

}
