package io.opencode.loopper.service.assist;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DocxTableRepresentationTest {
    private final AssistDocumentParser parser = new AssistDocumentParser();

    @Test void screenshotSamplePreservesUpAcAndOptionalFlagCoordinates() throws Exception {
        var parsed = parser.parse("sample.docx", Files.readAllBytes(Path.of("docs/samples/azx0-requirement/AZX0接口附言优化_模拟需规.docx")));
        String table = parsed.sections().stream().filter(s -> s.title().equals("2.1 功能描述")).findFirst().orElseThrow().markdown();
        assertThat(table).contains("3 行 × 5 列", "<td data-row=\"1\" data-column=\"1\" rowspan=\"2\" colspan=\"1\">名称",
                "<td data-row=\"1\" data-column=\"3\" rowspan=\"1\" colspan=\"2\">出现要求",
                "<td data-row=\"2\" data-column=\"3\" rowspan=\"1\" colspan=\"1\">UP",
                "<td data-row=\"2\" data-column=\"4\" rowspan=\"1\" colspan=\"1\">AC",
                "<td data-row=\"3\" data-column=\"3\" rowspan=\"1\" colspan=\"1\">O");
        assertThat(parsed.sections()).hasSize(15);
        assertThat(parsed.sections().stream().map(AssistDocumentParser.Section::markdown).reduce("", String::concat))
                .contains("R1 附言映射", "R2 空值处理", "R3 内容保留", "R4 改造范围", "T1", "T8", "S120021183ReceiptAdv");
    }

    @Test void ordinaryTableKeepsExistingMarkdownRepresentation() throws Exception {
        assertThat(parse("<w:tr>" + cell("", "one") + cell("", "two") + "</w:tr>"))
                .isEqualTo("| one | two |\n| --- | --- |\n\n");
    }

    @Test void verticalAndHorizontalMergeRetainOnlyOneAnchorAndEscapeContent() throws Exception {
        String rows = "<w:tr>" + cell("<w:gridSpan w:val=\"2\"/><w:vMerge w:val=\"restart\"/>", "A &amp; &lt;script&gt;") + cell("", "right") + "</w:tr>"
                + "<w:tr>" + cell("<w:gridSpan w:val=\"2\"/><w:vMerge/>", "") + cell("", "bottom") + "</w:tr>";
        assertThat(parse(rows)).contains("rowspan=\"2\" colspan=\"2\">A &amp; &lt;script&gt;", "data-row=\"2\" data-column=\"3\"")
                .doesNotContain("<script>", "data-omitted", "data-row=\"2\" data-column=\"1\"");
    }

    @Test void omittedGridColumnsAreNotShiftedOrInventedAsEmptyValues() throws Exception {
        String rows = "<w:tr><w:trPr><w:gridBefore w:val=\"1\"/><w:gridAfter w:val=\"1\"/></w:trPr>"
                + cell("", "center") + "</w:tr>";
        assertThat(parse(rows)).contains("1 行 × 3 列", "data-column=\"2\" rowspan=\"1\" colspan=\"1\">center",
                "data-column=\"1\" data-omitted=\"true\"", "data-column=\"3\" data-omitted=\"true\"");
    }

    @Test void orphanAndMismatchedMergeFailWithoutDroppingText() {
        assertThatThrownBy(() -> parse("<w:tr>" + cell("<w:vMerge/>", "") + "</w:tr>"))
                .isInstanceOf(AssistFailure.class).hasMessageContaining("缺少匹配");
        String first = "<w:tr>" + cell("<w:gridSpan w:val=\"2\"/><w:vMerge w:val=\"restart\"/>", "a") + "</w:tr>";
        assertThatThrownBy(() -> parse(first + "<w:tr>" + cell("<w:vMerge/>", "") + "</w:tr>"))
                .hasMessageContaining("缺少匹配");
        assertThatThrownBy(() -> parse(first + "<w:tr>" + cell("<w:gridSpan w:val=\"2\"/><w:vMerge/>", "hidden rule") + "</w:tr>"))
                .hasMessageContaining("额外文字");
    }

    @Test void oversizedOrUnsupportedMergeCannotPretendToParse() {
        for (String span : new String[]{"0", "-1", "257", "999999999999999999"})
            assertThatThrownBy(() -> parse("<w:tr>" + cell("<w:gridSpan w:val=\"" + span + "\"/>", "a") + "</w:tr>"))
                    .isInstanceOf(AssistFailure.class);
        assertThatThrownBy(() -> parse("<w:tr>" + cell("<w:hMerge w:val=\"restart\"/>", "a") + "</w:tr>"))
                .hasMessageContaining("旧式横向合并");
    }

    private static String cell(String properties, String text) {
        return "<w:tc><w:tcPr>" + properties + "</w:tcPr><w:p><w:r><w:t>" + text + "</w:t></w:r></w:p></w:tc>";
    }
    private String parse(String rows) throws Exception {
        byte[] base;
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) { doc.write(out); base = out.toByteArray(); }
        try (var in = new ZipInputStream(new ByteArrayInputStream(base)); var out = new ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(out)) {
                ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    zip.putNextEntry(new ZipEntry(entry.getName()));
                    byte[] content = in.readAllBytes();
                    if (entry.getName().equals("word/document.xml")) content = ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:tbl>"
                            + rows + "</w:tbl></w:body></w:document>").getBytes(StandardCharsets.UTF_8);
                    zip.write(content); zip.closeEntry();
                }
            }
            return parser.parse("test.docx", out.toByteArray()).sections().getFirst().markdown();
        }
    }
}
