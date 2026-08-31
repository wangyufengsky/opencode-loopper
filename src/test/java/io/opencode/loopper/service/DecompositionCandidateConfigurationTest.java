package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opencode.loopper.persistence.LoopperMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class DecompositionCandidateConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DecompositionCandidateConfiguration.class)
            .withBean(LoopperMapper.class, () -> mock(LoopperMapper.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void rollbackFlagDoesNotRemoveAdaptersNeededByPersistedRuns() {
        context.withPropertyValues("loopper.internal-candidate.decomposer-enabled=false")
                .run(application -> {
                    assertThat(application).hasSingleBean(DesignerDecompositionCandidateCompiler.class);
                    assertThat(application).hasSingleBean(DecompositionCandidatePolicy.class);
                    assertThat(application).hasSingleBean(DecompositionAcceptedCandidateWriter.class);
                });
    }

}
