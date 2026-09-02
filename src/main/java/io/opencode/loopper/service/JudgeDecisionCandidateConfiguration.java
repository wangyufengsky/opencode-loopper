package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Registers DB-only Judge candidate seams independently of the staged workflow rollout flag. */
@Configuration(proxyBeanMethods = false)
class JudgeDecisionCandidateConfiguration {
    @Bean
    JudgeDecisionCandidateCodec judgeDecisionCandidateCodec(ObjectMapper json) {
        return new JudgeDecisionCandidateCodec(json);
    }

    @Bean
    JudgeDecisionCompilationInputLoader judgeDecisionCompilationInputs(
            LoopperMapper mapper, JudgeDecisionCandidateCodec codec) {
        return new JudgeDecisionCompilationInputLoader.MapperLoader(mapper, codec);
    }

    @Bean
    CandidatePolicy judgeDecisionCandidatePolicy(
            JudgeDecisionCompilationInputLoader inputs, JudgeDecisionCompilation compilation) {
        return new JudgeDecisionCandidatePolicy(inputs, compilation);
    }

    @Bean
    AcceptedCandidateWriter judgeDecisionAcceptedCandidateWriter(
            LoopperMapper mapper, JudgeDecisionCandidateCodec codec,
            JudgeDecisionCompilationInputLoader inputs, JudgeDecisionCompilation compilation) {
        return new JudgeDecisionAcceptedCandidateWriter(mapper, codec, inputs, compilation);
    }

    @Bean
    JudgeDecisionCandidateSourceSnapshotStore judgeDecisionCandidateSourceSnapshotStore(
            LoopperMapper mapper, JudgeDecisionCandidateCodec codec) {
        return new JudgeDecisionCandidateSourceSnapshotStore(mapper, codec);
    }

    @Bean
    JudgeDecisionAcceptedResultStore judgeDecisionAcceptedResultStore(
            LoopperMapper mapper, JudgeDecisionCandidateCodec codec,
            JudgeDecisionCompilationInputLoader inputs, JudgeDecisionCompilation compilation) {
        return new JudgeDecisionAcceptedResultStore(mapper, codec, inputs, compilation);
    }
}
