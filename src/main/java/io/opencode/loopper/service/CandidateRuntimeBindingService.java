package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignWorkPackageState;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.DesignRequirementRevisionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.OpenCodeSessionRuntimeBindingRow;
import io.opencode.loopper.persistence.TaskDecompositionRow;
import io.opencode.loopper.runtime.InternalMcpCredentialProvider;
import io.opencode.loopper.runtime.InternalMcpReadiness;
import io.opencode.loopper.runtime.InternalMcpRuntimeAccess;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Freezes the ownership and generation of every Session participating in the
 * candidate protocol, then revalidates that boundary before policy evaluation
 * and again inside the short accepted-candidate transaction.
 */
@Service
@ConditionalOnProperty(name = "loopper.internal-candidate.runtime-guard-enabled",
        havingValue = "true", matchIfMissing = true)
public final class CandidateRuntimeBindingService implements CandidateRunGuard {
    private final LoopperMapper mapper;
    private final InternalMcpRuntimeAccess runtimeAccess;

    public CandidateRuntimeBindingService(LoopperMapper mapper, InternalMcpRuntimeAccess runtimeAccess) {
        this.mapper = mapper;
        this.runtimeAccess = runtimeAccess;
    }

    public synchronized Binding bind(OpenCodeClient.OpenCodeSession session,
                                     MachineCandidateSubmission.SubmissionChannel channel) {
        if (session == null || blank(session.id()) || session.worktree() == null || channel == null) {
            throw new BadRequestException("CANDIDATE_RUNTIME_BINDING_INVALID",
                    "候选运行的 OpenCode Session 绑定参数不完整");
        }
        OpenCodeSessionRuntimeBindingRow existing = mapper.findOpenCodeSessionRuntimeBinding(session.id())
                .orElse(null);
        if (managed(session)) {
            if (existing == null || !"MANAGED".equals(existing.ownershipMode())
                    || !java.util.Objects.equals(existing.runtimeGenerationId(), session.generation())
                    || !java.util.Objects.equals(existing.internalMcpServer(), session.internalMcpServer())) {
                throw new ConflictException("CANDIDATE_RUNTIME_BINDING_STALE",
                        "OpenCode Session 缺少创建时的受管运行时代际绑定");
            }
            validateActiveManaged(existing);
            return binding(existing);
        }
        if (existing != null) {
            requireExistingExternal(existing, channel);
            return binding(existing);
        }
        OpenCodeSessionRuntimeBindingRow desired = externalBinding(session, channel);
        if (mapper.insertOpenCodeSessionRuntimeBinding(desired) != 1) {
            throw new ConflictException("CANDIDATE_RUNTIME_BINDING_CONFLICT",
                    "OpenCode Session 运行时代际绑定未能持久化");
        }
        return binding(desired);
    }

    @Override
    public void validate(MachineCandidateSubmission.RunSnapshot run,
                         MachineCandidateSubmission.SubmissionChannel submissionChannel) {
        OpenCodeSessionRuntimeBindingRow binding = mapper
                .findOpenCodeSessionRuntimeBinding(run.externalSessionId())
                .orElseThrow(() -> new ConflictException("CANDIDATE_RUNTIME_BINDING_STALE",
                        "OpenCode Session 运行时代际绑定不存在"));
        if (!binding.runtimeGenerationId().equals(run.runtimeGenerationId())) {
            throw new ConflictException("CANDIDATE_RUNTIME_BINDING_STALE",
                    "OpenCode Session 运行时代际绑定已经变化");
        }
        if (submissionChannel == MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP) {
            if (!"MANAGED".equals(binding.ownershipMode())) {
                throw new ConflictException("CANDIDATE_MANAGED_RUNTIME_REQUIRED",
                        "内部 MCP 仅接受受管 OpenCode Session");
            }
            validateActiveManaged(binding);
        } else if ("MANAGED".equals(binding.ownershipMode())) {
            // A fresh in-process fallback may still use a managed Session, but
            // it must never revive a Session from an earlier process generation.
            validateActiveManaged(binding);
        }
        validateOwnerAndSource(run);
    }

    private OpenCodeSessionRuntimeBindingRow externalBinding(
            OpenCodeClient.OpenCodeSession session,
            MachineCandidateSubmission.SubmissionChannel channel) {
        if (channel != MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY) {
            throw new ConflictException("CANDIDATE_MANAGED_RUNTIME_REQUIRED",
                    "内部 MCP 仅接受受管 OpenCode Session");
        }
        String fingerprint = sha256("EXTERNAL\n" + session.id() + "\n"
                + session.worktree().toAbsolutePath().normalize());
        return new OpenCodeSessionRuntimeBindingRow(session.id(), "external-" + fingerprint,
                "EXTERNAL", fingerprint, null, Instant.now().toString());
    }

    private void requireExistingExternal(OpenCodeSessionRuntimeBindingRow existing,
                                         MachineCandidateSubmission.SubmissionChannel channel) {
        if (channel != MachineCandidateSubmission.SubmissionChannel.IN_PROCESS_LEGACY) {
            throw new ConflictException("CANDIDATE_MANAGED_RUNTIME_REQUIRED",
                    "内部 MCP 仅接受受管 OpenCode Session");
        }
        if (!"EXTERNAL".equals(existing.ownershipMode())
                || blank(existing.runtimeGenerationId())
                || !existing.runtimeGenerationId().startsWith("external-")
                || blank(existing.endpointFingerprint())
                || existing.endpointFingerprint().length() != 64
                || !blank(existing.internalMcpServer())) {
            throw new ConflictException("CANDIDATE_RUNTIME_BINDING_CONFLICT",
                    "OpenCode Session 已绑定到不兼容的运行时代际");
        }
    }

    private void validateActiveManaged(OpenCodeSessionRuntimeBindingRow binding) {
        InternalMcpCredentialProvider.Credentials active = runtimeAccess.current()
                .orElseThrow(() -> new ConflictException("CANDIDATE_RUNTIME_GENERATION_STALE",
                        "受管 OpenCode 运行时代际已停止"));
        InternalMcpReadiness readiness = runtimeAccess.readiness();
        if (!active.generation().equals(binding.runtimeGenerationId())
                || !active.serverName().equals(binding.internalMcpServer())
                || !"CONNECTED".equals(readiness.status())
                || !active.generation().equals(readiness.generation())) {
            throw new ConflictException("CANDIDATE_RUNTIME_GENERATION_STALE",
                    "受管 OpenCode 运行时代际或内部 MCP 就绪证明已经变化");
        }
    }

    private void validateOwnerAndSource(MachineCandidateSubmission.RunSnapshot run) {
        if (run.candidateKind() == MachineCandidateKind.DECOMPOSITION_PLAN_V2) {
            TaskDecompositionRow owner = mapper.findTaskDecomposition(run.owner().taskDecompositionId())
                    .orElseThrow(() -> new ConflictException("CANDIDATE_OWNER_MISSING",
                            "Task decomposition candidate owner no longer exists"));
            if (!run.designerSessionId().equals(owner.designerSessionId())
                    || owner.version() != run.ownerVersion()) {
                throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                        "Task decomposition candidate owner revision has changed");
            }
            DesignRequirementRevisionRow revision = mapper
                    .findDesignRequirementRevision(owner.requirementRevisionId())
                    .filter(item -> run.designerSessionId().equals(item.designerSessionId())
                            && item.revision() == run.sourceRevision())
                    .orElseThrow(() -> new ConflictException("CANDIDATE_SOURCE_REVISION_STALE",
                            "Frozen requirement revision has changed"));
            return;
        }
        if (run.candidateKind() == MachineCandidateKind.PACKAGE_DESIGN_V1) {
            var owner = mapper.findDesignWorkPackage(run.owner().designWorkPackageId())
                    .orElseThrow(() -> new ConflictException("CANDIDATE_OWNER_MISSING",
                            "Package design candidate owner no longer exists"));
            if (!run.designerSessionId().equals(owner.designerSessionId())
                    || owner.version() != run.ownerVersion()) {
                throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                        "Package design candidate owner revision has changed");
            }
            if (!DesignWorkPackageState.DESIGNING.name().equals(owner.state())
                    && !DesignWorkPackageState.QUESTIONING.name().equals(owner.state())) {
                throw new ConflictException("CANDIDATE_OWNER_STATE_INVALID",
                        "Package design candidate owner is no longer accepting candidates");
            }
            if (!run.externalSessionId().equals(owner.designerExternalSessionId())) {
                throw new ConflictException("CANDIDATE_OWNER_SESSION_STALE",
                        "Package design candidate owner remote Session has changed");
            }
            if ((long) owner.designRevision() + 1 != run.sourceRevision()) {
                throw new ConflictException("CANDIDATE_SOURCE_REVISION_STALE",
                        "Package design candidate source revision has changed");
            }
            return;
        }
        var owner = mapper.findLoopSpecCompilation(run.owner().loopSpecCompilationId())
                .orElseThrow(() -> new ConflictException("CANDIDATE_OWNER_MISSING",
                        "LoopSpec compilation candidate owner no longer exists"));
        if (!run.designerSessionId().equals(owner.designerSessionId())
                || owner.version() != run.ownerVersion()
                || owner.designRevision() != run.sourceRevision()) {
            throw new ConflictException("CANDIDATE_OWNER_REVISION_STALE",
                    "LoopSpec compilation candidate owner or source revision has changed");
        }
    }

    private boolean managed(OpenCodeClient.OpenCodeSession session) {
        return !blank(session.generation()) || !blank(session.internalMcpServer());
    }

    private Binding binding(OpenCodeSessionRuntimeBindingRow row) {
        return new Binding(row.externalSessionId(), row.runtimeGenerationId(), row.ownershipMode());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record Binding(String externalSessionId, String runtimeGenerationId, String ownershipMode) { }
}
