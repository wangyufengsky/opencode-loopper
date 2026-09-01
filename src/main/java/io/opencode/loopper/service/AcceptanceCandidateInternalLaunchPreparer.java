package io.opencode.loopper.service;

import io.opencode.loopper.persistence.AcceptanceCandidateInternalLaunchRow;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Freezes one Acceptance-v7 internal create plan using local runtime identity only. */
@Service
final class AcceptanceCandidateInternalLaunchPreparer {
    private final OpenCodeClient openCode;
    private final AcceptanceCandidateInternalLaunchGuard guard;
    private final AcceptanceCandidateInternalLaunchStore store;
    private final AcceptanceCandidateInternalLaunchPlanCodec plans;
    private final AcceptanceCandidateCreationCredentialSource credentials;
    private final ObjectMapper json;

    AcceptanceCandidateInternalLaunchPreparer(OpenCodeClient openCode,
            AcceptanceCandidateInternalLaunchGuard guard, AcceptanceCandidateInternalLaunchStore store,
            AcceptanceCandidateInternalLaunchPlanCodec plans,
            AcceptanceCandidateCreationCredentialSource credentials, ObjectMapper json) {
        this.openCode = openCode;
        this.guard = guard;
        this.store = store;
        this.plans = plans;
        this.credentials = credentials;
        this.json = json;
    }

    Optional<Prepared> prepareFrozen(LoopSpecCompilationRow compilation, DesignerSessionRow session,
            DesignWorkPackageRow workPackage, DesignAcceptancePlanningRow planning,
            DesignerAcceptanceWorkflow.RoutingResult routing, OpenCodeClient.OpenCodeModel model,
            boolean allowCreate) {
        AcceptanceCandidateInternalLaunchRow existing = store.findForCompilation(compilation.id()).orElse(null);
        if (existing == null && !allowCreate) return Optional.empty();
        String route = routePlan(routing);
        OpenCodeClient.OpenCodeModel frozenModel = existing == null ? model
                : existing.modelProviderId() == null ? null : new OpenCodeClient.OpenCodeModel(
                        existing.modelProviderId(), existing.modelId(), existing.thinking());
        if (existing != null) {
            guard.validateCurrent(existing, compilation, session, workPackage, planning, route);
        }
        PrepareCommand command = new PrepareCommand(compilation.id(), session.id(), workPackage.packageId(),
                compilation.designRevision(), compilation.sourceDesignMessageId(), compilation.sourceDraftVersion(),
                existing == null ? planning.designSha256() : existing.sourceDesignSha256(),
                existing == null ? planning.version() : existing.planningVersion(),
                existing == null ? planning.bindingSource() : existing.planningBindingSource(),
                existing == null ? planning.bindingJson() : existing.planningBindingJson(),
                existing == null ? sha256(planning.bindingJson()) : existing.planningBindingSha256(),
                existing == null ? route : existing.routePlanJson(),
                existing == null ? sha256(route) : existing.routePlanSha256(),
                existing == null ? compilation.version() : existing.preparedOwnerVersion(), frozenModel);
        if (existing == null) return Optional.of(prepare(command));
        guard.validateReplay(command, existing);
        return Optional.of(new Prepared(existing, plans.decode(existing)));
    }

    Prepared prepare(PrepareCommand command) {
        AcceptanceCandidateInternalLaunchGuard.Anchor anchor = guard.validate(command);
        AcceptanceCandidateInternalLaunchRow existing = store.findForCompilation(
                command.compilationId()).orElse(null);
        if (existing != null) {
            guard.validateReplay(command, existing);
            return new Prepared(existing, plans.decode(existing));
        }

        String launchId = launchId(command);
        String candidateRunId = candidateRunId(command);
        String credential = credentials.create();
        String baseTitle = baseTitle(command.workPackageId(), candidateRunId, launchId);
        OpenCodeClient.SessionCreationPlan plan = openCode.prepareCandidateSessionCreationLocally(
                anchor.projectRoot(), baseTitle, command.model(),
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                credential);
        plans.validatePreparedPlan(baseTitle, command.model(), plan);
        AcceptanceCandidateInternalLaunchRow created = row(command, launchId, candidateRunId, plan);
        if (!plans.decode(created).equals(plan)) {
            throw new ConflictException("ACCEPTANCE_INTERNAL_LAUNCH_PLAN_INVALID",
                    "验收候选 internal launch 的本地 create plan 无法无损冻结");
        }
        AcceptanceCandidateInternalLaunchRow stored;
        try {
            stored = store.insert(created);
        } catch (ConflictException concurrent) {
            if (!"ACCEPTANCE_INTERNAL_LAUNCH_CREATE_CONFLICT".equals(concurrent.code())) throw concurrent;
            stored = store.findForCompilation(command.compilationId()).orElseThrow(() -> concurrent);
            guard.validateReplay(command, stored);
        }
        return new Prepared(stored, plans.decode(stored));
    }

    private AcceptanceCandidateInternalLaunchRow row(PrepareCommand command, String launchId,
            String candidateRunId, OpenCodeClient.SessionCreationPlan plan) {
        String now = Instant.now().toString();
        return new AcceptanceCandidateInternalLaunchRow(
                launchId, command.compilationId(), command.designerSessionId(), command.workPackageId(),
                command.sourceDesignRevision(), command.sourceDesignMessageId(), command.sourceDraftVersion(),
                command.sourceDesignSha256(), command.planningVersion(), command.planningBindingSource(),
                command.planningBindingJson(), command.planningBindingSha256(), command.routePlanJson(),
                command.routePlanSha256(), candidateRunId, AcceptanceCandidateInternalLaunchPlanCodec.CONTRACT,
                AcceptanceCandidateInternalLaunchPlanCodec.CONTRACT, "PREPARED",
                command.preparedOwnerVersion(), null, null, plan.exactTitle(),
                plan.canonicalDirectory().toString(), plan.runtimeGenerationId(), plan.managed(),
                plan.internalMcpServer(), plan.endpointFingerprint(),
                plan.model() == null ? null : plan.model().providerId(),
                plan.model() == null ? null : plan.model().modelId(),
                plan.model() == null ? null : plan.model().thinking(), plan.profile().name(),
                plans.encodePermissionPolicy(plan), plan.permissionPolicyDigest(), plan.createRequestSha256(),
                plan.creationCredential(), "LOCAL_REQUEST_ATTESTED", null, null, null, 0,
                false, null, null, null, null, null, null, null, null, now, now, 0);
    }

    static String launchId(PrepareCommand command) {
        return deterministic("acceptance-v7-internal-launch", command);
    }

    static String candidateRunId(PrepareCommand command) {
        return deterministic("acceptance-v7-candidate-run", command);
    }

    static String baseTitle(String workPackageId, String candidateRunId, String launchId) {
        if (blank(workPackageId) || blank(candidateRunId) || blank(launchId)) {
            throw new IllegalArgumentException("Complete internal launch title identity is required");
        }
        return "OpenCode Loopper Acceptance Closed-Choice " + workPackageId
                + " [candidate-run:" + candidateRunId + "] [internal-launch:" + launchId + "]";
    }

    private static String deterministic(String domain, PrepareCommand command) {
        Objects.requireNonNull(command, "Internal launch command is required");
        String identity = domain + "\n" + command.compilationId() + "\n"
                + command.designerSessionId() + "\n" + command.workPackageId() + "\n"
                + command.sourceDesignRevision() + "\n" + command.sourceDesignMessageId() + "\n"
                + command.sourceDraftVersion() + "\n" + command.sourceDesignSha256() + "\n"
                + command.planningVersion() + "\n" + command.planningBindingSha256() + "\n"
                + command.routePlanSha256() + "\n" + command.preparedOwnerVersion();
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private String routePlan(DesignerAcceptanceWorkflow.RoutingResult routing) {
        if (routing == null || routing.resolution() == null) {
            throw new ConflictException("ACCEPTANCE_INTERNAL_LAUNCH_ROUTE_INVALID",
                    "验收候选 internal launch 缺少冻结的闭集路由");
        }
        LinkedHashMap<String, Object> frozen = new LinkedHashMap<>();
        frozen.put("contractVersion", AcceptanceCandidateInternalLaunchPlanCodec.CONTRACT);
        frozen.put("serverResolved", routing.serverResolved());
        frozen.put("compilerRequired", routing.compilerRequired());
        try {
            frozen.put("resolution", json.readTree(
                    new DesignerClosedChoiceContract(json, null).resolution(routing.resolution())));
            return json.writeValueAsString(frozen);
        } catch (JacksonException invalid) {
            throw new ConflictException("ACCEPTANCE_INTERNAL_LAUNCH_ROUTE_INVALID",
                    "验收候选 internal launch 无法冻结闭集路由");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record PrepareCommand(
            String compilationId, String designerSessionId, String workPackageId,
            long sourceDesignRevision, String sourceDesignMessageId, long sourceDraftVersion,
            String sourceDesignSha256, long planningVersion, String planningBindingSource,
            String planningBindingJson, String planningBindingSha256,
            String routePlanJson, String routePlanSha256,
            long preparedOwnerVersion, OpenCodeClient.OpenCodeModel model) { }

    record Prepared(AcceptanceCandidateInternalLaunchRow row,
                    OpenCodeClient.SessionCreationPlan plan) { }
}
