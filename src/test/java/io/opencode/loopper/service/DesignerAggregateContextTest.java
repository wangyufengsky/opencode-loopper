package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.service.DesignerSemanticContracts.GlobalConstraint;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DesignerAggregateContextTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void repeatedAggregationKeepsOneCanonicalConstraintSection() throws Exception {
        String constraints = json.writeValueAsString(List.of(
                new GlobalConstraint("不得新增第三方依赖", List.of("RQ-1")),
                new GlobalConstraint("保持成功路径", List.of("RQ-2", "RQ-3"))));

        String once = DesignerAggregateContext.merge(json, "原始上下文", constraints);
        String twice = DesignerAggregateContext.merge(json, once, constraints);

        assertThat(twice).isEqualTo(once);
        assertThat(count(twice, "全局约束（来源可追踪）：")).isOne();
    }

    @Test
    void legacyDuplicateSectionsAreCompactedWithoutLosingOriginalContext() throws Exception {
        String constraints = json.writeValueAsString(List.of(
                new GlobalConstraint("不得访问网络", List.of("RQ-4"))));
        String once = DesignerAggregateContext.merge(json, "原始上下文", constraints);

        String compacted = DesignerAggregateContext.merge(json, once + "\n\n" + once.substring(once.indexOf("全局约束")),
                constraints);

        assertThat(compacted).isEqualTo(once);
    }

    private int count(String source, String value) {
        return source.split(java.util.regex.Pattern.quote(value), -1).length - 1;
    }
}
