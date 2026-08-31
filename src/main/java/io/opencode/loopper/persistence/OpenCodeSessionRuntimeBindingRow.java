package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record OpenCodeSessionRuntimeBindingRow(
        String externalSessionId, String runtimeGenerationId, String ownershipMode,
        String endpointFingerprint, String internalMcpServer, String createdAt) {
    @AutomapConstructor public OpenCodeSessionRuntimeBindingRow { }
}
