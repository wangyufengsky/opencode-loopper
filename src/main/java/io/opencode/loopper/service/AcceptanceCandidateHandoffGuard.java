package io.opencode.loopper.service;

import io.opencode.loopper.persistence.AcceptanceCandidateLegacyHandoffRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopSpecCompilationRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.OpenCodeSessionRuntimeBindingRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Exact owner, source and runtime-binding guards shared by handoff transactions. */
@Component
final class AcceptanceCandidateHandoffGuard {
    private final LoopperMapper mapper;

    AcceptanceCandidateHandoffGuard(LoopperMapper mapper) { this.mapper = mapper; }

    LoopSpecCompilationRow currentOwner(AcceptanceCandidateLegacyHandoffRow row, String expectedRemote) {
        DesignerSessionRow session = mapper.findDesignerSession(row.designerSessionId())
                .orElseThrow(() -> stale("CANDIDATE_OWNER_MISSING"));
        LoopSpecCompilationRow owner = mapper.findLoopSpecCompilation(row.compilationId())
                .orElseThrow(() -> stale("CANDIDATE_OWNER_MISSING"));
        owner(owner, session, expectedRemote, row.currentOwnerVersion());
        if (owner.designRevision() != row.sourceDesignRevision()
                || !owner.sourceDesignMessageId().equals(row.sourceDesignMessageId())
                || owner.sourceDraftVersion() != row.sourceDraftVersion()) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_SOURCE_STALE");
        }
        String source = mapper.findDesignerMessage(row.sourceDesignMessageId())
                .orElseThrow(() -> stale("ACCEPTANCE_LEGACY_HANDOFF_SOURCE_MISSING")).content();
        if (!row.sourceDesignSha256().equals(sha256(source))) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_SOURCE_STALE");
        }
        if (java.util.Objects.equals(row.oldExternalSessionId(), expectedRemote)) {
            if (expectedRemote != null) binding(expectedRemote, row.oldRuntimeGenerationId(),
                    row.oldEndpointFingerprint());
        } else if (java.util.Objects.equals(row.legacyExternalSessionId(), expectedRemote)) {
            binding(expectedRemote, row.legacyRuntimeGenerationId(), row.legacyEndpointFingerprint());
        } else {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_REMOTE_STALE");
        }
        return owner;
    }

    LoopSpecCompilationRow attachedOrPreparedOwner(AcceptanceCandidateLegacyHandoffRow row) {
        LoopSpecCompilationRow owner = mapper.findLoopSpecCompilation(row.compilationId())
                .orElseThrow(() -> stale("CANDIDATE_OWNER_MISSING"));
        if (java.util.Objects.equals(row.legacyExternalSessionId(), owner.externalSessionId())) {
            return currentOwner(row, row.legacyExternalSessionId());
        }
        if (java.util.Objects.equals(row.oldExternalSessionId(), owner.externalSessionId())) {
            return currentOwner(row, row.oldExternalSessionId());
        }
        throw stale("ACCEPTANCE_LEGACY_HANDOFF_REMOTE_STALE");
    }

    void owner(LoopSpecCompilationRow compilation, DesignerSessionRow session,
            String expectedRemote, long expectedVersion) {
        if ("STOPPING".equals(session.state()) || "CANCELLED".equals(session.state())) {
            throw stale("ACCEPTANCE_CANDIDATE_OWNER_STOPPING");
        }
        if (!"RUNNING".equals(compilation.state()) || !session.id().equals(compilation.designerSessionId())
                || !java.util.Objects.equals(expectedRemote, compilation.externalSessionId())
                || compilation.version() != expectedVersion) {
            throw stale("CANDIDATE_OWNER_REVISION_STALE");
        }
    }

    void sameAnchor(AcceptanceCandidateLegacyHandoffRow row, LoopSpecCompilationRow compilation) {
        if (!java.util.Objects.equals(row.oldExternalSessionId(), compilation.externalSessionId())
                || row.sourceDesignRevision() != compilation.designRevision()
                || !row.sourceDesignMessageId().equals(compilation.sourceDesignMessageId())) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_ANCHOR_STALE");
        }
    }

    OpenCodeSessionRuntimeBindingRow binding(String remoteId) {
        return mapper.findOpenCodeSessionRuntimeBinding(remoteId)
                .orElseThrow(() -> stale("OPENCODE_SESSION_RUNTIME_BINDING_MISSING"));
    }

    void binding(String remoteId, String generation, String fingerprint) {
        OpenCodeSessionRuntimeBindingRow current = binding(remoteId);
        if (!java.util.Objects.equals(generation, current.runtimeGenerationId())
                || !java.util.Objects.equals(fingerprint, current.endpointFingerprint())) {
            throw stale("ACCEPTANCE_LEGACY_HANDOFF_BINDING_STALE");
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    static ConflictException stale(String code) {
        return new ConflictException(code, "验收候选兼容交接的 owner/source/session 已变化");
    }
}
