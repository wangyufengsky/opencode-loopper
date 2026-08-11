package io.opencode.loopper.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigurationTest {
    @Test
    void schedulingIsEnabledByDefault() {
        try (AnnotationConfigApplicationContext context = context()) {
            assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isTrue();
        }
    }

    @Test
    void schedulingCanBeDisabledWithoutRemovingMonitorBeansFromTheApplication() {
        try (AnnotationConfigApplicationContext context = context("loopper.scheduling.enabled=false")) {
            assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isFalse();
        }
    }

    private AnnotationConfigApplicationContext context(String... properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of(properties).applyTo(context);
        context.register(SchedulingConfiguration.class);
        context.refresh();
        return context;
    }
}
