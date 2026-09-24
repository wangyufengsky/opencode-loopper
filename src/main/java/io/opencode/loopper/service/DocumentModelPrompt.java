package io.opencode.loopper.service;

import io.opencode.loopper.service.roles.RolePromptResources;
import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.DocumentTemplateModelRow;
import io.opencode.loopper.runtime.InternalMcpContractCatalog;
import io.opencode.loopper.runtime.DocumentTemplateProfiles;
import io.opencode.loopper.template.DocumentModelInput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class DocumentModelPrompt {
    private final ObjectMapper json;
    @Autowired(required = false) private RoleSessions roleSessions;
    public DocumentModelPrompt(ObjectMapper json) { this.json = json; }
    public String build(DocumentTemplateModelRow row, String serverName) {
        var profile = DocumentTemplateProfiles.profile(MachineCandidateKind.valueOf(row.candidateKind()));
        return RoleSessions.render(roleSessions, "DOCUMENT_TEMPLATE_RUN", row.runId(), profile, null,
                () -> buildUnscoped(row, serverName));
    }

    private String buildUnscoped(DocumentTemplateModelRow row, String serverName) {
        var kind = MachineCandidateKind.valueOf(row.candidateKind());
        var input = json.readValue(row.inputJson(), DocumentModelInput.class);
        String instruction = switch (kind) {
            case DOCUMENT_REQUIREMENTS_V1 -> RolePromptResources.read("prompt.v1.DocumentModelPrompt.block01");
            case DOCUMENT_REQUIREMENT_REVIEW_V1 -> RolePromptResources.read("prompt.v1.DocumentModelPrompt.block02");
            case REQUIREMENT_CODE_ASSESSMENT_V1, DOCUMENT_CODE_ASSESSMENT_V2 -> RolePromptResources.read("prompt.v1.DocumentModelPrompt.block03");
            case REQUIREMENT_ASSESSMENT_REVIEW_V1, DOCUMENT_CODE_REVIEW_V2 -> RolePromptResources.read("prompt.v1.DocumentModelPrompt.block04");
            default -> throw new IllegalArgumentException("Unsupported document role");
        };
        if (!input.clarifications().isEmpty()) instruction += RolePromptResources.read("prompt.v1.DocumentModelPrompt.clarification-guidance");
        if (kind == MachineCandidateKind.DOCUMENT_CODE_ASSESSMENT_V2 || kind == MachineCandidateKind.DOCUMENT_CODE_REVIEW_V2) {
            instruction += RolePromptResources.read("prompt.v1.DocumentModelPrompt.direct-assessment-guidance")
                    + "本批条目 requirementKey 使用 RQ-" + (row.ordinal() * 256 + 1) + " 至 RQ-" + ((row.ordinal() + 1) * 256) + RolePromptResources.read("prompt.v1.DocumentModelPrompt.direct-assessment-review-guidance")
                    + "原文资源入口：loopper-document://review/" + row.id() + "/index/0；也可使用原文 MCP 读取工具。";
        }
        instruction += RolePromptResources.read("prompt.v1.DocumentModelPrompt.submit-schema-guidance");
        if (input.interactionVersion() >= 1) instruction += RolePromptResources.read("prompt.v1.DocumentModelPrompt.interactive-review-guidance");
        return (RolePromptResources.read("prompt.v1.DocumentModelPrompt.block05.segment0")
                + String.format("%s", (Object) (instruction))
                + "\n候选运行 ID: "
                + String.format("%s", (Object) (row.id()))
                + "\n提交工具: "
                + String.format("%s", (Object) (serverName))
                + "_"
                + String.format("%s", (Object) (InternalMcpContractCatalog.toolName(kind)))
                + RolePromptResources.read("prompt.v1.DocumentModelPrompt.block05.segment4")
                + String.format("%s", (Object) (json.writeValueAsString(input)))
                + "\n");
    }
}
