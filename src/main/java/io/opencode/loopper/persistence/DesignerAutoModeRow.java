package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record DesignerAutoModeRow(String designerSessionId, String state, String lastAction,
                                  String errorCode, String errorDetail, String taskId,
                                  String authorizedAt, String disabledAt, String updatedAt,
                                  long version) {
    @AutomapConstructor
    public DesignerAutoModeRow { }
}
