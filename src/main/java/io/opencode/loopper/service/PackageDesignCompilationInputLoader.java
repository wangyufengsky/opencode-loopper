package io.opencode.loopper.service;

import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.domain.WorkflowTemplate;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.LoopperDesignerMapper;
import io.opencode.loopper.persistence.WorkPackageRoleProfileRow;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Loads only frozen SQLite facts needed by package-design policy and accepted writer. */
interface PackageDesignCompilationInputLoader {
    PackageDesignCompilation.Input load(CandidatePolicy.Context context);

    final class MapperLoader implements PackageDesignCompilationInputLoader {
        private final LoopperDesignerMapper mapper;
        private final ObjectMapper json;

        MapperLoader(LoopperDesignerMapper mapper, ObjectMapper json) {
            this.mapper = mapper;
            this.json = json;
        }

        @Override
        public PackageDesignCompilation.Input load(CandidatePolicy.Context context) {
            String ownerId = context.owner().designWorkPackageId();
            DesignWorkPackageRow owner = mapper.findDesignWorkPackage(ownerId).orElseThrow(() ->
                    new ConflictException("CANDIDATE_OWNER_MISSING", "Package design candidate owner no longer exists"));
            if (!context.designerSessionId().equals(owner.designerSessionId())
                    || owner.version() != context.ownerVersion()
                    || context.sourceRevision() != owner.designRevision() + 1L) {
                throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                        "Package design candidate owner revision has changed");
            }
            DesignRequirementRevisionRow requirement = mapper.findDesignRequirementRevision(owner.requirementRevisionId())
                    .filter(row -> context.designerSessionId().equals(row.designerSessionId()))
                    .orElseThrow(() -> new ConflictException(
                            "CANDIDATE_REQUIREMENT_MISSING", "Frozen package requirement no longer exists"));
            WorkPackageRoleProfileRow frozenRole = mapper.findWorkPackageRoleProfile(owner.id())
                    .filter(row -> context.designerSessionId().equals(row.designerSessionId()))
                    .orElseThrow(() -> new ConflictException(
                            "CANDIDATE_PACKAGE_ROLE_MISSING", "Frozen package role no longer exists"));
            var profile = mapper.findDesignerTaskProfile(frozenRole.taskProfileId())
                    .orElseThrow(() -> new ConflictException(
                            "CANDIDATE_TASK_PROFILE_MISSING", "Frozen task profile no longer exists"));
            boolean direct = WorkflowTemplate.DIRECT_SOFTWARE_DESIGN.name().equals(profile.workflowTemplate());
            WorkPackageRoleService.View role = new WorkPackageRoleService.View(
                    frozenRole.rolePackId(), frozenRole.rolePackVersion(),
                    ExecutionStrategy.valueOf(frozenRole.executionStrategy()),
                    TestPolicy.valueOf(frozenRole.testPolicy()), read(frozenRole.technologiesJson()),
                    frozenRole.projectStackProfileId(), read(frozenRole.componentKeysJson()),
                    frozenRole.stackFingerprint());
            return new PackageDesignCompilation.Input(targetRevision(owner, Math.toIntExact(context.sourceRevision())),
                    requirement.requirementText(), role, read(owner.scopeInJson()), read(owner.scopeOutJson()),
                    read(owner.deliverablesJson()), direct ? 6 : 3, direct);
        }

        private List<String> read(String source) {
            try { return source == null ? List.of() : json.readValue(source, new TypeReference<>() { }); }
            catch (JacksonException invalid) {
                throw new ConflictException("CANDIDATE_PACKAGE_SNAPSHOT_INVALID",
                        "Frozen package design inputs cannot be read");
            }
        }

        private static DesignWorkPackageRow targetRevision(DesignWorkPackageRow row, int revision) {
            return new DesignWorkPackageRow(row.id(), row.designerSessionId(), row.requirementRevisionId(),
                    row.decompositionId(), row.packageId(), row.ordinal(), row.title(), row.objective(),
                    row.scopeInJson(), row.scopeOutJson(), row.dependenciesJson(), row.deliverablesJson(),
                    row.acceptanceIntentJson(), row.requirementRefsJson(), row.state(),
                    row.designerExternalSessionId(), row.designerExternalSessionState(), row.designMessageId(),
                    revision, row.redesignCount(), row.designerTransportRetryCount(), row.compilerSummary(),
                    row.handoffSummary(), row.lastErrorCode(), row.lastErrorDetail(), row.approvedDesignRevision(),
                    row.discussionRoundCount(), row.invalidatedByPackageId(), row.approvedAt(), row.createdAt(),
                    row.updatedAt(), row.version(), row.planRevision(), row.correctionOfPackageId(), row.supersededAt());
        }
    }
}
