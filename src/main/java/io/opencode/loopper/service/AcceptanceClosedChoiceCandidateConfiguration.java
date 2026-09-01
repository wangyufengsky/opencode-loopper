package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.LoopperMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** Adapters stay available to settle persisted runs; the feature flag gates only opening new v7 runs. */
@Configuration(proxyBeanMethods = false)
class AcceptanceClosedChoiceCandidateConfiguration {
    @Bean
    AcceptanceClosedChoiceCandidateCoordinator acceptanceClosedChoiceCandidateCoordinator(
            MachineCandidateSubmission submissions, LoopperProperties properties,
            Optional<CandidateRuntimeBindingService> bindings,
            Optional<AcceptanceCandidateInternalLaunchStore> internalLaunches, ObjectMapper json) {
        return new AcceptanceClosedChoiceCandidateCoordinator(
                submissions, properties, bindings, internalLaunches, json);
    }

    @Bean
    CandidatePolicy acceptanceClosedChoiceCandidatePolicy(LoopperMapper mapper, ObjectMapper json) {
        return new AcceptanceClosedChoiceCandidatePolicy(mapper, json);
    }

    @Bean
    AcceptedCandidateWriter acceptanceClosedChoiceAcceptedCandidateWriter(LoopperMapper mapper, ObjectMapper json) {
        return new AcceptanceClosedChoiceAcceptedCandidateWriter(mapper, json);
    }
}
