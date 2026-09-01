package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opencode.loopper.persistence.LoopperMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class ProjectConventionCandidateConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ProjectConventionCandidateConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(LoopperMapper.class, () -> mock(LoopperMapper.class))
            .withBean(ProjectConventionCompilation.class,
                    () -> mock(ProjectConventionCompilation.class));

    @Test
    void registersExactlyOnePolicyWriterAndEveryDbOnlyRecoverySeam() {
        context.run(application -> {
            assertThat(application).hasSingleBean(ProjectConventionCandidateCodec.class);
            assertThat(application).hasSingleBean(ProjectConventionCompilationInputLoader.class);
            assertThat(application).hasSingleBean(ProjectConventionCandidateSourceSnapshotStore.class);
            assertThat(application).hasSingleBean(ProjectConventionAcceptedResultStore.class);
            assertThat(application).hasSingleBean(CandidatePolicy.class);
            assertThat(application.getBean(CandidatePolicy.class))
                    .isInstanceOf(ProjectConventionCandidatePolicy.class);
            assertThat(application).hasSingleBean(AcceptedCandidateWriter.class);
            assertThat(application.getBean(AcceptedCandidateWriter.class))
                    .isInstanceOf(ProjectConventionAcceptedCandidateWriter.class);
        });
    }
}
