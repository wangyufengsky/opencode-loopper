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
                : compileDocument(session, profile, discussion.snapshotMarkdown(), draft);
    }

    public Result compilePackagedDocument(String sessionId, TaskProfileService.View profile) {
        if (profile.intent() != TaskIntent.DOCUMENT_AUTHORING
                || profile.workflowTemplate() != io.opencode.loopper.domain.WorkflowTemplate.PACKAGED_ARTIFACT) {
            throw new ConflictException("PACKAGED_DOCUMENT_PROFILE_INVALID", "当前画像不是大型分包文档");
        }
        DesignDiscussionRevisionRow discussion = mapper.findLatestDesignDiscussionRevision(sessionId, "REQUIREMENT")
                .orElseThrow(() -> new ConflictException("REQUIREMENT_DISCUSSION_MISSING", "需求讨论快照不存在"));
        long sections = discussion.snapshotMarkdown().lines().filter(line -> line.matches("^##\\s+.+")).count();
        if (sections < 2 || sections > 6) {
            throw new BadRequestException("PACKAGED_DOCUMENT_SECTION_LIMIT",
                    "大型文档必须在确认稿中包含 2-6 个二级章节；每章作为冻结结构化片段后由服务端按顺序聚合");
        }
        DesignerSessionRow session = mapper.findDesignerSession(sessionId)
                .orElseThrow(() -> new NotFoundException("Designer session not found: " + sessionId));
        if (profile.id() == null || !"FROZEN".equals(profile.state())) {
            throw new ConflictException("TASK_PROFILE_NOT_FROZEN", "分包文档方案只能从冻结任务画像生成");
        }
        LoopDraftRow draft = drafts.get(session.loopDraftId());
        String markdown = discussion.snapshotMarkdown();
        boolean docx = profile.artifactKinds().contains(ArtifactKind.DOCX);
        String target = paths(markdown).stream()
                .filter(value -> docx ? extension(value).equals("docx") : List.of("md", "markdown").contains(extension(value)))
                .findFirst().orElse(docx ? "output/document.docx" : "output/document.md");
        String title = title(markdown, draft.goal());
        PackagedDocument packaged = packagedDocument(markdown, title);
        ArtifactPlanRow plan = artifacts.registerDocumentPlan(session.id(), profile.id(),
                new ArtifactMaterializationService.DocumentPlan(target, docx ? "DOCX" : "MARKDOWN", title,
                        packaged.preamble(), packaged.chapters()));
        artifacts.freeze(plan.id());
        List<String> criterionIds = packaged.chapters().stream()
                .map(chapter -> chapter.workPackageId() + "-AC-1").toList();
        List<LoopSpec.DocumentAssertion> assertions = packaged.chapters().stream()
                .map(chapter -> new LoopSpec.DocumentAssertion("HEADING_EXISTS", chapter.title(), null, 2)).toList();
        LoopSpec.VerifierSpec verifier = verifier("DOCUMENT_STRUCTURE", target, criterionIds, assertions, List.of());
        List<LoopSpec.AcceptanceCriterion> criteria = packaged.chapters().stream()
                .map(chapter -> new LoopSpec.AcceptanceCriterion(chapter.workPackageId() + "-AC-1",
                        "聚合文档包含已冻结章节：" + chapter.title())).toList();
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("按冻结章节包顺序聚合并验收 " + target,
                List.of(target), List.of(), List.of(target), List.of(verifier), criteria,
                null, ImplementationKind.NON_JAVA, "WP-1", StageKind.DOCUMENT_MATERIALIZATION,
                ExecutionStrategy.SERVER_DOCUMENT_MATERIALIZATION, plan.id());
        return updateDraft(draft, title, markdown, stage, plan.id());
    }

    private Result compileDocument(DesignerSessionRow session, TaskProfileService.View profile, String markdown,
                                   LoopDraftRow draft) {
        boolean docx = profile.artifactKinds().contains(ArtifactKind.DOCX);
        String target = paths(markdown).stream()
                .filter(value -> docx ? extension(value).equals("docx") : List.of("md", "markdown").contains(extension(value)))
                .findFirst().orElse(docx ? "output/document.docx" : "output/document.md");
        String title = title(markdown, draft.goal());
        List<ArtifactMaterializationService.DocumentBlock> blocks = documentBlocks(markdown, title);
        ArtifactPlanRow plan = artifacts.registerDocumentPlan(session.id(), profile.id(),
                new ArtifactMaterializationService.DocumentPlan(target, docx ? "DOCX" : "MARKDOWN", title, blocks));
        artifacts.freeze(plan.id());
        String criterionId = "WP-1-AC-1";
        LoopSpec.VerifierSpec verifier = verifier("DOCUMENT_STRUCTURE", target, List.of(criterionId),
                List.of(new LoopSpec.DocumentAssertion("TEXT_EXISTS", title, null, null)), List.of());
        LoopSpec.StageSpec stage = new LoopSpec.StageSpec("按冻结文档方案生成并验收 " + target,
                List.of(target), List.of(), List.of(target), List.of(verifier),
                List.of(new LoopSpec.AcceptanceCriterion(criterionId, "生成的文档包含已确认标题和正文结构")),
                null, ImplementationKind.NON_JAVA, "WP-1", StageKind.DOCUMENT_MATERIALIZATION,
                ExecutionStrategy.SERVER_DOCUMENT_MATERIALIZATION, plan.id());
        return updateDraft(draft, title, markdown, stage, plan.id());
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

    private static String title(String markdown, String fallback) {
        for (String line : markdown.split("\\R")) {
            String value = line.trim();
            if (value.startsWith("# ") && value.length() > 2) return value.substring(2).trim();
        }
        return fallback == null || fallback.isBlank() ? "文档" : fallback.trim();
    }

    private static List<ArtifactMaterializationService.DocumentBlock> documentBlocks(String markdown, String title) {
        List<ArtifactMaterializationService.DocumentBlock> blocks = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        List<String> items = new ArrayList<>();
        boolean code = false;
        StringBuilder codeText = new StringBuilder();
        String[] sourceLines = markdown.split("\\R", -1);
        for (int lineIndex = 0; lineIndex < sourceLines.length; lineIndex++) {
            String line = sourceLines[lineIndex];
            if (line.trim().startsWith("```")) {
                flushParagraph(blocks, paragraph); flushList(blocks, items);
                if (code) { blocks.add(new ArtifactMaterializationService.DocumentBlock("CODE", 0, codeText.toString(), List.of(), List.of())); codeText.setLength(0); }
                code = !code; continue;
            }
            if (code) { if (!codeText.isEmpty()) codeText.append('\n'); codeText.append(line); continue; }
            if (tableStart(sourceLines, lineIndex)) {
                flushParagraph(blocks, paragraph); flushList(blocks, items);
                List<List<String>> rows = new ArrayList<>();
                rows.add(tableCells(line));
                lineIndex += 2;
                while (lineIndex < sourceLines.length && sourceLines[lineIndex].contains("|")) {
                    rows.add(tableCells(sourceLines[lineIndex]));
                    lineIndex++;
                }
                lineIndex--;
                blocks.add(new ArtifactMaterializationService.DocumentBlock("TABLE", 0, "", List.of(), rows));
                continue;
            }
            Matcher heading = Pattern.compile("^(#{1,4})\\s+(.+)$").matcher(line.trim());
            if (heading.matches()) {
                flushParagraph(blocks, paragraph); flushList(blocks, items);
                String value = heading.group(2).trim();
                if (!(heading.group(1).length() == 1 && value.equals(title))) {
                    blocks.add(new ArtifactMaterializationService.DocumentBlock("HEADING", heading.group(1).length(), value, List.of(), List.of()));
                }
            } else if (line.trim().matches("^[-*+]\\s+.+")) {
                flushParagraph(blocks, paragraph); items.add(line.trim().substring(2).trim());
            } else if (line.isBlank()) {
                flushParagraph(blocks, paragraph); flushList(blocks, items);
            } else {
                flushList(blocks, items); paragraph.add(line.trim());
            }
        }
        flushParagraph(blocks, paragraph); flushList(blocks, items);
        if (code && !codeText.isEmpty()) blocks.add(new ArtifactMaterializationService.DocumentBlock("CODE", 0, codeText.toString(), List.of(), List.of()));
        if (blocks.isEmpty()) blocks.add(new ArtifactMaterializationService.DocumentBlock("PARAGRAPH", 0, title, List.of(), List.of()));
        return List.copyOf(blocks);
    }

    private static boolean tableStart(String[] lines, int index) {
        if (index + 1 >= lines.length || !lines[index].contains("|")) return false;
        String separator = lines[index + 1].trim();
        if (separator.startsWith("|")) separator = separator.substring(1);
        if (separator.endsWith("|")) separator = separator.substring(0, separator.length() - 1);
        String[] cells = separator.split("\\|", -1);
        return cells.length > 0 && java.util.Arrays.stream(cells)
                .allMatch(value -> value.trim().matches(":?-{3,}:?"));
    }

    private static List<String> tableCells(String line) {
        String value = line.strip();
        if (value.startsWith("|")) value = value.substring(1);
        if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : value.split("(?<!\\\\)\\|", -1)) {
            cells.add(cell.strip().replace("\\|", "|").replace("\\\\", "\\"));
        }
        return List.copyOf(cells);
    }

    private static PackagedDocument packagedDocument(String markdown, String title) {
        List<String> preamble = new ArrayList<>();
        List<ArtifactMaterializationService.DocumentChapter> chapters = new ArrayList<>();
        String chapterTitle = null;
        List<String> chapterLines = new ArrayList<>();
        for (String line : markdown.split("\\R", -1)) {
            Matcher heading = Pattern.compile("^##\\s+(.+)$").matcher(line.trim());
            if (heading.matches()) {
                if (chapterTitle != null) {
                    chapters.add(new ArtifactMaterializationService.DocumentChapter("WP-" + (chapters.size() + 1),
                            chapterTitle, documentBlocks(String.join("\n", chapterLines), chapterTitle)));
                }
                chapterTitle = heading.group(1).trim();
                chapterLines = new ArrayList<>();
            } else if (chapterTitle == null) preamble.add(line);
            else chapterLines.add(line);
        }
        if (chapterTitle != null) {
            chapters.add(new ArtifactMaterializationService.DocumentChapter("WP-" + (chapters.size() + 1),
                    chapterTitle, documentBlocks(String.join("\n", chapterLines), chapterTitle)));
        }
        return new PackagedDocument(documentBlocks(String.join("\n", preamble), title), List.copyOf(chapters));
    }

    private static void flushParagraph(List<ArtifactMaterializationService.DocumentBlock> blocks, List<String> lines) {
        if (!lines.isEmpty()) {
            blocks.add(new ArtifactMaterializationService.DocumentBlock("PARAGRAPH", 0, String.join(" ", lines), List.of(), List.of()));
            lines.clear();
        }
    }

    private static void flushList(List<ArtifactMaterializationService.DocumentBlock> blocks, List<String> items) {
        if (!items.isEmpty()) {
            blocks.add(new ArtifactMaterializationService.DocumentBlock("LIST", 0, "", List.copyOf(items), List.of()));
            items.clear();
        }
    }

    public record Result(String artifactPlanId, LoopSpec loopSpec) { }
    private record PackagedDocument(List<ArtifactMaterializationService.DocumentBlock> preamble,
                                    List<ArtifactMaterializationService.DocumentChapter> chapters) { }
}
