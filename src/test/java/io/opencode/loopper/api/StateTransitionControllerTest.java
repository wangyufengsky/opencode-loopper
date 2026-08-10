package io.opencode.loopper.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.StateTransitionEventRow;
import io.opencode.loopper.service.StateTransitionQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class StateTransitionControllerTest {
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final StateTransitionQueryService service = new StateTransitionQueryService(mapper, JsonMapper.builder().build());
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StateTransitionController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void returnsAscendingCursorPageWithoutChineseDescriptions() throws Exception {
        when(mapper.listStateTransitionsForEntity("TASK", "task-1", 0, 2)).thenReturn(List.of(
                event(11, "CREATED", null, "READY"),
                event(12, "START", "READY", "RUNNING")));

        mvc.perform(get("/api/state-transitions")
                        .queryParam("machineType", "TASK").queryParam("entityId", "task-1")
                        .queryParam("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sequence").value(11))
                .andExpect(jsonPath("$.items[0].event").value("CREATED"))
                .andExpect(jsonPath("$.items[0].machineDescription").doesNotExist())
                .andExpect(jsonPath("$.nextAfterSequence").value(11))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void rejectsPartialOrMixedSelectorsAndInvalidPagination() throws Exception {
        mvc.perform(get("/api/state-transitions").queryParam("machineType", "TASK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("STATE_TRANSITION_QUERY_INVALID"));
        mvc.perform(get("/api/state-transitions")
                        .queryParam("machineType", "TASK").queryParam("entityId", "task-1")
                        .queryParam("scopeType", "TASK").queryParam("scopeId", "task-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/state-transitions")
                        .queryParam("scopeType", "TASK").queryParam("scopeId", "task-1")
                        .queryParam("limit", "201"))
                .andExpect(status().isBadRequest());
    }

    private StateTransitionEventRow event(long sequence, String event, String from, String to) {
        return new StateTransitionEventRow(sequence, "event-" + sequence, "TASK", "task-1", "TASK", "task-1",
                event, from, to, null, "{}", "2026-08-10T00:00:00Z");
    }
}
