package io.opencode.loopper.service;

import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.MutationMode;
import io.opencode.loopper.domain.ProjectStackProfileState;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.domain.WorkflowTemplate;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Combines immutable project evidence with bounded requirement and semantic labels. */
@Component
public final class TaskProfileRouter {
    static final int AUTO_ROUTE_CONFIDENCE = 80;
    private static final Pattern JAVA = Pattern.compile(
            "(?<![a-z0-9])(java|jdk|junit|jupiter|surefire|spring|maven|gradle|kotlin)(?![a-z0-9])|\\.java(?![a-z0-9])");
    private static final Pattern NODE = Pattern.compile(
            "(?<![a-z0-9])(javascript|typescript|node(?:\\.js)?|npm|pnpm|yarn|vue|react|vite|vitest|frontend)(?![a-z0-9])|前端");
    private static final Pattern PYTHON = Pattern.compile(
            "(?<![a-z0-9])(python(?:3)?|pytest|unittest|django|flask|fastapi)(?![a-z0-9])|\\.py(?![a-z0-9])");
    private static final Pattern LOCAL_MAINTENANCE = Pattern.compile(
            "(?:^|[。！？；;：:\\n\\r])\\s*(?:[-*#>]\\s*)*(?:(?:请|需要|帮我)\\s*)?"
                    + "(?:(?:安全|本地|日常|简单|常规)(?:配置|依赖)?维护|(?:配置|依赖)维护|依赖升级)"
                    + "|(?:修改|更新|调整|修复|升级).{0,12}(?:配置(?:文件|项|值|参数|版本|[\\s，。；;]|$)|依赖版本)"
                    + "|(?:配置(?:文件|项|值|参数|版本)|依赖版本).{0,12}(?:修改|更新|调整|修复|升级)");
    private final ProjectStackAnalyzer analyzer;
    private final TaskProfileSafetyPolicy safetyPolicy;
    private final TaskProfileIntentPolicy intentPolicy;

    public TaskProfileRouter(ProjectStackAnalyzer analyzer) {
        this.analyzer = analyzer;
        this.safetyPolicy = new TaskProfileSafetyPolicy();
        this.intentPolicy = new TaskProfileIntentPolicy();
    }
    public TaskProfileRouter() { this(new ProjectStackAnalyzer()); }

    public Decision route(Path root, String requirement) { return route(ephemeral(root), requirement); }
    public Decision route(Path root, String requirement, SemanticLabels semantic) {
        return route(ephemeral(root), requirement, semantic);
    }
    public Decision route(ProjectStackSnapshot profile, String requirement) {
        StackSelection selection = select(profile, requirement);
        return deterministic(requirement, profile, selection);
    }

    public Decision route(ProjectStackSnapshot profile, String requirement, SemanticLabels semantic) {
        if (semantic == null) return genericFallback(profile, requirement, "router-output-unavailable");
        StackSelection selection = select(profile, requirement);
        Decision deterministic = deterministic(requirement, profile, selection);
        boolean unsafe = deterministic.evidence().contains("unsafe-operation-conflict");
        boolean artifactConflict = !artifactsCompatible(semantic.intent(), semantic.artifactKinds());
        boolean intentConflict = semantic.intent() != deterministic.intent()
                && deterministic.confidence() >= AUTO_ROUTE_CONFIDENCE;
        boolean conflict = intentConflict || artifactConflict
                || deterministic.evidence().contains("mixed-mutation-conflict");
        TaskIntent intent = unsafe ? deterministic.intent() : semantic.intent();
        List<ArtifactKind> artifacts = semantic.artifactKinds().isEmpty()
                ? deterministic.artifactKinds() : semantic.artifactKinds();
        boolean packaged = "PACKAGED".equals(semantic.complexity())
                || deterministic.workflowTemplate() == WorkflowTemplate.PACKAGED_ARTIFACT;
        WorkflowTemplate workflow = workflow(intent, packaged);
        MutationMode mutation = mutation(intent);
        boolean componentSelectionRequired = requiresComponentSelection(intent, selection);
        boolean semanticAgreement = semantic.intent() == deterministic.intent() && !artifactConflict;
        int confidence = semanticAgreement
                ? Math.min(100, deterministic.confidence() + 10)
                : deterministic.confidence();
        if (conflict) confidence = Math.min(confidence, 69);
        if (unsafe) confidence = Math.min(confidence, 50);
        if (componentSelectionRequired) confidence = Math.min(confidence, 69);
        List<String> evidence = new ArrayList<>(selection.evidence());
        deterministic.evidence().stream()
                .filter(value -> value.startsWith("requirement-tests=")
                        || value.equals("mixed-mutation-conflict") || value.equals("unsafe-operation-conflict"))
                .filter(value -> !evidence.contains(value)).forEach(evidence::add);
        evidence.add("ai-router-intent=" + semantic.intent().name());
        evidence.add("ai-router-complexity=" + semantic.complexity());
        evidence.add("ai-router-contract=TASK_PROFILE_ROUTER_V2");
        if (intent == TaskIntent.SOFTWARE_CHANGE && packaged) evidence.add("large-task-recommended");
        if (conflict) evidence.add("router-evidence-conflict=" + deterministic.intent().name());
        if (artifactConflict) evidence.add("router-artifact-conflict=" + semantic.intent().name());
        return decision(profile, selection, intent, workflow, mutation, artifacts, confidence,
                confidence < AUTO_ROUTE_CONFIDENCE || conflict || unsafe || componentSelectionRequired, evidence);
    }

    public Decision genericFallback(Path root, String reason) {
        return genericFallback(ephemeral(root), "", reason);
    }
    public Decision genericFallback(ProjectStackSnapshot profile, String requirement, String reason) {
        StackSelection selection = select(profile, requirement);
        List<String> evidence = new ArrayList<>(selection.evidence());
        evidence.add("router-fallback=" + reason);
        return decision(profile, selection, TaskIntent.SOFTWARE_CHANGE, WorkflowTemplate.DIRECT_SOFTWARE_DESIGN,
                MutationMode.WRITE_CODE, List.of(ArtifactKind.SOURCE_CODE), 0, true, evidence);
    }

    private Decision deterministic(String requirement, ProjectStackSnapshot profile, StackSelection selection) {
        String text = requirement == null ? "" : requirement.toLowerCase(Locale.ROOT);
        boolean unsafe = safetyPolicy.requestsUnsafeOperation(text);
        List<ArtifactKind> artifacts = new ArrayList<>();
        TaskIntent intent;
        WorkflowTemplate workflow;
        MutationMode mutation;
        int confidence;
        boolean readOnlyReview = intentPolicy.requestsReadOnlyReview(text);
        if (readOnlyReview) {
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
        } else if (LOCAL_MAINTENANCE.matcher(text).find() && !unsafe) {
            intent = TaskIntent.LOCAL_MAINTENANCE; workflow = WorkflowTemplate.LOCAL_MAINTENANCE;
            mutation = MutationMode.SAFE_LOCAL_MAINTENANCE; artifacts.add(ArtifactKind.CONFIGURATION); confidence = 82;
        } else {
            intent = TaskIntent.SOFTWARE_CHANGE; workflow = WorkflowTemplate.DIRECT_SOFTWARE_DESIGN;
            mutation = MutationMode.WRITE_CODE; artifacts.add(text.contains("python") || text.contains("py脚本")
                    ? ArtifactKind.PYTHON_SCRIPT : ArtifactKind.SOURCE_CODE);
            confidence = selection.technologies().isEmpty() && explicitFamilies(text).isEmpty() ? 65 : 86;
        }
        List<String> evidence = new ArrayList<>(selection.evidence());
        evidence.add("requirement-intent=" + intent.name());
        if (containsAny(text, "必须测试", "需要测试", "编写测试", "补充测试", "新增测试", "单元测试",
                "with tests", "add tests")) evidence.add("requirement-tests=required");
        if (unsafe) { confidence = Math.min(confidence, 50); evidence.add("unsafe-operation-conflict"); }
        boolean readAndWrite = readOnlyReview && intentPolicy.requestsMutation(text);
        if (readAndWrite) { confidence = Math.min(confidence, 60); evidence.add("mixed-mutation-conflict"); }
        boolean componentSelectionRequired = requiresComponentSelection(intent, selection);
        if (componentSelectionRequired) confidence = Math.min(confidence, 69);
        return decision(profile, selection, intent, workflow, mutation, artifacts, confidence,
                confidence < AUTO_ROUTE_CONFIDENCE || unsafe || componentSelectionRequired, evidence);
    }

    private StackSelection select(ProjectStackSnapshot profile, String requirement) {
        String text = requirement == null ? "" : requirement.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> requestedFamilies = new LinkedHashSet<>(explicitFamilies(text));
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (ProjectStackSnapshot.Component component : profile.components()) {
            if (mentionsComponent(text, component)
                    || component.technologyFamilies().stream().anyMatch(requestedFamilies::contains)) {
                selected.add(component.key());
            }
        }
        LinkedHashSet<String> projectFamilies = new LinkedHashSet<>(profile.technologyFamilies());
        boolean ambiguous = selected.isEmpty() && projectFamilies.size() > 1;
        if (selected.isEmpty() && projectFamilies.size() == 1) {
            profile.components().forEach(component -> selected.add(component.key()));
        }
        LinkedHashSet<String> technologies = new LinkedHashSet<>();
        profile.components().stream().filter(component -> selected.contains(component.key()))
                .forEach(component -> technologies.addAll(component.technologies()));
        // Technology selection is server-owned and may only use repository evidence or explicit requirement text.
        if (profile.components().isEmpty()) explicitTechnologies(text).forEach(technologies::add);
        List<String> evidence = new ArrayList<>(profile.evidence());
        if (profile.id() != null) evidence.add("stack-profile=" + profile.id());
        if (profile.manifestFingerprint() != null) evidence.add("stack-fingerprint=" + profile.manifestFingerprint());
        selected.forEach(key -> evidence.add("component=" + key));
        profile.components().stream().filter(component -> selected.contains(component.key()))
                .flatMap(component -> component.testFrameworks().stream()).distinct()
                .forEach(framework -> evidence.add("test-framework=" + framework));
        if (ambiguous) evidence.add("component-selection-ambiguous");
        if (profile.components().isEmpty() && technologies.isEmpty()) evidence.add("stack-evidence-empty");
        if (profile.state() == ProjectStackProfileState.PARTIAL) evidence.add("stack-profile-partial");
        if (profile.state() == ProjectStackProfileState.FAILED) evidence.add("stack-profile-failed");
        boolean required = ambiguous || profile.state() == ProjectStackProfileState.PARTIAL
                || profile.state() == ProjectStackProfileState.FAILED
                || profile.components().isEmpty() && technologies.isEmpty();
        return new StackSelection(List.copyOf(selected), List.copyOf(technologies), required, List.copyOf(evidence));
    }

    private static boolean mentionsComponent(String text, ProjectStackSnapshot.Component component) {
        if (".".equals(component.relativeRoot())) return false;
        String root = component.relativeRoot().toLowerCase(Locale.ROOT);
        String name = root.substring(root.lastIndexOf('/') + 1);
        return text.contains("`" + root + "`") || text.contains(root + "/")
                || Pattern.compile("(?<![a-z0-9_-])" + Pattern.quote(name) + "(?![a-z0-9_-])")
                        .matcher(text).find();
    }

    private static List<String> explicitFamilies(String text) {
        List<String> result = new ArrayList<>();
        if (JAVA.matcher(text).find()) result.add("java");
        if (NODE.matcher(text).find()) result.add("node");
        if (PYTHON.matcher(text).find()) result.add("python");
        if (Pattern.compile("(?<![a-z0-9])(go|golang|rust|cargo)(?![a-z0-9])").matcher(text).find()) result.add("other");
        return result;
    }

    private static List<String> explicitTechnologies(String text) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (JAVA.matcher(text).find()) values.add("java");
        if (NODE.matcher(text).find()) values.add("node");
        if (PYTHON.matcher(text).find()) values.add("python");
        if (Pattern.compile("(?<![a-z0-9])(go|golang)(?![a-z0-9])").matcher(text).find()) values.add("go");
        if (Pattern.compile("(?<![a-z0-9])(rust|cargo)(?![a-z0-9])").matcher(text).find()) values.add("rust");
        return List.copyOf(values);
    }

    private ProjectStackSnapshot ephemeral(Path root) {
        ProjectStackAnalyzer.Analysis analysis = analyzer.analyze(root);
        List<ProjectStackSnapshot.Component> components = analysis.components().stream()
                .map(component -> new ProjectStackSnapshot.Component(component.key(), component.relativeRoot(),
                        component.technologyFamilies(), component.technologies(), component.buildTools(),
                        component.testFrameworks(), component.manifestSources(), component.evidence())).toList();
        return new ProjectStackSnapshot(null, null, analysis.state(), analysis.manifestFingerprint(),
                analysis.technologyFamilies(), analysis.technologies(), analysis.evidence(), analysis.filesScanned(),
                analysis.errorCode(), analysis.errorDetail(), null, components);
    }

    private static Decision decision(ProjectStackSnapshot profile, StackSelection selection, TaskIntent intent,
                                     WorkflowTemplate workflow, MutationMode mutation, List<ArtifactKind> artifacts,
                                     int confidence, boolean required, List<String> evidence) {
        return new Decision(intent, workflow, mutation, List.copyOf(artifacts), selection.technologies(), confidence,
                required, List.copyOf(evidence), profile.id(), profile.manifestFingerprint(),
                selection.componentKeys(), profile.components(), profile.state().name());
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
    private static boolean requiresComponentSelection(TaskIntent intent, StackSelection selection) {
        return (intent == TaskIntent.SOFTWARE_CHANGE || intent == TaskIntent.LEGACY_SOFTWARE)
                && selection.decisionRequired();
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
    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }
    private static long markdownSectionCount(String requirement) {
        if (requirement == null || requirement.isBlank()) return 0;
        return requirement.lines().map(String::strip).filter(line -> line.matches("^##\\s+.+")).count();
    }
    private record StackSelection(List<String> componentKeys, List<String> technologies,
                                  boolean decisionRequired, List<String> evidence) { }
    public record Decision(TaskIntent intent, WorkflowTemplate workflowTemplate, MutationMode mutationMode,
                           List<ArtifactKind> artifactKinds, List<String> technologies, int confidence,
                           boolean decisionRequired, List<String> evidence, String projectStackProfileId,
                           String stackFingerprint, List<String> componentKeys,
                           List<ProjectStackSnapshot.Component> availableComponents, String stackProfileState) {
        public Decision(TaskIntent intent, WorkflowTemplate workflowTemplate, MutationMode mutationMode,
                        List<ArtifactKind> artifactKinds, List<String> technologies, int confidence,
                        boolean decisionRequired, List<String> evidence) {
            this(intent, workflowTemplate, mutationMode, artifactKinds, technologies, confidence,
                    decisionRequired, evidence, null, null, List.of(), List.of(), "UNANALYZED");
        }
    }
    public record SemanticLabels(TaskIntent intent, List<ArtifactKind> artifactKinds, String complexity) {
        public SemanticLabels {
            artifactKinds = artifactKinds == null ? List.of() : List.copyOf(artifactKinds);
        }
    }
}
