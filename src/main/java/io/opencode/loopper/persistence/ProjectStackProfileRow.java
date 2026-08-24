package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record ProjectStackProfileRow(
        String id, String projectId, String analysisState, String manifestFingerprint,
        String technologyFamiliesJson, String technologiesJson, String evidenceJson,
        int filesScanned, int componentCount, String errorCode, String errorDetail,
        String analyzedAt, String createdAt) {
    @AutomapConstructor public ProjectStackProfileRow { }
}
