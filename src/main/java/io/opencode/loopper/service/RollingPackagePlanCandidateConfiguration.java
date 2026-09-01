package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Compiler, policy and writer remain registered for recovery; the rollout flag gates only new runs. */
@Configuration(proxyBeanMethods = false)
class RollingPackagePlanCandidateConfiguration {
    @Bean
    RollingPackagePlanCompilation rollingPackagePlanCompilation(ObjectMapper json) {
        return new DeterministicRollingPackagePlanCompilation(json);
    }

    @Bean
    RollingPackagePlanCompilationInputLoader rollingPackagePlanCompilationInputs(
            LoopperMapper mapper, ObjectMapper json) {
        return new RollingPackagePlanCompilationInputLoader.MapperLoader(mapper, json);
    }

    @Bean
    CandidatePolicy rollingPackagePlanCandidatePolicy(
            RollingPackagePlanCompilationInputLoader inputs,
            RollingPackagePlanCompilation compilation) {
        return new RollingPackagePlanCandidatePolicy(inputs, compilation);
    }

    @Bean
    AcceptedCandidateWriter rollingPackagePlanAcceptedCandidateWriter(
            LoopperMapper mapper, RollingPackagePlanCompilationInputLoader inputs,
            RollingPackagePlanCompilation compilation) {
        return new RollingPackagePlanAcceptedCandidateWriter(mapper, inputs, compilation);
    }
}
