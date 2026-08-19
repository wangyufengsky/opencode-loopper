package io.opencode.loopper.verification;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.VerificationState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeArtifactVerifierTest {
    @TempDir Path root;

    @Test void validatesMarkdownStructureAndLocalLinks() throws Exception {
        Files.writeString(root.resolve("target.md"), "# Title\n\n## Scope\n\nBody\n\n[local](source.md)\n\n| A |\n| --- |\n| 1 |\n");
        Files.writeString(root.resolve("source.md"), "source");
        LoopSpec.VerifierSpec spec = verifier("DOCUMENT_STRUCTURE", "target.md",
                List.of(new LoopSpec.DocumentAssertion("HEADING_EXISTS", "Scope", null, 2),
                        new LoopSpec.DocumentAssertion("TEXT_EXISTS", "Body", null, null),
                        new LoopSpec.DocumentAssertion("TABLE_COUNT", null, 1, null),
                        new LoopSpec.DocumentAssertion("LOCAL_LINKS_VALID", null, null, null)), List.of());
        VerifierOutcome result = new NativeVerifierRegistry().verify(context(), spec);
        assertThat(result.state()).isEqualTo(VerificationState.PASS);
    }

    @Test void validatesTabularCellsAndEquivalence() throws Exception {
        Files.writeString(root.resolve("source.csv"), "A,B\n1,2\n");
        Files.writeString(root.resolve("table.md"), "## source.csv\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n");
        LoopSpec.VerifierSpec spec = verifier("TABULAR_DATA", "table.md", List.of(),
                List.of(new LoopSpec.TabularAssertion("ROW_COUNT", "source.csv", null, null, null, 2, null),
                        new LoopSpec.TabularAssertion("CELL_EQUALS", "source.csv", 1, 1, "2", null, null),
                        new LoopSpec.TabularAssertion("EQUIVALENT_TO", null, null, null, null, null, "source.csv")));
        VerifierOutcome result = new NativeVerifierRegistry().verify(context(), spec);
        assertThat(result.state()).isEqualTo(VerificationState.PASS);
    }

    @Test void rejectsOoxmlPackagesWithExternalRelationships() throws Exception {
        Path input = root.resolve("external.xlsx");
        try (var workbook = new XSSFWorkbook(); var output = Files.newOutputStream(input)) {
            workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("safe");
            workbook.write(output);
        }
        try (OPCPackage pkg = OPCPackage.open(input.toFile(), PackageAccess.READ_WRITE)) {
            pkg.addExternalRelationship("https://example.invalid/payload", "urn:loopper:test-external");
        }

        assertThatThrownBy(() -> OoxmlSafety.open(input))
                .isInstanceOfSatisfying(TaskFailure.class, failure ->
                        assertThat(failure.code()).isEqualTo("OOXML_EXTERNAL_RELATIONSHIP_FORBIDDEN"));
    }

    private NativeVerifierContext context() { return new NativeVerifierContext(root, Duration.ofSeconds(5), new BinaryArtifactStore(root.resolve("artifacts"))); }
    private static LoopSpec.VerifierSpec verifier(String type, String path,
                                                   List<LoopSpec.DocumentAssertion> document,
                                                   List<LoopSpec.TabularAssertion> tabular) {
        return new LoopSpec.VerifierSpec(type, List.of(), path, null, List.of(), List.of(), null,
                null, null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of("AC-1"), null, List.of(), document, tabular);
    }
}
