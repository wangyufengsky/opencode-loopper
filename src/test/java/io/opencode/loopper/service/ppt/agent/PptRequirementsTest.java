package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.persistence.PptAgentRows.Question;
import io.opencode.loopper.persistence.PptAgentRows.Run;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class PptRequirementsTest {
    private final ObjectMapper json = new ObjectMapper();
    private Run run(String context) {
        return new Run("run", "document", "key", "sha", "需求", "{\"kind\":\"DOCUMENT\"}", 0, "BRIEFING", "{}", "/tmp",
                context, "RUNNING", "", "", null, null, null, "message", null, null, 0, 0, null, null, null, null, "now", "now", 0);
    }
    private Run dialogue() { return run("{\"requirementsProtocol\":\"" + PptRequirements.PROTOCOL + "\"}"); }
    private Question answer(String kind, Boolean confirmed) {
        return new Question("question", "run", "document", "问题", "[]", "ANSWERED", "回答", "key", "sha", "now", 1, kind, confirmed);
    }
    @Test void onlyNewCreatePlanningFreezesTheDialogueProtocol() {
        var context = json.createObjectNode();
        PptRequirements.freeze(context, new PptAgentWorkflowGate.Authorization("generation", 0, "PLANNING", "CREATE"));
        assertThat(context.path("requirementsProtocol").asText()).isEqualTo(PptRequirements.PROTOCOL);
        for (var authorization : List.of(new PptAgentWorkflowGate.Authorization("generation", 0, "PRODUCING", "CREATE"),
                new PptAgentWorkflowGate.Authorization("generation", 0, "PRODUCING", "REVISE"))) {
            var other = json.createObjectNode(); PptRequirements.freeze(other, authorization);
            assertThat(other.has("requirementsProtocol")).isFalse();
        }
        PptRequirements.requireConfirmed(run("{}"), List.of(), json);
        PptRequirements.requireConfirmed(run("{\"generationAuthorization\":{\"mode\":\"CREATE\",\"step\":\"PLANNING\"}}"), List.of(), json);
        assertThatThrownBy(() -> PptRequirements.requireConfirmed(dialogue(), List.of(), json)).hasMessageContaining("尚未完成需求确认");
    }
    @Test void aSummaryCannotReplaceTheInitialDialogueAndPlainAffirmationIsNotConsent() {
        var args = json.createObjectNode().put("kind", PptRequirements.CONFIRMATION);
        assertThatThrownBy(() -> PptRequirements.questionKind(args, dialogue(), List.of(), json)).hasMessageContaining("先围绕内容重点");
        var clarification = answer(PptRequirements.CLARIFICATION, null);
        assertThat(PptRequirements.questionKind(args, dialogue(), List.of(clarification), json)).isEqualTo(PptRequirements.CONFIRMATION);
        assertThat(PptRequirements.replyDecision(answer(PptRequirements.CONFIRMATION, null), null)).isFalse();
        assertThat(PptRequirements.replyDecision(answer(PptRequirements.CONFIRMATION, null), true)).isTrue();
        assertThatThrownBy(() -> PptRequirements.replyDecision(clarification, true)).hasMessageContaining("普通问题不能确认");
    }
    @Test void laterRequirementChangesInvalidateTheEarlierConfirmation() {
        var clarification = answer(PptRequirements.CLARIFICATION, null);
        var confirmation = answer(PptRequirements.CONFIRMATION, true);
        PptRequirements.requireConfirmed(dialogue(), List.of(clarification, confirmation), json);
        assertThatThrownBy(() -> PptRequirements.requireConfirmed(dialogue(), List.of(clarification, confirmation, clarification), json))
                .hasMessageContaining("尚未完成需求确认");
        assertThatThrownBy(() -> PptRequirements.requireConfirmed(dialogue(), List.of(clarification, answer(PptRequirements.CONFIRMATION, false)), json))
                .hasMessageContaining("尚未完成需求确认");
        PptRequirements.validateWrite("ppt_read_source", dialogue(), List.of(), json);
        assertThatThrownBy(() -> PptRequirements.validateWrite("ppt_apply_operations", dialogue(), List.of(), json))
                .hasMessageContaining("尚未完成需求确认");
    }
    @Test void recoveryReusesKnownAnswersButRequiresAFreshExplicitSummaryAcceptance() {
        var resumed = run("{\"requirementsProtocol\":\"DIALOGUE_CONFIRMATION_V1\",\"generationAnswers\":[{\"question\":\"重点？\",\"answer\":\"成果\"}]}");
        var args = json.createObjectNode().put("kind", PptRequirements.CONFIRMATION);
        assertThat(PptRequirements.questionKind(args, resumed, List.of(), json)).isEqualTo(PptRequirements.CONFIRMATION);
        assertThatThrownBy(() -> PptRequirements.requireConfirmed(resumed, List.of(), json)).hasMessageContaining("尚未完成需求确认");
        PptRequirements.requireConfirmed(resumed, List.of(answer(PptRequirements.CONFIRMATION, true)), json);
    }
}
