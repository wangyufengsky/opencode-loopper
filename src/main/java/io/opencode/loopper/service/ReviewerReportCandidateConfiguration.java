package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
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

    @Bean
    ReviewerReportCandidateCodec reviewerReportCandidateCodec(ObjectMapper json) {
        return new ReviewerReportCandidateCodec(json);
    }

    @Bean
    ReviewerReportCompilationInputLoader reviewerReportCompilationInputs(
            LoopperMapper mapper, ReviewerReportCandidateCodec codec) {
        return new ReviewerReportCompilationInputLoader.MapperLoader(mapper, codec);
    }

    @Bean
    CandidatePolicy reviewerReportCandidatePolicy(
            ReviewerReportCandidateCodec codec,
            ReviewerReportCompilationInputLoader inputs,
            ReviewerReportCompilation compilation) {
        return new ReviewerReportCandidatePolicy(codec, inputs, compilation);
    }

    @Bean
    AcceptedCandidateWriter reviewerReportAcceptedCandidateWriter(
            LoopperMapper mapper,
            ObjectMapper json,
            ReviewerReportCandidateCodec codec,
            ReviewerReportCompilationInputLoader inputs,
            ReviewerReportCompilation compilation) {
        return new ReviewerReportAcceptedCandidateWriter(mapper, json, codec, inputs, compilation);
    }

    @Bean
    ReviewerReportSourceSnapshotStore reviewerReportSourceSnapshotStore(
            LoopperMapper mapper, ReviewerReportCandidateCodec codec) {
        return new ReviewerReportSourceSnapshotStore(mapper, codec);
    }

    @Bean
    ReviewerReportAcceptedResultStore reviewerReportAcceptedResultStore(
            LoopperMapper mapper,
            ReviewerReportCandidateCodec codec,
            ReviewerReportCompilation compilation) {
        return new ReviewerReportAcceptedResultStore(mapper, codec, compilation);
    }
}
