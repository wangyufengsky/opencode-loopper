package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.TestPolicy;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PackageDesignLunaSessionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void offlineSessionUsesProductionRepairProgressAndAcceptedWriter() {
        var session = session();
        var rejected = session.submit(candidate().replace("\"SC-1\",\"DEL-1\"", "\"SC-1\",\"MISSING\""));
        assertThat(rejected.responseJson()).contains("PACKAGE_REPAIR_V1", "repairProgress", "FIX_AND_RESUBMIT");
        var first = json.readTree(rejected.responseJson());
        assertThat(first.path("repairProgress").path("issues").size()).isPositive();
        var accepted = session.submit(candidate());
        assertThat(accepted.responseJson()).contains("STOP_ACCEPTED", "ACCEPTED");
        var second = json.readTree(accepted.responseJson());
        assertThat(second.path("repairProgress").path("resolved").size()).isPositive();
        assertThat(second.path("submissionRevision").asInt()).isEqualTo(2);
        assertThat(session.accepted().compiledResultJson()).contains("COMPILED");
    }

    @Test void repeatedRejectedObjectStopsBeforeFourAccordingToProductionPolicy() {
        var session = session();
        String invalid = candidate().replace("\"SC-1\",\"DEL-1\"", "\"SC-1\",\"MISSING\"");
        session.submit(invalid);
        var response = json.readTree(session.submit(invalid).responseJson());
        assertThat(response.path("outcome").asText()).isEqualTo("WAITING_INPUT");
        assertThat(response.path("stopReason").asText()).isEqualTo("REPEATED_CANDIDATE");
        assertThat(response.path("remainingAttempts").asInt()).isEqualTo(2);
    }

    private PackageDesignLunaSession session() {
        var row = mock(DesignWorkPackageRow.class);
        when(row.id()).thenReturn("package-row");
        when(row.packageId()).thenReturn("WP-1");
        var input = new PackageDesignCompilation.Input(row, "新增事件分发安全分支",
                new WorkPackageRoleService.View("software-java", "2026-08-dynamic-v7", ExecutionStrategy.OPEN_CODE_IMPLEMENTATION,
                        TestPolicy.REQUIRED, List.of("java")), List.of("src/test/java/example/EventBusTest.java"),
                List.of(), List.of("EventBusTest"), 6, true);
        return new PackageDesignLunaSession(json, input);
    }

    private String candidate() {
        return """
                {"contractVersion":"PACKAGE_DESIGN_V1","outcome":"READY",
                "requirements":[{"key":"REQ-1","statement":"未注册事件安全忽略"}],
                "scenarios":[{"key":"SC-1","title":"未注册事件被安全忽略","precondition":"事件未注册","action":"发布该事件",
                "observableResult":"发布正常返回且没有处理器被调用","invariant":"既有事件行为不变","requirementRefs":["REQ-1"]}],
                "deliverables":[{"key":"DEL-1","kind":"DELIVERABLE","target":"src/test/java/example/EventBusTest.java",
                "description":"新增 EventBusTest 聚焦测试验证未注册事件分支","requirementRefs":["REQ-1"]}],"reviews":[],
                "stages":[{"key":"STAGE-1","title":"事件分发测试","objective":"验证事件安全分支","includes":["SC-1","DEL-1"],"dependencies":[]}],"gapCodes":[]}
                """;
    }
}
