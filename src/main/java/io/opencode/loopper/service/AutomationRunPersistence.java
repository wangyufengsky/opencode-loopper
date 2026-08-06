package io.opencode.loopper.service;

import io.opencode.loopper.persistence.AutomationRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits run evidence independently from draft/task admission transactions. */
@Service
public class AutomationRunPersistence {
    private final LoopperMapper mapper;

    public AutomationRunPersistence(LoopperMapper mapper) { this.mapper = mapper; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(AutomationRunRow row) { mapper.insertAutomationRun(row); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(AutomationRunRow row) { mapper.updateAutomationRun(row); }
}
