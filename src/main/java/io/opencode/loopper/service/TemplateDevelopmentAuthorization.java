package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DocumentDesignContextMapper;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** Shared admission query; each origin retains its own immutable source tables and scope grants. */
final class TemplateDevelopmentAuthorization {
    private static final ObjectMapper JSON = new ObjectMapper();
    private TemplateDevelopmentAuthorization() { }
    static boolean designer(DocumentDesignContextMapper mapper, String id) {
        return mapper.documentDesigner(id) || mapper.sourceDevelopmentDesigner(id).isPresent();
    }
    static boolean design(DocumentDesignContextMapper mapper, String revision, String designer) {
        return mapper.documentDesign(revision, designer).isPresent()
                || mapper.sourceDevelopmentDesign(revision, designer).isPresent();
    }
    static Optional<String> contract(DocumentDesignContextMapper mapper, String designer) {
        var document = mapper.documentDesignerContract(designer);
        return document.isPresent() ? document : mapper.sourceDevelopmentDesigner(designer).map(row -> row.contractJson());
    }
    static String model(DocumentDesignContextMapper mapper, String designer, String fallback) {
        return contract(mapper, designer).map(value -> JSON.readTree(value).path("model").asText()).orElse(fallback);
    }
    static boolean planCurrent(DocumentDesignContextMapper mapper, String designer, String plan) {
        return mapper.sourceDevelopmentDesigner(designer).isPresent() ? mapper.sourceDevelopmentPlan(plan).isPresent()
                : mapper.documentPlanSourceCurrent(plan);
    }
    static boolean planning(DocumentDesignContextMapper mapper, String task) {
        var source = mapper.sourceDevelopmentTask(task);
        return source.isPresent() ? source.get().state().equals("EXECUTING")
                : mapper.documentTaskState(task).filter("DESIGNING"::equals).isPresent();
    }
    static Duration timeout(DocumentDesignContextMapper mapper, String designer, Duration fallback) {
        var contract = contract(mapper, designer);
        if (contract.isEmpty()) return fallback;
        var value = JSON.readTree(contract.get());
        return value.path("timeoutEnabled").asBoolean(true) ? Duration.ofSeconds(value.path("attemptTimeoutSeconds").asLong()) : null;
    }
}
