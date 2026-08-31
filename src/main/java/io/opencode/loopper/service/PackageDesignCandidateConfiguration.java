package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Compilation/policy/writer stay available for recovery; the flag gates only opening new package runs. */
@Configuration(proxyBeanMethods = false)
class PackageDesignCandidateConfiguration {
    @Bean
    PackageDesignCompilation packageDesignCompilation(ObjectMapper json) {
        return new DeterministicPackageDesignCompilation(json);
    }

    @Bean
    CandidatePolicy packageDesignCandidatePolicy(
            LoopperMapper mapper, ObjectMapper json, PackageDesignCompilation compilation) {
        return new PackageDesignCandidatePolicy(mapper, json, compilation);
    }

    @Bean
    AcceptedCandidateWriter packageDesignAcceptedCandidateWriter(
            LoopperMapper mapper, ObjectMapper json, PackageDesignCompilation compilation) {
        return new PackageDesignAcceptedCandidateWriter(mapper, mapper, json, compilation);
    }
}
