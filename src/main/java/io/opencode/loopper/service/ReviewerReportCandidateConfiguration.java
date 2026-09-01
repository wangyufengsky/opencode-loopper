package io.opencode.loopper.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Reviewer compilation remains registered independently of the staged Candidate rollout flag. */
@Configuration(proxyBeanMethods = false)
class ReviewerReportCandidateConfiguration {
    @Bean
    ReviewerReportCompilation reviewerReportCompilation(ObjectMapper json) {
        return new DeterministicReviewerReportCompilation(json);
    }

    @Bean
    ReviewerReportLiveSourceAdapter reviewerReportLiveSourceAdapter() {
        return new ReviewerReportLiveSourceAdapter();
    }
}
