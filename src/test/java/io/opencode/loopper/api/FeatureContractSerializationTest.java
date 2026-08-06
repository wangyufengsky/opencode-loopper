package io.opencode.loopper.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.domain.InteractionAction;
import io.opencode.loopper.domain.InteractionKind;
import io.opencode.loopper.domain.InteractionState;
import io.opencode.loopper.domain.AutomationApprovalMode;
import io.opencode.loopper.domain.AutomationTriggerType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FeatureContractSerializationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void preservesDiscriminatorsVersionsAndUnknownUsage() throws Exception {
        var interaction = new FeatureContracts.InteractionDto(
                "i-1", InteractionKind.PERMISSION, InteractionState.PENDING,
                "task-1", null, "session-1", "permission-1",
                json.readTree("{\"permission\":\"bash\",\"hardDenied\":false}"),
                3, null, "2026-08-05T00:00:00Z", "2026-08-05T00:00:00Z", null);
        var usage = new FeatureContracts.UsageSummaryDto(
                "task-1", null, null, null, null, null, false, 1, 250, 2);

        String interactionJson = json.writeValueAsString(interaction);
        String usageJson = json.writeValueAsString(usage);

        assertThat(interactionJson).contains("\"kind\":\"PERMISSION\"")
                .contains("\"state\":\"PENDING\"")
                .contains("\"version\":3");
        assertThat(usageJson).contains("\"inputTokens\":null")
                .contains("\"costAmount\":null")
                .contains("\"reliable\":false")
                .doesNotContain("\"totalTokens\":0");
        assertThat(InteractionAction.SESSION.allowedFor(InteractionKind.PERMISSION)).isTrue();
        assertThat(InteractionAction.SESSION.allowedFor(InteractionKind.QUESTION)).isFalse();
    }

    @Test
    void serializesAutomationTriggerUsingTheFrontendWireShape() throws Exception {
        var rule = new FeatureContracts.AutomationRuleDto(
                "rule-1", "Nightly", "project-1", "version-3",
                AutomationTriggerType.CRON, Map.of("expression", "0 2 * * *", "timezone", "Asia/Shanghai"),
                "DISABLED", AutomationApprovalMode.REVIEW_REQUIRED, "now", 4);

        String wire = json.writeValueAsString(rule);
        var restored = json.readValue(wire, FeatureContracts.AutomationRuleDto.class);
        var tree = json.readTree(wire);

        assertThat(wire).contains("\"triggerType\":\"CRON\"")
                .doesNotContain("\"trigger\":");
        assertThat(tree.path("triggerConfig").path("expression").asText()).isEqualTo("0 2 * * *");
        assertThat(tree.path("triggerConfig").path("timezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(restored).isEqualTo(rule);
    }

    @Test
    void webhookMutationKeepsTheOneTimeTokenOutsideThePersistedRuleShape() throws Exception {
        var rule = new FeatureContracts.AutomationRuleDto(
                "rule-webhook", "Webhook", "project-1", "version-3",
                AutomationTriggerType.WEBHOOK, Map.of(), "DISABLED",
                AutomationApprovalMode.REVIEW_REQUIRED, "now", 0);
        var mutation = new FeatureContracts.AutomationRuleMutationDto(
                rule, "one-time-secret", "/api/automations/webhooks/rule-webhook/one-time-secret");

        var wire = json.readTree(json.writeValueAsString(mutation));

        assertThat(wire.path("rule").path("triggerConfig").isEmpty()).isTrue();
        assertThat(wire.path("webhookToken").asText()).isEqualTo("one-time-secret");
        assertThat(json.writeValueAsString(rule)).doesNotContain("one-time-secret");
    }
}
