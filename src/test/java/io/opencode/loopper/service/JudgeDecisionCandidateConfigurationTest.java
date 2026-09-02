package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opencode.loopper.persistence.LoopperMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class JudgeDecisionCandidateConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(JudgeDecisionCandidateConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(LoopperMapper.class, () -> mock(LoopperMapper.class))
            .withBean(JudgeDecisionCompilation.class, () -> mock(JudgeDecisionCompilation.class));

    @Test
    void registersOnePolicyWriterAndAllDbOnlyRecoverySeams() {
        context.run(application -> {
            assertThat(application).hasSingleBean(JudgeDecisionCandidateCodec.class);
            assertThat(application).hasSingleBean(JudgeDecisionCompilationInputLoader.class);
            assertThat(application).hasSingleBean(JudgeDecisionCandidateSourceSnapshotStore.class);
            assertThat(application).hasSingleBean(JudgeDecisionAcceptedResultStore.class);
            assertThat(application).hasSingleBean(CandidatePolicy.class);
            assertThat(application.getBean(CandidatePolicy.class))
                    .isInstanceOf(JudgeDecisionCandidatePolicy.class);
            assertThat(application).hasSingleBean(AcceptedCandidateWriter.class);
            assertThat(application.getBean(AcceptedCandidateWriter.class))
                    .isInstanceOf(JudgeDecisionAcceptedCandidateWriter.class);
        });
    }
}
