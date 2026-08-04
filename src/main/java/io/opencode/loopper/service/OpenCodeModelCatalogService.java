package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.ProcessResult;
import io.opencode.loopper.runtime.SafeProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OpenCodeModelCatalogService {
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(20);
    private final SafeProcessRunner runner;

    public OpenCodeModelCatalogService(SafeProcessRunner runner) {
        this.runner = runner;
    }

    public List<AvailableModel> discover(String cliPath) {
        String executable = normalizedCliPath(cliPath);
        ProcessResult result;
        try {
            result = runner.run(Path.of(".").toAbsolutePath().normalize(), List.of(executable, "models"), DISCOVERY_TIMEOUT);
            if (!result.timedOut() && result.exitCode() != 0) {
                // The CLI can briefly contend with a just-started local server/config cache.
                // One bounded retry keeps initial page load stable without hiding a durable failure.
                result = runner.run(Path.of(".").toAbsolutePath().normalize(), List.of(executable, "models"), DISCOVERY_TIMEOUT);
            }
        } catch (TaskFailure failure) {
            throw new ServiceUnavailableException("OPENCODE_MODEL_DISCOVERY_FAILED", failure.getMessage());
        }
        if (result.timedOut()) {
            throw new ServiceUnavailableException("OPENCODE_MODEL_DISCOVERY_TIMEOUT", "OpenCode model discovery timed out");
        }
        if (result.exitCode() != 0) {
            throw new ServiceUnavailableException("OPENCODE_MODEL_DISCOVERY_FAILED",
                    "OpenCode model discovery exited with code " + result.exitCode());
        }
        Map<String, AvailableModel> models = new LinkedHashMap<>();
        for (String line : result.output().lines().toList()) {
            String id = line.trim();
            int separator = id.indexOf('/');
            if (separator <= 0 || separator >= id.length() - 1 || id.chars().anyMatch(Character::isWhitespace)) continue;
            String provider = id.substring(0, separator);
            String model = id.substring(separator + 1);
            models.putIfAbsent(id, new AvailableModel(id, provider, model, provider + " / " + model));
        }
        List<AvailableModel> discovered = models.values().stream()
                .sorted(Comparator.comparing(AvailableModel::provider).thenComparing(AvailableModel::model))
                .toList();
        if (discovered.isEmpty()) {
            throw new ServiceUnavailableException("OPENCODE_MODEL_DISCOVERY_EMPTY", "OpenCode returned no selectable models");
        }
        return discovered;
    }

    public static String normalizedCliPath(String value) {
        if (value == null || value.isBlank()) throw new BadRequestException("OPENCODE_CLI_REQUIRED", "OpenCode CLI path is required");
        String normalized = value.trim();
        if (normalized.length() > 2_048 || normalized.indexOf('\0') >= 0 || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new BadRequestException("OPENCODE_CLI_INVALID", "OpenCode CLI path is invalid");
        }
        return normalized;
    }

    public record AvailableModel(String id, String provider, String model, String label) { }
}
