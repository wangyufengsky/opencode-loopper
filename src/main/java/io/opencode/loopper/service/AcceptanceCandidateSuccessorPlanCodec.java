package io.opencode.loopper.service;

import io.opencode.loopper.persistence.AcceptanceCandidateLegacyHandoffRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Freezes and reconstructs the exact successor create request across process restarts. */
@Component
final class AcceptanceCandidateSuccessorPlanCodec {
    private final LoopperMapper mapper;
    private final ObjectMapper json;

    AcceptanceCandidateSuccessorPlanCodec(LoopperMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    OpenCodeClient.SessionCreationPlan decode(AcceptanceCandidateLegacyHandoffRow row) {
        try {
            List<OpenCodeClient.SessionPermissionRule> permissionPolicy = json.readValue(
                    row.successorPermissionPolicyJson(), new TypeReference<>() { });
            OpenCodeClient.OpenCodeModel model = row.successorModelProviderId() == null ? null
                    : new OpenCodeClient.OpenCodeModel(row.successorModelProviderId(), row.successorModelId(),
                    row.successorThinking());
            return OpenCodeClient.SessionCreationPlan.fromPersisted(
                    Path.of(row.successorCanonicalDirectory()), row.successorExactTitle(),
                    row.successorRuntimeGenerationId(), row.successorManaged(), row.successorInternalMcpServer(),
                    row.successorEndpointFingerprint(), model,
                    OpenCodeClient.SessionProfile.valueOf(row.successorProfile()), permissionPolicy,
                    row.successorPermissionPolicyDigest(), row.successorCreationCredential(),
                    row.successorCreateRequestSha256());
        } catch (RuntimeException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw stale();
        }
    }

    String encodePermissionPolicy(OpenCodeClient.SessionCreationPlan plan) {
        try { return json.writeValueAsString(plan.permissionPolicy()); }
        catch (Exception invalid) { throw stale(); }
    }

    void validate(DesignerSessionRow owner, OpenCodeClient.SessionCreationPlan plan) {
        Path root = Path.of(mapper.findProject(owner.projectId()).orElseThrow().rootPath())
                .toAbsolutePath().normalize();
        if (plan == null || plan.profile() != OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS
                || !root.equals(plan.canonicalDirectory())
                || !OpenCodeClient.permissionPolicyDigest(plan.permissionPolicy()).equals(plan.permissionPolicyDigest())
                || !OpenCodeClient.sessionCreationRequestSha256(plan).equals(plan.createRequestSha256())) {
            throw stale();
        }
    }

    void validate(AcceptanceCandidateLegacyHandoffRow row, OpenCodeClient.SessionAttestation attestation) {
        if (attestation == null || !decode(row).equals(attestation.plan())
                || attestation.attestationKind() != OpenCodeClient.SessionAttestationKind.LOCAL_REQUEST_ATTESTED) {
            throw new ConflictException("ACCEPTANCE_LEGACY_HANDOFF_ATTESTATION_STALE",
                    "验收候选兼容交接的 successor attestation 已变化");
        }
    }

    private static ConflictException stale() {
        return new ConflictException("ACCEPTANCE_LEGACY_HANDOFF_PLAN_INVALID",
                "验收候选兼容交接的 successor create plan 无法验证");
    }
}
