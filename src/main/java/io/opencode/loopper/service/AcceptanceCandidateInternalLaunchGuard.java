package io.opencode.loopper.service;

import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Re-proves every owner, source, planning and route fact before local launch preparation. */
@Component
final class AcceptanceCandidateInternalLaunchGuard {
    private static final Set<String> OWNER_STATES = Set.of("PENDING_HANDOFF", "RUNNING");
    private final LoopperMapper mapper;
    private final AcceptanceCandidateInternalLaunchPlanCodec plans;

    AcceptanceCandidateInternalLaunchGuard(LoopperMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.plans = new AcceptanceCandidateInternalLaunchPlanCodec(json);
    }

    Anchor validate(AcceptanceCandidateInternalLaunchPreparer.PrepareCommand command) {
        requireCommand(command);
        DesignerSessionRow session = mapper.findDesignerSession(command.designerSessionId())
                .orElseThrow(() -> stale("ACCEPTANCE_INTERNAL_LAUNCH_OWNER_STALE"));
        LoopSpecCompilationRow compilation = mapper.findLoopSpecCompilation(command.compilationId())
                .orElseThrow(() -> stale("ACCEPTANCE_INTERNAL_LAUNCH_OWNER_STALE"));
        requireOwner(command, session, compilation);
        DesignerMessageRow source = mapper.findDesignerMessage(command.sourceDesignMessageId())
                .orElseThrow(() -> stale("ACCEPTANCE_INTERNAL_LAUNCH_SOURCE_STALE"));
        requireSource(command, source);
        DesignWorkPackageRow workPackage = mapper.findLatestDesignWorkPackage(
                command.designerSessionId(), command.workPackageId())
                .orElseThrow(() -> stale("ACCEPTANCE_INTERNAL_LAUNCH_SOURCE_STALE"));
        requireWorkPackage(command, workPackage);
        DesignAcceptancePlanningRow planning = mapper.findDesignAcceptancePlanning(command.compilationId())
                .orElseThrow(() -> stale("ACCEPTANCE_INTERNAL_LAUNCH_PLANNING_STALE"));
        requirePlanning(command, planning);
        plans.validateRoutePlan(command.routePlanJson());
        if (!constantTimeEquals(command.routePlanSha256(), sha256(command.routePlanJson()))) {
            throw stale("ACCEPTANCE_INTERNAL_LAUNCH_ROUTE_INVALID");
        }
        ProjectRow project = mapper.findProject(session.projectId())
                .orElseThrow(() -> stale("ACCEPTANCE_INTERNAL_LAUNCH_OWNER_STALE"));
        return new Anchor(Path.of(project.rootPath()).toAbsolutePath().normalize());
    }

    void validateReplay(AcceptanceCandidateInternalLaunchPreparer.PrepareCommand command,
            AcceptanceCandidateInternalLaunchRow row) {
        String launchId = AcceptanceCandidateInternalLaunchPreparer.launchId(command);
        String runId = AcceptanceCandidateInternalLaunchPreparer.candidateRunId(command);
        OpenCodeClient.OpenCodeModel rowModel = row.modelProviderId() == null ? null
                : new OpenCodeClient.OpenCodeModel(row.modelProviderId(), row.modelId(), row.thinking());
        if (!Objects.equals(launchId, row.id()) || !Objects.equals(runId, row.candidateRunId())
                || !Objects.equals(command.compilationId(), row.compilationId())
                || !Objects.equals(command.designerSessionId(), row.designerSessionId())
                || !Objects.equals(command.workPackageId(), row.workPackageId())
                || command.sourceDesignRevision() != row.sourceDesignRevision()
                || !Objects.equals(command.sourceDesignMessageId(), row.sourceDesignMessageId())
                || command.sourceDraftVersion() != row.sourceDraftVersion()
                || !Objects.equals(command.sourceDesignSha256(), row.sourceDesignSha256())
                || command.planningVersion() != row.planningVersion()
                || !Objects.equals(command.planningBindingSource(), row.planningBindingSource())
                || !Objects.equals(command.planningBindingJson(), row.planningBindingJson())
                || !Objects.equals(command.planningBindingSha256(), row.planningBindingSha256())
                || !Objects.equals(command.routePlanJson(), row.routePlanJson())
                || !Objects.equals(command.routePlanSha256(), row.routePlanSha256())
                || command.preparedOwnerVersion() != row.preparedOwnerVersion()
                || !Objects.equals(command.model(), rowModel)) {
            throw stale("ACCEPTANCE_INTERNAL_LAUNCH_REPLAY_MISMATCH");
        }
    }

    void validateCurrent(AcceptanceCandidateInternalLaunchRow row,
            LoopSpecCompilationRow compilation, DesignerSessionRow session,
            DesignWorkPackageRow workPackage, DesignAcceptancePlanningRow planning,
            String currentRoutePlanJson) {
        if (row == null || compilation == null || session == null || workPackage == null || planning == null
                || !Objects.equals(row.compilationId(), compilation.id())
                || !Objects.equals(row.designerSessionId(), compilation.designerSessionId())
                || !Objects.equals(row.designerSessionId(), session.id())
                || !Objects.equals(row.workPackageId(), compilation.workPackageId())
                || !Objects.equals(row.workPackageId(), session.activeWorkPackageId())
                || !Objects.equals(row.workPackageId(), workPackage.packageId())
                || compilation.designRevision() != row.sourceDesignRevision()
                || workPackage.designRevision() != row.sourceDesignRevision()
                || !Objects.equals(compilation.sourceDesignMessageId(), row.sourceDesignMessageId())
                || !Objects.equals(workPackage.designMessageId(), row.sourceDesignMessageId())
                || compilation.sourceDraftVersion() != row.sourceDraftVersion()
                || !OWNER_STATES.contains(session.state()) || !"COMPILING".equals(session.workflowPhase())
                || !"COMPILING".equals(workPackage.state())) {
            throw stale("ACCEPTANCE_INTERNAL_LAUNCH_OWNER_STALE");
        }
        boolean terminalLaunch = Set.of("FAILED_STOPPED", "CANCELLED", "STALE").contains(row.state());
        if (row.settledOwnerVersion() != null) {
            if (!"SETTLED".equals(row.state()) || !"RUNNING".equals(compilation.state())
                    || !Objects.equals(row.externalSessionId(), compilation.externalSessionId())
                    || compilation.version() < row.settledOwnerVersion()) {
                throw stale("ACCEPTANCE_INTERNAL_LAUNCH_OWNER_STALE");
            }
        } else if (!terminalLaunch && (!"PENDING_HANDOFF".equals(compilation.state())
                || compilation.externalSessionId() != null
                || compilation.version() != row.preparedOwnerVersion())) {
            throw stale("ACCEPTANCE_INTERNAL_LAUNCH_OWNER_STALE");
        }
        if (!Objects.equals(row.compilationId(), planning.compilationId())
                || !Objects.equals(row.designerSessionId(), planning.designerSessionId())
                || !Objects.equals(row.workPackageId(), planning.workPackageId())
                || planning.designRevision() != row.sourceDesignRevision()
                || !DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(planning.contractVersion())
                || !constantTimeEquals(row.sourceDesignSha256(), planning.designSha256())) {
            throw planningStale("OWNER");
        }
        boolean frozenPlanning = "EXTRACTED".equals(planning.state())
                && planning.version() == row.planningVersion()
                && Objects.equals(row.planningBindingSource(), planning.bindingSource())
                && Objects.equals(row.planningBindingJson(), planning.bindingJson());
        boolean advancedPlanning = (row.settledOwnerVersion() != null || terminalLaunch)
                && Set.of("BOUND", "COMPILED").contains(planning.state())
                && planning.version() >= row.planningVersion();
        if (!frozenPlanning && !advancedPlanning) throw planningStale("STATE");
        if (!constantTimeEquals(row.routePlanSha256(), sha256(currentRoutePlanJson))
                || !Objects.equals(row.routePlanJson(), currentRoutePlanJson)) {
            throw stale("ACCEPTANCE_INTERNAL_LAUNCH_ROUTE_INVALID");
        }
    }

    private void requireCommand(AcceptanceCandidateInternalLaunchPreparer.PrepareCommand command) {
        if (command == null || blank(command.compilationId()) || blank(command.designerSessionId())
                || blank(command.workPackageId()) || command.sourceDesignRevision() <= 0
                || blank(command.sourceDesignMessageId()) || command.sourceDraftVersion() < 0
                || command.preparedOwnerVersion() < 0
                || !sha(command.sourceDesignSha256()) || command.planningVersion() < 0
                || !"AI_DISAMBIGUATION_V6".equals(command.planningBindingSource())
                || !sha(command.planningBindingSha256()) || !sha(command.routePlanSha256())) {
            throw stale("ACCEPTANCE_INTERNAL_LAUNCH_COMMAND_INVALID");
        }
        if (command.model() != null && (blank(command.model().providerId())
                || blank(command.model().modelId()))) {
            throw stale("ACCEPTANCE_INTERNAL_LAUNCH_COMMAND_INVALID");
        }
        plans.validatePlanningObject(command.planningBindingJson());
    }

    private void requireOwner(AcceptanceCandidateInternalLaunchPreparer.PrepareCommand command,
            DesignerSessionRow session, LoopSpecCompilationRow compilation) {
        if (!OWNER_STATES.contains(session.state()) || !"COMPILING".equals(session.workflowPhase())
                || !Objects.equals(command.workPackageId(), session.activeWorkPackageId())
                || !"PENDING_HANDOFF".equals(compilation.state())
                || compilation.externalSessionId() != null
                || compilation.version() != command.preparedOwnerVersion()
                || !Objects.equals(command.designerSessionId(), compilation.designerSessionId())
                || !Objects.equals(command.workPackageId(), compilation.workPackageId())
                || compilation.designRevision() != command.sourceDesignRevision()
                || !Objects.equals(command.sourceDesignMessageId(), compilation.sourceDesignMessageId())
                || compilation.sourceDraftVersion() != command.sourceDraftVersion()) {
            throw stale("ACCEPTANCE_INTERNAL_LAUNCH_OWNER_STALE");
        }
    }

    private void requireSource(AcceptanceCandidateInternalLaunchPreparer.PrepareCommand command,
            DesignerMessageRow source) {
        if (!Objects.equals(command.designerSessionId(), source.designerSessionId())
                || !constantTimeEquals(command.sourceDesignSha256(), sha256(source.content()))) {
            throw stale("ACCEPTANCE_INTERNAL_LAUNCH_SOURCE_STALE");
        }
    }

    private void requireWorkPackage(AcceptanceCandidateInternalLaunchPreparer.PrepareCommand command,
            DesignWorkPackageRow workPackage) {
        var revision = mapper.findDesignRequirementRevision(workPackage.requirementRevisionId())
                .orElseThrow(() -> stale("ACCEPTANCE_INTERNAL_LAUNCH_SOURCE_STALE"));
        if (!Objects.equals(command.designerSessionId(), workPackage.designerSessionId())
                || !Objects.equals(command.workPackageId(), workPackage.packageId())
                || workPackage.designRevision() != command.sourceDesignRevision()
                || !Objects.equals(command.sourceDesignMessageId(), workPackage.designMessageId())
                || !"COMPILING".equals(workPackage.state())
                || !Objects.equals(command.designerSessionId(), revision.designerSessionId())
                || revision.sourceDraftVersion() != command.sourceDraftVersion()) {
            throw stale("ACCEPTANCE_INTERNAL_LAUNCH_SOURCE_STALE");
        }
    }

    private void requirePlanning(AcceptanceCandidateInternalLaunchPreparer.PrepareCommand command,
            DesignAcceptancePlanningRow planning) {
        if (!Objects.equals(command.compilationId(), planning.compilationId())
                || !Objects.equals(command.designerSessionId(), planning.designerSessionId())
                || !Objects.equals(command.workPackageId(), planning.workPackageId())
                || planning.designRevision() != command.sourceDesignRevision()) throw planningStale("OWNER");
        if (!DesignerAcceptancePlanning.CONTRACT_VERSION_V7.equals(planning.contractVersion())
                || !"EXTRACTED".equals(planning.state())) throw planningStale("STATE");
        if (planning.version() != command.planningVersion()) throw planningStale("VERSION");
        if (!Objects.equals(command.planningBindingSource(), planning.bindingSource())
                || !Objects.equals(command.planningBindingJson(), planning.bindingJson())) {
            throw planningStale("BINDING");
        }
        if (!constantTimeEquals(command.sourceDesignSha256(), planning.designSha256())) {
            throw planningStale("DESIGN_DIGEST");
        }
        if (!constantTimeEquals(command.planningBindingSha256(), sha256(command.planningBindingJson()))) {
            throw planningStale("BINDING_DIGEST");
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean sha(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static ConflictException stale(String code) {
        return new ConflictException(code, "验收候选 internal launch 的 owner/source/planning/route 已变化");
    }

    private static ConflictException planningStale(String fact) {
        return new ConflictException("ACCEPTANCE_INTERNAL_LAUNCH_PLANNING_STALE",
                "验收候选 internal launch 的冻结规划事实已变化：" + fact);
    }

    record Anchor(Path projectRoot) { }
}
