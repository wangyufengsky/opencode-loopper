package io.opencode.loopper.persistence;

import org.apache.ibatis.annotations.AutomapConstructor;

public record StoryAccountingCallRow(String id, String accountingSessionId, String phase,
                                     String messageId, String operation, String argumentsText,
                                     String state, String pluginRunId, String resultText, String errorCode,
                                     String errorDetail, boolean notificationEmitted,
                                     String startedAt, String finishedAt) {
    @AutomapConstructor public StoryAccountingCallRow { }
}
