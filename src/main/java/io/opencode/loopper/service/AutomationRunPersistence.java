package io.opencode.loopper.service;

import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.AutomationRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits run evidence independently from draft/task admission transactions. */
@Service
public class AutomationRunPersistence {
    private final LoopperMapper mapper;
    private final LifecycleTransitionService lifecycle;

    public AutomationRunPersistence(LoopperMapper mapper, LifecycleTransitionService lifecycle) {
        this.mapper = mapper;
        this.lifecycle = lifecycle;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(AutomationRunRow row) {
        lifecycle.create(subject(row), row.state(), java.util.Map.of("source", row.triggerType()),
                () -> mapper.insertAutomationRun(row),
                () -> new ConflictException("AUTOMATION_RUN_CREATE_CONFLICT", "Automation run could not be created"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(AutomationRunRow row) {
        AutomationRunRow current = mapper.findAutomationRun(row.id())
                .orElseThrow(() -> new NotFoundException("Automation run not found: " + row.id()));
        lifecycle.transition(subject(row), current.state(), row.state(), null, java.util.Map.of(),
                () -> mapper.updateAutomationRun(row),
                () -> new ConflictException("AUTOMATION_RUN_VERSION_CONFLICT", "Automation run changed concurrently"));
    }

    private LifecycleTransitionService.Subject subject(AutomationRunRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.AUTOMATION_RUN, row.id(),
                LifecycleScopeType.AUTOMATION_RULE, row.ruleId());
    }
}
