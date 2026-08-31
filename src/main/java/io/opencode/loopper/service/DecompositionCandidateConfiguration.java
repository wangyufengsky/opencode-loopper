package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Adapters stay available to settle persisted runs; the Decomposer flag gates only opening new runs. */
@Configuration(proxyBeanMethods = false)
class DecompositionCandidateConfiguration {
    @Bean
    DesignerDecompositionCandidateCompiler designerDecompositionCandidateCompiler(ObjectMapper json) {
        return new DesignerDecompositionCandidateCompiler(json);
    }

    @Bean
    CandidatePolicy decompositionCandidatePolicy(LoopperMapper mapper,
                                                 DesignerDecompositionCandidateCompiler compiler) {
        return new DecompositionCandidatePolicy(mapper, compiler);
    }

    @Bean
    AcceptedCandidateWriter decompositionAcceptedCandidateWriter(LoopperMapper mapper) {
        return new DecompositionAcceptedCandidateWriter(mapper);
    }
}
