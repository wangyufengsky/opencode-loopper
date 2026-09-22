package io.opencode.loopper.runtime;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PptAgentPolicyTest {
    @Test void actualPptAgentCannotBeReplacedByBuildAndOnlyExactPptToolsAreGranted() {
        var profile = OpenCodeClient.SessionProfile.PPT_AGENT;
        assertThat(OpenCodeAgentPolicy.promptAgent("build", profile, true)).isEqualTo(PptAgentProfile.AGENT);
        assertThat(((Map<?, ?>) OpenCodeAgentPolicy.managedDefinitions().get(PptAgentProfile.AGENT)).get("prompt")).isEqualTo(PptAgentProfile.PROMPT);
        assertThat(OpenCodeAgentPolicy.stepLimit(profile)).isZero();
        var rules = OpenCodePermissionPolicy.rules(profile, List.of("github", "filesystem"), "private_generation");
        assertThat(rules.stream().filter(r -> r.get("action").equals("allow")).map(r -> r.get("permission")))
                .containsExactlyElementsOf(PptAgentProfile.TOOLS.stream().map(name -> "private_generation_" + name).toList());
        assertThat(rules).contains(Map.of("permission", "*", "pattern", "*", "action", "deny"));
        assertThat(OpenCodePermissionPolicy.rules(profile)).noneMatch(rule -> rule.get("action").equals("allow"));
        for (var other : OpenCodeClient.SessionProfile.values()) if (other != profile) {
            assertThat(OpenCodePermissionPolicy.rules(other, List.of(), "private_generation"))
                    .noneMatch(r -> r.get("permission").startsWith("private_generation_ppt_"));
        }
    }
}
