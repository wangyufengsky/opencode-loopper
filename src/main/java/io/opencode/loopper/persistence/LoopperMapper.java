package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.Mapper;

/**
 * Compatibility aggregate for the domain-focused persistence contracts.
 * New services should depend on the narrowest parent interface they use.
 */
@Mapper
public interface LoopperMapper extends DesignerTimeoutMapper, AutomationMapper, MonitorReadMapper, PackageSemanticPreparationMapper, LoopperInfrastructureMapper, LoopperProjectMapper,
        LoopperDesignerMapper, LoopperTaskMapper, LoopperAttachmentMapper, LoopperMachineCandidateMapper,
        LoopperAcceptanceCandidateLaunchMapper, LoopperAcceptanceCandidateTerminationMapper,
        LoopperGenericCandidateLaunchMapper, LoopperGenericCandidateTerminationMapper,
        LoopperJudgeCandidateMapper, ModelTokenUsageMapper, StoryBindingMapper, DesignerConversationMapper,
        PackageDesignEvidenceMapper {
}
