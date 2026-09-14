package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.domain.MachineCandidateKind;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DocumentTemplatePermissionsTest {
    @ParameterizedTest
    @EnumSource(value = MachineCandidateKind.class, names = {"DOCUMENT_REQUIREMENTS_V1", "DOCUMENT_REQUIREMENT_REVIEW_V1",
            "REQUIREMENT_CODE_ASSESSMENT_V1", "REQUIREMENT_ASSESSMENT_REVIEW_V1"})
    void frozenRolesOnlyAllowTheirOwnInternalTools(MachineCandidateKind kind) {
        var profile = DocumentTemplateProfiles.profile(kind);
        var rules = OpenCodePermissionPolicy.rules(profile, List.of("untrusted", "internal"), "internal");
        var allowed = rules.stream().filter(rule -> rule.get("action").equals("allow")).map(rule -> rule.get("permission")).toList();
        assertThat(allowed).contains("internal_" + InternalMcpContractCatalog.toolName(kind));
        assertThat(allowed).allMatch(permission -> permission.startsWith("internal_"));
        assertThat(allowed).doesNotContain("read", "glob", "grep", "bash", "edit", "question", "untrusted_*");
        for (MachineCandidateKind other : MachineCandidateKind.values()) {
            if (other != kind) assertThat(allowed).doesNotContain("internal_" + InternalMcpContractCatalog.toolName(other));
        }
        assertThat(OpenCodePermissionPolicy.rules(profile).stream().filter(rule -> rule.get("action").equals("allow"))).isEmpty();
        assertThat(OpenCodeAgentPolicy.stepLimit(profile)).isZero();
    }
}
