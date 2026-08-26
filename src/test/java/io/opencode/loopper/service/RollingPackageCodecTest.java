package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.persistence.PackageFactSnapshotRow;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RollingPackageCodecTest {
    private final RollingPackageCodec codec = new RollingPackageCodec(new ObjectMapper());

    @Test
    void boundsEachNavigationSummaryAndTheAggregateFactPromptByUtf8Bytes() {
        List<PackageFactSnapshotRow> facts = IntStream.rangeClosed(1, 8)
                .mapToObj(index -> fact(index, "下一包导航".repeat(1_500)))
                .toList();

        String context = codec.factContext(facts);

        assertThat(context.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(24 * 1024);
        assertThat(context.split("### 已冻结事实", -1).length - 1).isBetween(1, 6);
        assertThat(codec.boundedSummary("中文摘要".repeat(2_000)).getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(4 * 1024);
        assertThat(context).contains("已证明索引", "已接受合同索引", "AI 导航摘要（非证据）");
    }

    @Test
    void representsMissingNavigationAsExplicitNonEvidenceInsteadOfAnEmptyFact() {
        assertThat(codec.boundedSummary(null)).isEqualTo("仅用于下一包导航，不属于机器证据。");
    }

    private PackageFactSnapshotRow fact(int index, String summary) {
        String suffix = String.valueOf(index);
        return new PackageFactSnapshotRow("fact-" + suffix, "task", "run-" + suffix,
                "checkpoint-" + suffix, "attempt-" + suffix, "input-" + suffix,
                "output-" + suffix, "manifest-" + suffix, "diff-" + suffix,
                "evidence-" + suffix, "{}", "{}", summary, "spec-" + suffix, "now");
    }
}
