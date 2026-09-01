package io.opencode.loopper.service;

import io.opencode.loopper.persistence.GenericCandidateInternalLaunchRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict lossless codec for a frozen V57 generic candidate create plan. */
@Component
final class GenericCandidateInternalLaunchPlanCodec {
    private final ObjectMapper json;
    private final ObjectMapper strictJson;

    GenericCandidateInternalLaunchPlanCodec(ObjectMapper json) {
        this.json = Objects.requireNonNull(json);
        this.strictJson = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    }

    OpenCodeClient.SessionCreationPlan decode(GenericCandidateInternalLaunchRow row) {
        try {
            requireIdentity(row);
            List<OpenCodeClient.SessionPermissionRule> permissions = permissions(row.permissionPolicyJson());
            OpenCodeClient.OpenCodeModel model = model(row.modelProviderId(), row.modelId(), row.thinking());
            OpenCodeClient.SessionCreationPlan plan = OpenCodeClient.SessionCreationPlan.fromPersisted(
                    Path.of(row.canonicalDirectory()), row.exactTitle(), row.runtimeGenerationId(), row.managed(),
                    row.internalMcpServer(), row.endpointFingerprint(), model,
                    OpenCodeClient.SessionProfile.valueOf(row.profile()), permissions,
                    row.permissionPolicyDigest(), row.creationCredential(), row.createRequestSha256());
            if (!GenericCandidateInternalLaunchPreparer.baseTitle(
                    io.opencode.loopper.domain.MachineCandidateKind.valueOf(row.candidateKind()),
                    row.candidateRunId(), row.id()).equals(stripCredential(plan.exactTitle(),
                            plan.creationCredential()))
                    || !hasFailClosedCandidatePermission(plan)) throw invalid();
            return plan;
        } catch (RuntimeException invalid) {
            throw invalid();
        }
    }

    void validatePreparedPlan(String baseTitle, OpenCodeClient.OpenCodeModel model,
            OpenCodeClient.SessionProfile profile, OpenCodeClient.SessionCreationPlan plan) {
        try {
            if (plan == null || profile == null || plan.profile() != profile || !plan.managed()
                    || !Objects.equals(model, plan.model())
                    || !OpenCodeClient.recoveryTitle(baseTitle, plan.creationCredential())
                            .equals(plan.exactTitle())
                    || !OpenCodeClient.permissionPolicyDigest(plan.permissionPolicy())
                            .equals(plan.permissionPolicyDigest())
                    || !OpenCodeClient.sessionCreationRequestSha256(plan).equals(plan.createRequestSha256())
                    || !hasFailClosedCandidatePermission(plan)) throw invalid();
        } catch (RuntimeException invalid) {
            throw invalid();
        }
    }

    String encodePermissionPolicy(OpenCodeClient.SessionCreationPlan plan) {
        try { return json.writeValueAsString(plan.permissionPolicy()); }
        catch (RuntimeException invalid) { throw invalid(); }
    }

    private List<OpenCodeClient.SessionPermissionRule> permissions(String value) {
        try {
            JsonNode root = strictJson.readTree(value);
            if (root == null || !root.isArray()) throw invalid();
            List<OpenCodeClient.SessionPermissionRule> result = new ArrayList<>();
            for (JsonNode item : root) {
                if (item == null || !item.isObject()) throw invalid();
                Set<String> fields = new LinkedHashSet<>();
                item.properties().forEach(entry -> fields.add(entry.getKey()));
                if (!fields.equals(Set.of("permission", "pattern", "action"))) throw invalid();
                result.add(new OpenCodeClient.SessionPermissionRule(
                        text(item, "permission"), text(item, "pattern"), text(item, "action")));
            }
            return List.copyOf(result);
        } catch (RuntimeException invalid) {
            throw invalid();
        }
    }

    private static boolean hasFailClosedCandidatePermission(OpenCodeClient.SessionCreationPlan plan) {
        String submit = plan.internalMcpServer().replaceAll("[^a-zA-Z0-9_-]", "_") + "_submit_candidate";
        return plan.permissionPolicy().contains(new OpenCodeClient.SessionPermissionRule("*", "*", "deny"))
                && plan.permissionPolicy().contains(
                        new OpenCodeClient.SessionPermissionRule("external_directory", "*", "deny"))
                && plan.permissionPolicy().contains(
                        new OpenCodeClient.SessionPermissionRule(submit, "*", "allow"));
    }

    private static void requireIdentity(GenericCandidateInternalLaunchRow row) {
        if (row == null || !row.managed() || !"LOCAL_REQUEST_ATTESTED".equals(row.attestationType())
                || !Objects.equals(row.candidateKind(), row.workflowStep())
                || !Objects.equals(row.candidateKind(), row.contractVersion())) throw invalid();
    }

    private static OpenCodeClient.OpenCodeModel model(String provider, String id, Boolean thinking) {
        if ((provider == null) != (id == null) || provider != null && (provider.isBlank() || id.isBlank())
                || provider == null && thinking != null) throw invalid();
        return provider == null ? null : new OpenCodeClient.OpenCodeModel(provider, id, thinking);
    }

    private static String stripCredential(String title, String credential) {
        String suffix = " [loopper-create:" + credential + "]";
        if (title == null || !title.endsWith(suffix)) throw invalid();
        return title.substring(0, title.length() - suffix.length());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private static ConflictException invalid() {
        return new ConflictException("GENERIC_CANDIDATE_INTERNAL_LAUNCH_PLAN_INVALID",
                "通用候选 internal launch 的冻结 create plan 无效");
    }
}
