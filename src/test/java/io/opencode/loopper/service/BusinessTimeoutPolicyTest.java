package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.persistence.*;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessTimeoutPolicyTest {
    private final ObjectMapper json=new ObjectMapper();
    private String document(Boolean enabled) {
        var node=(tools.jackson.databind.node.ObjectNode)json.valueToTree(new DocumentTemplateService.Contract("1","fake/model",7200,1800,12,3,3,false,"STATIC_ONLY"));
        if(enabled==null)node.remove("timeoutEnabled");else node.put("timeoutEnabled",enabled);
        return json.writeValueAsString(node);
    }
    @Test void explicitDisabledAndHistoricalAbsentContractsRemainDistinctAfterRoundTrip() {
        var old=json.readValue("{\"attemptTimeoutSeconds\":1800}",LoopSpec.Limits.class);
        assertThat(old.timeoutsEnabled()).isTrue(); assertThat(old.timeoutEnabled()).isNull();
        var off=json.readValue("{\"timeoutEnabled\":false,\"attemptTimeoutSeconds\":1800}",LoopSpec.Limits.class);
        assertThat(json.readValue(json.writeValueAsString(off),LoopSpec.Limits.class).timeoutsEnabled()).isFalse();
        assertThat(json.readValue(document(null),DocumentTemplateService.Contract.class).timeoutEnabled()).isTrue();
        assertThat(json.readValue(document(false),DocumentTemplateService.Contract.class).timeoutEnabled()).isFalse();
    }
    @Test void oldSettingsWithoutSwitchDefaultToDisabled() {
        var value=json.readValue("{\"maxStageAttempts\":3,\"maxTaskAttempts\":12,\"sessionErrorLimit\":3,\"maxDurationMinutes\":120,\"attemptTimeoutMinutes\":30,\"verifierTimeoutMinutes\":10,\"designerTimeoutMinutes\":30}",SettingsService.LimitSettings.class);
        assertThat(value.timeoutEnabled()).isFalse();
    }
    @Test void designPolicyRetainsSavedValueWhenDefaultsChangeAndDocumentPolicyOverridesIt() {
        var mapper=mock(LoopperMapper.class);
        when(mapper.designerTimeout("off")).thenReturn(Optional.of(new DesignerTimeoutMapper.Policy(false,1800)));
        when(mapper.designerTimeout("on")).thenReturn(Optional.of(new DesignerTimeoutMapper.Policy(true,7200)));
        assertThat(DesignerTimeoutPolicy.duration(mapper,"off",Duration.ofMinutes(1))).isNull();
        assertThat(DesignerTimeoutPolicy.reviewerDeadline(mapper,"off",java.time.Instant.EPOCH)).isNull();
        assertThat(DesignerTimeoutPolicy.reviewerDeadline(mapper,"on",java.time.Instant.EPOCH)).isEqualTo("1970-01-01T02:00:00Z");
        assertThat(DesignerTimeoutPolicy.reviewerDeadline(mapper,"legacy",java.time.Instant.EPOCH)).isEqualTo("1970-01-01T00:02:00Z");
        assertThat(DesignerTimeoutPolicy.duration(mapper,"on",Duration.ofMinutes(1))).isEqualTo(Duration.ofHours(2));
        assertThat(DesignerTimeoutPolicy.duration(mapper,"legacy",Duration.ofMinutes(30))).isEqualTo(Duration.ofMinutes(30));
        when(mapper.documentDesignerContract("document")).thenReturn(Optional.of(document(false)));
        assertThat(DocumentRequirementContext.attemptTimeout(mapper,"document",Duration.ofMinutes(1))).isNull();
    }
}
