package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record StoryAccountingSessionRow(String id, String bindingId, String designerSessionId,
                                        String taskId, String externalSessionId,
                                        String runtimeGenerationId, String worktreePath, String role, int ordinal,
                                        String bindOperation, boolean ownerObserved, String state, String pluginRunId,
                                        String createdAt, String updatedAt) {
    @AutomapConstructor public StoryAccountingSessionRow { }
}
