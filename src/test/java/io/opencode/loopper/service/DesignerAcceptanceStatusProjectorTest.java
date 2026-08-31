package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.domain.AcceptanceBindingSource;
import io.opencode.loopper.persistence.DesignAcceptancePlanningRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DesignerAcceptanceStatusProjectorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final DesignerAcceptanceStatusProjector projector =
            new DesignerAcceptanceStatusProjector(json);

    @Test
    void projectsFrozenFactsCapabilitiesAndDiagnostics() throws Exception {
        DesignerAcceptancePlanning.Catalog facts = new DesignerAcceptancePlanning.Catalog(
                DesignerAcceptancePlanning.CONTRACT_VERSION_V7, "WP-1", 1, "a".repeat(64), true,
                List.of(
                        new DesignerAcceptancePlanning.Fact(0,
                                DesignerAcceptancePlanning.FactKind.SCENARIO,
                                "支付成功", "余额充足", "提交支付", "返回成功", "账务一致",
                                null, "DS-L001", "受控设计", "b".repeat(64)),
                        new DesignerAcceptancePlanning.Fact(1,
                                DesignerAcceptancePlanning.FactKind.REVIEW,
                                "风险复核", null, null, null, null,
                                "复核幂等风险", "DS-L002", "受控设计", "c".repeat(64))),
                List.of(), List.of(), List.of("必改路径未守恒"), List.of("事实警告"));
        DesignerAcceptancePlanning.CapabilityCatalog capabilities =
                new DesignerAcceptancePlanning.CapabilityCatalog(
                        DesignerAcceptancePlanning.CONTRACT_VERSION_V7,
                        List.of(new DesignerAcceptancePlanning.Capability(0, "FOCUSED_TEST", "支付聚焦测试",
                                List.of("mvn", "-Dtest=PaymentTest", "test"), List.of(0),
                                List.of("PaymentTest"), true, true, 100)),
                        List.of("能力警告"));
        String diagnostics = """
                {"routingReasons":["UNKNOWN_FACT_REFERENCE:支付成功"],
                 "mutationObligationCount":2,"resolvedMutationObligationCount":1,
                 "unresolvedMutationObligationCount":1,"pathConservation":"BLOCKED",
                 "mutationBindingReasons":["src/main/java/example/Payment.java：尚未归属"]}
                """;

        AcceptancePlanningStatus status = projector.project(row(
                json.writeValueAsString(facts), json.writeValueAsString(capabilities),
                diagnostics, "REQUIRED_MUTATION_PATH_UNASSIGNED"));

        assertThat(status.state()).isEqualTo("BOUND");
        assertThat(status.bindingSource()).isEqualTo(AcceptanceBindingSource.SERVER_STAGE_HINTS.name());
        assertThat(status.routingReasons()).containsExactly("阶段表引用“支付成功”无法对应前文标题");
        assertThat(status.factCount()).isEqualTo(2);
        assertThat(status.scenarioCount()).isEqualTo(2);
        assertThat(status.automatedCount()).isEqualTo(1);
        assertThat(status.judgeCount()).isEqualTo(1);
        assertThat(status.unresolvedCount()).isZero();
        assertThat(status.issues()).containsExactly(
                "事实警告", "必改路径未守恒", "能力警告", "REQUIRED_MUTATION_PATH_UNASSIGNED");
        assertThat(status.mutationObligationCount()).isEqualTo(2);
        assertThat(status.resolvedMutationObligationCount()).isEqualTo(1);
        assertThat(status.unresolvedMutationObligationCount()).isEqualTo(1);
        assertThat(status.pathConservation()).isEqualTo("BLOCKED");
        assertThat(status.mutationBindingReasons())
                .containsExactly("src/main/java/example/Payment.java：尚未归属");
    }

    @Test
    void failsClosedWhenFrozenSnapshotCannotBeRead() {
        AcceptancePlanningStatus status = projector.project(row("{", "{}", null, null));

        assertThat(status.state()).isEqualTo("FAILED");
        assertThat(status.bindingSource()).isEqualTo(AcceptanceBindingSource.LEGACY_UNKNOWN.name());
        assertThat(status.factCount()).isZero();
        assertThat(status.issues()).containsExactly("验收意图快照不可读");
        assertThat(status.pathConservation()).isEqualTo("NOT_EVALUATED");
    }

    private static DesignAcceptancePlanningRow row(
            String factsJson, String capabilitiesJson, String diagnosticsJson, String errorCode) {
        return new DesignAcceptancePlanningRow("compilation-1", "designer-1", "WP-1", 1,
                DesignerAcceptancePlanning.CONTRACT_VERSION_V7, "a".repeat(64), "BOUND",
                AcceptanceBindingSource.SERVER_STAGE_HINTS.name(), factsJson, capabilitiesJson,
                null, diagnosticsJson, errorCode, null,
                "2026-08-31T00:00:00Z", "2026-08-31T00:00:00Z", 0);
    }
}
