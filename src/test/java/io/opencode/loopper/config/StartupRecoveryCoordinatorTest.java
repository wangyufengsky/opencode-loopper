package io.opencode.loopper.config;

import io.opencode.loopper.service.AutomationService;
import io.opencode.loopper.service.LocalSyncConflictService;
import io.opencode.loopper.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StartupRecoveryCoordinatorTest {
    @Test
    void startupRecoveryIsEnabledByDefault() {
        LocalSyncConflictService conflicts = mock(LocalSyncConflictService.class);
        TaskService tasks = mock(TaskService.class);
        AutomationService automations = mock(AutomationService.class);

        try (AnnotationConfigApplicationContext context = context(conflicts, tasks, automations)) {
            StartupRecoveryCoordinator coordinator = context.getBean(StartupRecoveryCoordinator.class);
            coordinator.recoverAfterRestart();
        }

        verify(conflicts).recoverInterruptedApplications();
        verify(tasks).recoverAfterRestart();
        verify(automations).recoverAfterRestart();
    }

    @Test
    void startupRecoveryCanBeDisabledWithoutRemovingRecoveryServices() {
        try (AnnotationConfigApplicationContext context = context(
                mock(LocalSyncConflictService.class), mock(TaskService.class), mock(AutomationService.class),
                "loopper.startup-recovery.enabled=false")) {
            assertThat(context.getBeansOfType(StartupRecoveryCoordinator.class)).isEmpty();
            assertThat(context.getBean(TaskService.class)).isNotNull();
        }
    }

    private AnnotationConfigApplicationContext context(LocalSyncConflictService conflicts, TaskService tasks,
                                                       AutomationService automations, String... properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of(properties).applyTo(context);
        context.registerBean(LocalSyncConflictService.class, () -> conflicts);
        context.registerBean(TaskService.class, () -> tasks);
        context.registerBean(AutomationService.class, () -> automations);
        context.register(StartupRecoveryCoordinator.class);
        context.refresh();
        return context;
    }
}
