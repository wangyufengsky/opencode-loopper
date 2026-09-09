package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CommitMessagePolicyTest {
    @Test void normalizesTheScreenshotSubjectWithoutLosingItsLines() {
        assertThat(CommitMessagePolicy.requireMessage("#2026_目标：创建arch网页\r\n使用场景：旧代码梳理\n验收标准：打开网页搜索"))
                .isEqualTo("#2026_目标：创建arch网页 使用场景：旧代码梳理 验收标准：打开网页搜索");
        assertThat(new CommitMessagePromptFactory(null, null).normalizeSubject("目标：创建arch网页\n补充搜索功能"))
                .isEqualTo("目标：创建arch网页 补充搜索功能");
    }

    @Test void reportsTheFailingFieldAndNeverTruncatesUserInput() {
        assertThatThrownBy(() -> CommitMessagePolicy.requireMessage("#12_说明")).hasMessageContaining("4位数字");
        assertThatThrownBy(() -> CommitMessagePolicy.requireMessage("#2026_ \n")).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> CommitMessagePolicy.requireMessage("#2026_" + "字".repeat(121))).hasMessageContaining("超过120");
        assertThatThrownBy(() -> CommitMessagePolicy.requireMessage("#2026_说明\u0000结束")).hasMessageContaining("控制字符");
        String unicode = "#2026_" + "🧪".repeat(120);
        assertThat(CommitMessagePolicy.requireMessage(unicode)).isEqualTo(unicode);
    }
}
