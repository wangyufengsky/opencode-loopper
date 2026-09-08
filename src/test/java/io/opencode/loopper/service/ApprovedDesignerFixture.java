package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class ApprovedDesignerFixture {
    private ApprovedDesignerFixture() { }
    /** Attachment/Judge fixtures start at the approved gate; they must not bypass unfinished design. */
    static void prepare(LoopperMapper mapper, JdbcTemplate jdbc, DesignerSessionRow designer, LoopDraftRow draft) {
        String now = Instant.now().toString();
        String requirementId = UUID.randomUUID().toString();
        String decompositionId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        mapper.insertDesignerMessage(new DesignerMessageRow(messageId, designer.id(), mapper.nextDesignerMessageOrdinal(designer.id()),
                "USER", "Approved fixture requirement", "SAVED", now, "USER", null, null));
        mapper.insertDesignRequirementRevision(new io.opencode.loopper.persistence.DesignRequirementRevisionRow(
                requirementId, designer.id(), 1, messageId, "Approved attachment fixture", "[]", draft.version(),
                "CONFIRMED", 0, 24, now, now, 0));
        mapper.insertTaskDecomposition(new io.opencode.loopper.persistence.TaskDecompositionRow(
                decompositionId, designer.id(), requirementId, "COMPLETED", "PLAN", draft.goal(), "[]", "{}",
                null, null, 0, 0, draft.version(), null, null, now, now, 0, "COMPLETED", "{}", 0));
        mapper.insertDesignWorkPackage(new io.opencode.loopper.persistence.DesignWorkPackageRow(
                UUID.randomUUID().toString(), designer.id(), requirementId, decompositionId, "WP-1", 1,
                "Attachment fixture", "Check README", "[]", "[]", "[]", "[]", "[]", "[]", "APPROVED",
                null, null, messageId, 1, 0, 0, null, null, null, null, 1, 0, null, now, now, now, 0));
        var json = new tools.jackson.databind.ObjectMapper();
        var tree = json.readTree(draft.specJson());
        ((tools.jackson.databind.node.ObjectNode) tree.path("stages").get(0)).put("workPackageId", "WP-1");
        mapper.updateDraftContent(new LoopDraftRow(draft.id(), draft.projectId(), draft.goal(), json.writeValueAsString(tree),
                draft.status(), draft.createdAt(), draft.updatedAt(), draft.version()));
        jdbc.update("UPDATE designer_session SET state='REVIEWING',workflow_phase='FINAL_REVIEW',current_requirement_revision=1 WHERE id=?",
                designer.id());
    }

}
