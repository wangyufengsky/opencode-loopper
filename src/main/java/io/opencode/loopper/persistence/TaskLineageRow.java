package io.opencode.loopper.persistence;

public record TaskLineageRow(String childTaskId, String parentTaskId, String recoveryMode,
                             String parentStageId, String workspaceFingerprint, String createdAt,
                             String designSourceTaskId, String designSourceLoopDraftId,
                             String designSourceDesignerSessionId) { }
