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
        String text = requirement == null ? "" : requirement.toLowerCase(Locale.ROOT);
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
        } else if (containsAny(text, "docx", "markdown", "文档", "说明书", "报告")
                && !containsAny(text, "代码", "接口", "程序", "脚本", "工具", "命令行", "cli", "python")) {
            intent = TaskIntent.DOCUMENT_AUTHORING;
            workflow = containsAny(text, "大型", "多章节", "整本")
                    ? WorkflowTemplate.PACKAGED_ARTIFACT : WorkflowTemplate.DIRECT_ARTIFACT;
            mutation = MutationMode.WRITE_FILES;
            artifacts.add(text.contains("docx") ? ArtifactKind.DOCX : ArtifactKind.MARKDOWN); confidence = 88;
        } else if (containsAny(text, "配置", "依赖升级", "维护") && !containsAny(text, "删除", "服务", "推送", "发布")) {
            intent = TaskIntent.LOCAL_MAINTENANCE; workflow = WorkflowTemplate.LOCAL_MAINTENANCE;
            mutation = MutationMode.SAFE_LOCAL_MAINTENANCE; artifacts.add(ArtifactKind.CONFIGURATION); confidence = 82;
        } else {
            intent = TaskIntent.SOFTWARE_CHANGE; workflow = WorkflowTemplate.FULL_PACKAGE_DESIGN;
            mutation = MutationMode.WRITE_CODE; artifacts.add(text.contains("python") || text.contains("py脚本")
                    ? ArtifactKind.PYTHON_SCRIPT : ArtifactKind.SOURCE_CODE);
            if ((text.contains("python") || text.contains("py脚本")) && !technologies.contains("python")) technologies.add("python");
            confidence = facts.technologies().isEmpty() && !containsAny(text, "java", "python", "node", "vue", "脚本", "代码") ? 65 : 86;
        }
        boolean unsafe = containsAny(text, "删除文件", "rm ", "启动服务", "停止服务", "重启服务", "提交", "推送", "发布", "外部系统");
        List<String> evidence = new ArrayList<>(facts.evidence());
        evidence.add("requirement-intent=" + intent.name());
        if (unsafe) { confidence = Math.min(confidence, 50); evidence.add("unsafe-operation-conflict"); }
        return new Decision(intent, workflow, mutation, List.copyOf(artifacts), List.copyOf(technologies),
                confidence, confidence < AUTO_ROUTE_CONFIDENCE || unsafe, List.copyOf(evidence));
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
                    if (name.equals("pytest.ini") || name.equals("conftest.py") || name.startsWith("test_") && name.endsWith(".py")) {
                        technologies.add("python"); evidence.add("test-framework=pytest");
                    } else if (name.endsWith(".py") && file.toString().replace('\\', '/').contains("/tests/")) {
                        technologies.add("python"); evidence.add("test-framework=unittest");
                    }
                    if (name.equals("package.json")) technologies.add("node");
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

    record RepositoryFacts(List<String> technologies, List<String> evidence) { }
    public record Decision(TaskIntent intent, WorkflowTemplate workflowTemplate, MutationMode mutationMode,
                           List<ArtifactKind> artifactKinds, List<String> technologies, int confidence,
                           boolean decisionRequired, List<String> evidence) { }
}
