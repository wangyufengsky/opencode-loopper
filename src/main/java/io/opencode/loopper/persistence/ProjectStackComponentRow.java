package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record ProjectStackComponentRow(
        String profileId, String componentKey, String relativeRoot,
        String technologyFamiliesJson, String technologiesJson, String buildToolsJson,
        String testFrameworksJson, String manifestSourcesJson, String evidenceJson) {
    @AutomapConstructor public ProjectStackComponentRow { }
}
