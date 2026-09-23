package io.opencode.loopper.template;

import java.util.List;

/** Independent executable contracts; never inherit report V7 completion semantics. */
public enum DocumentTemplateDefinition {
    REQUIREMENT_DEVELOPMENT("需求开发", "根据需求文档完成代码开发、测试和验收", "软件开发", "lucide:code-xml"),
    REQUIREMENT_CODE_REVIEW("需求代码评审", "核对分支代码的需求完整性、正确性和质量问题", "代码质量", "lucide:clipboard-check");

    public static final String VERSION = "3";
    private final String title;
    private final String description;
    private final String category;
    private final String icon;
    DocumentTemplateDefinition(String title, String description, String category, String icon) {
        this.title = title; this.description = description; this.category = category; this.icon = icon;
    }
    public String title() { return title; }
    public boolean review() { return this == REQUIREMENT_CODE_REVIEW; }
    public View view() {
        return new View(name(), VERSION, title, description, category, icon,
                new Inputs(true, review(), false, List.of("docx", "md", "markdown", "pdf"), 10, 20, 50),
                review() ? "STATIC_REQUIREMENT_REVIEW" : "CURRENT_DIRECTORY_DEVELOPMENT");
    }
    public record Inputs(boolean documents, boolean branch, boolean dates, List<String> extensions,
                         int maxFiles, int maxFileMiB, int maxTotalMiB,
                         boolean sourcePath, boolean testOutputPath, boolean documentOutputPath) {
        public Inputs(boolean documents, boolean branch, boolean dates, List<String> extensions,
                      int maxFiles, int maxFileMiB, int maxTotalMiB) {
            this(documents, branch, dates, extensions, maxFiles, maxFileMiB, maxTotalMiB, false, false, !documents);
        }
    }
    public record View(String id, String version, String title, String description, String category,
                       String icon, Inputs inputs, String workflow) { }
}
