package io.opencode.loopper.verification;

import tools.jackson.databind.ObjectMapper;
import io.opencode.loopper.persistence.ArtifactPlanRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactMaterializationServiceTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final LoopperMapper mapper = mock(LoopperMapper.class);
    private final ArtifactMaterializationService service = new ArtifactMaterializationService(mapper, json);

    @Test void materializesDocxOnlyFromFrozenPlan() throws Exception {
        var plan = new ArtifactMaterializationService.DocumentPlan("out/report.docx", "DOCX", "报告",
                List.of(new ArtifactMaterializationService.DocumentBlock("HEADING", 2, "范围", List.of(), List.of()),
                        new ArtifactMaterializationService.DocumentBlock("PARAGRAPH", 0, "正文", List.of(), List.of()),
                        new ArtifactMaterializationService.DocumentBlock("TABLE", 0, "", List.of(),
                                List.of(List.of("A", "B"), List.of("1", "2")))));
        stub("plan", "DOCUMENT", json.writeValueAsString(plan));
        ArtifactMaterializationService.Result result = service.materialize(root, "plan");
        assertThat(result.path()).isEqualTo("out/report.docx");
        assertThat(Files.size(root.resolve(result.path()))).isPositive();
        DocumentSnapshot snapshot = DocumentSnapshot.read(root.resolve(result.path()), root);
        assertThat(snapshot.headings()).anyMatch(value -> value.level() == 2 && value.text().equals("范围"));
        assertThat(snapshot.tableCount()).isEqualTo(1);
    }

    @Test void convertsMultipleSheetsUsingCachedFormulaAndMarkdownEscaping() throws Exception {
        Path input = root.resolve("input.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet first = workbook.createSheet("Main"); Row header = first.createRow(0);
            header.createCell(0).setCellValue("A|B"); header.createCell(1).setCellValue("Path\\Name");
            Row data = first.createRow(1); data.createCell(0).setCellValue("line1\nline2");
            Cell formula = data.createCell(1); formula.setCellFormula("1+1"); workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            first.addMergedRegion(new CellRangeAddress(2, 2, 0, 1)); first.createRow(2).createCell(0).setCellValue("merged");
            workbook.createSheet("Second").createRow(0).createCell(0).setCellValue("only");
            try (var output = Files.newOutputStream(input)) { workbook.write(output); }
        }
        var plan = new ArtifactMaterializationService.TabularConversionPlan("input.xlsx", List.of(), "out/table.md");
        stub("table", "TABULAR_CONVERSION", json.writeValueAsString(plan));
        service.materialize(root, "table");
        String markdown = Files.readString(root.resolve("out/table.md"));
        assertThat(markdown).contains("## Main", "## Second", "A\\|B", "Path\\\\Name", "line1<br>line2", "| 2 |", "merged");
    }

    private void stub(String id, String kind, String plan) {
        when(mapper.findArtifactPlan(id)).thenReturn(Optional.of(new ArtifactPlanRow(id, "session", "profile",
                kind, "FROZEN", plan, "hash", "now", "now", 0)));
    }
}
