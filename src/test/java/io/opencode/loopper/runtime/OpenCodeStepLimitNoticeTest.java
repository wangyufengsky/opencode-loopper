package io.opencode.loopper.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.domain.SessionFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpenCodeStepLimitNoticeTest {
    private static final String NOTICE = "CRITICAL - MAXIMUM STEPS REACHED\n"
            + "The maximum number of steps allowed for this task has been reached. "
            + "Tools are disabled until next user input. Respond with text only.";

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "## ", "**", "## **"})
    void recognizesPlainAndMarkdownControlNotices(String prefix) {
        assertThatThrownBy(() -> OpenCodeStepLimitNotice.requireBusinessOutput(prefix + NOTICE))
                .isInstanceOf(SessionFailure.class).hasMessageContaining("步数上限控制提示");
    }

    @Test
    void quotedReferenceMaterialAndDescriptionsAreStillBusinessOutput() {
        for (String value : new String[] {"# 设计说明\n复现用例：\n" + NOTICE, "> " + NOTICE,
                "```text\n" + NOTICE + "\n```", "CRITICAL - MAXIMUM STEPS REACHED 的检测方案", ""}) {
            assertThat(OpenCodeStepLimitNotice.requireBusinessOutput(value)).isEqualTo(value);
        }
        assertThat(OpenCodeStepLimitNotice.requireBusinessOutput(null)).isNull();
    }
}
