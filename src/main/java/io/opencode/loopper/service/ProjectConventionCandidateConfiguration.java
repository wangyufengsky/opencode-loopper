package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Registers the DB-only Convention candidate seams independently of workflow rollout. */
@Configuration(proxyBeanMethods = false)
class ProjectConventionCandidateConfiguration {
    @Bean
    ProjectConventionCandidateCodec projectConventionCandidateCodec(ObjectMapper json) {
        return new ProjectConventionCandidateCodec(json);
    }

    @Bean
    ProjectConventionCompilationInputLoader projectConventionCompilationInputs(
            LoopperMapper mapper, ProjectConventionCandidateCodec codec) {
        return new ProjectConventionCompilationInputLoader.MapperLoader(mapper, codec);
    }

    @Bean
    CandidatePolicy projectConventionCandidatePolicy(
            ProjectConventionCompilationInputLoader inputs,
            ProjectConventionCompilation compilation) {
        return new ProjectConventionCandidatePolicy(inputs, compilation);
    }

    @Bean
    AcceptedCandidateWriter projectConventionAcceptedCandidateWriter(
            LoopperMapper mapper,
            ProjectConventionCandidateCodec codec,
            ProjectConventionCompilationInputLoader inputs,
            ProjectConventionCompilation compilation) {
        return new ProjectConventionAcceptedCandidateWriter(mapper, codec, inputs, compilation);
    }

    @Bean
    ProjectConventionCandidateSourceSnapshotStore projectConventionCandidateSourceSnapshotStore(
            LoopperMapper mapper,
            ProjectConventionCandidateCodec codec,
            ProjectConventionCompilation compilation) {
        return new ProjectConventionCandidateSourceSnapshotStore(mapper, codec, compilation);
    }

    @Bean
    ProjectConventionAcceptedResultStore projectConventionAcceptedResultStore(
            LoopperMapper mapper,
            ProjectConventionCandidateCodec codec,
            ProjectConventionCompilationInputLoader inputs,
            ProjectConventionCompilation compilation) {
        return new ProjectConventionAcceptedResultStore(mapper, codec, inputs, compilation);
    }
}
