package io.opencode.loopper.service;

import io.opencode.loopper.domain.ModelResponseMode;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeStructuredSchemas;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Owns structured/marker prompt transport while attaching the allowed Designer context scope. */
final class DesignerModelPromptTransport {
    private final OpenCodeClient openCode;
    private final DesignerAttachmentContext attachments;
    private final ObjectMapper json;

    DesignerModelPromptTransport(OpenCodeClient openCode, DesignerAttachmentContext attachments, ObjectMapper json) {
        this.openCode = openCode;
        this.attachments = attachments;
        this.json = json;
    }

    void submit(OpenCodeClient.OpenCodeSession remote, String prompt, String responseMode, String schemaId,
            String designerSessionId, String workPackageId) {
        OpenCodeClient.PromptRequest request = ModelResponseMode.JSON_SCHEMA.name().equals(responseMode)
                && schemaId != null && !schemaId.isBlank()
                ? new OpenCodeClient.PromptRequest(prompt, null, null, OpenCodeStructuredSchemas.format(schemaId))
                : OpenCodeClient.PromptRequest.text(prompt);
        DesignerAttachmentContext.ContextUse use = workPackageId == null || workPackageId.isBlank()
                ? DesignerAttachmentContext.ContextUse.requirement(designerSessionId)
                : DesignerAttachmentContext.ContextUse.workPackage(designerSessionId, workPackageId);
        openCode.promptAsync(remote, attachments.withContext(use, request));
    }

    String responseOutput(OpenCodeClient.OpenCodeSession remote, String responseMode) {
        if (!ModelResponseMode.JSON_SCHEMA.name().equals(responseMode)) return openCode.sessionOutput(remote);
        OpenCodeClient.SessionResult result = openCode.sessionResult(remote);
        if (result.structuredRetryCount() != 0) throw new SessionFailure(
                "OPENCODE_STRUCTURED_RETRY_UNEXPECTED", "OpenCode performed an unbudgeted structured-output retry");
        if (result.hasStructured()) {
            try { return json.writeValueAsString(result.structured()); }
            catch (JacksonException failure) {
                throw new IllegalStateException("Unable to serialize design workflow", failure);
            }
        }
        String detail = result.errorDetail() != null && !result.errorDetail().isBlank() ? result.errorDetail()
                : result.errorType() != null && !result.errorType().isBlank() ? result.errorType()
                : "OpenCode completed without the requested structured object";
        throw new SessionFailure("OPENCODE_STRUCTURED_OUTPUT_FAILED", detail);
    }
}
