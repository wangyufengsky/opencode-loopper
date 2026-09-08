package io.opencode.loopper.service;

import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.ExecutionStrategy;
import io.opencode.loopper.domain.ImplementationKind;
import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.StageKind;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.persistence.ArtifactPlanRow;
import io.opencode.loopper.persistence.DesignDiscussionRevisionRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.verification.ArtifactMaterializationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Compiles a confirmed simple artifact discussion into one implicit WP-1 without an AI Decomposer. */
@Service
public class DirectArtifactDesignService {
    private static final Pattern BACKTICK_PATH = Pattern.compile("`([^`]+\\.(?:md|markdown|docx|xlsx|csv|tsv))`", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_PATH = Pattern.compile("(?<![\\w./-])([\\w./-]+\\.(?:md|markdown|docx|xlsx|csv|tsv))(?![\\w./-])", Pattern.CASE_INSENSITIVE);
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final LoopDraftService drafts;
    private final ArtifactMaterializationService artifacts;

    public DirectArtifactDesignService(LoopperMapper mapper, ProjectService projects, LoopDraftService drafts,
                                       ArtifactMaterializationService artifacts) {
        this.mapper = mapper;
        this.projects = projects;
        this.drafts = drafts;
        this.artifacts = artifacts;
    }

    public Result compile(String sessionId, TaskProfileService.View profile) {
        DesignerSessionRow session = mapper.findDesignerSession(sessionId)
                .orElseThrow(() -> new NotFoundException("Designer session not found: " + sessionId));
        if (profile.id() == null || !"FROZEN".equals(profile.state())) {
            throw new ConflictException("TASK_PROFILE_NOT_FROZEN", "直接制品方案只能从冻结任务画像生成");
        }
        DesignDiscussionRevisionRow discussion = mapper.findLatestDesignDiscussionRevision(sessionId, "REQUIREMENT")
                .orElseThrow(() -> new ConflictException("REQUIREMENT_DISCUSSION_MISSING", "需求讨论快照不存在"));
        if (!"REVIEWING".equals(discussion.state()) || discussion.snapshotMarkdown() == null
                || discussion.snapshotMarkdown().isBlank()) {
            throw new ConflictException("REQUIREMENT_DISCUSSION_INCOMPLETE", "请先回答任务问题并等待完整需求稿");
        }
        LoopDraftRow draft = drafts.get(session.loopDraftId());
        ProjectRow project = projects.get(session.projectId());
        return profile.intent() == TaskIntent.DATA_CONVERSION
                ? compileTabular(session, profile, discussion.snapshotMarkdown(), draft, project)
                : compileDocument(profile, discussion.snapshotMarkdown(), draft);
    }

    public Result compilePackagedDocument(String sessionId, TaskProfileService.View profile) {
        if (profile.intent() != TaskIntent.DOCUMENT_AUTHORING
                || profile.workflowTemplate() != io.opencode.loopper.domain.WorkflowTemplate.PACKAGED_ARTIFACT) {
            throw new ConflictException("PACKAGED_DOCUMENT_PROFILE_INVALID", "当前画像不是大型分包文档");
        }
        return compile(sessionId, profile);
    }

    private Result compileDocument(TaskProfileService.View profile, String markdown,
                                   LoopDraftRow draft) {
        boolean docx = profile.artifactKinds().contains(ArtifactKind.DOCX);
        String target = DocumentOutputPathResolver.resolve(markdown, docx);
        String criterionId = "WP-1-AC-1";
        LoopSpec.VerifierSpec verifier = verifier("DOCUMENT_STRUCTURE", target, List.of(criterionId),
                List.of(new LoopSpec.DocumentAssertion("TEXT_NON_EMPTY", null, null, null),
                        new LoopSpec.DocumentAssertion("LOCAL_LINKS_VALID", null, null, null)), List.of());
        List<LoopSpec.AcceptanceCriterion> criteria = List.of(
                new LoopSpec.AcceptanceCriterion(criterionId, "交付文档可解析、文本非空且本地链接有效"),
                new LoopSpec.AcceptanceCriterion("WP-1-AC-2", "正文完整满足冻结需求中的范围、深度、事实来源和验收意向",
                        "JUDGE", "逐项对照冻结需求审阅实际交付正文；需要依据仓库资料的结论必须与来源核对。"
                        + "需求快照、写作计划、章节提纲或仅罗列要求均不能替代成品正文；结构检查通过不能证明内容完成。",
                        "通用格式检查不能证明自然语言内容的完整性与事实准确性"));
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("依据冻结需求读取相关资料并撰写完整文档 " + target,
                List.of(target), List.of(), List.of(target), List.of(verifier), criteria,
                null, ImplementationKind.NON_JAVA, "WP-1", StageKind.DOCUMENT_AUTHORING,
                ExecutionStrategy.OPEN_CODE_IMPLEMENTATION, null);
        return updateDraft(draft, draft.goal(), markdown, stage, null);
    }

    private Result compileTabular(DesignerSessionRow session, TaskProfileService.View profile, String markdown,
                                  LoopDraftRow draft, ProjectRow project) {
        List<String> candidates = paths(markdown);
        String input = candidates.stream().filter(value -> List.of("xlsx", "csv", "tsv").contains(extension(value)))
                .filter(value -> safeRegularFile(Path.of(project.rootPath()), value)).findFirst()
                .orElseThrow(() -> new BadRequestException("TABULAR_INPUT_REQUIRED",
                        "一次性表格转换必须在需求中用反引号标明已登记目录内存在的 .xlsx/.csv/.tsv 输入文件"));
        String target = candidates.stream().filter(value -> List.of("md", "markdown").contains(extension(value)))
                .findFirst().orElse("output/converted-table.md");
        ArtifactPlanRow plan = artifacts.registerTabularPlan(session.id(), profile.id(),
                new ArtifactMaterializationService.TabularConversionPlan(input, List.of(), target));
        artifacts.freeze(plan.id());
        String criterionId = "WP-1-AC-1";
        LoopSpec.VerifierSpec verifier = verifier("TABULAR_DATA", target, List.of(criterionId), List.of(),
                List.of(new LoopSpec.TabularAssertion("EQUIVALENT_TO", null, null, null, null, null, input)));
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("把 " + input + " 转换为 Markdown 表格并验证等价性",
                List.of(target), List.of(input), List.of(target), List.of(verifier),
                List.of(new LoopSpec.AcceptanceCriterion(criterionId, "目标 Markdown 表格与源表的显示值、Sheet 和有效单元格等价")),
                null, ImplementationKind.NON_JAVA, "WP-1", StageKind.TABULAR_CONVERSION,
                ExecutionStrategy.SERVER_TABULAR_CONVERSION, plan.id());
        return updateDraft(draft, "表格转换：" + input, markdown, stage, plan.id());
    }

    private Result updateDraft(LoopDraftRow draft, String goal, String context, LoopSpec.StageSpec stage, String planId) {
        LoopSpec old = drafts.spec(draft);
        LoopSpec compiled = new LoopSpec("v2", old.projectId(), goal, context, List.of(stage), old.limits(),
                old.model(), old.sessionPolicy(), old.nextAttemptPromptTemplate(), old.budget());
        drafts.updateAtVersion(draft.id(), compiled, draft.version());
        return new Result(planId, compiled);
    }

    private static LoopSpec.VerifierSpec verifier(String type, String path, List<String> criteria,
                                                   List<LoopSpec.DocumentAssertion> documents,
                                                   List<LoopSpec.TabularAssertion> tables) {
        return new LoopSpec.VerifierSpec(type, List.of(), path, null, List.of(), List.of(), null,
                null, null, null, null, null, null, null, null, null, null, null,
                List.of(), criteria, null, List.of(), documents, tables);
    }

    private static List<String> paths(String source) {
        List<String> result = new ArrayList<>();
        collect(BACKTICK_PATH.matcher(source), result);
        collect(BARE_PATH.matcher(source), result);
        return result.stream().map(String::trim).filter(value -> !value.startsWith("/") && !value.contains(".."))
                .distinct().toList();
    }

    private static void collect(Matcher matcher, List<String> target) {
        while (matcher.find() && target.size() < 32) target.add(matcher.group(1));
    }

    private static boolean safeRegularFile(Path root, String relative) {
        try {
            Path candidate = root.resolve(relative).normalize();
            return candidate.startsWith(root.normalize()) && !Files.isSymbolicLink(candidate) && Files.isRegularFile(candidate);
        } catch (RuntimeException ignored) { return false; }
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record Result(String artifactPlanId, LoopSpec loopSpec) { }
}
