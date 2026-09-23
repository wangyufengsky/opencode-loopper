package io.opencode.loopper.template;

import java.util.List;

/** Complete role candidates; coverage and source references are independently checked by the server. */
public final class SourceDesign {
    private SourceDesign() { }
    public record Input(String manifestSha256, List<String> paths, String requirements,
                        String draftModelId, String draftSha256, String feedback) { }
    public record Candidate(String title, String summary, List<Section> sections, List<String> limitations) { }
    public record Section(String key, String title, String markdown, List<String> paths, List<Reference> references) { }
    public record Reference(String path, String sha256, int startLine, int endLine, String quote) { }
    public record Review(String verdict, String reason, List<String> checkedPaths,
                         List<Reference> references, List<Issue> issues) { }
    public record Issue(String sectionKey, String detail, String recommendation) { }
    public record Plan(List<Batch> batches) { }
    public record Batch(int ordinal, String title, List<String> paths) { }
}
