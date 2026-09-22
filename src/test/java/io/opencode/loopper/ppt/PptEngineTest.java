package io.opencode.loopper.ppt;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.poi.xslf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static io.opencode.loopper.ppt.PptModel.*;

class PptEngineTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PptEngine engine = new PptEngine(json);
    @TempDir Path temporary;

    @Test void optionalWireFieldsHaveDefaultsButElementGeometryRemainsRequired() {
        OperationResult made = apply(Deck.empty("缺省"), true,
                "{\"op\":\"create_slide\",\"slide\":{\"id\":\"s\"}}",
                "{\"op\":\"add_element\",\"slideId\":\"s\",\"element\":{\"id\":\"t\",\"type\":\"text\",\"x\":0,\"y\":0,\"width\":100,\"height\":60,\"text\":\"内容\"}}");
        Element e = made.deck().slides().getFirst().elements().getFirst();
        assertEquals(0., e.rotation()); assertFalse(e.locked()); assertFalse(e.bold()); assertFalse(e.bullets());
        assertThrows(PptFailure.class, () -> apply(made.deck(), true,
                "{\"op\":\"add_element\",\"slideId\":\"s\",\"element\":{\"id\":\"broken\",\"type\":\"text\",\"width\":100,\"height\":60}}"));
    }

    @Test void twelvePageChineseAcceptanceSampleExportsAndRendersEveryPage() throws Exception {
        Deck deck;
        try (InputStream input = getClass().getResourceAsStream("/ppt/sample-12.json")) {
            assertNotNull(input); deck = json.readValue(input, Deck.class);
        }
        byte[] image;
        try (InputStream input = getClass().getResourceAsStream("/ppt/sample-illustration.png")) {
            assertNotNull(input); image = input.readAllBytes();
        }
        assertEquals(12, deck.slides().size());
        Validation validation = engine.validate(deck);
        assertTrue(validation.issues().isEmpty(), () -> json.writeValueAsString(validation));
        Path evidence = Path.of("target/backend-dev/ppt-evidence"); Files.createDirectories(evidence);
        byte[] pptx = engine.exportPptx(deck, id -> image); Files.write(evidence.resolve("sample-12.pptx"), pptx);
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(pptx))) {
            assertEquals(12, show.getSlides().size());
            assertEquals(3, show.getCharts().size());
            assertTrue(show.getSlides().stream().allMatch(slide -> slide.getNotes() != null));
        }
        for (Slide slide : deck.slides()) {
            byte[] png = engine.renderPng(deck, slide.id(), 1, id -> image);
            assertEquals(960, ImageIO.read(new ByteArrayInputStream(png)).getWidth());
            Files.write(evidence.resolve(slide.id() + ".png"), png);
        }
    }

    @Test void administratorFontsExtendOnlyTheirEngineAllowlist() throws Exception {
        try (InputStream font = getClass().getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(font); Files.copy(font, temporary.resolve("LiberationSans-Regular.ttf"));
        }
        PptEngine configured = new PptEngine(json, temporary.toString());
        assertTrue(configured.capabilities().fonts().contains("Liberation Sans"));
        assertFalse(new PptEngine(json).capabilities().fonts().contains("Liberation Sans"));
        Deck deck = configured.applyOperations(fixture(), List.of(json.readTree(
                "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"body\",\"patch\":{\"fontFamily\":\"Liberation Sans\",\"text\":\"Readable English\"}}")), false).deck();
        assertFalse(configured.measureText(deck, deck.slides().getFirst().elements().get(1)).overflow());
        assertThrows(PptFailure.class, () -> engine.validate(deck));
        assertNotNull(ImageIO.read(new ByteArrayInputStream(configured.renderPng(deck, "slide-1", .25, id -> image()))));
        Deck missingGlyph = configured.applyOperations(deck, List.of(json.readTree(
                "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"body\",\"patch\":{\"text\":\"中文\"}}")), false).deck();
        assertTrue(configured.validate(missingGlyph).issues().stream().anyMatch(i -> i.code().equals("MISSING_GLYPHS") && i.message().contains("font-dir")));
    }

    @Test void brokenFontConfigurationHasActionableRecoveryAndCanRetryAfterRepair() throws Exception {
        Path bad = temporary.resolve("bad.ttf"); Files.writeString(bad, "not a font");
        PptEngine configured = new PptEngine(json, temporary.toString());
        assertFalse(configured.capabilities().renderingAvailable());
        assertTrue(configured.capabilities().renderingMessage().contains("loopper.ppt.font-dir"));
        PptFailure failure = assertThrows(PptFailure.class, () -> configured.renderPng(fixture(), "slide-1", .25, id -> image()));
        assertEquals("PPT_FONT_UNAVAILABLE", failure.code());
        Files.delete(bad);
        assertTrue(configured.capabilities().renderingAvailable());
        assertTrue(new PptEngine(json).capabilities().renderingAvailable());
    }

    @Test void completeOnePageFixtureRemainsEditableAndRendersWithoutOffice() throws Exception {
        Deck deck = fixture();
        byte[] bytes = engine.exportPptx(deck, id -> image());
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertEquals(1, ppt.getSlides().size());
            var slide = ppt.getSlides().getFirst();
            assertEquals(6, slide.getShapes().size());
            assertTrue(slide.getShapes().stream().anyMatch(s -> s instanceof XSLFTextBox t && t.getText().contains("项目收益")));
            assertTrue(slide.getShapes().stream().anyMatch(s -> s instanceof XSLFPictureShape));
            assertTrue(slide.getShapes().stream().anyMatch(s -> s instanceof XSLFTable));
            XSLFGraphicFrame chart = (XSLFGraphicFrame) slide.getShapes().stream().filter(s -> s instanceof XSLFGraphicFrame f && f.hasChart()).findFirst().orElseThrow();
            assertEquals("chart", chart.getShapeName());
            assertEquals(365., chart.getAnchor().getX(), .01); assertEquals(540., chart.getAnchor().getWidth(), .01);
            assertEquals(20., chart.getChart().getWorkbook().getSheetAt(0).getRow(2).getCell(1).getNumericCellValue());
            assertTrue(slide.getNotes().getTextParagraphs().stream().flatMap(Collection::stream).anyMatch(p -> p.getText().contains("演讲备注")));
        }
        BufferedImage preview = ImageIO.read(new ByteArrayInputStream(engine.renderPng(deck, "slide-1", 1, id -> image())));
        Files.createDirectories(Path.of("target/backend-dev/ppt-evidence"));
        ImageIO.write(preview, "PNG", Path.of("target/backend-dev/ppt-evidence/fixture.png").toFile());
        assertEquals(960, preview.getWidth()); assertEquals(540, preview.getHeight());
        int chartColoredPixels = 0;
        for (int x = 390; x < 880; x += 3) for (int y = 120; y < 350; y += 3) {
            Color c = new Color(preview.getRGB(x, y));
            if (c.getBlue() > c.getRed() + 30) chartColoredPixels++;
        }
        assertTrue(chartColoredPixels > 100, "The custom native-chart preview must paint bars, not a blank graphical frame");
    }

    @Test void batchCreatesObjectsReturnsIdsAndNeverMutatesItsInput() {
        Deck empty = Deck.empty("测试");
        OperationResult result = apply(empty, true,
                "{\"op\":\"create_slide\",\"clientRef\":\"page\",\"slide\":{\"id\":\"s1\",\"title\":\"第一页\"}}",
                "{\"op\":\"add_element\",\"slideId\":\"s1\",\"clientRef\":\"title\",\"element\":{\"type\":\"text\",\"x\":40,\"y\":30,\"width\":800,\"height\":60,\"text\":\"标题\"}}");
        assertTrue(empty.slides().isEmpty()); assertEquals("s1", result.createdIds().get("page"));
        assertEquals(result.deck().slides().getFirst().elements().getFirst().id(), result.createdIds().get("title"));
    }

    @Test void failureInLastOperationRollsBackWholeBatch() {
        Deck deck = fixture();
        assertThrows(PptFailure.class, () -> apply(deck, true,
                "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"title\",\"patch\":{\"text\":\"新标题\"}}",
                "{\"op\":\"remove_element\",\"slideId\":\"slide-1\",\"elementId\":\"missing\"}"));
        assertEquals("项目收益", deck.slides().getFirst().elements().getFirst().text());
    }

    @Test void lockCannotBeBypassedByAgentPatchDeleteLayoutOrLayer() {
        Deck locked = apply(fixture(), false,
                "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"title\",\"patch\":{\"locked\":true}}").deck();
        for (String operation : List.of(
                "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"title\",\"patch\":{\"locked\":false}}",
                "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"title\",\"patch\":{\"text\":\"绕过\"}}",
                "{\"op\":\"delete_slide\",\"slideId\":\"slide-1\"}",
                "{\"op\":\"apply_layout\",\"slideId\":\"slide-1\",\"layout\":\"grid\"}",
                "{\"op\":\"move_element\",\"slideId\":\"slide-1\",\"elementId\":\"title\",\"index\":5}")) assertThrows(PptFailure.class, () -> apply(locked, true, operation));
        assertThrows(PptFailure.class, () -> apply(locked, false,
                "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"title\",\"patch\":{\"locked\":false,\"text\":\"绕过\"}}"));
        Deck unlocked = apply(locked, false,
                "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"title\",\"patch\":{\"locked\":false}}").deck();
        assertFalse(unlocked.slides().getFirst().elements().getFirst().locked());
    }

    @Test void globalThemeFreezesLockedPageAndElementStyleButUpdatesRemainingContent() throws Exception {
        Deck locked = apply(fixture(), false,
                "{\"op\":\"duplicate_slide\",\"slideId\":\"slide-1\",\"clientRef\":\"copy\"}",
                "{\"op\":\"update_slide\",\"slideId\":\"slide-1\",\"patch\":{\"locked\":true}}").deck();
        byte[] before = engine.renderPng(locked, "slide-1", .25, id -> image());
        Deck changed = apply(locked, true, "{\"op\":\"apply_theme\",\"theme\":\"dark\"}").deck();
        assertEquals("dark", changed.theme()); assertEquals("business", changed.slides().getFirst().theme());
        assertNull(changed.slides().get(1).theme());
        assertArrayEquals(before, engine.renderPng(changed, "slide-1", .25, id -> image()));
        Deck elementLocked = apply(fixture(), false,
                "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"chart\",\"patch\":{\"locked\":true}}").deck();
        Deck themed = apply(elementLocked, true, "{\"op\":\"apply_theme\",\"theme\":\"minimal\"}").deck();
        assertEquals("business", themed.slides().getFirst().elements().get(3).theme());
        assertNull(themed.slides().getFirst().elements().getFirst().theme());
        assertEquals(elementLocked.slides().getFirst().elements().get(3).chart(), themed.slides().getFirst().elements().get(3).chart());
    }

    @Test void moveElementChangesNativeStackOrder() throws Exception {
        Deck changed = apply(fixture(), true,
                "{\"op\":\"move_element\",\"slideId\":\"slide-1\",\"elementId\":\"title\",\"index\":5}").deck();
        assertEquals("title", changed.slides().getFirst().elements().getLast().id());
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(engine.exportPptx(changed, id -> image())))) {
            assertEquals("title", ppt.getSlides().getFirst().getShapes().getLast().getShapeName());
        }
    }

    @Test void layoutReportsTextOverflowBoundsAndIntentionalOverlap() {
        Deck deck = apply(fixture(), false,
                "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"body\",\"patch\":{\"text\":\"内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容内容\",\"height\":20,\"width\":80}}",
                "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"title\",\"patch\":{\"x\":-20}}").deck();
        var result = engine.validate(deck);
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("TEXT_OVERFLOW")));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("OUT_OF_BOUNDS")));
        assertFalse(result.issues().stream().anyMatch(i -> i.code().equals("MISSING_GLYPHS")));
    }

    @Test void objectMeasurementUsesBundledCjkAndPreservesText() {
        Deck deck = fixture(); Element body = deck.slides().getFirst().elements().get(1);
        var measured = engine.measureText(deck, body);
        assertTrue(measured.requiredHeight() > 0); assertFalse(measured.overflow());
        assertTrue(measured.missingCharacters().isEmpty()); assertEquals(PptFonts.DEFAULT, measured.fontFamily());
    }

    @Test void contrastCheckUsesActualFillsAndPreservesExplicitThemeColors() {
        Deck deck = apply(Deck.empty("对比度"), false,
                "{\"op\":\"create_slide\",\"slide\":{\"id\":\"contrast\"}}",
                "{\"op\":\"add_element\",\"slideId\":\"contrast\",\"element\":{\"id\":\"text\",\"type\":\"text\",\"x\":40,\"y\":40,\"width\":600,\"height\":80,\"text\":\"自定义颜色\",\"color\":\"18324F\"}}",
                "{\"op\":\"apply_theme\",\"theme\":\"dark\"}").deck();
        Issue problem = engine.validate(deck).issues().stream().filter(i -> i.code().equals("LOW_CONTRAST")).findFirst().orElseThrow();
        assertEquals("ERROR", problem.severity()); assertTrue(problem.message().contains("不会随主题自动换色"));
        assertEquals("18324F", deck.slides().getFirst().elements().getFirst().color());
        Deck filled = apply(deck, false, "{\"op\":\"update_element\",\"slideId\":\"contrast\",\"elementId\":\"text\",\"patch\":{\"fill\":\"FFFFFF\"}}").deck();
        assertTrue(engine.validate(filled).issues().isEmpty());
        Deck inherited = apply(deck, false, "{\"op\":\"update_element\",\"slideId\":\"contrast\",\"elementId\":\"text\",\"patch\":{\"color\":null}}").deck();
        assertTrue(engine.validate(inherited).issues().isEmpty());
        Deck overlap = apply(deck, false,
                "{\"op\":\"add_element\",\"slideId\":\"contrast\",\"element\":{\"id\":\"backdrop\",\"type\":\"shape\",\"x\":35,\"y\":35,\"width\":620,\"height\":100,\"fill\":\"FFFFFF\",\"allowOverlap\":true}}").deck();
        assertTrue(engine.validate(overlap).issues().stream().filter(i -> i.code().equals("LOW_CONTRAST"))
                .allMatch(i -> i.severity().equals("WARNING") && i.message().contains("估算")));
    }

    @Test void chartLabelsReportMeasuredOverflowAndPreviewUsesVisibleEllipsis() {
        Deck deck = apply(fixture(), false,
                "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"chart\",\"patch\":{\"width\":240,\"chart\":{\"type\":\"bar\",\"categories\":[\"这是第一个需要完整表达的分类名称\",\"第二个分类\"],\"series\":[{\"name\":\"这是非常长的图例名称，需要显示明确的省略标志\",\"values\":[10,20]}]}}}").deck();
        var issues = engine.validate(deck).issues().stream().filter(i -> i.code().equals("CHART_LABEL_OVERFLOW")).toList();
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("chart.categories[0]")));
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("chart.series[0].name")));
        assertTrue(issues.stream().allMatch(i -> i.severity().equals("ERROR") && i.message().contains("需要") && i.message().contains("可用")));
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB); var graphics = scratch.createGraphics();
        try {
            graphics.setFont(new PptFonts("").font(PptFonts.DEFAULT, false, 12));
            String shortened = PptChartPreview.abbreviate(graphics.getFontMetrics(), "中文分类标签", 40);
            assertTrue(shortened.endsWith("…")); assertTrue(graphics.getFontMetrics().stringWidth(shortened) <= 40);
            assertEquals("分类", PptChartPreview.abbreviate(graphics.getFontMetrics(), "分类", 100));
        } finally { graphics.dispose(); scratch.flush(); }
    }

    @Test void sampleThemeChangesExposeCustomColorRiskAndRenderForVisualReview() throws Exception {
        Deck sample; byte[] image;
        try (InputStream input = getClass().getResourceAsStream("/ppt/sample-12.json")) { sample = json.readValue(input, Deck.class); }
        try (InputStream input = getClass().getResourceAsStream("/ppt/sample-illustration.png")) { image = input.readAllBytes(); }
        Path evidence = Path.of("target/backend-dev/ppt-evidence"); Files.createDirectories(evidence);
        for (String theme : List.of("minimal", "dark")) {
            Deck changed = apply(sample, false, "{\"op\":\"apply_theme\",\"theme\":\"" + theme + "\"}").deck();
            Validation checked = engine.validate(changed);
            if (theme.equals("dark")) assertTrue(checked.issues().stream().anyMatch(i -> i.code().equals("LOW_CONTRAST")));
            else assertTrue(checked.issues().isEmpty(), () -> json.writeValueAsString(checked));
            Files.writeString(evidence.resolve("sample-" + theme + "-issues.json"), json.writeValueAsString(checked));
            for (String slideId : List.of("demo-01", "demo-02", "demo-06"))
                Files.write(evidence.resolve(theme + "-" + slideId + ".png"), engine.renderPng(changed, slideId, 1, id -> image));
        }
    }

    @Test void duplicateRekeysAllObjectsAndMovesByStableSlideId() {
        OperationResult result = apply(fixture(), true,
                "{\"op\":\"duplicate_slide\",\"slideId\":\"slide-1\",\"index\":0,\"clientRef\":\"copy\"}");
        Deck deck = result.deck(); assertEquals(2, deck.slides().size()); assertNotEquals("slide-1", deck.slides().getFirst().id());
        Set<String> ids = new HashSet<>();
        deck.slides().forEach(s -> s.elements().forEach(e -> assertTrue(ids.add(e.id()))));
        Deck moved = apply(deck, true, "{\"op\":\"move_slide\",\"slideId\":\"slide-1\",\"index\":0}").deck();
        assertEquals("slide-1", moved.slides().getFirst().id());
    }

    @Test void nativeGroupingPreservesImageAndChartRelationshipsAndPreview() throws Exception {
        Deck deck = apply(fixture(), true,
                "{\"op\":\"group\",\"slideId\":\"slide-1\",\"elementIds\":[\"body\",\"picture\",\"chart\"],\"groupId\":\"group-one\"}").deck();
        byte[] exported = engine.exportPptx(deck, id -> image());
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(exported))) {
            XSLFGroupShape group = (XSLFGroupShape) ppt.getSlides().getFirst().getShapes().stream().filter(XSLFGroupShape.class::isInstance).findFirst().orElseThrow();
            assertEquals("group-one", ppt.getSlides().getFirst().getShapes().get(1).getShapeName());
            assertEquals(3, group.getShapes().size());
            assertTrue(group.getShapes().stream().anyMatch(s -> s instanceof XSLFPictureShape p && p.getPictureData().getData().length > 0));
            assertTrue(group.getShapes().stream().anyMatch(s -> s instanceof XSLFGraphicFrame f && f.hasChart() && f.getChart() != null));
        }
        assertNotNull(ImageIO.read(new ByteArrayInputStream(engine.renderPng(deck, "slide-1", .5, id -> image()))));
    }

    @Test void rejectsBadAssetsAndUnsupportedPrimitiveInputs() {
        assertThrows(PptFailure.class, () -> engine.exportPptx(fixture(), id -> new byte[]{1, 2, 3}));
        assertThrows(PptFailure.class, () -> engine.renderPng(fixture(), "slide-1", Double.NaN, id -> image()));
        assertThrows(PptFailure.class, () -> apply(fixture(), true,
                "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"body\",\"patch\":{\"fontSize\":0}}"));
        assertThrows(PptFailure.class, () -> apply(fixture(), true,
                "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"chart\",\"patch\":{\"rotation\":90}}"));
    }

    @Test void allChartTypesSupportNegativeAndZeroDataWhereMeaningful() throws Exception {
        for (String type : List.of("bar", "line", "pie")) {
            String values = "pie".equals(type) ? "[10,20]" : "[-10,0]";
            Deck deck = apply(fixture(), true,
                    "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"chart\",\"patch\":{\"chart\":{\"type\":\"" + type
                            + "\",\"categories\":[\"甲\",\"乙\"],\"series\":[{\"name\":\"收益\",\"values\":" + values + "}]}}}").deck();
            assertTrue(engine.exportPptx(deck, id -> image()).length > 1000);
            assertNotNull(ImageIO.read(new ByteArrayInputStream(engine.renderPng(deck, "slide-1", .25, id -> image()))));
        }
    }

    @Test void nativeChartAxesKeepOuterCategorySlotsInsideThePlotAndPieHasNoAxes() throws Exception {
        for (String type : List.of("bar", "line", "pie")) {
            String values = type.equals("pie") ? "[10,20]" : "[-10,20]";
            Deck deck = apply(fixture(), true,
                    "{\"op\":\"update_element\",\"slideId\":\"slide-1\",\"elementId\":\"chart\",\"patch\":{\"chart\":{\"type\":\"" + type
                            + "\",\"categories\":[\"首组\",\"末组\"],\"series\":[{\"name\":\"指标\",\"values\":" + values + "}]}}}").deck();
            try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(engine.exportPptx(deck, id -> image())))) {
                var nativeChart = ppt.getCharts().getFirst();
                assertTrue(nativeChart.getCTChartSpace().validate(), "Exported " + type + " chart must satisfy its OOXML schema");
                var plot = nativeChart.getCTChart().getPlotArea();
                if (type.equals("line")) assertEquals("standard", plot.getLineChartArray(0).getGrouping().getVal().toString());
                if (type.equals("pie")) {
                    assertEquals(0, plot.sizeOfCatAxArray()); assertEquals(0, plot.sizeOfValAxArray());
                    assertEquals(1, plot.sizeOfPieChartArray());
                } else {
                    assertEquals(1, plot.sizeOfCatAxArray()); assertEquals(1, plot.sizeOfValAxArray());
                    var category = plot.getCatAxArray(0); var value = plot.getValAxArray(0);
                    assertEquals("between", value.getCrossBetween().getVal().toString(), "Native " + type + " must preserve half-slot padding at both edges");
                    assertEquals(category.getAxId().getVal(), value.getCrossAx().getVal());
                    assertEquals(value.getAxId().getVal(), category.getCrossAx().getVal());
                    assertEquals(-13., value.getScaling().getMin().getVal(), .001);
                    assertEquals(23., value.getScaling().getMax().getVal(), .001);
                    assertEquals("autoZero", value.getCrosses().getVal().toString());
                }
            }
        }
    }

    @Test void alignmentDistributionAndLayoutsDoNotChangeText() {
        Deck deck = apply(fixture(), true,
                "{\"op\":\"align\",\"slideId\":\"slide-1\",\"elementIds\":[\"body\",\"picture\"],\"alignment\":\"left\"}",
                "{\"op\":\"apply_layout\",\"slideId\":\"slide-1\",\"elementIds\":[\"body\",\"picture\",\"chart\"],\"layout\":\"three_columns\"}").deck();
        assertEquals("交付周期缩短\n投入产出更清晰", deck.slides().getFirst().elements().get(1).text());
        assertEquals(405., deck.slides().getFirst().elements().get(2).height(), .01);
        assertTrue(engine.capabilities().renderingAvailable()); assertEquals(3, engine.capabilities().themes().size());
    }

    private OperationResult apply(Deck deck, boolean agent, String... operations) {
        return engine.applyOperations(deck, Arrays.stream(operations).map(json::readTree).toList(), agent);
    }
    private Deck fixture() {
        return json.readValue("""
                {"title":"项目收益","width":960,"height":540,"theme":"business","slides":[
                  {"id":"slide-1","title":"项目收益","section":"价值","notes":"演讲备注：说明收益来源","elements":[
                    {"id":"title","type":"text","x":40,"y":25,"width":880,"height":65,"text":"项目收益","fontSize":32,"bold":true},
                    {"id":"body","type":"text","x":40,"y":110,"width":300,"height":140,"text":"交付周期缩短\\n投入产出更清晰","fontSize":22,"bullets":true},
                    {"id":"picture","type":"image","x":40,"y":270,"width":260,"height":170,"assetId":"asset-1","fit":"cover"},
                    {"id":"chart","type":"chart","x":365,"y":100,"width":540,"height":280,"chart":{"type":"bar","categories":["一期","二期"],"series":[{"name":"收益","values":[10,20],"color":"246BCE"}]}},
                    {"id":"table","type":"table","x":365,"y":405,"width":540,"height":90,"fontSize":16,"rows":[["指标","结果"],["交付周期","缩短 20%"]]},
                    {"id":"footer","type":"shape","shape":"roundRect","x":40,"y":470,"width":260,"height":36,"text":"来源：项目测算","fontSize":14,"fill":"E8F1FF"}
                  ]}
                ]}
                """, Deck.class);
    }
    private static byte[] image() throws IOException {
        BufferedImage image = new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics();
        graphics.setColor(Color.ORANGE); graphics.fillRect(0, 0, 100, 60); graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "PNG", out); return out.toByteArray();
    }
}
