package io.opencode.loopper.service;

import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.LoopperAcceptanceCandidateLaunchMapper;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Narrow durable store for the PREPARED internal-launch checkpoint. */
@Component
final class AcceptanceCandidateInternalLaunchStore {
    private final LoopperAcceptanceCandidateLaunchMapper mapper;

    AcceptanceCandidateInternalLaunchStore(
            @Qualifier("loopperAcceptanceCandidateLaunchMapper")
            LoopperAcceptanceCandidateLaunchMapper mapper) {
        this.mapper = mapper;
    }

    Optional<AcceptanceCandidateInternalLaunchRow> findForCompilation(String compilationId) {
        return mapper.findAcceptanceCandidateInternalLaunchForCompilation(compilationId);
    }

    AcceptanceCandidateInternalLaunchRow insert(AcceptanceCandidateInternalLaunchRow row) {
        if (mapper.insertAcceptanceCandidateInternalLaunch(row) != 1) {
            throw new ConflictException("ACCEPTANCE_INTERNAL_LAUNCH_CREATE_CONFLICT",
                    "验收候选 internal launch 无法冻结");
        }
        return mapper.findAcceptanceCandidateInternalLaunch(row.id())
                .orElseThrow(() -> new ConflictException("ACCEPTANCE_INTERNAL_LAUNCH_CREATE_CONFLICT",
                        "验收候选 internal launch 冻结结果缺失"));
    }
}
