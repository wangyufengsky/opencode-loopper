package io.opencode.loopper.service;

import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.MutationMode;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.WorkflowTemplate;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Bounded, symlink-free fact scan followed by server-owned deterministic routing. */
@Component
public final class TaskProfileRouter {
    static final int AUTO_ROUTE_CONFIDENCE = 80;
    private static final Set<String> SKIP = Set.of(".git", "target", "node_modules", "dist", "data");
    private static final int MAX_FILES = 2_000;
    private static final int MAX_DEPTH = 5;

    public Decision route(Path root, String requirement) {
        RepositoryFacts facts = scan(root);
        return deterministic(requirement, facts);
    }

    public Decision route(Path root, String requirement, SemanticLabels semantic) {
        RepositoryFacts facts = scan(root);
        Decision deterministic = deterministic(requirement, facts);
        if (semantic == null) return genericFallback(facts, "router-output-unavailable");
        boolean unsafe = deterministic.evidence().contains("unsafe-operation-conflict");
        boolean artifactConflict = !artifactsCompatible(semantic.intent(), semantic.artifactKinds());
        boolean conflict = semantic.intent() != deterministic.intent()
                && deterministic.confidence() >= AUTO_ROUTE_CONFIDENCE || artifactConflict
                || deterministic.evidence().contains("mixed-mutation-conflict");
        TaskIntent intent = unsafe ? deterministic.intent() : semantic.intent();
        List<String> technologies = new ArrayList<>(facts.technologies());
        semantic.technologies().forEach(value -> { if (!technologies.contains(value)) technologies.add(value); });
        List<ArtifactKind> artifacts = semantic.artifactKinds().isEmpty()
                ? deterministic.artifactKinds() : semantic.artifactKinds();
        boolean packaged = "PACKAGED".equals(semantic.complexity())
                || deterministic.workflowTemplate() == WorkflowTemplate.PACKAGED_ARTIFACT;
        WorkflowTemplate workflow = workflow(intent, packaged);
        MutationMode mutation = mutation(intent);
        int confidence = Math.min(100, (semantic.confidence() + deterministic.confidence()) / 2
                + (semantic.intent() == deterministic.intent() ? 10 : 0));
        if (conflict) confidence = Math.min(confidence, 69);
        if (unsafe) confidence = Math.min(confidence, 50);
        List<String> evidence = new ArrayList<>(facts.evidence());
        deterministic.evidence().stream()
                .filter(value -> value.startsWith("requirement-tests=")
                        || value.equals("mixed-mutation-conflict"))
                .forEach(evidence::add);
        evidence.add("ai-router-intent=" + semantic.intent().name());
        evidence.add("ai-router-complexity=" + semantic.complexity());
        if (intent == TaskIntent.SOFTWARE_CHANGE && packaged) evidence.add("large-task-recommended");
        semantic.signals().forEach(value -> evidence.add("ai-router-signal=" + value));
        if (conflict) evidence.add("router-evidence-conflict=" + deterministic.intent().name());
        if (artifactConflict) evidence.add("router-artifact-conflict=" + semantic.intent().name());
        if (unsafe) evidence.add("unsafe-operation-conflict");
        return new Decision(intent, workflow, mutation, List.copyOf(artifacts), List.copyOf(technologies),
                confidence, confidence < AUTO_ROUTE_CONFIDENCE || conflict || unsafe, List.copyOf(evidence));
    }

    public Decision genericFallback(Path root, String reason) {
        return genericFallback(scan(root), reason);
    }

    private Decision deterministic(String requirement, RepositoryFacts facts) {
        String text = requirement == null ? "" : requirement.toLowerCase(Locale.ROOT);
        boolean unsafe = unsafeOperationRequested(text);
        List<ArtifactKind> artifacts = new ArrayList<>();
        List<String> technologies = new ArrayList<>(facts.technologies());
        TaskIntent intent;
        WorkflowTemplate workflow;
        MutationMode mutation;
        int confidence;
        if (containsAny(text, "评审", "review", "检查代码", "诊断", "只读报告")) {
            intent = TaskIntent.READ_ONLY_REVIEW; workflow = WorkflowTemplate.READ_ONLY_REPORT;
            mutation = MutationMode.READ_ONLY; artifacts.add(ArtifactKind.ANALYSIS_REPORT); confidence = 92;
        } else if (containsAny(text, "调研", "research", "调查报告")) {
            intent = TaskIntent.RESEARCH; workflow = WorkflowTemplate.READ_ONLY_REPORT;
            mutation = MutationMode.READ_ONLY; artifacts.add(ArtifactKind.ANALYSIS_REPORT); confidence = 88;
        } else if (containsAny(text, "xlsx", "excel", "csv", "tsv")
                && containsAny(text, "转换", "转成", "导出") && !containsAny(text, "脚本", "程序", "工具")) {
            intent = TaskIntent.DATA_CONVERSION; workflow = WorkflowTemplate.DIRECT_ARTIFACT;
            mutation = MutationMode.WRITE_FILES; artifacts.add(ArtifactKind.MARKDOWN); confidence = 95;
        } else if (containsAny(text, "docx", "markdown", ".md", "文档", "说明书", "手册", "报告")
                && !containsAny(text, "代码", "接口", "程序", "脚本", "工具", "命令行", "cli", "python")) {
            intent = TaskIntent.DOCUMENT_AUTHORING;
            workflow = containsAny(text, "大型", "多章节", "整本") || markdownSectionCount(requirement) >= 2
                    ? WorkflowTemplate.PACKAGED_ARTIFACT : WorkflowTemplate.DIRECT_ARTIFACT;
            mutation = MutationMode.WRITE_FILES;
            artifacts.add(text.contains("docx") ? ArtifactKind.DOCX : ArtifactKind.MARKDOWN); confidence = 88;
        } else if (containsAny(text, "配置", "依赖升级", "维护") && !unsafe) {
            intent = TaskIntent.LOCAL_MAINTENANCE; workflow = WorkflowTemplate.LOCAL_MAINTENANCE;
            mutation = MutationMode.SAFE_LOCAL_MAINTENANCE; artifacts.add(ArtifactKind.CONFIGURATION); confidence = 82;
        } else {
            intent = TaskIntent.SOFTWARE_CHANGE; workflow = WorkflowTemplate.DIRECT_SOFTWARE_DESIGN;
            mutation = MutationMode.WRITE_CODE; artifacts.add(text.contains("python") || text.contains("py脚本")
                    ? ArtifactKind.PYTHON_SCRIPT : ArtifactKind.SOURCE_CODE);
            if ((text.contains("python") || text.contains("py脚本")) && !technologies.contains("python")) technologies.add("python");
            confidence = facts.technologies().isEmpty() && !containsAny(text, "java", "python", "node", "vue", "脚本", "代码") ? 65 : 86;
        }
        List<String> evidence = new ArrayList<>(facts.evidence());
        evidence.add("requirement-intent=" + intent.name());
        if (containsAny(text, "必须测试", "需要测试", "编写测试", "补充测试", "with tests", "add tests")) {
            evidence.add("requirement-tests=required");
        }
        if (unsafe) { confidence = Math.min(confidence, 50); evidence.add("unsafe-operation-conflict"); }
        boolean readAndWrite = containsAny(text, "评审", "review", "诊断", "只读")
                && containsAny(text, "修改", "修复", "新增", "写入", "生成文件", "implement", "fix ");
        if (readAndWrite) {
            confidence = Math.min(confidence, 60);
            evidence.add("mixed-mutation-conflict");
        }
        return new Decision(intent, workflow, mutation, List.copyOf(artifacts), List.copyOf(technologies),
                confidence, confidence < AUTO_ROUTE_CONFIDENCE || unsafe, List.copyOf(evidence));
    }

    private Decision genericFallback(RepositoryFacts facts, String reason) {
        List<String> evidence = new ArrayList<>(facts.evidence());
        evidence.add("router-fallback=" + reason);
        return new Decision(TaskIntent.SOFTWARE_CHANGE, WorkflowTemplate.DIRECT_SOFTWARE_DESIGN,
                MutationMode.WRITE_CODE, List.of(ArtifactKind.SOURCE_CODE), facts.technologies(),
                0, true, List.copyOf(evidence));
    }

    private static WorkflowTemplate workflow(TaskIntent intent, boolean packaged) {
        return switch (intent) {
            case DOCUMENT_AUTHORING -> packaged ? WorkflowTemplate.PACKAGED_ARTIFACT : WorkflowTemplate.DIRECT_ARTIFACT;
            case DATA_CONVERSION -> WorkflowTemplate.DIRECT_ARTIFACT;
            case READ_ONLY_REVIEW, RESEARCH -> WorkflowTemplate.READ_ONLY_REPORT;
            case CONFIGURATION, LOCAL_MAINTENANCE -> packaged ? WorkflowTemplate.FULL_PACKAGE_DESIGN : WorkflowTemplate.LOCAL_MAINTENANCE;
            default -> WorkflowTemplate.DIRECT_SOFTWARE_DESIGN;
        };
    }

    private static MutationMode mutation(TaskIntent intent) {
        return switch (intent) {
            case DOCUMENT_AUTHORING, DATA_CONVERSION -> MutationMode.WRITE_FILES;
            case READ_ONLY_REVIEW, RESEARCH -> MutationMode.READ_ONLY;
            case CONFIGURATION, LOCAL_MAINTENANCE -> MutationMode.SAFE_LOCAL_MAINTENANCE;
            default -> MutationMode.WRITE_CODE;
        };
    }

    private static boolean artifactsCompatible(TaskIntent intent, List<ArtifactKind> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) return false;
        Set<ArtifactKind> allowed = switch (intent) {
            case READ_ONLY_REVIEW, RESEARCH -> Set.of(ArtifactKind.ANALYSIS_REPORT);
            case DOCUMENT_AUTHORING -> Set.of(ArtifactKind.MARKDOWN, ArtifactKind.DOCX);
            case DATA_CONVERSION -> Set.of(ArtifactKind.MARKDOWN, ArtifactKind.XLSX, ArtifactKind.CSV, ArtifactKind.TSV);
            case CONFIGURATION, LOCAL_MAINTENANCE -> Set.of(ArtifactKind.CONFIGURATION, ArtifactKind.MARKDOWN);
            default -> Set.of(ArtifactKind.SOURCE_CODE, ArtifactKind.PYTHON_SCRIPT, ArtifactKind.CONFIGURATION,
                    ArtifactKind.MARKDOWN, ArtifactKind.OTHER);
        };
        return artifacts.stream().allMatch(allowed::contains);
    }

    RepositoryFacts scan(Path root) {
        Path canonical = root.toAbsolutePath().normalize();
        Set<String> technologies = new LinkedHashSet<>();
        List<String> evidence = new ArrayList<>();
        int[] count = {0};
        try {
            Files.walkFileTree(canonical, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(canonical) && (SKIP.contains(dir.getFileName().toString()) || Files.isSymbolicLink(dir)))
                        return FileVisitResult.SKIP_SUBTREE;
                    return count[0] >= MAX_FILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++count[0] > MAX_FILES || Files.isSymbolicLink(file) || !attrs.isRegularFile())
                        return count[0] > MAX_FILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts")) technologies.add("java");
                    if (name.equals("pyproject.toml") || name.equals("requirements.txt") || name.equals("pytest.ini")) technologies.add("python");
                    if (name.equals("go.mod")) technologies.add("go");
                    if (name.equals("cargo.toml")) technologies.add("rust");
                    if (name.equals("pytest.ini") || name.equals("conftest.py") || name.startsWith("test_") && name.endsWith(".py")) {
                        technologies.add("python"); evidence.add("test-framework=pytest");
                    } else if (name.endsWith(".py") && file.toString().replace('\\', '/').contains("/tests/")) {
                        technologies.add("python"); evidence.add("test-framework=unittest");
                    }
                    if (name.equals("package.json")) {
                        technologies.add("node");
                        try {
                            if (attrs.size() <= 512_000 && Files.readString(file).matches("(?s).*\\\"test[^\\\"]*\\\"\\s*:.*")) {
                                evidence.add("test-framework=npm");
                            }
                        } catch (IOException ignored) { }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            evidence.add("scan-error=" + failure.getClass().getSimpleName());
        }
        technologies.forEach(value -> evidence.add("manifest=" + value));
        evidence.add("files-scanned=" + Math.min(count[0], MAX_FILES));
        return new RepositoryFacts(List.copyOf(technologies), List.copyOf(evidence));
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private static long markdownSectionCount(String requirement) {
        if (requirement == null || requirement.isBlank()) return 0;
        return requirement.lines().map(String::strip).filter(line -> line.matches("^##\\s+.+")).count();
    }

    private static boolean releaseOperation(String text) {
        if (!text.contains("发布")) return containsAny(text, "上线部署", "执行 release", "create release");
        return !containsAny(text, "发布边界", "事件发布", "发布事件", "发布能力", "发布订阅");
    }

    private static boolean unsafeOperationRequested(String text) {
        String positive = text;
        for (String negated : List.of(
                "不删除文件", "不得删除文件", "禁止删除文件",
                "不操作服务", "不得操作服务", "禁止操作服务",
                "不启动服务", "不得启动服务", "禁止启动服务",
                "不停止服务", "不得停止服务", "禁止停止服务",
                "不重启服务", "不得重启服务", "禁止重启服务",
                "不提交推送或发布", "不得提交推送或发布", "禁止提交推送或发布",
                "不提交代码", "不得提交代码", "禁止提交代码",
                "不创建提交", "不得创建提交", "禁止创建提交",
                "不推送", "不得推送", "禁止推送",
                "不发布", "不得发布", "禁止发布",
                "不写入外部系统", "不得写入外部系统", "禁止写入外部系统")) {
            positive = positive.replace(negated, "");
        }
        return containsAny(positive, "删除文件", "rm ", "启动服务", "停止服务", "重启服务", "推送", "外部系统")
                || containsAny(positive, "git 提交", "提交代码", "创建提交", "commit ")
                || releaseOperation(positive);
    }

    record RepositoryFacts(List<String> technologies, List<String> evidence) { }
    public record Decision(TaskIntent intent, WorkflowTemplate workflowTemplate, MutationMode mutationMode,
                           List<ArtifactKind> artifactKinds, List<String> technologies, int confidence,
                           boolean decisionRequired, List<String> evidence) { }
    public record SemanticLabels(TaskIntent intent, List<ArtifactKind> artifactKinds, List<String> technologies,
                                 String complexity, int confidence, List<String> signals) {
        public SemanticLabels {
            artifactKinds = artifactKinds == null ? List.of() : List.copyOf(artifactKinds);
            technologies = technologies == null ? List.of() : List.copyOf(technologies);
            signals = signals == null ? List.of() : List.copyOf(signals);
        }
    }
}
