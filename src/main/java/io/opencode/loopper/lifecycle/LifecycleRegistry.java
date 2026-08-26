package io.opencode.loopper.lifecycle;

import static io.opencode.loopper.domain.LifecycleEvent.*;

import io.opencode.loopper.domain.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Authoritative topology for every persisted business lifecycle. */
@Component
public final class LifecycleRegistry {
    private final Map<LifecycleMachineType, RegisteredMachine<?>> machines = new EnumMap<>(LifecycleMachineType.class);

    public LifecycleRegistry() {
        register(LifecycleMachineType.TASK, TaskState.class, task());
        register(LifecycleMachineType.STAGE, StageState.class, stage());
        register(LifecycleMachineType.ATTEMPT, AttemptState.class, attempt());
        register(LifecycleMachineType.EXECUTION_SESSION, SessionState.class, session());
        register(LifecycleMachineType.JUDGE_RUN, JudgeRunState.class, judge());
        register(LifecycleMachineType.LOOP_DRAFT, LoopDraftStatus.class, draft());
        register(LifecycleMachineType.DESIGNER_SESSION, DesignerSessionState.class, designer());
        register(LifecycleMachineType.DESIGNER_AUTO_MODE, DesignerAutoModeState.class, designerAutoMode());
        register(LifecycleMachineType.LOOPSPEC_COMPILATION, LoopSpecCompilationState.class, compilation());
        register(LifecycleMachineType.DESIGN_REQUIREMENT_REVISION, DesignRequirementRevisionState.class, requirementRevision());
        register(LifecycleMachineType.TASK_DECOMPOSITION, TaskDecompositionState.class, decomposition());
        register(LifecycleMachineType.DESIGN_WORK_PACKAGE, DesignWorkPackageState.class, workPackage());
        register(LifecycleMachineType.PROJECT_CONVENTION, ProjectConventionState.class, convention());
        register(LifecycleMachineType.INTERACTION, InteractionState.class, interaction());
        register(LifecycleMachineType.WORKSPACE_LEASE, WorkspaceLeaseState.class, lease());
        register(LifecycleMachineType.TASK_QUEUE, TaskQueueState.class, queue());
        register(LifecycleMachineType.LOOPSPEC_TEMPLATE, LoopSpecTemplateState.class, template());
        register(LifecycleMachineType.AUTOMATION_RULE, AutomationRuleState.class, rule());
        register(LifecycleMachineType.AUTOMATION_RUN, AutomationRunState.class, automationRun());
        register(LifecycleMachineType.TASK_PUBLICATION, TaskPublicationState.class, taskPublication());
        register(LifecycleMachineType.TASK_EXECUTION_CYCLE, ExecutionCycleState.class, executionCycle());
        register(LifecycleMachineType.WORKSPACE_CHECKPOINT, WorkspaceCheckpointState.class, workspaceCheckpoint());
        register(LifecycleMachineType.TASK_PACKAGE_RUN, TaskPackageRunState.class, packageRun());
        register(LifecycleMachineType.PACKAGE_PLAN_REVISION, PackagePlanRevisionState.class, packagePlanRevision());
        if (machines.size() != LifecycleMachineType.values().length) {
            throw new IllegalStateException("Every lifecycle machine type must be registered");
        }
    }

    public ResolvedTransition resolve(LifecycleMachineType type, String entityId, String from, String to,
                                      LifecycleEvent explicitEvent) {
        RegisteredMachine<?> machine = machines.get(type);
        if (machine == null) throw new IllegalStateException("Lifecycle machine is not registered: " + type);
        return machine.resolve(entityId, from, to, explicitEvent);
    }

    public void requireState(LifecycleMachineType type, String entityId, String state) {
        machines.get(type).requireState(entityId, state);
    }

    List<DefinedTransition> definitions() {
        List<DefinedTransition> definitions = new ArrayList<>();
        machines.forEach((type, machine) -> definitions.addAll(machine.definitions(type)));
        return List.copyOf(definitions);
    }

    List<String> states(LifecycleMachineType type) { return machines.get(type).states(); }

    private <S extends Enum<S> & DescribedEnum> void register(LifecycleMachineType type, Class<S> stateType,
                                                               FiniteStateMachine<S, LifecycleEvent> machine) {
        if (machines.putIfAbsent(type, new RegisteredMachine<>(stateType, machine)) != null) {
            throw new IllegalStateException("Duplicate lifecycle machine: " + type);
        }
    }

    private static FiniteStateMachine<TaskState, LifecycleEvent> task() {
        var b = machine(LifecycleMachineType.TASK, TaskState.class);
        b.transition(TaskState.PENDING_START, REQUEST_START, TaskState.QUEUED)
                .transition(TaskState.QUEUED, PREPARE, TaskState.PREPARING)
                .transition(TaskState.PREPARING, PREPARATION_SUCCEEDED, TaskState.READY)
                .transition(TaskState.READY, START, TaskState.RUNNING)
                .transition(TaskState.RUNNING, BEGIN_VERIFICATION, TaskState.VERIFYING)
                .transition(TaskState.RUNNING, SCHEDULE_RETRY, TaskState.RETRY_WAIT)
                .transition(TaskState.VERIFYING, SCHEDULE_RETRY, TaskState.RETRY_WAIT)
                .transition(TaskState.RETRY_WAIT, RETRY, TaskState.RUNNING)
                .transition(TaskState.VERIFYING, ADVANCE_STAGE, TaskState.RUNNING)
                .transition(TaskState.VERIFYING, BEGIN_PACKAGE_CHECKPOINT, TaskState.PACKAGE_DESIGNING)
                .transition(TaskState.VERIFYING, BEGIN_FINAL_REVIEW, TaskState.JUDGING)
                .transition(TaskState.PACKAGE_DESIGNING, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.PACKAGE_DESIGNING, BEGIN_FINAL_REVIEW, TaskState.JUDGING)
                .transition(TaskState.WAITING_INPUT, BEGIN_PACKAGE_DESIGN, TaskState.PACKAGE_DESIGNING)
                .transition(TaskState.WAITING_INPUT, REQUEST_START, TaskState.QUEUED)
                .transition(TaskState.JUDGING, BEGIN_PACKAGE_DESIGN, TaskState.PACKAGE_DESIGNING)
                .transition(TaskState.JUDGING, APPROVE, TaskState.AWAITING_DECISION)
                .transition(TaskState.JUDGING, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.WAITING_INPUT, RETRY_FINAL_REVIEW, TaskState.JUDGING)
                .transition(TaskState.WAITING_INPUT, RECOVER, TaskState.RUNNING)
                .transition(TaskState.WAITING_INPUT, RETRY_PREPARATION, TaskState.PREPARING)
                .transition(TaskState.SUCCEEDED, REOPEN_FINAL_REVIEW, TaskState.JUDGING)
                .transition(TaskState.RUNNING, PAUSE, TaskState.PAUSED)
                .transition(TaskState.VERIFYING, PAUSE, TaskState.PAUSED)
                .transition(TaskState.RETRY_WAIT, PAUSE, TaskState.PAUSED)
                .transition(TaskState.PAUSED, RESUME, TaskState.RUNNING)
                .transition(TaskState.PAUSED, RESUME_RETRY, TaskState.RETRY_WAIT)
                .transition(TaskState.RUNNING, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.VERIFYING, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.QUEUED, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.PREPARING, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.READY, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.RETRY_WAIT, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.PAUSED, REQUIRE_INPUT, TaskState.WAITING_INPUT)
                .transition(TaskState.RUNNING, RECOVER, TaskState.RUNNING)
                .transition(TaskState.VERIFYING, RECOVER, TaskState.RUNNING)
                .transition(TaskState.RETRY_WAIT, RECOVER, TaskState.RUNNING);
        b.transition(TaskState.AWAITING_DECISION, REQUEST_START, TaskState.QUEUED)
                .transition(TaskState.AWAITING_DECISION, ACCEPT_RESULT, TaskState.COMPLETED)
                .transition(TaskState.AWAITING_DECISION, COMPLETE, TaskState.COMPLETED)
                .transition(TaskState.AWAITING_DECISION, SUPERSEDE, TaskState.SUPERSEDED)
                .transition(TaskState.AWAITING_DECISION, CANCEL, TaskState.CANCELLED);
        for (TaskState state : TaskState.values()) {
            if (!state.terminal() && state != TaskState.AWAITING_DECISION && state != TaskState.STOPPING) {
                b.transition(state, CANCEL, TaskState.STOPPING).transition(state, FAIL, TaskState.AWAITING_DECISION);
            }
        }
        b.transition(TaskState.STOPPING, COMPLETE, TaskState.CANCELLED);
        return b.build();
    }

    private static FiniteStateMachine<TaskPackageRunState, LifecycleEvent> packageRun() {
        var b = machine(LifecycleMachineType.TASK_PACKAGE_RUN, TaskPackageRunState.class);
        b.transition(TaskPackageRunState.PLANNED, BEGIN_PACKAGE_DESIGN, TaskPackageRunState.DESIGNING)
                .transition(TaskPackageRunState.DESIGNING, REQUEST_PACKAGE_REVIEW, TaskPackageRunState.DESIGN_REVIEW)
                .transition(TaskPackageRunState.DESIGN_REVIEW, APPROVE_PACKAGE_DESIGN, TaskPackageRunState.EXECUTION_READY)
                .transition(TaskPackageRunState.EXECUTION_READY, REQUEST_PACKAGE_EXECUTION, TaskPackageRunState.QUEUED)
                .transition(TaskPackageRunState.QUEUED, START, TaskPackageRunState.RUNNING)
                .transition(TaskPackageRunState.RUNNING, BEGIN_VERIFICATION, TaskPackageRunState.VERIFYING)
                .transition(TaskPackageRunState.VERIFYING, BEGIN_PACKAGE_CHECKPOINT, TaskPackageRunState.CHECKPOINTING)
                .transition(TaskPackageRunState.CHECKPOINTING, FREEZE_PACKAGE_FACT, TaskPackageRunState.FACT_FROZEN);
        for (TaskPackageRunState state : TaskPackageRunState.values()) {
            if (!state.terminal() && state != TaskPackageRunState.WAITING_INPUT) {
                b.transition(state, REQUIRE_INPUT, TaskPackageRunState.WAITING_INPUT)
                        .transition(state, CANCEL, TaskPackageRunState.CANCELLED);
            }
            if (state == TaskPackageRunState.PLANNED || state == TaskPackageRunState.DESIGNING
                    || state == TaskPackageRunState.DESIGN_REVIEW || state == TaskPackageRunState.EXECUTION_READY
                    || state == TaskPackageRunState.WAITING_INPUT) {
                b.transition(state, SUPERSEDE_PACKAGE, TaskPackageRunState.SUPERSEDED);
            }
        }
        b.transition(TaskPackageRunState.WAITING_INPUT, BEGIN_PACKAGE_DESIGN, TaskPackageRunState.DESIGNING)
                .transition(TaskPackageRunState.DESIGN_REVIEW, BEGIN_PACKAGE_DESIGN, TaskPackageRunState.DESIGNING)
                .transition(TaskPackageRunState.EXECUTION_READY, BEGIN_PACKAGE_DESIGN, TaskPackageRunState.DESIGNING)
                .transition(TaskPackageRunState.WAITING_INPUT, APPROVE_PACKAGE_DESIGN, TaskPackageRunState.EXECUTION_READY)
                .transition(TaskPackageRunState.WAITING_INPUT, REQUEST_PACKAGE_EXECUTION, TaskPackageRunState.QUEUED)
                .transition(TaskPackageRunState.WAITING_INPUT, CANCEL, TaskPackageRunState.CANCELLED);
        return b.build();
    }

    private static FiniteStateMachine<PackagePlanRevisionState, LifecycleEvent> packagePlanRevision() {
        return machine(LifecycleMachineType.PACKAGE_PLAN_REVISION, PackagePlanRevisionState.class)
                .transition(PackagePlanRevisionState.GENERATING, COMPLETE_PACKAGE_REPLAN,
                        PackagePlanRevisionState.PROPOSED)
                .transition(PackagePlanRevisionState.GENERATING, FAIL_PACKAGE_REPLAN,
                        PackagePlanRevisionState.FAILED)
                .transition(PackagePlanRevisionState.PROPOSED, APPROVE_PACKAGE_REPLAN,
                        PackagePlanRevisionState.ACTIVE)
                .transition(PackagePlanRevisionState.ACTIVE, SUPERSEDE,
                        PackagePlanRevisionState.SUPERSEDED)
                .build();
    }

    private static FiniteStateMachine<TaskPublicationState, LifecycleEvent> taskPublication() {
        var b = machine(LifecycleMachineType.TASK_PUBLICATION, TaskPublicationState.class);
        b.transition(TaskPublicationState.NOT_STARTED, RECORD_COMMIT, TaskPublicationState.COMMITTED)
                .transition(TaskPublicationState.NOT_STARTED, RECORD_PUSH, TaskPublicationState.PUSHED)
                .transition(TaskPublicationState.NOT_STARTED, COMPLETE_LOCAL_PUBLICATION, TaskPublicationState.LOCAL_COMPLETED)
                .transition(TaskPublicationState.COMMITTED, RECORD_PUSH, TaskPublicationState.PUSHED)
                .transition(TaskPublicationState.COMMITTED, COMPLETE_LOCAL_PUBLICATION, TaskPublicationState.LOCAL_COMPLETED)
                .transition(TaskPublicationState.COMMITTED, OPEN_MERGE_REQUEST, TaskPublicationState.MERGE_REQUEST_OPENED)
                .transition(TaskPublicationState.COMMITTED, CLOSE_MERGE_REQUEST, TaskPublicationState.MERGE_REQUEST_CLOSED)
                .transition(TaskPublicationState.COMMITTED, RECORD_MERGE, TaskPublicationState.MERGED)
                .transition(TaskPublicationState.PUSHED, OPEN_MERGE_REQUEST, TaskPublicationState.MERGE_REQUEST_OPENED)
                .transition(TaskPublicationState.PUSHED, CLOSE_MERGE_REQUEST, TaskPublicationState.MERGE_REQUEST_CLOSED)
                .transition(TaskPublicationState.PUSHED, RECORD_MERGE, TaskPublicationState.MERGED)
                .transition(TaskPublicationState.MERGE_REQUEST_OPENED, CLOSE_MERGE_REQUEST, TaskPublicationState.MERGE_REQUEST_CLOSED)
                .transition(TaskPublicationState.MERGE_REQUEST_OPENED, RECORD_MERGE, TaskPublicationState.MERGED)
                .transition(TaskPublicationState.MERGE_REQUEST_CLOSED, OPEN_MERGE_REQUEST, TaskPublicationState.MERGE_REQUEST_OPENED)
                .transition(TaskPublicationState.MERGE_REQUEST_CLOSED, RECORD_MERGE, TaskPublicationState.MERGED);
        return b.build();
    }

    private static FiniteStateMachine<ExecutionCycleState, LifecycleEvent> executionCycle() {
        return machine(LifecycleMachineType.TASK_EXECUTION_CYCLE, ExecutionCycleState.class)
                .transition(ExecutionCycleState.RUNNING, CYCLE_SUCCEED, ExecutionCycleState.SUCCEEDED)
                .transition(ExecutionCycleState.RUNNING, CYCLE_FAIL, ExecutionCycleState.FAILED)
                .transition(ExecutionCycleState.RUNNING, CYCLE_INTERRUPT, ExecutionCycleState.INTERRUPTED)
                .transition(ExecutionCycleState.RUNNING, CYCLE_AUDIT_COMPLETE, ExecutionCycleState.AUDIT_COMPLETED)
                .build();
    }

    private static FiniteStateMachine<WorkspaceCheckpointState, LifecycleEvent> workspaceCheckpoint() {
        return machine(LifecycleMachineType.WORKSPACE_CHECKPOINT, WorkspaceCheckpointState.class)
                .transition(WorkspaceCheckpointState.CAPTURING, COMPLETE, WorkspaceCheckpointState.READY)
                .transition(WorkspaceCheckpointState.CAPTURING, FAIL, WorkspaceCheckpointState.BLOCKED)
                .transition(WorkspaceCheckpointState.READY, RESTORE, WorkspaceCheckpointState.RESTORING)
                .transition(WorkspaceCheckpointState.RESTORED, RESTORE, WorkspaceCheckpointState.RESTORING)
                .transition(WorkspaceCheckpointState.RESTORING, COMPLETE, WorkspaceCheckpointState.RESTORED)
                .transition(WorkspaceCheckpointState.RESTORING, FAIL, WorkspaceCheckpointState.BLOCKED)
                .build();
    }

    private static FiniteStateMachine<StageState, LifecycleEvent> stage() {
        return machine(LifecycleMachineType.STAGE, StageState.class)
                .transition(StageState.PENDING, START, StageState.RUNNING)
                .transition(StageState.PAUSED, RESUME, StageState.RUNNING)
                .transition(StageState.RUNNING, PAUSE, StageState.PAUSED)
                .transition(StageState.RUNNING, FAIL, StageState.FAILED)
                .transition(StageState.PAUSED, FAIL, StageState.FAILED)
                .transition(StageState.PENDING, CANCEL, StageState.CANCELLED)
                .transition(StageState.RUNNING, CANCEL, StageState.CANCELLED)
                .transition(StageState.PAUSED, CANCEL, StageState.CANCELLED)
                .transition(StageState.FAILED, RECOVER, StageState.PENDING)
                .transition(StageState.SUCCEEDED, RECOVER, StageState.PENDING)
                .transition(StageState.RUNNING, COMPLETE, StageState.SUCCEEDED).build();
    }

    private static FiniteStateMachine<AttemptState, LifecycleEvent> attempt() {
        return machine(LifecycleMachineType.ATTEMPT, AttemptState.class)
                .transition(AttemptState.RUNNING, COMPLETE, AttemptState.SUCCEEDED)
                .transition(AttemptState.RUNNING, VERIFICATION_FAIL, AttemptState.VERIFICATION_FAILED)
                .transition(AttemptState.RUNNING, SESSION_FAIL, AttemptState.SESSION_ERROR)
                .transition(AttemptState.RUNNING, TASK_FAIL, AttemptState.TASK_ERROR)
                .transition(AttemptState.RUNNING, CANCEL, AttemptState.CANCELLED).build();
    }

    private static FiniteStateMachine<SessionState, LifecycleEvent> session() {
        var b = machine(LifecycleMachineType.EXECUTION_SESSION, SessionState.class)
                .transition(SessionState.CREATING, START, SessionState.RUNNING)
                .transition(SessionState.RUNNING, COMPLETE, SessionState.COMPLETED)
                .transition(SessionState.CREATING, FAIL, SessionState.FAILED)
                .transition(SessionState.RUNNING, FAIL, SessionState.FAILED)
                .transition(SessionState.CREATING, DISCONNECT, SessionState.DISCONNECTED)
                .transition(SessionState.RUNNING, DISCONNECT, SessionState.DISCONNECTED)
                .transition(SessionState.CREATING, ABORT, SessionState.ABORTED)
                .transition(SessionState.RUNNING, ABORT, SessionState.ABORTED)
                .transition(SessionState.DISCONNECTED, ABORT, SessionState.ABORTED);
        return b.build();
    }

    private static FiniteStateMachine<JudgeRunState, LifecycleEvent> judge() {
        return machine(LifecycleMachineType.JUDGE_RUN, JudgeRunState.class)
                .transition(JudgeRunState.CREATING, START, JudgeRunState.RUNNING)
                .transition(JudgeRunState.RUNNING, COMPLETE, JudgeRunState.COMPLETED)
                .transition(JudgeRunState.CREATING, SESSION_FAIL, JudgeRunState.SESSION_ERROR)
                .transition(JudgeRunState.RUNNING, SESSION_FAIL, JudgeRunState.SESSION_ERROR)
                .transition(JudgeRunState.CREATING, ABORT, JudgeRunState.ABORTED)
                .transition(JudgeRunState.RUNNING, ABORT, JudgeRunState.ABORTED).build();
    }

    private static FiniteStateMachine<LoopDraftStatus, LifecycleEvent> draft() {
        return machine(LifecycleMachineType.LOOP_DRAFT, LoopDraftStatus.class)
                .transition(LoopDraftStatus.DRAFTING, UPDATE, LoopDraftStatus.DRAFT_READY)
                .transition(LoopDraftStatus.HANDOFF_FAILED, UPDATE, LoopDraftStatus.DRAFT_READY)
                .transition(LoopDraftStatus.DRAFT_READY, CONFIRM, LoopDraftStatus.CONFIRMED).build();
    }

    private static FiniteStateMachine<DesignerSessionState, LifecycleEvent> designer() {
        return machine(LifecycleMachineType.DESIGNER_SESSION, DesignerSessionState.class)
                .transition(DesignerSessionState.PENDING_HANDOFF, DISPATCH, DesignerSessionState.RUNNING)
                .transition(DesignerSessionState.COMPLETED, DISPATCH, DesignerSessionState.RUNNING)
                .transition(DesignerSessionState.REVIEWING, DISPATCH, DesignerSessionState.RUNNING)
                .transition(DesignerSessionState.SESSION_ERROR, DISPATCH, DesignerSessionState.RUNNING)
                .transition(DesignerSessionState.COMPLETED, DEFER, DesignerSessionState.PENDING_HANDOFF)
                .transition(DesignerSessionState.SESSION_ERROR, DEFER, DesignerSessionState.PENDING_HANDOFF)
                .transition(DesignerSessionState.REVIEWING, DEFER, DesignerSessionState.PENDING_HANDOFF)
                .transition(DesignerSessionState.WAITING_INPUT, DISPATCH, DesignerSessionState.RUNNING)
                .transition(DesignerSessionState.WAITING_INPUT, DEFER, DesignerSessionState.PENDING_HANDOFF)
                .transition(DesignerSessionState.PENDING_HANDOFF, DEFER, DesignerSessionState.PENDING_HANDOFF)
                .transition(DesignerSessionState.RUNNING, DEFER, DesignerSessionState.PENDING_HANDOFF)
                .transition(DesignerSessionState.PENDING_HANDOFF, SESSION_FAIL, DesignerSessionState.SESSION_ERROR)
                .transition(DesignerSessionState.PENDING_HANDOFF, REQUIRE_INPUT, DesignerSessionState.WAITING_INPUT)
                .transition(DesignerSessionState.RUNNING, REQUIRE_INPUT, DesignerSessionState.WAITING_INPUT)
                .transition(DesignerSessionState.REVIEWING, REQUIRE_INPUT, DesignerSessionState.WAITING_INPUT)
                .transition(DesignerSessionState.RUNNING, REQUIRE_REVIEW, DesignerSessionState.REVIEWING)
                .transition(DesignerSessionState.REVIEWING, COMPLETE, DesignerSessionState.COMPLETED)
                .transition(DesignerSessionState.RUNNING, COMPLETE, DesignerSessionState.COMPLETED)
                .transition(DesignerSessionState.RUNNING, SESSION_FAIL, DesignerSessionState.SESSION_ERROR)
                .transition(DesignerSessionState.PENDING_HANDOFF, CANCEL, DesignerSessionState.STOPPING)
                .transition(DesignerSessionState.RUNNING, CANCEL, DesignerSessionState.STOPPING)
                .transition(DesignerSessionState.REVIEWING, CANCEL, DesignerSessionState.STOPPING)
                .transition(DesignerSessionState.WAITING_INPUT, CANCEL, DesignerSessionState.STOPPING)
                .transition(DesignerSessionState.COMPLETED, CANCEL, DesignerSessionState.STOPPING)
                .transition(DesignerSessionState.SESSION_ERROR, CANCEL, DesignerSessionState.STOPPING)
                .transition(DesignerSessionState.STOPPING, FINISH, DesignerSessionState.CANCELLED).build();
    }

    private static FiniteStateMachine<DesignerAutoModeState, LifecycleEvent> designerAutoMode() {
        return machine(LifecycleMachineType.DESIGNER_AUTO_MODE, DesignerAutoModeState.class)
                .transition(DesignerAutoModeState.DISABLED, ENABLE, DesignerAutoModeState.ACTIVE)
                .transition(DesignerAutoModeState.ACTIVE, DISABLE, DesignerAutoModeState.DISABLED)
                .transition(DesignerAutoModeState.ACTIVE, REQUIRE_INPUT, DesignerAutoModeState.BLOCKED)
                .transition(DesignerAutoModeState.BLOCKED, DISABLE, DesignerAutoModeState.DISABLED)
                .transition(DesignerAutoModeState.BLOCKED, ENABLE, DesignerAutoModeState.ACTIVE)
                .transition(DesignerAutoModeState.BLOCKED, RESUME, DesignerAutoModeState.ACTIVE)
                .transition(DesignerAutoModeState.ACTIVE, COMPLETE, DesignerAutoModeState.COMPLETED)
                .build();
    }

    private static FiniteStateMachine<DesignRequirementRevisionState, LifecycleEvent> requirementRevision() {
        return machine(LifecycleMachineType.DESIGN_REQUIREMENT_REVISION, DesignRequirementRevisionState.class)
                .transition(DesignRequirementRevisionState.ACTIVE, COMPLETE, DesignRequirementRevisionState.COMPLETED)
                .transition(DesignRequirementRevisionState.ACTIVE, REQUIRE_INPUT, DesignRequirementRevisionState.WAITING_INPUT)
                .transition(DesignRequirementRevisionState.WAITING_INPUT, RETRY, DesignRequirementRevisionState.ACTIVE)
                .transition(DesignRequirementRevisionState.COMPLETED, RETRY, DesignRequirementRevisionState.ACTIVE)
                .transition(DesignRequirementRevisionState.ACTIVE, SUPERSEDE, DesignRequirementRevisionState.SUPERSEDED)
                .transition(DesignRequirementRevisionState.WAITING_INPUT, SUPERSEDE, DesignRequirementRevisionState.SUPERSEDED)
                .transition(DesignRequirementRevisionState.COMPLETED, SUPERSEDE, DesignRequirementRevisionState.SUPERSEDED)
                .build();
    }

    private static FiniteStateMachine<TaskDecompositionState, LifecycleEvent> decomposition() {
        return machine(LifecycleMachineType.TASK_DECOMPOSITION, TaskDecompositionState.class)
                .transition(TaskDecompositionState.PENDING_HANDOFF, DISPATCH, TaskDecompositionState.RUNNING)
                .transition(TaskDecompositionState.RUNNING, BEGIN_VERIFICATION, TaskDecompositionState.VALIDATING)
                .transition(TaskDecompositionState.VALIDATING, RETRY, TaskDecompositionState.RUNNING)
                .transition(TaskDecompositionState.VALIDATING, COMPLETE, TaskDecompositionState.COMPLETED)
                .transition(TaskDecompositionState.VALIDATING, REQUIRE_INPUT, TaskDecompositionState.NEEDS_INPUT)
                .transition(TaskDecompositionState.VALIDATING, REQUIRE_REVIEW, TaskDecompositionState.MULTI_TASK_REQUIRED)
                .transition(TaskDecompositionState.PENDING_HANDOFF, SESSION_FAIL, TaskDecompositionState.SESSION_ERROR)
                .transition(TaskDecompositionState.RUNNING, SESSION_FAIL, TaskDecompositionState.SESSION_ERROR)
                .transition(TaskDecompositionState.VALIDATING, SESSION_FAIL, TaskDecompositionState.SESSION_ERROR)
                .build();
    }

    private static FiniteStateMachine<DesignWorkPackageState, LifecycleEvent> workPackage() {
        return machine(LifecycleMachineType.DESIGN_WORK_PACKAGE, DesignWorkPackageState.class)
                .transition(DesignWorkPackageState.PENDING, DISPATCH, DesignWorkPackageState.QUESTIONING)
                .transition(DesignWorkPackageState.PENDING, START, DesignWorkPackageState.DESIGNING)
                .transition(DesignWorkPackageState.STALE, DISPATCH, DesignWorkPackageState.QUESTIONING)
                .transition(DesignWorkPackageState.STALE, START, DesignWorkPackageState.DESIGNING)
                .transition(DesignWorkPackageState.REVIEWING, DISPATCH, DesignWorkPackageState.QUESTIONING)
                .transition(DesignWorkPackageState.REVIEWING, START, DesignWorkPackageState.DESIGNING)
                .transition(DesignWorkPackageState.QUESTIONING, START, DesignWorkPackageState.DESIGNING)
                .transition(DesignWorkPackageState.DESIGNING, ADVANCE_STAGE, DesignWorkPackageState.COMPILING)
                .transition(DesignWorkPackageState.COMPILING, BEGIN_VERIFICATION, DesignWorkPackageState.VALIDATING)
                .transition(DesignWorkPackageState.VALIDATING, REQUIRE_REVIEW, DesignWorkPackageState.REVIEWING)
                .transition(DesignWorkPackageState.COMPLETED, REQUIRE_REVIEW, DesignWorkPackageState.REVIEWING)
                .transition(DesignWorkPackageState.REVIEWING, APPROVE, DesignWorkPackageState.APPROVED)
                .transition(DesignWorkPackageState.APPROVED, REOPEN_FINAL_REVIEW, DesignWorkPackageState.REVIEWING)
                .transition(DesignWorkPackageState.APPROVED, STALE, DesignWorkPackageState.STALE)
                .transition(DesignWorkPackageState.REVIEWING, STALE, DesignWorkPackageState.STALE)
                .transition(DesignWorkPackageState.PENDING, STALE, DesignWorkPackageState.STALE)
                .transition(DesignWorkPackageState.DESIGNING, REQUIRE_INPUT, DesignWorkPackageState.WAITING_INPUT)
                .transition(DesignWorkPackageState.QUESTIONING, REQUIRE_INPUT, DesignWorkPackageState.WAITING_INPUT)
                .transition(DesignWorkPackageState.COMPILING, REQUIRE_INPUT, DesignWorkPackageState.WAITING_INPUT)
                .transition(DesignWorkPackageState.VALIDATING, REQUIRE_INPUT, DesignWorkPackageState.WAITING_INPUT)
                .transition(DesignWorkPackageState.WAITING_INPUT, DISPATCH, DesignWorkPackageState.QUESTIONING)
                .transition(DesignWorkPackageState.WAITING_INPUT, START, DesignWorkPackageState.DESIGNING)
                .transition(DesignWorkPackageState.WAITING_INPUT, RETRY, DesignWorkPackageState.COMPILING)
                .transition(DesignWorkPackageState.REVIEWING, RETRY, DesignWorkPackageState.COMPILING)
                .transition(DesignWorkPackageState.DESIGNING, FAIL, DesignWorkPackageState.FAILED)
                .transition(DesignWorkPackageState.QUESTIONING, FAIL, DesignWorkPackageState.FAILED)
                .transition(DesignWorkPackageState.COMPILING, FAIL, DesignWorkPackageState.FAILED)
                .transition(DesignWorkPackageState.VALIDATING, FAIL, DesignWorkPackageState.FAILED)
                .transition(DesignWorkPackageState.PENDING, SUPERSEDE_PACKAGE, DesignWorkPackageState.SUPERSEDED)
                .transition(DesignWorkPackageState.REVIEWING, SUPERSEDE_PACKAGE, DesignWorkPackageState.SUPERSEDED)
                .transition(DesignWorkPackageState.APPROVED, SUPERSEDE_PACKAGE, DesignWorkPackageState.SUPERSEDED)
                .transition(DesignWorkPackageState.WAITING_INPUT, SUPERSEDE_PACKAGE, DesignWorkPackageState.SUPERSEDED)
                .build();
    }

    private static FiniteStateMachine<LoopSpecCompilationState, LifecycleEvent> compilation() {
        return machine(LifecycleMachineType.LOOPSPEC_COMPILATION, LoopSpecCompilationState.class)
                .transition(LoopSpecCompilationState.PENDING_HANDOFF, DISPATCH, LoopSpecCompilationState.RUNNING)
                .transition(LoopSpecCompilationState.RUNNING, COMPLETE, LoopSpecCompilationState.COMPLETED)
                .transition(LoopSpecCompilationState.RUNNING, REQUIRE_INPUT, LoopSpecCompilationState.DESIGN_INCOMPLETE)
                .transition(LoopSpecCompilationState.RUNNING, SESSION_FAIL, LoopSpecCompilationState.SESSION_ERROR)
                .transition(LoopSpecCompilationState.PENDING_HANDOFF, SESSION_FAIL, LoopSpecCompilationState.SESSION_ERROR)
                .build();
    }

    private static FiniteStateMachine<ProjectConventionState, LifecycleEvent> convention() {
        return machine(LifecycleMachineType.PROJECT_CONVENTION, ProjectConventionState.class)
                .transition(ProjectConventionState.RUNNING, COMPLETE, ProjectConventionState.READY)
                .transition(ProjectConventionState.RUNNING, FAIL, ProjectConventionState.FAILED)
                .transition(ProjectConventionState.RUNNING, CANCEL, ProjectConventionState.STOPPING)
                .transition(ProjectConventionState.STOPPING, COMPLETE, ProjectConventionState.CANCELLED)
                .transition(ProjectConventionState.STOPPING, FAIL, ProjectConventionState.FAILED)
                .transition(ProjectConventionState.READY, APPLY, ProjectConventionState.APPLYING)
                .transition(ProjectConventionState.APPLYING, COMPLETE, ProjectConventionState.APPLIED)
                .transition(ProjectConventionState.APPLYING, FAIL, ProjectConventionState.FAILED).build();
    }

    private static FiniteStateMachine<InteractionState, LifecycleEvent> interaction() {
        var b = machine(LifecycleMachineType.INTERACTION, InteractionState.class)
                .transition(InteractionState.PENDING, CLAIM, InteractionState.RESOLVING)
                .transition(InteractionState.RESOLVING, RELEASE_CLAIM, InteractionState.PENDING)
                .transition(InteractionState.RESOLVING, RESOLVE, InteractionState.RESOLVED)
                .transition(InteractionState.RESOLVING, REJECT, InteractionState.REJECTED);
        for (InteractionState state : new InteractionState[]{InteractionState.PENDING, InteractionState.RESOLVING,
                InteractionState.HARD_DENIED}) b.transition(state, STALE, InteractionState.STALE);
        for (InteractionState state : new InteractionState[]{InteractionState.PENDING, InteractionState.RESOLVING,
                InteractionState.STALE}) b.transition(state, HARD_DENY, InteractionState.HARD_DENIED);
        return b.build();
    }

    private static FiniteStateMachine<WorkspaceLeaseState, LifecycleEvent> lease() {
        return machine(LifecycleMachineType.WORKSPACE_LEASE, WorkspaceLeaseState.class)
                .transition(WorkspaceLeaseState.RELEASED, ACQUIRE, WorkspaceLeaseState.HELD)
                .transition(WorkspaceLeaseState.HELD, TRANSFER, WorkspaceLeaseState.HELD)
                .transition(WorkspaceLeaseState.RELEASE_PENDING, TRANSFER, WorkspaceLeaseState.HELD)
                .transition(WorkspaceLeaseState.HELD, RELEASE_PENDING, WorkspaceLeaseState.RELEASE_PENDING)
                .transition(WorkspaceLeaseState.RELEASE_PENDING, ACQUIRE, WorkspaceLeaseState.HELD)
                .transition(WorkspaceLeaseState.HELD, RELEASE, WorkspaceLeaseState.RELEASED)
                .transition(WorkspaceLeaseState.RELEASE_PENDING, RELEASE, WorkspaceLeaseState.RELEASED).build();
    }

    private static FiniteStateMachine<TaskQueueState, LifecycleEvent> queue() {
        return machine(LifecycleMachineType.TASK_QUEUE, TaskQueueState.class)
                .transition(TaskQueueState.QUEUED, ADMIT, TaskQueueState.ADMITTED)
                .transition(TaskQueueState.QUEUED, CANCEL, TaskQueueState.CANCELLED)
                .transition(TaskQueueState.ADMITTED, FINISH, TaskQueueState.FINISHED)
                .transition(TaskQueueState.FINISHED, REQUEUE, TaskQueueState.QUEUED)
                .transition(TaskQueueState.FINISHED, ADMIT, TaskQueueState.ADMITTED).build();
    }

    private static FiniteStateMachine<LoopSpecTemplateState, LifecycleEvent> template() {
        return machine(LifecycleMachineType.LOOPSPEC_TEMPLATE, LoopSpecTemplateState.class)
                .transition(LoopSpecTemplateState.ACTIVE, ARCHIVE, LoopSpecTemplateState.ARCHIVED)
                .transition(LoopSpecTemplateState.ARCHIVED, RESTORE, LoopSpecTemplateState.ACTIVE).build();
    }

    private static FiniteStateMachine<AutomationRuleState, LifecycleEvent> rule() {
        return machine(LifecycleMachineType.AUTOMATION_RULE, AutomationRuleState.class)
                .transition(AutomationRuleState.DISABLED, ENABLE, AutomationRuleState.ENABLED)
                .transition(AutomationRuleState.ENABLED, DISABLE, AutomationRuleState.DISABLED).build();
    }

    private static FiniteStateMachine<AutomationRunState, LifecycleEvent> automationRun() {
        var b = machine(LifecycleMachineType.AUTOMATION_RUN, AutomationRunState.class)
                .transition(AutomationRunState.DETECTED, REQUIRE_REVIEW, AutomationRunState.REVIEW_REQUIRED)
                .transition(AutomationRunState.DETECTED, QUEUE, AutomationRunState.QUEUED)
                .transition(AutomationRunState.DETECTED, START, AutomationRunState.RUNNING)
                .transition(AutomationRunState.REVIEW_REQUIRED, QUEUE, AutomationRunState.QUEUED)
                .transition(AutomationRunState.REVIEW_REQUIRED, START, AutomationRunState.RUNNING)
                .transition(AutomationRunState.QUEUED, START, AutomationRunState.RUNNING);
        for (AutomationRunState state : new AutomationRunState[]{AutomationRunState.DETECTED,
                AutomationRunState.REVIEW_REQUIRED, AutomationRunState.QUEUED, AutomationRunState.RUNNING}) {
            b.transition(state, SUCCEED, AutomationRunState.SUCCEEDED)
                    .transition(state, FAIL, AutomationRunState.FAILED);
        }
        b.transition(AutomationRunState.DETECTED, SKIP, AutomationRunState.SKIPPED);
        return b.build();
    }

    private static <S extends Enum<S> & DescribedEnum> FiniteStateMachine.Builder<S, LifecycleEvent>
    machine(LifecycleMachineType type, Class<S> stateType) {
        return FiniteStateMachine.builder(type, stateType, LifecycleEvent.class);
    }

    private record RegisteredMachine<S extends Enum<S> & DescribedEnum>(
            Class<S> stateType, FiniteStateMachine<S, LifecycleEvent> machine) {
        ResolvedTransition resolve(String entityId, String fromValue, String toValue, LifecycleEvent explicitEvent) {
            S from = machine.parse(fromValue, entityId);
            S to = machine.parse(toValue, entityId);
            LifecycleEvent event = explicitEvent == null ? machine.defaultEvent(from, to) : explicitEvent;
            machine.requireTarget(from, event, to);
            return new ResolvedTransition(event, from.name(), to.name());
        }
        void requireState(String entityId, String value) { machine.parse(value, entityId); }
        List<String> states() { return java.util.Arrays.stream(stateType.getEnumConstants()).map(Enum::name).toList(); }
        List<DefinedTransition> definitions(LifecycleMachineType type) {
            return machine.definitions().entrySet().stream()
                    .map(entry -> new DefinedTransition(type, entry.getKey().state().name(),
                            entry.getKey().event(), entry.getValue().name()))
                    .toList();
        }
    }

    public record ResolvedTransition(LifecycleEvent event, String fromState, String toState) { }
    record DefinedTransition(LifecycleMachineType machineType, String fromState,
                             LifecycleEvent event, String toState) { }
}
