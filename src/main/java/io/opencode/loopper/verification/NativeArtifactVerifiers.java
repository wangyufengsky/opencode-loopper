package io.opencode.loopper.verification;

import io.opencode.loopper.domain.LoopSpec.DocumentAssertion;
import io.opencode.loopper.domain.LoopSpec.TabularAssertion;
import io.opencode.loopper.domain.LoopSpec.VerifierSpec;
import io.opencode.loopper.domain.TaskFailure;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.Link;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

final class DocumentStructureVerifier implements NativeVerifierHandler {
    @Override public String type() { return "DOCUMENT_STRUCTURE"; }

    @Override public VerifierOutcome verify(NativeVerifierContext context, VerifierSpec spec) {
        Path file = VerifierSafety.managedRelative(context.worktree(), spec.path());
        DocumentSnapshot snapshot = DocumentSnapshot.read(file, context.worktree());
        List<Map<String, Object>> observed = new ArrayList<>();
        boolean passed = !spec.documentAssertions().isEmpty();
        for (DocumentAssertion assertion : spec.documentAssertions()) {
            boolean current = switch (assertion.type()) {
                case "HEADING_EXISTS" -> snapshot.headings().stream().anyMatch(heading ->
                        (assertion.headingLevel() == null || heading.level() == assertion.headingLevel())
                                && assertion.value() != null && heading.text().contains(assertion.value()));
                case "TEXT_EXISTS" -> assertion.value() != null && snapshot.text().contains(assertion.value());
                case "TABLE_COUNT" -> snapshot.tableCount() == (assertion.expectedCount() == null ? 0 : assertion.expectedCount());
                case "LOCAL_LINKS_VALID" -> snapshot.invalidLocalLinks().isEmpty();
                default -> throw new TaskFailure("DOCUMENT_ASSERTION_INVALID", "Unsupported document assertion: " + assertion.type());
            };
            observed.add(Map.of("type", assertion.type(), "passed", current));
            passed &= current;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("path", NativeVerifierHandlers.relative(context.worktree(), file));
        evidence.put("format", snapshot.format()); evidence.put("headingCount", snapshot.headings().size());
        evidence.put("tableCount", snapshot.tableCount()); evidence.put("invalidLocalLinks", snapshot.invalidLocalLinks());
        evidence.put("assertions", observed); evidence.put("sha256", BinaryArtifactStore.sha256(snapshot.bytes()));
        return NativeVerifierHandlers.outcome(type(), passed,
                passed ? "Document structure assertions matched" : "Document structure assertions failed", evidence);
    }
}

final class TabularDataVerifier implements NativeVerifierHandler {
    @Override public String type() { return "TABULAR_DATA"; }

    @Override public VerifierOutcome verify(NativeVerifierContext context, VerifierSpec spec) {
        Path file = VerifierSafety.managedRelative(context.worktree(), spec.path());
        TabularSnapshot snapshot = TabularSnapshot.read(file);
        boolean passed = !spec.tabularAssertions().isEmpty();
        List<Map<String, Object>> observed = new ArrayList<>();
        for (TabularAssertion assertion : spec.tabularAssertions()) {
            SheetData sheet = snapshot.sheet(assertion.sheet());
            boolean current = switch (assertion.type()) {
                case "SHEET_EXISTS" -> sheet != null;
                case "ROW_COUNT" -> sheet != null && sheet.rows().size() == integer(assertion.expectedCount());
                case "COLUMN_COUNT" -> sheet != null && sheet.columnCount() == integer(assertion.expectedCount());
                case "HEADER_EQUALS" -> sheet != null && !sheet.rows().isEmpty()
                        && String.join("|", sheet.rows().getFirst()).equals(nullToEmpty(assertion.expectedValue()));
                case "CELL_EQUALS" -> sheet != null && cell(sheet, assertion.row(), assertion.column())
                        .equals(nullToEmpty(assertion.expectedValue()));
                case "EQUIVALENT_TO" -> assertion.sourcePath() != null
                        && snapshot.equivalent(TabularSnapshot.read(VerifierSafety.managedRelative(context.worktree(), assertion.sourcePath())));
                default -> throw new TaskFailure("TABULAR_ASSERTION_INVALID", "Unsupported tabular assertion: " + assertion.type());
            };
            observed.add(Map.of("type", assertion.type(), "passed", current));
            passed &= current;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("path", NativeVerifierHandlers.relative(context.worktree(), file));
        evidence.put("sheetNames", snapshot.sheets().stream().map(SheetData::name).toList());
        evidence.put("rows", snapshot.sheets().stream().mapToInt(value -> value.rows().size()).sum());
        evidence.put("assertions", observed); evidence.put("sha256", BinaryArtifactStore.sha256(snapshot.bytes()));
        return NativeVerifierHandlers.outcome(type(), passed,
                passed ? "Tabular data assertions matched" : "Tabular data assertions failed", evidence);
    }

    private static int integer(Integer value) { return value == null ? 0 : value; }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static String cell(SheetData sheet, Integer row, Integer column) {
        if (row == null || column == null || row < 0 || column < 0 || row >= sheet.rows().size()) return "";
        List<String> values = sheet.rows().get(row);
        return column >= values.size() ? "" : values.get(column);
    }
}

record HeadingFact(int level, String text) { }
record DocumentSnapshot(String format, byte[] bytes, String text, List<HeadingFact> headings,
                        int tableCount, List<String> invalidLocalLinks) {
    static DocumentSnapshot read(Path file, Path root) {
        String extension = extension(file);
        byte[] bytes = NativeVerifierHandlers.readBounded(file, 20_000_000, "DOCUMENT_STRUCTURE");
        if ("md".equals(extension) || "markdown".equals(extension)) return markdown(file, root, bytes);
        if ("docx".equals(extension)) return docx(file, bytes);
        throw new TaskFailure("DOCUMENT_FORMAT_INVALID", "DOCUMENT_STRUCTURE supports only Markdown and DOCX");
    }

    private static DocumentSnapshot markdown(Path file, Path root, byte[] bytes) {
        String source = new String(bytes, StandardCharsets.UTF_8);
        List<HeadingFact> headings = new ArrayList<>();
        List<String> links = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Parser.builder().build().parse(source).accept(new AbstractVisitor() {
            private Heading active;
            @Override public void visit(Heading heading) { active = heading; visitChildren(heading); active = null; }
            @Override public void visit(Text node) {
                text.append(node.getLiteral());
                if (active != null) headings.add(new HeadingFact(active.getLevel(), node.getLiteral()));
            }
            @Override public void visit(SoftLineBreak node) { text.append('\n'); }
            @Override public void visit(Link link) { links.add(link.getDestination()); visitChildren(link); }
        });
        Path canonicalRoot;
        try { canonicalRoot = root.toRealPath(); }
        catch (IOException failure) { throw new TaskFailure("DOCUMENT_ROOT_INVALID", "Document root could not be canonicalized"); }
        List<String> invalid = links.stream().filter(link -> !link.isBlank() && !link.startsWith("#")
                        && !link.matches("(?i)^[a-z][a-z0-9+.-]*:.*"))
                .filter(link -> {
                    String path = link.contains("#") ? link.substring(0, link.indexOf('#')) : link;
                    if (path.isBlank()) return false;
                    try {
                        Path target = file.getParent().resolve(path).normalize();
                        return !target.startsWith(canonicalRoot) || Files.isSymbolicLink(target) || !Files.exists(target);
                    } catch (RuntimeException invalidPath) { return true; }
                }).toList();
        int tables = markdownTableCount(source);
        return new DocumentSnapshot("MARKDOWN", bytes, text.toString(), List.copyOf(headings), tables, invalid);
    }

    private static DocumentSnapshot docx(Path file, byte[] bytes) {
        OoxmlSafety.configure();
        try (OPCPackage pkg = OoxmlSafety.open(file); XWPFDocument document = new XWPFDocument(pkg)) {
            List<HeadingFact> headings = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String value = paragraph.getText(); text.append(value).append('\n');
                String style = paragraph.getStyle();
                if (style != null && style.toLowerCase(Locale.ROOT).startsWith("heading")) {
                    String suffix = style.substring("heading".length()).replaceAll("\\D", "");
                    int level = suffix.isEmpty() ? 1 : Math.min(4, Integer.parseInt(suffix));
                    headings.add(new HeadingFact(level, value));
                }
            }
            return new DocumentSnapshot("DOCX", bytes, text.toString(), List.copyOf(headings),
                    document.getTables().size(), List.of());
        } catch (TaskFailure failure) { throw failure; }
        catch (Exception failure) { throw new TaskFailure("DOCX_INVALID", "DOCX could not be parsed safely: " + failure.getMessage()); }
    }

    private static int markdownTableCount(String source) {
        String[] lines = source.split("\\R", -1); int count = 0;
        for (int i = 1; i < lines.length; i++) {
            String candidate = lines[i].trim();
            if (candidate.startsWith("|")) candidate = candidate.substring(1);
            if (candidate.endsWith("|")) candidate = candidate.substring(0, candidate.length() - 1);
            String[] columns = candidate.split("\\|", -1);
            if (columns.length > 0 && java.util.Arrays.stream(columns)
                    .allMatch(value -> value.trim().matches(":?-{3,}:?"))) count++;
        }
        return count;
    }
    static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1);
    }
}

record SheetData(String name, List<List<String>> rows) {
    int columnCount() { return rows.stream().mapToInt(List::size).max().orElse(0); }
}

record TabularSnapshot(byte[] bytes, List<SheetData> sheets) {
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_COLUMNS = 1_000;
    static TabularSnapshot read(Path file) {
        String extension = DocumentSnapshot.extension(file);
        byte[] bytes = NativeVerifierHandlers.readBounded(file, 50_000_000, "TABULAR_DATA");
        return switch (extension) {
            case "xlsx" -> xlsx(file, bytes);
            case "csv" -> delimited(file, bytes, ',');
            case "tsv" -> delimited(file, bytes, '\t');
            case "md", "markdown" -> markdown(bytes);
            default -> throw new TaskFailure("TABULAR_FORMAT_INVALID", "TABULAR_DATA supports XLSX, CSV, TSV, and Markdown tables");
        };
    }

    SheetData sheet(String name) {
        if (sheets.isEmpty()) return null;
        if (name == null || name.isBlank()) return sheets.getFirst();
        return sheets.stream().filter(value -> value.name().equals(name)).findFirst().orElse(null);
    }
    boolean equivalent(TabularSnapshot other) { return sheets.equals(other.sheets); }

    private static TabularSnapshot xlsx(Path file, byte[] bytes) {
        OoxmlSafety.configure();
        try (OPCPackage pkg = OoxmlSafety.open(file); Workbook workbook = new XSSFWorkbook(pkg)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<SheetData> sheets = new ArrayList<>();
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                if (sheet.getLastRowNum() + 1 > MAX_ROWS) throw new TaskFailure("TABULAR_ROW_LIMIT", "XLSX exceeds the row limit");
                List<List<String>> rows = new ArrayList<>();
                for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex); List<String> values = new ArrayList<>();
                    int cells = row == null ? 0 : Math.max(0, row.getLastCellNum());
                    if (cells > MAX_COLUMNS) throw new TaskFailure("TABULAR_COLUMN_LIMIT", "XLSX exceeds the column limit");
                    for (int column = 0; column < cells; column++) {
                        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        values.add(cell == null ? "" : displayValue(formatter, cell));
                    }
                    trimTrailing(values); rows.add(List.copyOf(values));
                }
                trimTrailingRows(rows); sheets.add(new SheetData(sheet.getSheetName(), List.copyOf(rows)));
            }
            return new TabularSnapshot(bytes, List.copyOf(sheets));
        } catch (TaskFailure failure) { throw failure; }
        catch (Exception failure) { throw new TaskFailure("XLSX_INVALID", "XLSX could not be parsed safely: " + failure.getMessage()); }
    }

    private static TabularSnapshot delimited(Path file, byte[] bytes, char delimiter) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).get().parse(reader)) {
            List<List<String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (rows.size() >= MAX_ROWS || record.size() > MAX_COLUMNS) throw new TaskFailure("TABULAR_LIMIT", "Delimited table exceeds safe limits");
                List<String> values = new ArrayList<>(); record.forEach(values::add); trimTrailing(values); rows.add(List.copyOf(values));
            }
            trimTrailingRows(rows);
            return new TabularSnapshot(bytes, List.of(new SheetData(file.getFileName().toString(), List.copyOf(rows))));
        } catch (TaskFailure failure) { throw failure; }
        catch (IOException failure) { throw new TaskFailure("TABULAR_INVALID", "Delimited table could not be parsed: " + failure.getMessage()); }
    }

    private static TabularSnapshot markdown(byte[] bytes) {
        String source = new String(bytes, StandardCharsets.UTF_8); List<SheetData> sheets = new ArrayList<>();
        String name = "Table"; List<List<String>> rows = new ArrayList<>();
        for (String line : source.split("\\R")) {
            if (line.startsWith("## ")) { if (!rows.isEmpty()) { sheets.add(new SheetData(name, List.copyOf(rows))); rows = new ArrayList<>(); } name = line.substring(3).trim(); }
            else if (line.contains("|") && !line.matches("\\s*\\|?\\s*:?-{3,}.*")) {
                String value = line.strip(); if (value.startsWith("|")) value = value.substring(1); if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
                List<String> cells = new ArrayList<>(); for (String cell : value.split("(?<!\\\\)\\|", -1)) cells.add(unescape(cell.strip()));
                trimTrailing(cells); rows.add(List.copyOf(cells));
            }
        }
        if (!rows.isEmpty()) sheets.add(new SheetData(name, List.copyOf(rows)));
        return new TabularSnapshot(bytes, List.copyOf(sheets));
    }
    private static String unescape(String value) { return value.replace("<br>", "\n").replace("\\|", "|").replace("\\\\", "\\"); }
    private static String displayValue(DataFormatter formatter, Cell cell) {
        if (cell.getCellType() != org.apache.poi.ss.usermodel.CellType.FORMULA) return formatter.formatCellValue(cell);
        return switch (cell.getCachedFormulaResultType()) {
            case NUMERIC -> formatter.formatRawCellContents(cell.getNumericCellValue(),
                    cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString());
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case ERROR -> org.apache.poi.ss.usermodel.FormulaError.forInt(cell.getErrorCellValue()).getString();
            case BLANK, _NONE, FORMULA -> "";
        };
    }
    private static void trimTrailing(List<String> values) { while (!values.isEmpty() && values.getLast().isEmpty()) values.removeLast(); }
    private static void trimTrailingRows(List<List<String>> rows) { while (!rows.isEmpty() && rows.getLast().stream().allMatch(String::isEmpty)) rows.removeLast(); }
}

final class OoxmlSafety {
    private OoxmlSafety() { }
    static void configure() {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(50_000_000L);
        ZipSecureFile.setMaxTextSize(10_000_000L);
    }
    static OPCPackage open(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".docx") || name.endsWith(".xlsx")))
            throw new TaskFailure("OOXML_FORMAT_FORBIDDEN", "Only .docx and .xlsx OOXML files are accepted");
        if (Files.isSymbolicLink(file)) throw new TaskFailure("OOXML_SYMLINK_FORBIDDEN", "OOXML input may not be a symbolic link");
        try {
            OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ);
            requireNoExternal(pkg);
            return pkg;
        } catch (TaskFailure failure) { throw failure; }
        catch (Exception failure) { throw new TaskFailure("OOXML_INVALID", "OOXML package could not be opened safely: " + failure.getMessage()); }
    }
    private static void requireNoExternal(OPCPackage pkg) throws Exception {
        for (PackageRelationship relationship : pkg.getRelationships())
            if (relationship.getTargetMode() == TargetMode.EXTERNAL) throw external();
        for (PackagePart part : pkg.getParts()) if (!part.isRelationshipPart()) for (PackageRelationship relationship : part.getRelationships())
            if (relationship.getTargetMode() == TargetMode.EXTERNAL) throw external();
    }
    private static TaskFailure external() { return new TaskFailure("OOXML_EXTERNAL_RELATIONSHIP_FORBIDDEN", "OOXML external relationships are forbidden"); }
}
