package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.AppSettingsRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SettingsService {
    private final LoopperMapper mapper;
    private final LoopperProperties properties;
    private final OpenCodeModelCatalogService models;
    private final SettingsPersistence persistence;

    public SettingsService(LoopperMapper mapper, LoopperProperties properties, OpenCodeModelCatalogService models,
                           SettingsPersistence persistence) {
        this.mapper = mapper;
        this.properties = properties;
        this.models = models;
        this.persistence = persistence;
        mapper.findAppSettings().ifPresent(this::apply);
    }

    public AppSettings get() {
        return mapper.findAppSettings().map(this::view).orElseGet(this::defaults);
    }

    public List<OpenCodeModelCatalogService.AvailableModel> models() {
        return models.discover(get().cliPath());
    }

    public List<OpenCodeModelCatalogService.AvailableModel> models(String cliPath) {
        return models.discover(cliPath == null || cliPath.isBlank() ? get().cliPath() : cliPath);
    }

    public AppSettings save(AppSettings requested) {
        AppSettings validated = validate(requested);
        List<OpenCodeModelCatalogService.AvailableModel> available = models.discover(validated.cliPath());
        String selected = validated.provider() + "/" + validated.model();
        if (available.stream().noneMatch(candidate -> candidate.id().equals(selected))) {
            throw new BadRequestException("OPENCODE_MODEL_UNKNOWN", "Selected model is not reported by this OpenCode CLI: " + selected);
        }
        AppSettingsRow row = new AppSettingsRow(1, validated.cliPath(), validated.allowedRoot(), validated.provider(), validated.model(),
                validated.maxTaskAttempts(), validated.timeoutMinutes(), 0, Instant.now().toString());
        AppSettingsRow saved = persistence.save(row);
        apply(saved);
        return view(saved);
    }

    private AppSettings validate(AppSettings value) {
        if (value == null) throw new BadRequestException("SETTINGS_REQUIRED", "Settings request is required");
        String cliPath = OpenCodeModelCatalogService.normalizedCliPath(value.cliPath());
        String allowedRoot = value.allowedRoot() == null ? "" : value.allowedRoot().trim();
        if (!allowedRoot.isEmpty()) {
            try {
                if (!Path.of(allowedRoot).isAbsolute()) throw new IllegalArgumentException();
                allowedRoot = Path.of(allowedRoot).toAbsolutePath().normalize().toString();
            } catch (RuntimeException invalid) {
                throw new BadRequestException("ALLOWED_ROOT_INVALID", "Allowed project root must be an absolute path");
            }
        }
        String provider = normalizedIdentifier(value.provider(), "MODEL_PROVIDER_REQUIRED", "Model provider is required");
        String model = normalizedIdentifier(value.model(), "MODEL_ID_REQUIRED", "Model is required");
        if (value.maxTaskAttempts() < 1 || value.maxTaskAttempts() > 50) {
            throw new BadRequestException("MAX_TASK_ATTEMPTS_INVALID", "Maximum task attempts must be between 1 and 50");
        }
        if (value.timeoutMinutes() < 1 || value.timeoutMinutes() > 120) {
            throw new BadRequestException("ATTEMPT_TIMEOUT_INVALID", "Attempt timeout must be between 1 and 120 minutes");
        }
        if (value.autoApprove()) {
            throw new BadRequestException("AUTO_APPROVE_UNSUPPORTED", "Automatic ask approval is not available; permissions remain fail-closed");
        }
        return new AppSettings(cliPath, allowedRoot, provider, model, value.maxTaskAttempts(), value.timeoutMinutes(), false, null);
    }

    private static String normalizedIdentifier(String value, String code, String message) {
        if (value == null || value.isBlank()) throw new BadRequestException(code, message);
        String normalized = value.trim();
        if (normalized.length() > 512 || normalized.chars().anyMatch(Character::isWhitespace)) throw new BadRequestException(code, message);
        return normalized;
    }

    private AppSettings defaults() {
        String configured = properties.getOpenCode().getModel();
        String provider = "";
        String model = "";
        if (configured != null) {
            int separator = configured.indexOf('/');
            if (separator > 0 && separator < configured.length() - 1) {
                provider = configured.substring(0, separator).trim();
                model = configured.substring(separator + 1).trim();
            }
        }
        String executable = properties.getOpenCode().getExecutable();
        return new AppSettings(executable == null || executable.isBlank() ? "opencode" : executable.trim(),
                properties.getAllowedRoot() == null ? "" : properties.getAllowedRoot(), provider, model,
                properties.getMaxTaskAttempts(), Math.toIntExact(properties.getAttemptTimeout().toMinutes()), false, null);
    }

    private AppSettings view(AppSettingsRow row) {
        return new AppSettings(row.cliPath(), row.allowedRoot(), row.providerId(), row.modelId(), row.maxTaskAttempts(),
                row.attemptTimeoutMinutes(), row.autoApprove() == 1, row.updatedAt());
    }

    private void apply(AppSettingsRow row) {
        properties.getOpenCode().setExecutable(row.cliPath());
        properties.getOpenCode().setModel(row.providerId() + "/" + row.modelId());
        properties.setAllowedRoot(row.allowedRoot());
        properties.setMaxTaskAttempts(row.maxTaskAttempts());
        properties.setAttemptTimeout(Duration.ofMinutes(row.attemptTimeoutMinutes()));
    }

    public record AppSettings(String cliPath, String allowedRoot, String provider, String model,
                              int maxTaskAttempts, int timeoutMinutes, boolean autoApprove, String updatedAt) { }
}
