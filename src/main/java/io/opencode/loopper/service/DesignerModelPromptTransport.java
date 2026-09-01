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
        openCode.promptAsync(remote, prepare(prompt, responseMode, schemaId,
                designerSessionId, workPackageId, null).request());
    }

    PreparedPrompt prepare(String prompt, String responseMode, String schemaId,
            String designerSessionId, String workPackageId, String messageId) {
        OpenCodeClient.PromptRequest request = ModelResponseMode.JSON_SCHEMA.name().equals(responseMode)
                && schemaId != null && !schemaId.isBlank()
                ? new OpenCodeClient.PromptRequest(prompt, null, null, OpenCodeStructuredSchemas.format(schemaId))
                : OpenCodeClient.PromptRequest.text(prompt);
        request = new OpenCodeClient.PromptRequest(request.text(), request.system(), request.agent(),
                request.responseFormat(), messageId, request.files());
        DesignerAttachmentContext.ContextUse use = workPackageId == null || workPackageId.isBlank()
                ? DesignerAttachmentContext.ContextUse.requirement(designerSessionId)
                : DesignerAttachmentContext.ContextUse.workPackage(designerSessionId, workPackageId);
        OpenCodeClient.PromptRequest contextual = attachments.withContext(use, request);
        return new PreparedPrompt(contextual, OpenCodeClient.promptRequestSha256(contextual));
    }

    OpenCodeClient.MessageLookup lookupPrompt(OpenCodeClient.OpenCodeSession remote, PreparedPrompt prompt) {
        OpenCodeClient.MessageLookup lookup = openCode.findPromptMessage(
                remote, prompt.request(), prompt.sha256());
        if (!lookup.supported()) {
            throw new SessionFailure("OPENCODE_PROMPT_LOOKUP_UNAVAILABLE",
                    "OpenCode cannot recover a deterministic prompt acknowledgement");
        }
        if (lookup.exists() && !prompt.sha256().equals(lookup.verifiedRequestSha256())) {
            throw new SessionFailure("OPENCODE_PROMPT_REQUEST_STALE",
                    "OpenCode prompt acknowledgement does not match the frozen request");
        }
        return lookup;
    }

    void dispatchPrompt(OpenCodeClient.OpenCodeSession remote, PreparedPrompt prompt) {
        openCode.promptAsync(remote, prompt.request());
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

    record PreparedPrompt(OpenCodeClient.PromptRequest request, String sha256) { }

}
