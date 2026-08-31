package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.AppSettingsRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class SettingsService {
    private static final List<String> LIVE_FIELDS = List.of(
            "runtime.allowedRoot", "runtime.abortCleanupAttempts", "openCode.cliPath", "openCode.model",
            "limits", "retryWait");
    private static final List<String> RESTART_FIELDS = List.of(
            "runtime.serverPort", "runtime.openBrowser", "runtime.monitorDelaySeconds",
            "runtime.designerMonitorDelayMillis", "openCode.mode", "openCode.baseUrl",
            "openCode.connectTimeoutSeconds", "openCode.requestTimeoutSeconds", "openCode.startupTimeoutSeconds",
            "publication");

    private final LoopperMapper mapper;
    private final LoopperProperties properties;
    private final OpenCodeModelCatalogService models;
    private final SettingsPersistence persistence;
    private final StartupSettingsFile startupFile;
    private final ObjectMapper json;

    public SettingsService(LoopperMapper mapper, LoopperProperties properties, OpenCodeModelCatalogService models,
                           SettingsPersistence persistence, StartupSettingsFile startupFile, ObjectMapper json) {
        this.mapper = mapper;
        this.properties = properties;
        this.models = models;
        this.persistence = persistence;
        this.startupFile = startupFile;
        this.json = json;
        // Complete V31 settings are applied at startup by the script mirror so an
        // explicit process environment can remain authoritative. Preserve the
        // historical V7 behavior only until that row is saved in the new format.
        mapper.findAppSettings().filter(row -> row.settingsJson() == null || row.settingsJson().isBlank())
                .ifPresent(row -> apply(view(row)));
    }

    public AppSettings get() {
        return mapper.findAppSettings().map(this::view).orElseGet(this::defaults);
    }

    public List<OpenCodeModelCatalogService.AvailableModel> models() {
        return models.discover(get().openCode().cliPath());
    }

    public List<OpenCodeModelCatalogService.AvailableModel> models(String cliPath) {
        return models.discover(cliPath == null || cliPath.isBlank() ? get().openCode().cliPath() : cliPath);
    }

    public AppSettings save(AppSettings requested) {
        AppSettings validated = validate(requested);
        OpenCodeSettings openCode = validated.openCode();
        String selected = openCode.provider() + "/" + openCode.model();
        if (models.discover(openCode.cliPath()).stream().noneMatch(candidate -> candidate.id().equals(selected))) {
            throw new BadRequestException("OPENCODE_MODEL_UNKNOWN",
                    "Selected model is not reported by this OpenCode CLI: " + selected);
        }
        String updatedAt = Instant.now().toString();
        AppSettings savedView = withMetadata(validated, updatedAt);
        AppSettingsRow row = new AppSettingsRow(1, openCode.cliPath(), validated.runtime().allowedRoot(),
                openCode.provider(), openCode.model(), validated.limits().maxTaskAttempts(),
                validated.limits().attemptTimeoutMinutes(), 0, write(savedView), updatedAt);
        AppSettingsRow saved = persistence.save(row, startupFile.prepare(startupValues(savedView)));
        AppSettings result = view(saved);
        apply(result);
        return result;
    }

    private AppSettings validate(AppSettings value) {
        if (value == null || value.runtime() == null || value.openCode() == null || value.limits() == null
                || value.retryWait() == null || value.publication() == null) {
            throw new BadRequestException("SETTINGS_REQUIRED", "All settings groups are required");
        }
        RuntimeSettings inputRuntime = value.runtime();
        RuntimeSettings runtime = new RuntimeSettings(
                between(inputRuntime.serverPort(), 1, 65535, "SERVER_PORT_INVALID", "Server port"),
                inputRuntime.openBrowser(), normalizedAbsolutePath(inputRuntime.allowedRoot()),
                between(inputRuntime.monitorDelaySeconds(), 1, 60, "MONITOR_DELAY_INVALID", "Monitor delay"),
                between(inputRuntime.designerMonitorDelayMillis(), 250, 10_000,
                        "DESIGNER_MONITOR_DELAY_INVALID", "Designer monitor delay"),
                between(inputRuntime.abortCleanupAttempts(), 1, 10,
                        "ABORT_CLEANUP_ATTEMPTS_INVALID", "Abort cleanup attempts"));

        OpenCodeSettings inputOpenCode = value.openCode();
        OpenCodeSettings openCode = new OpenCodeSettings(
                OpenCodeModelCatalogService.normalizedCliPath(inputOpenCode.cliPath()),
                normalizedMode(inputOpenCode.mode()),
                normalizedHttpUri(inputOpenCode.baseUrl(), "OPENCODE_BASE_URL_INVALID"),
                normalizedIdentifier(inputOpenCode.provider(), "MODEL_PROVIDER_REQUIRED", "Model provider is required"),
                normalizedIdentifier(inputOpenCode.model(), "MODEL_ID_REQUIRED", "Model is required"),
                between(inputOpenCode.connectTimeoutSeconds(), 1, 120,
                        "OPENCODE_CONNECT_TIMEOUT_INVALID", "OpenCode connect timeout"),
                between(inputOpenCode.requestTimeoutSeconds(), 1, 600,
                        "OPENCODE_REQUEST_TIMEOUT_INVALID", "OpenCode request timeout"),
                between(inputOpenCode.startupTimeoutSeconds(), 1, 300,
                        "OPENCODE_STARTUP_TIMEOUT_INVALID", "OpenCode startup timeout"));

        LimitSettings inputLimits = value.limits();
        LimitSettings limits = new LimitSettings(
                between(inputLimits.maxStageAttempts(), 1, 10, "MAX_STAGE_ATTEMPTS_INVALID", "Maximum stage attempts"),
                between(inputLimits.maxTaskAttempts(), 1, 50, "MAX_TASK_ATTEMPTS_INVALID", "Maximum task attempts"),
                between(inputLimits.sessionErrorLimit(), 1, 10, "SESSION_ERROR_LIMIT_INVALID", "Session error limit"),
                between(inputLimits.maxDurationMinutes(), 1, 1_440, "MAX_DURATION_INVALID", "Maximum task duration"),
                between(inputLimits.attemptTimeoutMinutes(), 1, 120, "ATTEMPT_TIMEOUT_INVALID", "Attempt timeout"),
                between(inputLimits.verifierTimeoutMinutes(), 1, 120, "VERIFIER_TIMEOUT_INVALID", "Verifier timeout"),
                between(inputLimits.designerTimeoutMinutes(), 1, 120, "DESIGNER_TIMEOUT_INVALID", "Designer timeout"));

        RetryWaitSettings retry = validateRetry(value.retryWait());
        PublicationSettings inputPublication = value.publication();
        PublicationSettings publication = new PublicationSettings(
                normalizedHosts(inputPublication.httpWebHosts()),
                normalizedHost(inputPublication.gitlabHost(), "GITLAB_HOST_INVALID"),
                normalizedHttpUri(inputPublication.gitlabApiBaseUrl(), "GITLAB_API_BASE_URL_INVALID"),
                between(inputPublication.connectTimeoutSeconds(), 1, 120,
                        "GITLAB_CONNECT_TIMEOUT_INVALID", "GitLab connect timeout"),
                between(inputPublication.requestTimeoutSeconds(), 1, 300,
                        "GITLAB_REQUEST_TIMEOUT_INVALID", "GitLab request timeout"));
        return withMetadata(new AppSettings(runtime, openCode, limits, retry, publication,
                null, List.of(), List.of(), null), null);
    }

    private RetryWaitSettings validateRetry(RetryWaitSettings retry) {
        int rateBase = between(retry.rateLimitBaseSeconds(), 5, 600,
                "RETRY_RATE_LIMIT_BASE_INVALID", "Rate-limit retry base");
        int rateMax = between(retry.rateLimitMaxSeconds(), rateBase, 3_600,
                "RETRY_RATE_LIMIT_MAX_INVALID", "Rate-limit retry maximum");
        int sessionBase = between(retry.sessionBaseSeconds(), 1, 300,
                "RETRY_SESSION_BASE_INVALID", "Session retry base");
        int sessionMax = between(retry.sessionMaxSeconds(), sessionBase, 1_800,
                "RETRY_SESSION_MAX_INVALID", "Session retry maximum");
        int verificationBase = between(retry.verificationBaseSeconds(), 1, 120,
                "RETRY_VERIFICATION_BASE_INVALID", "Verification retry base");
        int verificationMax = between(retry.verificationMaxSeconds(), verificationBase, 600,
                "RETRY_VERIFICATION_MAX_INVALID", "Verification retry maximum");
        return new RetryWaitSettings(rateBase, rateMax, sessionBase, sessionMax, verificationBase, verificationMax);
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
        Set<String> configuredHosts = properties.getPublication().getHttpWebHosts();
        List<String> hosts = configuredHosts == null || configuredHosts.isEmpty()
                ? List.of("gitlab.spdb.com") : List.copyOf(configuredHosts);
        LoopperProperties.GitLab gitlab = properties.getPublication().getGitlab();
        LoopperProperties.RetryWait retry = properties.getRetryWait();
        return new AppSettings(
                new RuntimeSettings(environmentInt("SERVER_PORT", 8080),
                        environmentBoolean("LOOPPER_OPEN_BROWSER", true),
                        properties.getAllowedRoot() == null ? "" : properties.getAllowedRoot(),
                        Math.toIntExact(properties.getMonitorDelay().toSeconds()),
                        Math.toIntExact(properties.getDesignerMonitorDelay().toMillis()),
                        properties.getAbortCleanupAttempts()),
                new OpenCodeSettings(executable == null || executable.isBlank() ? "opencode" : executable.trim(),
                        properties.getOpenCode().getMode(), properties.getOpenCode().getBaseUrl().toString(),
                        provider, model, seconds(properties.getOpenCode().getConnectTimeout()),
                        seconds(properties.getOpenCode().getRequestTimeout()),
                        seconds(properties.getOpenCode().getStartupTimeout())),
                new LimitSettings(properties.getMaxStageAttempts(), properties.getMaxTaskAttempts(),
                        properties.getSessionErrorLimit(), minutes(properties.getMaxDuration()),
                        minutes(properties.getAttemptTimeout()), minutes(properties.getVerifierTimeout()),
                        minutes(properties.getDesignerTimeout())),
                new RetryWaitSettings(seconds(retry.getRateLimitBase()), seconds(retry.getRateLimitMax()),
                        seconds(retry.getSessionBase()), seconds(retry.getSessionMax()),
                        seconds(retry.getVerificationBase()), seconds(retry.getVerificationMax())),
                new PublicationSettings(hosts, blankDefault(gitlab.getHost(), "gitlab.spdb.com"),
                        gitlab.getApiBaseUrl() == null ? "http://gitlab.spdb.com/api/v4" : gitlab.getApiBaseUrl().toString(),
                        seconds(gitlab.getConnectTimeout()), seconds(gitlab.getRequestTimeout())),
                startupFile.path().toString(), LIVE_FIELDS, RESTART_FIELDS, null);
    }

    private AppSettings view(AppSettingsRow row) {
        if (row.settingsJson() != null && !row.settingsJson().isBlank()) {
            try {
                return withMetadata(json.readValue(row.settingsJson(), AppSettings.class), row.updatedAt());
            } catch (JacksonException ignored) {
                // V7 columns remain a safe startup fallback if the optional projection is corrupt.
            }
        }
        AppSettings base = defaults();
        return withMetadata(new AppSettings(
                new RuntimeSettings(base.runtime().serverPort(), base.runtime().openBrowser(), row.allowedRoot(),
                        base.runtime().monitorDelaySeconds(), base.runtime().designerMonitorDelayMillis(),
                        base.runtime().abortCleanupAttempts()),
                new OpenCodeSettings(row.cliPath(), base.openCode().mode(), base.openCode().baseUrl(),
                        row.providerId(), row.modelId(), base.openCode().connectTimeoutSeconds(),
                        base.openCode().requestTimeoutSeconds(), base.openCode().startupTimeoutSeconds()),
                new LimitSettings(base.limits().maxStageAttempts(), row.maxTaskAttempts(),
                        base.limits().sessionErrorLimit(), base.limits().maxDurationMinutes(),
                        row.attemptTimeoutMinutes(), base.limits().verifierTimeoutMinutes(),
                        base.limits().designerTimeoutMinutes()), base.retryWait(), base.publication(),
                null, List.of(), List.of(), row.updatedAt()), row.updatedAt());
    }

    private AppSettings withMetadata(AppSettings value, String updatedAt) {
        return new AppSettings(value.runtime(), value.openCode(), value.limits(), value.retryWait(), value.publication(),
                startupFile.path().toString(), LIVE_FIELDS, RESTART_FIELDS, updatedAt);
    }

    private void apply(AppSettings settings) {
        RuntimeSettings runtime = settings.runtime();
        OpenCodeSettings openCode = settings.openCode();
        LimitSettings limits = settings.limits();
        RetryWaitSettings retry = settings.retryWait();
        properties.setAllowedRoot(runtime.allowedRoot());
        properties.setAbortCleanupAttempts(runtime.abortCleanupAttempts());
        properties.getOpenCode().setExecutable(openCode.cliPath());
        properties.getOpenCode().setModel(openCode.provider() + "/" + openCode.model());
        properties.setMaxStageAttempts(limits.maxStageAttempts());
        properties.setMaxTaskAttempts(limits.maxTaskAttempts());
        properties.setSessionErrorLimit(limits.sessionErrorLimit());
        properties.setMaxDuration(Duration.ofMinutes(limits.maxDurationMinutes()));
        properties.setAttemptTimeout(Duration.ofMinutes(limits.attemptTimeoutMinutes()));
        properties.setVerifierTimeout(Duration.ofMinutes(limits.verifierTimeoutMinutes()));
        properties.setDesignerTimeout(Duration.ofMinutes(limits.designerTimeoutMinutes()));
        properties.getRetryWait().setRateLimitBase(Duration.ofSeconds(retry.rateLimitBaseSeconds()));
        properties.getRetryWait().setRateLimitMax(Duration.ofSeconds(retry.rateLimitMaxSeconds()));
        properties.getRetryWait().setSessionBase(Duration.ofSeconds(retry.sessionBaseSeconds()));
        properties.getRetryWait().setSessionMax(Duration.ofSeconds(retry.sessionMaxSeconds()));
        properties.getRetryWait().setVerificationBase(Duration.ofSeconds(retry.verificationBaseSeconds()));
        properties.getRetryWait().setVerificationMax(Duration.ofSeconds(retry.verificationMaxSeconds()));
    }

    private Map<String, String> startupValues(AppSettings settings) {
        RuntimeSettings runtime = settings.runtime();
        OpenCodeSettings openCode = settings.openCode();
        LimitSettings limits = settings.limits();
        RetryWaitSettings retry = settings.retryWait();
        PublicationSettings publication = settings.publication();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("SERVER_PORT", String.valueOf(runtime.serverPort()));
        values.put("LOOPPER_OPEN_BROWSER", String.valueOf(runtime.openBrowser()));
        values.put("LOOPPER_ALLOWED_ROOT", runtime.allowedRoot());
        values.put("LOOPPER_MONITOR_DELAY", runtime.monitorDelaySeconds() + "s");
        values.put("LOOPPER_DESIGNER_MONITOR_DELAY", runtime.designerMonitorDelayMillis() + "ms");
        values.put("LOOPPER_ABORT_CLEANUP_ATTEMPTS", String.valueOf(runtime.abortCleanupAttempts()));
        values.put("LOOPPER_OPENCODE_MODE", openCode.mode());
        values.put("OPENCODE_BASE_URL", openCode.baseUrl());
        values.put("OPENCODE_EXECUTABLE", openCode.cliPath());
        values.put("OPENCODE_MODEL", openCode.provider() + "/" + openCode.model());
        values.put("LOOPPER_OPENCODE_CONNECT_TIMEOUT", openCode.connectTimeoutSeconds() + "s");
        values.put("LOOPPER_OPENCODE_REQUEST_TIMEOUT", openCode.requestTimeoutSeconds() + "s");
        values.put("LOOPPER_OPENCODE_STARTUP_TIMEOUT", openCode.startupTimeoutSeconds() + "s");
        values.put("LOOPPER_MAX_STAGE_ATTEMPTS", String.valueOf(limits.maxStageAttempts()));
        values.put("LOOPPER_MAX_TASK_ATTEMPTS", String.valueOf(limits.maxTaskAttempts()));
        values.put("LOOPPER_SESSION_ERROR_LIMIT", String.valueOf(limits.sessionErrorLimit()));
        values.put("LOOPPER_MAX_DURATION", limits.maxDurationMinutes() + "m");
        values.put("LOOPPER_ATTEMPT_TIMEOUT", limits.attemptTimeoutMinutes() + "m");
        values.put("LOOPPER_VERIFIER_TIMEOUT", limits.verifierTimeoutMinutes() + "m");
        values.put("LOOPPER_DESIGNER_TIMEOUT", limits.designerTimeoutMinutes() + "m");
        values.put("LOOPPER_RETRY_RATE_LIMIT_BASE", retry.rateLimitBaseSeconds() + "s");
        values.put("LOOPPER_RETRY_RATE_LIMIT_MAX", retry.rateLimitMaxSeconds() + "s");
        values.put("LOOPPER_RETRY_SESSION_BASE", retry.sessionBaseSeconds() + "s");
        values.put("LOOPPER_RETRY_SESSION_MAX", retry.sessionMaxSeconds() + "s");
        values.put("LOOPPER_RETRY_VERIFICATION_BASE", retry.verificationBaseSeconds() + "s");
        values.put("LOOPPER_RETRY_VERIFICATION_MAX", retry.verificationMaxSeconds() + "s");
        values.put("LOOPPER_PUBLICATION_HTTP_WEB_HOSTS", String.join(",", publication.httpWebHosts()));
        values.put("LOOPPER_GITLAB_HOST", publication.gitlabHost());
        values.put("LOOPPER_GITLAB_API_BASE_URL", publication.gitlabApiBaseUrl());
        values.put("LOOPPER_GITLAB_CONNECT_TIMEOUT", publication.connectTimeoutSeconds() + "s");
        values.put("LOOPPER_GITLAB_REQUEST_TIMEOUT", publication.requestTimeoutSeconds() + "s");
        values.replaceAll(SettingsService::safeStartupValue);
        return values;
    }

    private static String normalizedAbsolutePath(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return "";
        try {
            Path path = Path.of(normalized);
            if (!path.isAbsolute()) throw new IllegalArgumentException();
            return path.toAbsolutePath().normalize().toString();
        } catch (RuntimeException invalid) {
            throw new BadRequestException("ALLOWED_ROOT_INVALID", "Allowed project root must be an absolute path");
        }
    }

    private static String normalizedMode(String value) {
        String mode = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("managed", "auto", "http").contains(mode)) {
            throw new BadRequestException("OPENCODE_MODE_INVALID", "OpenCode mode must be managed, auto, or http");
        }
        return mode;
    }

    private static String normalizedHttpUri(String value, String code) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim()).normalize();
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            return uri.toString();
        } catch (RuntimeException invalid) {
            throw new BadRequestException(code, "A valid http/https URL without credentials or fragments is required");
        }
    }

    private static List<String> normalizedHosts(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) values.forEach(value -> result.add(normalizedHost(value, "PUBLICATION_HTTP_HOST_INVALID")));
        return List.copyOf(result);
    }

    private static String normalizedHost(String value, String code) {
        String host = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || host.length() > 253 || !host.matches("[a-z0-9.-]+")
                || host.startsWith(".") || host.endsWith(".")) {
            throw new BadRequestException(code, "A valid host name is required");
        }
        return host;
    }

    private static String normalizedIdentifier(String value, String code, String message) {
        if (value == null || value.isBlank()) throw new BadRequestException(code, message);
        String normalized = value.trim();
        if (normalized.length() > 512 || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new BadRequestException(code, message);
        }
        return normalized;
    }

    private static int between(int value, int min, int max, String code, String label) {
        if (value < min || value > max) {
            throw new BadRequestException(code, label + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static String safeStartupValue(String key, String value) {
        if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            throw new BadRequestException("STARTUP_SETTING_VALUE_INVALID",
                    "Startup setting contains an unsafe value: " + key);
        }
        return value;
    }

    private String write(AppSettings value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalStateException("Settings could not be serialized", failure);
        }
    }

    private static int environmentInt(String name, int fallback) {
        try {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
    private static boolean environmentBoolean(String name, boolean fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }
    private static int seconds(Duration value) { return Math.toIntExact(value.toSeconds()); }
    private static int minutes(Duration value) { return Math.toIntExact(value.toMinutes()); }
    private static String blankDefault(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    public record RuntimeSettings(int serverPort, boolean openBrowser, String allowedRoot,
                                  int monitorDelaySeconds, int designerMonitorDelayMillis,
                                  int abortCleanupAttempts) { }
    public record OpenCodeSettings(String cliPath, String mode, String baseUrl, String provider, String model,
                                   int connectTimeoutSeconds, int requestTimeoutSeconds,
                                   int startupTimeoutSeconds) { }
    public record LimitSettings(int maxStageAttempts, int maxTaskAttempts, int sessionErrorLimit,
                                int maxDurationMinutes, int attemptTimeoutMinutes,
                                int verifierTimeoutMinutes, int designerTimeoutMinutes) { }
    public record RetryWaitSettings(int rateLimitBaseSeconds, int rateLimitMaxSeconds,
                                    int sessionBaseSeconds, int sessionMaxSeconds,
                                    int verificationBaseSeconds, int verificationMaxSeconds) { }
    public record PublicationSettings(List<String> httpWebHosts, String gitlabHost, String gitlabApiBaseUrl,
                                      int connectTimeoutSeconds, int requestTimeoutSeconds) {
        public PublicationSettings {
            httpWebHosts = httpWebHosts == null ? List.of() : List.copyOf(httpWebHosts);
        }
    }
    public record AppSettings(RuntimeSettings runtime, OpenCodeSettings openCode, LimitSettings limits,
                              RetryWaitSettings retryWait, PublicationSettings publication,
                              String startupConfigPath, List<String> appliedLiveFields,
                              List<String> restartRequiredFields, String updatedAt) {
        public AppSettings {
            appliedLiveFields = appliedLiveFields == null ? List.of() : List.copyOf(appliedLiveFields);
            restartRequiredFields = restartRequiredFields == null ? List.of() : List.copyOf(restartRequiredFields);
        }
    }
}
