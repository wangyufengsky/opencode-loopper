package io.opencode.loopper.service.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.opencode.loopper.workflow.WorkflowFixtures.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.workflow.*;
import io.opencode.loopper.runtime.DurableCommandProtocol.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorkflowEncodingTest {
    private final WorkflowEncoding encoding = new WorkflowEncoding(new ObjectMapper());
    @Test void historicalCommandJsonKeepsItsFrozenBytesAndHashWithoutNewEmptyFields() {
        String legacy = "{\"argv\":[\"java\",\"-version\"],\"directory\":\"" + java.nio.file.Path.of(".").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
                + "\",\"id\":\"00000000-0000-0000-0000-000000000001\",\"timeoutSeconds\":10}";
        var request = encoding.decode(legacy, Request.class);
        assertThat(request.preparations()).isEmpty(); assertThat(encoding.encode(request)).isEqualTo(legacy);
        String oldResult = "{\"cancelled\":false,\"children\":[],\"error\":\"\",\"exitCode\":0,\"launched\":true,\"output\":\"okay\",\"outputTruncated\":false,\"requestSha256\":\"abc\",\"stopConfirmed\":true,\"timedOut\":false,\"worker\":{\"pid\":42,\"startedAt\":\"2026-01-01T00:00:00Z\"}}";
        var result = encoding.decode(oldResult, Result.class);
        assertThat(result.preparations()).isEmpty(); assertThat(encoding.encode(result)).isEqualTo(oldResult);
        var v2 = new Request(request.id(), request.directory(), request.argv(), 10, List.of(new Preparation("INSTALL", request.directory(), List.of("npm", "ci"))));
        assertThat(encoding.decode(encoding.encode(v2), Request.class)).isEqualTo(v2);
        assertThat(WorkflowEncoding.hash(encoding.encode(v2))).isNotEqualTo(WorkflowEncoding.hash(legacy));
        var pipeline = new Result(result.requestSha256(), result.worker(), 0, true, false, false, false, true, "okay", "", List.of(), List.of(result));
        assertThat(encoding.decode(encoding.encode(pipeline), Result.class)).isEqualTo(pipeline);
    }
    @Test void requestIdentityDoesNotDependOnMapIterationOrderOrLayoutMapOrder() {
        var first = new LinkedHashMap<String, Object>(); first.put("a", "one"); first.put("b", Map.of("y", 2, "x", 1));
        var second = new LinkedHashMap<String, Object>(); second.put("b", Map.of("x", 1, "y", 2)); second.put("a", "one");
        assertThat(encoding.digest("SAVE", "owner", first)).isEqualTo(encoding.digest("SAVE", "owner", second));
        assertThat(encoding.digest("SAVE", "another", second)).isNotEqualTo(encoding.digest("SAVE", "owner", first));
        var snapshot = encoding.definition(chain());
        assertThat(encoding.read(snapshot.body(), snapshot.sha256())).isEqualTo(chain());
        assertThatThrownBy(() -> encoding.read(snapshot.body() + " ", snapshot.sha256())).isInstanceOf(ConflictException.class);
    }
    @Test void layoutCannotSmuggleNewNodeIdsOrNonfiniteCoordinatesIntoBusinessState() {
        assertThatThrownBy(() -> encoding.layout(new CanvasLayout(Map.of("not-present", new CanvasLayout.Point(0, 0)), 0, 0, 1), single()))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> encoding.layout(new CanvasLayout(Map.of(), Double.NaN, 0, 1), single()))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> encoding.layout(new CanvasLayout(Map.of(), 0, 0, 10), single()))
                .isInstanceOf(BadRequestException.class);
        var old = encoding.layout(new CanvasLayout(Map.of("second", new CanvasLayout.Point(5, 8)), 0, 0, 1), chain());
        assertThat(encoding.readLayout(old, single()).positions()).isEmpty();
    }
}
