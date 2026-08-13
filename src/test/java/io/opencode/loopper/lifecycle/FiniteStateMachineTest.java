package io.opencode.loopper.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opencode.loopper.domain.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;
import org.junit.jupiter.api.Test;

class FiniteStateMachineTest {
    @Test
    void resolvesLegalTransitionAndRejectsIllegalTransition() {
        LifecycleRegistry registry = new LifecycleRegistry();

        var resolved = registry.resolve(LifecycleMachineType.TASK, "task-1",
                TaskState.RUNNING.name(), TaskState.VERIFYING.name(), null);

        assertThat(resolved.event()).isEqualTo(LifecycleEvent.BEGIN_VERIFICATION);
        assertThatThrownBy(() -> registry.resolve(LifecycleMachineType.TASK, "task-1",
                TaskState.READY.name(), TaskState.SUCCEEDED.name(), null))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void explicitEventDisambiguatesRecoveryFromOrdinaryStageAdvance() {
        LifecycleRegistry registry = new LifecycleRegistry();

        assertThat(registry.resolve(LifecycleMachineType.TASK, "task-1",
                TaskState.VERIFYING.name(), TaskState.RUNNING.name(), null).event())
                .isEqualTo(LifecycleEvent.ADVANCE_STAGE);
        assertThat(registry.resolve(LifecycleMachineType.TASK, "task-1",
                TaskState.VERIFYING.name(), TaskState.RUNNING.name(), LifecycleEvent.RECOVER).event())
                .isEqualTo(LifecycleEvent.RECOVER);
    }

    @Test
    void activeStageCanEnterFailedStateAtTheTaskFatalBoundary() {
        LifecycleRegistry registry = new LifecycleRegistry();

        assertThat(registry.resolve(LifecycleMachineType.STAGE, "stage-1",
                StageState.RUNNING.name(), StageState.FAILED.name(), null).event())
                .isEqualTo(LifecycleEvent.FAIL);
        assertThat(registry.resolve(LifecycleMachineType.STAGE, "stage-1",
                StageState.PAUSED.name(), StageState.FAILED.name(), null).event())
                .isEqualTo(LifecycleEvent.FAIL);
    }

    @Test
    void validatingDecompositionCanFailWhenItsJsonRepairBudgetIsExhausted() {
        LifecycleRegistry registry = new LifecycleRegistry();

        assertThat(registry.resolve(LifecycleMachineType.TASK_DECOMPOSITION, "decomposition-1",
                TaskDecompositionState.VALIDATING.name(), TaskDecompositionState.SESSION_ERROR.name(), null).event())
                .isEqualTo(LifecycleEvent.SESSION_FAIL);
    }

    @Test
    void duplicateStateAndEventDefinitionFailsFast() {
        var builder = FiniteStateMachine.builder(LifecycleMachineType.STAGE, StageState.class, LifecycleEvent.class)
                .transition(StageState.PENDING, LifecycleEvent.START, StageState.RUNNING);

        assertThatThrownBy(() -> builder.transition(StageState.PENDING, LifecycleEvent.START, StageState.PAUSED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate transition");
    }

    @Test
    void validatesEveryLegalEdgeAndRejectsEveryUndefinedStateEventPair() {
        LifecycleRegistry registry = new LifecycleRegistry();
        Map<String, LifecycleRegistry.DefinedTransition> definitions = registry.definitions().stream()
                .collect(Collectors.toMap(FiniteStateMachineTest::key, Function.identity()));

        for (LifecycleMachineType type : LifecycleMachineType.values()) {
            for (String state : registry.states(type)) {
                for (LifecycleEvent event : LifecycleEvent.values()) {
                    LifecycleRegistry.DefinedTransition definition = definitions.get(key(type, state, event));
                    if (definition == null) {
                        assertThatThrownBy(() -> registry.resolve(type, "entity", state, state, event))
                                .as(type + " " + state + " " + event)
                                .isInstanceOf(InvalidStateTransitionException.class);
                    } else {
                        assertThat(registry.resolve(type, "entity", state, definition.toState(), event).toState())
                                .isEqualTo(definition.toState());
                    }
                }
            }
        }
    }

    @Test
    void onlyExplicitlyDeclaredBusinessSelfTransitionsExist() {
        LifecycleRegistry registry = new LifecycleRegistry();
        Set<String> selfTransitions = registry.definitions().stream()
                .filter(edge -> edge.fromState().equals(edge.toState()))
                .map(FiniteStateMachineTest::key)
                .collect(Collectors.toSet());

        assertThat(selfTransitions).containsExactlyInAnyOrder(
                key(LifecycleMachineType.TASK, "RUNNING", LifecycleEvent.RECOVER),
                key(LifecycleMachineType.DESIGNER_SESSION, "PENDING_HANDOFF", LifecycleEvent.DEFER),
                key(LifecycleMachineType.WORKSPACE_LEASE, "HELD", LifecycleEvent.TRANSFER));
    }

    @Test
    void mergedPublicationHasNoOutgoingTransition() {
        LifecycleRegistry registry = new LifecycleRegistry();

        assertThat(registry.definitions()).noneMatch(edge -> edge.machineType() == LifecycleMachineType.TASK_PUBLICATION
                && TaskPublicationState.MERGED.name().equals(edge.fromState()));
        assertThatThrownBy(() -> registry.resolve(LifecycleMachineType.TASK_PUBLICATION, "task-1",
                TaskPublicationState.MERGED.name(), TaskPublicationState.PUSHED.name(), LifecycleEvent.RECORD_PUSH))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void everyDescribedDomainEnumValueHasTrimmedChineseDescription() throws Exception {
        Path domainDirectory = Path.of(DescribedEnum.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("io/opencode/loopper/domain");
        assertThat(domainDirectory).isDirectory();
        List<? extends Class<?>> enumTypes;
        try (var files = Files.list(domainDirectory)) {
            enumTypes = files.filter(path -> path.getFileName().toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().contains("$"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.class$", ""))
                    .map(name -> load("io.opencode.loopper.domain." + name))
                    .filter(Class::isEnum)
                    .filter(DescribedEnum.class::isAssignableFrom)
                    .toList();
        }
        assertThat(enumTypes).isNotEmpty();
        for (Class<?> type : enumTypes) {
            for (Object constant : type.getEnumConstants()) {
                DescribedEnum described = (DescribedEnum) constant;
                assertThat(described.description()).as(type.getSimpleName() + "." + constant)
                        .isNotBlank().isEqualTo(described.description().trim())
                        .matches(".*[\\p{IsHan}].*");
            }
        }
    }

    private static Class<?> load(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException failure) { throw new IllegalStateException(failure); }
    }

    private static String key(LifecycleRegistry.DefinedTransition edge) {
        return key(edge.machineType(), edge.fromState(), edge.event());
    }

    private static String key(LifecycleMachineType type, String state, LifecycleEvent event) {
        return type + "|" + state + "|" + event;
    }
}
