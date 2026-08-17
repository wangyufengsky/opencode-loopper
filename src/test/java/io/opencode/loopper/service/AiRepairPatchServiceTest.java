package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.stream.IntStream;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AiRepairPatchServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AiRepairPatchService service = new AiRepairPatchService(json, new AiOutputExtractor(json));

    @Test
    void appliesBoundedSemanticPatch() throws Exception {
        AiRepairPatchService.Result result = service.apply(
                "{\"stages\":[{\"evidence\":[{\"command\":[\"mvn\",\"test\"]}]}]}",
                "{\"patches\":[{\"op\":\"replace\",\"path\":\"/stages/0/evidence/0/command\",\"value\":[\"mvn\",\"-Dtest=EventBusTest\",\"test\"]}]}",
                Pattern.compile("(?s)(.*)"), "PATCH", Set.of("stages"));

        assertThat(json.readTree(result.json()).at("/stages/0/evidence/0/command/1").asText())
                .isEqualTo("-Dtest=EventBusTest");
    }

    @Test
    void rejectsDerivedOrExcessivePatchSpace() {
        assertThatThrownBy(() -> service.apply("{\"stages\":[]}",
                "{\"patches\":[{\"op\":\"add\",\"path\":\"/criterionIds\",\"value\":[]}]}",
                null, "PATCH", Set.of("stages")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("outside model-owned");

        String excessive = "{\"patches\":[" + IntStream.range(0, 17)
                .mapToObj(index -> "{\"op\":\"add\",\"path\":\"/stages/-\",\"value\":{}}")
                .collect(java.util.stream.Collectors.joining(",")) + "]}";
        assertThatThrownBy(() -> service.apply("{\"stages\":[]}", excessive,
                null, "PATCH", Set.of("stages")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1-16");

        assertThatThrownBy(() -> service.apply("{\"stages\":[]}",
                "{\"patches\":[{\"op\":\"copy\",\"path\":\"/stages/0\",\"value\":{}}]}",
                null, "PATCH", Set.of("stages")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only add, replace, and remove");
    }
}
