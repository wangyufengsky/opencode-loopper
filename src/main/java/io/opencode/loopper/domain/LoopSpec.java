package io.opencode.loopper.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record LoopSpec(
        String schemaVersion,
        @NotBlank String projectId,
        @NotBlank @Size(max = 12_000) String goal,
        @Size(max = 12_000) String context,
        @NotEmpty @Size(max = 64) List<@Valid StageSpec> stages,
        @Valid Limits limits,
        @Valid ModelSpec model,
        @Valid SessionPolicy sessionPolicy,
        @Size(max = 4_000) String nextAttemptPromptTemplate,
        @Valid BudgetSpec budget) {
    public LoopSpec(String schemaVersion, String projectId, String goal, String context,
                    List<StageSpec> stages, Limits limits, ModelSpec model,
                    SessionPolicy sessionPolicy, String nextAttemptPromptTemplate) {
        this(schemaVersion, projectId, goal, context, stages, limits, model, sessionPolicy,
                nextAttemptPromptTemplate, null);
    }

    public LoopSpec {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "v1" : schemaVersion.trim();
        context = context == null ? "" : context;
        stages = immutable(stages);
        limits = limits == null ? Limits.defaults() : limits;
        model = model == null ? new ModelSpec(null, null, null) : model;
        sessionPolicy = sessionPolicy == null ? SessionPolicy.defaults() : sessionPolicy;
        budget = budget == null ? BudgetSpec.unlimited() : budget;
    }
    public record StageSpec(
            @NotBlank @Size(max = 4_000) String objective,
            @Size(max = 64) List<@Size(max = 512) String> allowedPaths,
            @Size(max = 64) List<@Size(max = 512) String> forbiddenPaths,
            @Size(max = 64) List<@Size(max = 2_000) String> deliverables,
            @Size(max = 32) List<@Valid VerifierSpec> verifiers,
            @Size(max = 64) List<@Valid AcceptanceCriterion> acceptanceCriteria,
            @Valid VerificationRuntime verificationRuntime,
            ImplementationKind implementationKind) {
        public StageSpec(String objective, List<String> allowedPaths, List<String> forbiddenPaths,
                         List<String> deliverables, List<VerifierSpec> verifiers) {
            this(objective, allowedPaths, forbiddenPaths, deliverables, verifiers, null, null, null);
        }
        public StageSpec(String objective, List<String> allowedPaths, List<String> forbiddenPaths,
                         List<String> deliverables, List<VerifierSpec> verifiers,
                         List<AcceptanceCriterion> acceptanceCriteria,
                         VerificationRuntime verificationRuntime) {
            this(objective, allowedPaths, forbiddenPaths, deliverables, verifiers,
                    acceptanceCriteria, verificationRuntime, null);
        }
        public StageSpec {
            allowedPaths = immutable(allowedPaths);
            forbiddenPaths = immutable(forbiddenPaths);
            deliverables = immutable(deliverables);
            verifiers = immutable(verifiers);
            acceptanceCriteria = immutable(acceptanceCriteria);
        }
    }

    public record AcceptanceCriterion(@NotBlank @Size(max = 64) String id,
                                      @NotBlank @Size(max = 2_000) String description,
                                      @Size(max = 32) String verificationMode,
                                      @Size(max = 4_000) String judgeRubric,
                                      @Size(max = 2_000) String judgeOnlyReason) {
        public AcceptanceCriterion(String id, String description) {
            this(id, description, null, null, null);
        }
        public AcceptanceCriterion {
            id = id == null ? null : id.trim();
            description = description == null ? null : description.trim();
            verificationMode = blankToNull(verificationMode);
            verificationMode = verificationMode == null ? "MACHINE" : verificationMode.toUpperCase(Locale.ROOT);
            judgeRubric = blankToNull(judgeRubric);
            judgeOnlyReason = blankToNull(judgeOnlyReason);
        }
    }

    public record VerificationRuntime(
            @Size(max = 64) List<@NotBlank @Size(max = 2_048) String> startCommand,
            @Valid RuntimeReadiness readiness,
            @Min(1) @Max(300) Integer startupTimeoutSeconds,
            @Min(1) @Max(60) Integer shutdownTimeoutSeconds) {
        public VerificationRuntime {
            startCommand = immutable(startCommand);
            startupTimeoutSeconds = startupTimeoutSeconds == null ? 60 : startupTimeoutSeconds;
            shutdownTimeoutSeconds = shutdownTimeoutSeconds == null ? 10 : shutdownTimeoutSeconds;
        }
    }

    public record RuntimeReadiness(
            @NotBlank @Size(max = 1_024) String path,
            @Min(100) @Max(599) Integer expectedStatus,
            @Size(max = 1_024) String jsonPath,
            @Size(max = 4_000) String expectedValue,
            @Size(max = 32) String matchMode) {
        public RuntimeReadiness {
            path = path == null ? null : path.trim();
            expectedStatus = expectedStatus == null ? 200 : expectedStatus;
            jsonPath = blankToNull(jsonPath);
            expectedValue = blankToNull(expectedValue);
            matchMode = blankToNull(matchMode);
            matchMode = matchMode == null ? null : matchMode.toUpperCase();
        }
    }

    public record VerifierSpec(@NotBlank String type,
                               @JsonAlias("argv")
                               @Size(max = 64) List<@NotBlank @Size(max = 2_048) String> command,
                               String path,
                               Boolean requireChanges,
                               @Size(max = 64) List<@Size(max = 512) String> allowedPaths,
                               @Size(max = 64) List<@Size(max = 512) String> forbiddenPaths,
                               Boolean forbidDeletes,
                               @Size(max = 4_000) String outputContains,
                               @Size(max = 2_048) String url,
                               @Size(max = 16) String httpMethod,
                               @Min(100) @Max(599) Integer expectedStatus,
                               @Size(max = 1_024) String jsonPath,
                               @Size(max = 4_000) String expectedValue,
                               @Size(max = 32) String matchMode,
                               @Size(max = 4_000) String expectedContent,
                               @Size(min = 64, max = 64) String expectedSha256,
                               @Size(max = 16_000) String sql,
                               @Min(0) Integer expectedRowCount,
                               @Size(max = 64) List<@Valid BrowserAssertion> assertions,
                               @Size(max = 64) List<@NotBlank @Size(max = 64) String> criterionIds,
                               @Size(max = 32) String processPurpose,
                               @Size(max = 64) List<@NotBlank @Size(max = 512) String> testTargets) {
        public VerifierSpec(String type, List<String> command, String path, Boolean requireChanges,
                            List<String> allowedPaths, List<String> forbiddenPaths, Boolean forbidDeletes,
                            String outputContains, String url, String httpMethod, Integer expectedStatus,
                            String jsonPath, String expectedValue, String matchMode, String expectedContent,
                            String expectedSha256, String sql, Integer expectedRowCount,
                            List<BrowserAssertion> assertions) {
            this(type, command, path, requireChanges, allowedPaths, forbiddenPaths, forbidDeletes,
                    outputContains, url, httpMethod, expectedStatus, jsonPath, expectedValue, matchMode,
                    expectedContent, expectedSha256, sql, expectedRowCount, assertions, null, null, null);
        }

        public VerifierSpec(String type, List<String> command, String path, Boolean requireChanges,
                            List<String> allowedPaths, List<String> forbiddenPaths, Boolean forbidDeletes,
                            String outputContains) {
            this(type, command, path, requireChanges, allowedPaths, forbiddenPaths, forbidDeletes,
                    outputContains, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null);
        }

        public VerifierSpec(String type, List<String> command, String path, Boolean requireChanges,
                            List<String> allowedPaths, List<String> forbiddenPaths, Boolean forbidDeletes) {
            this(type, command, path, requireChanges, allowedPaths, forbiddenPaths, forbidDeletes, null,
                    null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null);
        }

        public VerifierSpec {
            type = type == null ? null : type.trim().toUpperCase();
            command = immutable(command);
            allowedPaths = immutable(allowedPaths);
            forbiddenPaths = immutable(forbiddenPaths);
            outputContains = blankToNull(outputContains);
            url = blankToNull(url);
            httpMethod = blankToNull(httpMethod);
            httpMethod = httpMethod == null ? null : httpMethod.toUpperCase();
            jsonPath = blankToNull(jsonPath);
            expectedValue = blankToNull(expectedValue);
            matchMode = blankToNull(matchMode);
            matchMode = matchMode == null ? null : matchMode.toUpperCase();
            expectedContent = blankPreservingToNull(expectedContent);
            expectedSha256 = blankToNull(expectedSha256);
            expectedSha256 = expectedSha256 == null ? null : expectedSha256.toLowerCase();
            sql = blankToNull(sql);
            assertions = immutable(assertions);
            criterionIds = immutable(criterionIds);
            processPurpose = blankToNull(processPurpose);
            processPurpose = processPurpose == null ? null : processPurpose.toUpperCase();
            testTargets = immutable(testTargets);
        }
    }

    public record BrowserAssertion(@NotBlank String type,
                                   @NotBlank @Size(max = 1_024) String selector,
                                   @Size(max = 4_000) String value,
                                   @Size(max = 256) String attribute,
                                   @Min(0) Integer expectedCount) {
        public BrowserAssertion {
            type = type == null ? null : type.trim().toUpperCase();
            selector = selector == null ? null : selector.trim();
            value = blankToNull(value);
            attribute = blankToNull(attribute);
        }
    }

    public record Limits(@Min(1) @Max(20) Integer maxStageAttempts,
                         @Min(1) @Max(100) Integer maxTaskAttempts,
                         @Min(1) @Max(20) Integer sessionErrorLimit,
                         @Min(1) @Max(20) Integer stagnationLimit,
                         @Min(1) @Max(604800) Long maxDurationSeconds,
                         @Min(1) @Max(86400) Long attemptTimeoutSeconds,
                         @Min(1) @Max(3600) Long verifierTimeoutSeconds) {
        public Limits {
            maxStageAttempts = maxStageAttempts == null ? 3 : maxStageAttempts;
            maxTaskAttempts = maxTaskAttempts == null ? 12 : maxTaskAttempts;
            sessionErrorLimit = sessionErrorLimit == null ? 3 : sessionErrorLimit;
            stagnationLimit = stagnationLimit == null ? 2 : stagnationLimit;
            maxDurationSeconds = maxDurationSeconds == null ? 7200L : maxDurationSeconds;
            attemptTimeoutSeconds = attemptTimeoutSeconds == null ? 1800L : attemptTimeoutSeconds;
            verifierTimeoutSeconds = verifierTimeoutSeconds == null ? 600L : verifierTimeoutSeconds;
        }
        public static Limits defaults() { return new Limits(3, 12, 3, 2, 7200L, 1800L, 600L); }
    }

    public record ModelSpec(String providerId, String modelId, Boolean thinking) {
        public ModelSpec {
            providerId = blankToNull(providerId);
            modelId = blankToNull(modelId);
        }
    }

    public record SessionPolicy(Boolean reuseHealthySession, Boolean createFreshOnVerifierFailure) {
        public SessionPolicy {
            reuseHealthySession = reuseHealthySession == null || reuseHealthySession;
            createFreshOnVerifierFailure = createFreshOnVerifierFailure == null || createFreshOnVerifierFailure;
        }
        public static SessionPolicy defaults() { return new SessionPolicy(true, true); }
    }

    /** Soft limits are enforced only from reliable provider usage. Null values mean unknown/unlimited. */
    public record BudgetSpec(@Min(1) Long maxTotalTokens,
                             @Size(max = 64) String maxCostAmount,
                             @Size(max = 16) String currency) {
        public BudgetSpec {
            maxCostAmount = blankToNull(maxCostAmount);
            currency = blankToNull(currency);
            currency = currency == null ? null : currency.toUpperCase();
        }
        public static BudgetSpec unlimited() { return new BudgetSpec(null, null, null); }
    }

    private static <T> List<T> immutable(List<T> input) {
        return input == null ? List.of() : input.stream().filter(Objects::nonNull).toList();
    }
    private static String blankToNull(String input) {
        return input == null || input.isBlank() ? null : input.trim();
    }
    private static String blankPreservingToNull(String input) {
        return input == null || input.isBlank() ? null : input;
    }
}
