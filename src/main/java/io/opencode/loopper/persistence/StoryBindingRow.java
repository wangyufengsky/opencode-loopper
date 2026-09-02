package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record StoryBindingRow(String id, String systemCode, String storyCode,
                              int nextSessionOrdinal, String createdAt) {
    @AutomapConstructor public StoryBindingRow { }
}
