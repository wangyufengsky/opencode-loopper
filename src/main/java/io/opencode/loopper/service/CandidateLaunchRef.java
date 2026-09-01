package io.opencode.loopper.service;

/** Typed reference to the protocol-specific durable launch that owns one INTERNAL_MCP run. */
record CandidateLaunchRef(Protocol protocol, String id) {
    CandidateLaunchRef {
        if (protocol == null) throw new IllegalArgumentException("Candidate launch protocol is required");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Candidate launch id is required");
    }

    static CandidateLaunchRef acceptanceV55(String id) {
        return new CandidateLaunchRef(Protocol.ACCEPTANCE_V55, id);
    }

    static CandidateLaunchRef genericV1(String id) {
        return new CandidateLaunchRef(Protocol.GENERIC_V1, id);
    }

    static CandidateLaunchRef fromColumns(String internalLaunchId, String candidateLaunchId) {
        if (internalLaunchId != null && candidateLaunchId != null) {
            throw new IllegalArgumentException("Candidate prompt cannot reference two launch protocols");
        }
        if (internalLaunchId != null) return acceptanceV55(internalLaunchId);
        if (candidateLaunchId != null) return genericV1(candidateLaunchId);
        return null;
    }

    String internalLaunchId() {
        return protocol == Protocol.ACCEPTANCE_V55 ? id : null;
    }

    String candidateLaunchId() {
        return protocol == Protocol.GENERIC_V1 ? id : null;
    }

    enum Protocol {
        ACCEPTANCE_V55,
        GENERIC_V1
    }
}
