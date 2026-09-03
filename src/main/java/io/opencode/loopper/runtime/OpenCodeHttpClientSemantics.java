package io.opencode.loopper.runtime;

import java.net.URI;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.web.client.RestClientResponseException;

/** Stateless classification and compatibility rules shared by the HTTP adapter. */
final class OpenCodeHttpClientSemantics {
    private OpenCodeHttpClientSemantics() { }

    static boolean isDeepSeek(OpenCodeClient.OpenCodeModel model) {
        return model != null && model.providerId() != null
                && "deepseek".equalsIgnoreCase(model.providerId().trim());
    }

    static boolean machineResponseProfile(OpenCodeClient.SessionProfile profile) {
        return profile == OpenCodeClient.SessionProfile.DECOMPOSER_CANDIDATE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.ROLLING_PACKAGE_CANDIDATE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.REVIEWER_CANDIDATE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.PROJECT_CONVENTION_CANDIDATE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.JUDGE_CANDIDATE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.DECOMPOSER_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.COMPILER_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.COMPILER_BINDING_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.COMPILER_REPAIR_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.REVIEWER_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.JUDGE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.JUDGE_FINALIZER_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.PROJECT_CONVENTION_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.MACHINE_FINALIZER_NO_TOOLS;
    }

    static boolean candidateProfile(OpenCodeClient.SessionProfile profile) {
        return profile == OpenCodeClient.SessionProfile.DECOMPOSER_CANDIDATE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.PACKAGE_DESIGN_CANDIDATE_INTERACTIVE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS
                || profile == OpenCodeClient.SessionProfile.ROLLING_PACKAGE_CANDIDATE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.REVIEWER_CANDIDATE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.PROJECT_CONVENTION_CANDIDATE_READ_ONLY
                || profile == OpenCodeClient.SessionProfile.JUDGE_CANDIDATE_READ_ONLY;
    }

    static boolean formatRejected(RestClientResponseException failure) {
        int status = failure.getStatusCode().value();
        if (status != 400 && status != 404 && status != 415 && status != 422) return false;
        String body = failure.getResponseBodyAsString();
        String detail = (failure.getMessage() + " " + (body == null ? "" : body)).toLowerCase(Locale.ROOT);
        return detail.contains("format") || detail.contains("json_schema") || detail.contains("schema");
    }

    static boolean structuredError(String type, String detail) {
        String value = ((type == null ? "" : type) + " " + (detail == null ? "" : detail))
                .toLowerCase(Locale.ROOT);
        return value.contains("structuredoutput") || value.contains("structured_output")
                || value.contains("json schema") || value.contains("json_schema");
    }

    static OpenCodeRuntimeManager.RuntimeIdentity externalIdentity(URI endpoint) {
        return new OpenCodeRuntimeManager.RuntimeIdentity(endpoint, false, null, null);
    }

    static Supplier<OpenCodeRuntimeManager.RuntimeIdentity> unavailableLocalIdentity() {
        return () -> { throw new IllegalStateException(
                "No no-I/O OpenCode runtime identity supplier was configured"); };
    }
}
