package io.opencode.loopper.service.assist;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class AssistDocumentsTest {
    @TempDir Path temp;private final AssistDocumentParser parser=new AssistDocumentParser();
    @Test void docxKeepsParagraphTableOrder() throws Exception {
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("before");doc.createTable(1,1).getRow(0).getCell(0).setText("middle");doc.createParagraph().createRun().setText("after");doc.write(out);
            var parsed=parser.parse("x.docx",out.toByteArray());assertThat(parsed.sections().getFirst().id()).isEqualTo("0");assertThat(parsed.sections().getFirst().markdown()).containsSubsequence("before","middle","after");
        }
    }
    @Test void spreadsheetRetainsSparseCoordinatesAndDoesNotEvaluate() throws Exception {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var row=book.createSheet("数据").createRow(9);row.createCell(3).setCellValue("中文");row.createCell(8).setCellFormula("1+2");book.write(out);
            String body=parser.parse("x.xlsx",out.toByteArray()).sections().getFirst().markdown();assertThat(body).contains("D10","I10","1+2","中文");
        }
    }
    @Test void slidesAndPdfKeepPageOrder() throws Exception {
        try(var slides=new XMLSlideShow();var out=new ByteArrayOutputStream()) {
            slides.createSlide().createTextBox().setText("first");slides.createSlide().createTextBox().setText("second");slides.write(out);
            var parsed=parser.parse("x.pptx",out.toByteArray());assertThat(parsed.sections()).hasSize(2);assertThat(parsed.sections().get(1).markdown()).contains("second");
        }
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            for(String text:java.util.List.of("one","two")){var page=new PDPage();pdf.addPage(page);try(var stream=new PDPageContentStream(pdf,page)){stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(50,700);stream.showText(text);stream.endText();}}
            pdf.save(out);var parsed=parser.parse("x.pdf",out.toByteArray());assertThat(parsed.sections()).hasSize(2);assertThat(parsed.sections().get(1).markdown()).contains("two");
        }
    }
    @Test void malformedMacrosAndEmptyScansFail() throws Exception {
        assertThatThrownBy(()->parser.parse("bad.docx",new byte[]{1,2})).isInstanceOf(AssistFailure.class);
        try(var out=new ByteArrayOutputStream()){try(var zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry("word/vbaProject.bin"));zip.write(1);zip.closeEntry();}assertThatThrownBy(()->parser.parse("macro.docx",out.toByteArray())).isInstanceOf(AssistFailure.class);}
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){pdf.addPage(new PDPage());pdf.save(out);assertThatThrownBy(()->parser.parse("scan.pdf",out.toByteArray())).isInstanceOf(AssistFailure.class);}
    }
    @Test void wordReopensWithRichTextAndTablesAndRejectsRemoteImages() throws Exception {
        var writer=new MarkdownWordRenderer();var output=writer.render("# 中文报告\n\n**重点** 与 *强调*\n\n- item\n\n| 项 | 值 |\n|---|---|\n| a | b |\n\n```java\nint a = 1;\n```",temp);
        try(var word=new XWPFDocument(new ByteArrayInputStream(output.bytes()))){assertThat(word.getTables()).hasSize(1);assertThat(word.getParagraphs().stream().flatMap(p->p.getRuns().stream()).anyMatch(r->r.isBold()&&r.text().equals("重点"))).isTrue();}
        assertThatThrownBy(()->writer.render("![remote](https://example.com/a.png)",temp)).isInstanceOf(AssistFailure.class);
    }
    @Test void chineseChunksFitResponseBudget(){var parsed=parser.parse("long.md","中".repeat(50000).getBytes(java.nio.charset.StandardCharsets.UTF_8));assertThat(parsed.sections()).hasSize(5);for(var section:parsed.sections())assertThat(section.markdown().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(65536);}
}
