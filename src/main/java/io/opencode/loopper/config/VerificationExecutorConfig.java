package io.opencode.loopper.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps bounded, potentially long-running deterministic verifiers off scheduler threads. */
@Configuration
public class VerificationExecutorConfig {
    @Bean(name = "taskVerificationExecutor", destroyMethod = "shutdown")
    Executor taskVerificationExecutor() {
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64),
                Thread.ofPlatform().name("loopper-verifier-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
