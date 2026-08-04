package io.opencode.loopper.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Objects;

public record LoopSpec(
        String schemaVersion,
        @NotBlank String projectId,
        @NotBlank String goal,
        String context,
        @NotEmpty List<@Valid StageSpec> stages,
        @Valid Limits limits,
        @Valid ModelSpec model,
        @Valid SessionPolicy sessionPolicy,
        String nextAttemptPromptTemplate) {
    public LoopSpec {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "v1" : schemaVersion.trim();
        context = context == null ? "" : context;
        stages = immutable(stages);
        limits = limits == null ? Limits.defaults() : limits;
        model = model == null ? new ModelSpec(null, null, null) : model;
        sessionPolicy = sessionPolicy == null ? SessionPolicy.defaults() : sessionPolicy;
    }
    public record StageSpec(
            @NotBlank String objective,
            List<String> allowedPaths,
            List<String> forbiddenPaths,
            List<String> deliverables,
            List<@Valid VerifierSpec> verifiers) {
        public StageSpec {
            allowedPaths = immutable(allowedPaths);
            forbiddenPaths = immutable(forbiddenPaths);
            deliverables = immutable(deliverables);
            verifiers = immutable(verifiers);
        }
    }

    public record VerifierSpec(@NotBlank String type, List<String> command, String path,
                               Boolean requireChanges, List<String> allowedPaths,
                               List<String> forbiddenPaths, Boolean forbidDeletes) {
        public VerifierSpec {
            type = type == null ? null : type.trim().toUpperCase();
            command = immutable(command);
            allowedPaths = immutable(allowedPaths);
            forbiddenPaths = immutable(forbiddenPaths);
        }
    }

    public record Limits(@Min(1) Integer maxStageAttempts, @Min(1) Integer maxTaskAttempts,
                         @Min(1) Integer sessionErrorLimit, @Min(1) Integer stagnationLimit,
                         @Min(1) Long maxDurationSeconds, @Min(1) Long attemptTimeoutSeconds,
                         @Min(1) Long verifierTimeoutSeconds) {
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

    private static <T> List<T> immutable(List<T> input) {
        return input == null ? List.of() : input.stream().filter(Objects::nonNull).toList();
    }
    private static String blankToNull(String input) {
        return input == null || input.isBlank() ? null : input.trim();
    }
}
