package io.opencode.loopper.template;

/** Local UI requests never supply model, execution permissions or snapshot identities. */
public final class SourceTemplateRequests {
    private SourceTemplateRequests() { }
    public record Create(String requestKey, String templateId, String templateVersion, String projectId,
                         String sourcePath, String testOutputPath, String documentPath, String requirements) { }
    public record Command(String requestKey, long expectedVersion) { }
}
