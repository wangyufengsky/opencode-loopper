package io.opencode.loopper;

import static org.assertj.core.api.Assertions.assertThat;

import io.opencode.loopper.domain.DescribedEnum;
import io.opencode.loopper.domain.DesignWorkPackageState;
import io.opencode.loopper.domain.DesignerSessionState;
import io.opencode.loopper.domain.LoopDraftStatus;
import io.opencode.loopper.domain.SessionState;
import io.opencode.loopper.domain.StageState;
import io.opencode.loopper.domain.TaskPackageRunState;
import io.opencode.loopper.domain.TaskState;
import io.opencode.loopper.domain.WorkPackageAggregateState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FrontendStateContractTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir"));
    private static final Pattern QUOTED = Pattern.compile("'([A-Z][A-Z0-9_]*)'");

    @Test
    void frontendRuntimeStateSetsExactlyMatchPublicBackendEnums() throws IOException {
        String source = Files.readString(ROOT.resolve("frontend/src/types/states.ts"));
        Map<String, Class<? extends Enum<?>>> contracts = new LinkedHashMap<>();
        contracts.put("TASK_STATUSES", TaskState.class);
        contracts.put("STAGE_STATUSES", StageState.class);
        contracts.put("SESSION_STATUSES", SessionState.class);
        contracts.put("LOOP_DRAFT_STATUSES", LoopDraftStatus.class);
        contracts.put("DESIGNER_SESSION_STATES", DesignerSessionState.class);
        contracts.put("DESIGN_WORK_PACKAGE_STATES", DesignWorkPackageState.class);
        contracts.put("TASK_PACKAGE_RUN_STATES", TaskPackageRunState.class);
        contracts.put("WORK_PACKAGE_AGGREGATE_STATUSES", WorkPackageAggregateState.class);

        contracts.forEach((constant, type) -> assertThat(frontendValues(source, constant))
                .as(constant)
                .containsExactlyInAnyOrderElementsOf(enumValues(type)));
    }

    @Test
    void everyPublicStateHasAChineseDisplayLabelAndBackendDescription() throws IOException {
        String labels = Files.readString(ROOT.resolve("frontend/src/utils/displayLabels.ts"));
        for (Class<? extends Enum<?>> type : Set.of(TaskState.class, StageState.class, SessionState.class,
                LoopDraftStatus.class, DesignerSessionState.class, DesignWorkPackageState.class,
                TaskPackageRunState.class, WorkPackageAggregateState.class)) {
            for (Enum<?> state : type.getEnumConstants()) {
                assertThat(labels).as("frontend label for %s.%s", type.getSimpleName(), state.name())
                        .containsPattern("(?m)\\b" + Pattern.quote(state.name()) + "\\s*:");
                assertThat(((DescribedEnum) state).description())
                        .as("Chinese description for %s.%s", type.getSimpleName(), state.name())
                        .containsPattern("[\\u3400-\\u9fff]");
            }
        }
    }

    private static Set<String> frontendValues(String source, String constant) {
        Matcher block = Pattern.compile("export const " + constant + " = \\[(.*?)] as const", Pattern.DOTALL)
                .matcher(source);
        assertThat(block.find()).as("runtime constant %s", constant).isTrue();
        Matcher values = QUOTED.matcher(block.group(1));
        Set<String> result = new LinkedHashSet<>();
        while (values.find()) result.add(values.group(1));
        return result;
    }

    private static Set<String> enumValues(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
