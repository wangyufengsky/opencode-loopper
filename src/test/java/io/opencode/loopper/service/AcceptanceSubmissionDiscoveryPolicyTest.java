package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class AcceptanceSubmissionDiscoveryPolicyTest {
    @TempDir Path directory;
    @Test void acceptsCurrentDiscoveryAndFrozenLegacyPolicyButRejectsAdditionalAuthority() {
        var client = new FakeOpenCodeClient();
        client.setManagedRuntime("generation", "private-mcp");
        var plan = client.prepareCandidateSessionCreationLocally(directory, "Acceptance", null,
                OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS,
                "0123456789abcdefghijklmnopqrstuvwxyz_ABCD12");
        var codec = new AcceptanceCandidateInternalLaunchPlanCodec(new ObjectMapper());
        assertThat(plan.permissionPolicy()).hasSize(4);
        assertThatCode(() -> codec.validatePreparedPlan("Acceptance", null, plan)).doesNotThrowAnyException();
        var legacy = plan.permissionPolicy().stream()
                .filter(rule -> !rule.permission().endsWith("_describe_submission_contract")).toList();
        assertThatCode(() -> codec.validatePreparedPlan("Acceptance", null, withPolicy(plan, legacy)))
                .doesNotThrowAnyException();
        var unexpected = new ArrayList<>(legacy);
        unexpected.add(new OpenCodeClient.SessionPermissionRule("bash", "*", "allow"));
        assertThatThrownBy(() -> codec.validatePreparedPlan("Acceptance", null, withPolicy(plan, unexpected)))
                .isInstanceOf(ConflictException.class);
    }
    private static OpenCodeClient.SessionCreationPlan withPolicy(OpenCodeClient.SessionCreationPlan plan,
            List<OpenCodeClient.SessionPermissionRule> policy) {
        String digest = OpenCodeClient.permissionPolicyDigest(policy);
        String request = OpenCodeClient.sessionCreationRequestSha256(plan.canonicalDirectory(), plan.exactTitle(),
                plan.runtimeGenerationId(), plan.managed(), plan.internalMcpServer(), plan.endpointFingerprint(),
                plan.model(), plan.profile(), digest, plan.creationCredential());
        return new OpenCodeClient.SessionCreationPlan(plan.canonicalDirectory(), plan.exactTitle(),
                plan.runtimeGenerationId(), plan.managed(), plan.internalMcpServer(), plan.endpointFingerprint(),
                plan.model(), plan.profile(), policy, digest, plan.creationCredential(), request);
    }
}
