package io.opencode.loopper.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables production polling while allowing integration tests to keep the same monitor beans without background races. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "loopper.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfiguration { }
