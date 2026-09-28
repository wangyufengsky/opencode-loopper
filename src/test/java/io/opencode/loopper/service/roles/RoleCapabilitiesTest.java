package io.opencode.loopper.service.roles;

import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodePermissionPolicy;
import io.opencode.loopper.service.assist.AssistToolCatalog;
import io.opencode.loopper.service.knowledge.WorkflowKnowledgePolicy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.opencode.loopper.service.roles.RoleCapabilities.Capability.*;
import static org.assertj.core.api.Assertions.*;

class RoleCapabilitiesTest {
    @Test void unknownAdaptersDoNotInheritNamedOrDefaultPrivileges() {
        for (String name : Arrays.asList(null, "CUSTOM_REVIEWER", "GENERAL_NEW", "IMPLEMENTATION_NEW")) {
            assertThat(RoleCapabilities.profile(name)).isEmpty();
            assertThat(AssistToolCatalog.allowed(name)).isEmpty();
            assertThat(WorkflowKnowledgePolicy.supports(name)).isFalse();
        }
        assertThat(AssistToolCatalog.knowledgeTool("unregistered_knowledge_writer")).isFalse();
        assertThat(AssistToolCatalog.knowledgeTool("internal_assist_search_project_knowledge")).isTrue();
        assertThat(AssistToolCatalog.knowledgeTool("@loopper-assist/read_knowledge_source")).isTrue();
        assertThat(RoleCapabilities.profiles()).hasSize(OpenCodeClient.SessionProfile.values().length + 1);
    }
    @Test void allExistingAdaptersRetainKnowledgeAndSqlBoundaries() {
        var excluded = Set.of("ROUTER_NO_TOOLS", "MACHINE_FINALIZER_NO_TOOLS", "JUDGE_FINALIZER_NO_TOOLS",
                "COMPILER_BINDING_NO_TOOLS", "COMPILER_REPAIR_NO_TOOLS", "ACCEPTANCE_CLOSED_CHOICE_CANDIDATE_NO_TOOLS", "PPT_AGENT");
        for (var profile : OpenCodeClient.SessionProfile.values()) {
            String name = profile.name();
            boolean external = Set.of("GENERAL_READ_ONLY", "IMPLEMENTATION", "DECOMPOSER_READ_ONLY",
                    "DESIGNER_INTERACTIVE_READ_ONLY", "COMPILER_READ_ONLY", "PROJECT_CONVENTION_READ_ONLY").contains(name);
            assertThat(RoleCapabilities.has(name, EXTERNAL_MCP)).as(name).isEqualTo(external);
            boolean shared = !name.startsWith("KNOWLEDGE_") && !excluded.contains(name);
            assertThat(WorkflowKnowledgePolicy.supports(name)).as(name).isEqualTo(shared);
            boolean oldEvidenceOnly = name.contains("JUDGE") || name.contains("REVIEWER") || name.contains("NO_TOOLS") || name.startsWith("PROJECT_CONVENTION_");
            if (shared) assertThat(WorkflowKnowledgePolicy.evidenceOnly(name)).as(name).isEqualTo(oldEvidenceOnly);
            var expected = AssistToolCatalog.tools().stream().filter(tool -> {
                if (RoleCapabilities.assist(tool.name()) == PROJECT_KNOWLEDGE)
                    return shared || name.startsWith("KNOWLEDGE_");
                if (name.contains("NO_TOOLS") && !name.startsWith("TEMPLATE_ANALYSIS") || name.startsWith("PROJECT_CONVENTION_")) return false;
                if (name.startsWith("KNOWLEDGE_")) return RoleCapabilities.assist(tool.name()) == DATABASE_READ;
                return (!tool.writes() || name.equals("IMPLEMENTATION"))
                        && (!(name.contains("JUDGE") || name.contains("REVIEWER")) || RoleCapabilities.assist(tool.name()) == TASK_EVIDENCE);
            }).map(AssistToolCatalog.Tool::name).toList();
            // PPT has a private protocol and never exposes auxiliary tools at runtime.
            if (profile == OpenCodeClient.SessionProfile.PPT_AGENT) expected = List.of();
            assertThat(AssistToolCatalog.allowed(name)).as(name).containsExactlyElementsOf(expected);
        }
        assertThat(AssistToolCatalog.tools()).allSatisfy(tool -> assertThat(RoleCapabilities.assist(tool.name())).as(tool.name()).isNotNull());
    }
    @Test void builtinsDeclareCapabilitiesAndPreserveEveryEffectiveGrant() throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream("roles/builtin.yaml");
        var document = new RoleArchive().parseYaml(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        assertThat(document.schemaVersion()).isEqualTo(2);
        for (var role : document.roles()) {
            assertThat(role.capabilities()).isNotNull();
            if (role.roleId().equals("builtin.implementation")) {
                var knowledge = AssistToolCatalog.tools().stream().map(AssistToolCatalog.Tool::name)
                        .filter(name -> RoleCapabilities.assist(name) == PROJECT_KNOWLEDGE)
                        .map(name -> "@loopper-assist/" + name).toList();
                assertThat(role.mcpTools()).containsAll(knowledge);
            }
            for (String slot : role.allowedSlots()) {
                String profile = document.slots().stream().filter(item -> item.slot().equals(slot)).findFirst().orElseThrow().adapterProfile();
                if (profile.equals("ACCOUNTING_COMMAND")) continue;
                var baseline = OpenCodePermissionPolicy.previewRules(OpenCodeClient.SessionProfile.valueOf(profile), List.of(), "internal");
                if (role.permissionMode().equals("BASELINE")) {
                    assertThat(RoleCapabilities.narrow(baseline, profile, role.capabilities(), "internal")).as(slot).isEqualTo(baseline);
                    assertThat(RoleCapabilities.effective(profile, role.capabilities())).as(slot).containsAll(RoleCapabilities.profile(profile));
                }
            }
        }
    }
    @Test void explicitEmptyClosesImplicitImplementationButKeepsPrivateProtocolAndLegacyRules() {
        var baseline = List.of(rule("todowrite", "allow"), rule("internal_submit_candidate", "allow"), rule("external_*", "allow"), rule("internal_assist_query_database_readonly", "allow"));
        assertThat(RoleCapabilities.narrow(baseline, "IMPLEMENTATION", null, "internal")).isSameAs(baseline);
        var narrowed = RoleCapabilities.narrow(baseline, "IMPLEMENTATION", List.of(), "internal");
        assertThat(narrowed).contains(rule("*", "deny"), rule("todowrite", "deny"), rule("external_*", "deny"), rule("internal_assist_query_database_readonly", "deny"), rule("internal_submit_candidate", "allow"));
        assertThat(RoleCapabilities.effective("JUDGE_CANDIDATE_READ_ONLY", List.of(DATABASE_READ, PROJECT_KNOWLEDGE))).containsExactly(PROJECT_KNOWLEDGE);
    }
    @Test void capabilityNarrowingCannotReintroduceNativeToolsRemovedByIntersection() {
        var baseline = List.of(rule("*", "deny"), rule("read", "allow"), rule("bash", "ask"));
        var narrowed = RoleCapabilities.narrow(baseline, "IMPLEMENTATION", List.of(NATIVE_TOOLS), "internal");
        assertThat(narrowed).doesNotContain(rule("edit", "allow"), rule("write", "allow"));
        var closed = RoleCapabilities.narrow(baseline, "IMPLEMENTATION", List.of(), "internal");
        assertThat(closed).contains(rule("bash", "deny")).doesNotContain(rule("bash", "ask"));
        var implicit = RoleCapabilities.narrow(List.of(rule("todowrite", "allow")), "IMPLEMENTATION", List.of(NATIVE_TOOLS), "internal");
        assertThat(implicit).contains(new OpenCodeClient.SessionPermissionRule("read", ".env", "deny"));
    }
    private static OpenCodeClient.SessionPermissionRule rule(String name, String action) {
        return new OpenCodeClient.SessionPermissionRule(name, "*", action);
    }
}
