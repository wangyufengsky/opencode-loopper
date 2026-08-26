package io.opencode.loopper.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Resumes durable read-only remaining-plan suggestions after process restarts. */
@Component
class RollingPackagePlanMonitor {
    private final RollingPackagePlanGenerationService generations;

    RollingPackagePlanMonitor(RollingPackagePlanGenerationService generations) {
        this.generations = generations;
    }

    @Scheduled(fixedDelayString = "${loopper.designer-monitor-delay:750ms}")
    void poll() { generations.pollGenerating(); }
}
