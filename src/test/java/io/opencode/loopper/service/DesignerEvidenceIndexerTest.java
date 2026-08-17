package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DesignerEvidenceIndexerTest {
    private final DesignerEvidenceIndexer indexer = new DesignerEvidenceIndexer();

    @Test
    void indexesNonBlankFrozenLinesAndResolvesMultipleExactSources() {
        DesignerEvidenceIndexer.Index index = indexer.index("# 目标\n\n未注册类型静默消费。\n监听异常上抛。\n");

        assertThat(index.promptText()).contains("DS-L001 | # 目标", "DS-L002 | 未注册类型静默消费。");
        assertThat(index.resolve(List.of("DS-L002", "DS-L003")))
                .containsExactly("未注册类型静默消费。", "监听异常上抛。");
    }

    @Test
    void rejectsUnknownSourceWithBoundedCandidates() {
        DesignerEvidenceIndexer.Index index = indexer.index("A\nB");
        assertThatThrownBy(() -> index.resolve(List.of("DS-L999")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("available refs");
    }
}
