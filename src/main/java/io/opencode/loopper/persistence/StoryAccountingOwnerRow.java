package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record StoryAccountingOwnerRow(String bindingId, String systemCode, String storyCode,
                                      String designerSessionId, String taskId, String role,
                                      boolean reusable) {
    @AutomapConstructor public StoryAccountingOwnerRow { }
}
