package io.opencode.loopper.verification;

import tools.jackson.databind.ObjectMapper;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.persistence.ArtifactPlanRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;

/** Product-owned artifact executor. It never invokes a shell, model, macro, or formula evaluator. */
@Component
public class ArtifactMaterializationService {
    private static final int MAX_OUTPUT_BYTES = 20_000_000;
    private static final int MAX_BLOCKS = 2_000;
    private final LoopperMapper mapper;
    private final ObjectMapper json;

    public ArtifactMaterializationService(LoopperMapper mapper, ObjectMapper json) {
        this.mapper = mapper; this.json = json;
    }

    public ArtifactPlanRow registerDocumentPlan(String designerSessionId, String taskProfileId, DocumentPlan plan) {
        validate(plan); return insert(designerSessionId, taskProfileId, "DOCUMENT", plan);
    }

    public ArtifactPlanRow registerTabularPlan(String designerSessionId, String taskProfileId, TabularConversionPlan plan) {
        validate(plan); return insert(designerSessionId, taskProfileId, "TABULAR_CONVERSION", plan);
    }

    public Result materialize(Path worktree, String artifactPlanId) {
        ArtifactPlanRow row = mapper.findArtifactPlan(artifactPlanId)
                .orElseThrow(() -> new TaskFailure("ARTIFACT_PLAN_MISSING", "Frozen artifact plan is unavailable"));
        if (!"FROZEN".equals(row.state())) throw new TaskFailure("ARTIFACT_PLAN_NOT_FROZEN", "Artifact plan is not frozen");
        try {
            return switch (row.kind()) {
                case "DOCUMENT" -> document(worktree, json.readValue(row.planJson(), DocumentPlan.class));
                case "TABULAR_CONVERSION" -> tabular(worktree, json.readValue(row.planJson(), TabularConversionPlan.class));
                default -> throw new TaskFailure("ARTIFACT_PLAN_KIND_INVALID", "Unknown artifact plan kind");
            };
        } catch (TaskFailure failure) { throw failure; }
        catch (Exception failure) { throw new TaskFailure("ARTIFACT_MATERIALIZATION_FAILED", failure.getMessage()); }
    }

    public ArtifactPlanRow freeze(String id) {
        ArtifactPlanRow current = mapper.findArtifactPlan(id)
                .orElseThrow(() -> new TaskFailure("ARTIFACT_PLAN_MISSING", "Artifact plan is unavailable"));
        if ("FROZEN".equals(current.state())) return current;
        ArtifactPlanRow frozen = new ArtifactPlanRow(current.id(), current.designerSessionId(), current.taskProfileId(),
                current.kind(), "FROZEN", current.planJson(), current.planSha256(), current.createdAt(),
                Instant.now().toString(), current.version());
        if (mapper.updateArtifactPlan(frozen) != 1) throw new TaskFailure("ARTIFACT_PLAN_CONFLICT", "Artifact plan changed concurrently");
        return mapper.findArtifactPlan(id).orElseThrow();
    }

    private ArtifactPlanRow insert(String sessionId, String profileId, String kind, Object plan) {
        try {
            String value = json.writeValueAsString(plan); String now = Instant.now().toString();
            ArtifactPlanRow row = new ArtifactPlanRow(UUID.randomUUID().toString(), sessionId, profileId, kind,
                    "PROVISIONAL", value, BinaryArtifactStore.sha256(value.getBytes(StandardCharsets.UTF_8)),
                    now, now, 0);
            if (mapper.insertArtifactPlan(row) != 1) throw new TaskFailure("ARTIFACT_PLAN_CREATE_CONFLICT", "Artifact plan could not be persisted");
            return row;
        } catch (TaskFailure failure) { throw failure; }
        catch (Exception failure) { throw new TaskFailure("ARTIFACT_PLAN_INVALID", failure.getMessage()); }
    }

    private Result document(Path root, DocumentPlan plan) throws Exception {
        validate(plan); Path target = target(root, plan.targetPath(), plan.format().equals("DOCX") ? ".docx" : ".md");
        byte[] bytes = plan.format().equals("DOCX") ? docx(plan) : markdown(plan).getBytes(StandardCharsets.UTF_8);
        writeAtomically(target, bytes);
        return result(root, target, bytes, Map.of("format", plan.format(), "blockCount", plan.blocks().size()));
    }

    private Result tabular(Path root, TabularConversionPlan plan) {
        validate(plan); Path input = VerifierSafety.managedRelative(root, plan.inputPath());
        Path target = target(root, plan.targetPath(), ".md");
        TabularSnapshot source = TabularSnapshot.read(input);
        List<SheetData> selected = plan.sheets().isEmpty() ? source.sheets() : source.sheets().stream()
                .filter(sheet -> plan.sheets().contains(sheet.name())).toList();
        if (selected.isEmpty()) throw new TaskFailure("TABULAR_SHEET_MISSING", "No requested XLSX/CSV/TSV sheet was found");
        StringBuilder markdown = new StringBuilder();
        for (SheetData sheet : selected) {
            markdown.append("## ").append(sheet.name()).append("\n\n");
            List<List<String>> rows = sheet.rows(); int columns = sheet.columnCount();
            if (rows.isEmpty() || columns == 0) { markdown.append("_空表_\n\n"); continue; }
            appendMarkdownRow(markdown, rows.getFirst(), columns);
            markdown.append('|'); for (int i = 0; i < columns; i++) markdown.append(" --- |"); markdown.append('\n');
            for (int index = 1; index < rows.size(); index++) appendMarkdownRow(markdown, rows.get(index), columns);
            markdown.append('\n');
        }
        byte[] bytes = markdown.toString().getBytes(StandardCharsets.UTF_8); writeAtomically(target, bytes);
        return result(root, target, bytes, Map.of("sheetCount", selected.size(), "formulaMode", "CACHED_DISPLAY_VALUE",
                "mergedCellMode", "TOP_LEFT_ONLY", "trimMode", "TRAILING_EMPTY_ONLY"));
    }

    private static void appendMarkdownRow(StringBuilder output, List<String> row, int columns) {
        output.append('|');
        for (int column = 0; column < columns; column++) {
            String value = column < row.size() ? row.get(column) : "";
            output.append(' ').append(escape(value)).append(" |");
        }
        output.append('\n');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|")
                .replace("\r\n", "<br>").replace("\n", "<br>").replace("\r", "<br>");
    }

    private static String markdown(DocumentPlan plan) {
        StringBuilder output = new StringBuilder("# ").append(plan.title()).append("\n\n");
        for (DocumentBlock block : plan.blocks()) {
            switch (block.type()) {
                case "HEADING" -> output.append("#".repeat(block.level())).append(' ').append(block.text()).append("\n\n");
                case "PARAGRAPH" -> output.append(block.text()).append("\n\n");
                case "CODE" -> output.append("```\n").append(block.text()).append("\n```\n\n");
                case "LIST" -> block.items().forEach(item -> output.append("- ").append(item).append('\n'));
                case "TABLE" -> { if (!block.rows().isEmpty()) { int columns = block.rows().getFirst().size(); appendMarkdownRow(output, block.rows().getFirst(), columns); output.append('|'); for (int i=0;i<columns;i++) output.append(" --- |"); output.append('\n'); block.rows().stream().skip(1).forEach(row -> appendMarkdownRow(output,row,columns)); output.append('\n'); } }
                default -> throw new TaskFailure("DOCUMENT_BLOCK_INVALID", "Unsupported document block: " + block.type());
            }
            if (block.type().equals("LIST")) output.append('\n');
        }
        return output.toString();
    }

    private static byte[] docx(DocumentPlan plan) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFParagraph title = document.createParagraph(); title.setStyle("Title"); title.createRun().setText(plan.title());
            for (DocumentBlock block : plan.blocks()) {
                switch (block.type()) {
                    case "HEADING" -> { XWPFParagraph p = document.createParagraph(); p.setStyle("Heading" + block.level()); p.createRun().setText(block.text()); }
                    case "PARAGRAPH" -> document.createParagraph().createRun().setText(block.text());
                    case "CODE" -> { XWPFParagraph p = document.createParagraph(); XWPFRun run = p.createRun(); run.setFontFamily("Consolas"); run.setText(block.text()); }
                    case "LIST" -> block.items().forEach(item -> { XWPFParagraph p=document.createParagraph(); p.setStyle("ListBullet"); p.createRun().setText(item); });
                    case "TABLE" -> { if (!block.rows().isEmpty()) { int columns=Math.max(1,block.rows().getFirst().size()); XWPFTable table=document.createTable(block.rows().size(),columns); for(int r=0;r<block.rows().size();r++) for(int c=0;c<columns;c++) table.getRow(r).getCell(c).setText(c<block.rows().get(r).size()?block.rows().get(r).get(c):""); } }
                    default -> throw new TaskFailure("DOCUMENT_BLOCK_INVALID", "Unsupported document block: " + block.type());
                }
            }
            document.write(output); return output.toByteArray();
        }
    }

    private static void validate(DocumentPlan plan) {
        if (plan == null || plan.title() == null || plan.title().isBlank() || plan.targetPath() == null
                || !List.of("MARKDOWN", "DOCX").contains(plan.format()) || plan.blocks() == null
                || plan.blocks().size() > MAX_BLOCKS) throw new TaskFailure("DOCUMENT_PLAN_INVALID", "DocumentPlan is incomplete or exceeds limits");
        for (DocumentBlock block : plan.blocks()) {
            if (block == null || !List.of("HEADING","PARAGRAPH","LIST","CODE","TABLE").contains(block.type()))
                throw new TaskFailure("DOCUMENT_BLOCK_INVALID", "DocumentPlan contains an unsupported block");
            if ("HEADING".equals(block.type()) && (block.level() < 1 || block.level() > 4))
                throw new TaskFailure("DOCUMENT_HEADING_LEVEL_INVALID", "DOCX/Markdown headings are limited to levels 1-4");
        }
    }
    private static void validate(TabularConversionPlan plan) {
        if (plan == null || plan.inputPath() == null || plan.targetPath() == null)
            throw new TaskFailure("TABULAR_PLAN_INVALID", "TabularConversionPlan requires input and target paths");
        String input = plan.inputPath().toLowerCase(Locale.ROOT);
        if (!(input.endsWith(".xlsx") || input.endsWith(".csv") || input.endsWith(".tsv")))
            throw new TaskFailure("TABULAR_INPUT_FORMAT_INVALID", "Only XLSX, CSV, and TSV input is supported");
        if (!plan.targetPath().toLowerCase(Locale.ROOT).endsWith(".md"))
            throw new TaskFailure("TABULAR_TARGET_FORMAT_INVALID", "Tabular conversion target must be Markdown");
    }
    private static Path target(Path root, String relative, String extension) {
        if (relative == null || !relative.toLowerCase(Locale.ROOT).endsWith(extension))
            throw new TaskFailure("ARTIFACT_TARGET_FORMAT_INVALID", "Artifact target extension must be " + extension);
        Path target = VerifierSafety.managedRelative(root, relative);
        if (Files.isSymbolicLink(target)) throw new TaskFailure("ARTIFACT_TARGET_SYMLINK_FORBIDDEN", "Artifact target may not be a symbolic link");
        return target;
    }
    private static void writeAtomically(Path target, byte[] bytes) {
        if (bytes.length > MAX_OUTPUT_BYTES) throw new TaskFailure("ARTIFACT_OUTPUT_LIMIT", "Generated artifact exceeds the output limit");
        try {
            Files.createDirectories(target.getParent()); Path temp = Files.createTempFile(target.getParent(), ".loopper-artifact-", ".tmp");
            try {
                Files.write(temp, bytes);
                try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
        } catch (Exception failure) { throw new TaskFailure("ARTIFACT_WRITE_FAILED", "Artifact could not be written atomically: " + failure.getMessage()); }
    }
    private static Result result(Path root, Path target, byte[] bytes, Map<String,Object> details) {
        try {
            return new Result(root.toRealPath().relativize(target.toRealPath()).toString().replace('\\','/'),
                    bytes.length, BinaryArtifactStore.sha256(bytes), Map.copyOf(details));
        } catch (Exception failure) {
            throw new TaskFailure("ARTIFACT_RESULT_PATH_INVALID", "Generated artifact path could not be canonicalized");
        }
    }

    public record DocumentPlan(String targetPath, String format, String title, List<DocumentBlock> blocks) {
        public DocumentPlan { format = format == null ? null : format.toUpperCase(Locale.ROOT); blocks = blocks == null ? List.of() : List.copyOf(blocks); }
    }
    public record DocumentBlock(String type, int level, String text, List<String> items, List<List<String>> rows) {
        public DocumentBlock { type = type == null ? null : type.toUpperCase(Locale.ROOT); text = text == null ? "" : text; items = items == null ? List.of() : List.copyOf(items); rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList(); }
    }
    public record TabularConversionPlan(String inputPath, List<String> sheets, String targetPath) {
        public TabularConversionPlan { sheets = sheets == null ? List.of() : List.copyOf(sheets); }
    }
    public record Result(String path, long sizeBytes, String sha256, Map<String,Object> details) { }
}
