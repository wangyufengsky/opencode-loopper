package io.opencode.loopper.ppt;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xslf.usermodel.*;
import static io.opencode.loopper.ppt.PptModel.*;

/** Geometry and font measurements are evidence for this renderer, not an Office visual verdict. */
final class PptLayoutChecks {
    private final PptFonts fonts;
    PptLayoutChecks(PptFonts fonts) { this.fonts = fonts; }
    Validation validate(Deck deck) {
        PptSceneValidation.require(deck, fonts); fonts.initialize();
        List<Issue> issues = new ArrayList<>();
        try (XMLSlideShow show = new XMLSlideShow()) {
            XSLFSlide scratch = show.createSlide();
            for (Slide slide : deck.slides()) {
                for (Element e : slide.elements()) {
                    Rectangle2D b = bounds(e);
                    if (b.getMinX() < -.1 || b.getMinY() < -.1 || b.getMaxX() > deck.width() + .1 || b.getMaxY() > deck.height() + .1)
                        issue(issues, "ERROR", "OUT_OF_BOUNDS", slide, e, "对象超出页面边界");
                    if (!e.text().isEmpty() && List.of("text", "shape").contains(e.type())) checkText(deck, scratch, slide, e, issues);
                    if ("table".equals(e.type())) checkTable(deck, scratch, slide, e, issues);
                    Issue contrast = PptContrastChecks.check(deck, slide, e);
                    if (contrast != null) issue(issues, contrast.severity(), contrast.code(), slide, e, contrast.message());
                    if ("chart".equals(e.type())) for (Issue label : PptChartLabelChecks.check(slide, e, fonts))
                        issue(issues, label.severity(), label.code(), slide, e, label.message());
                    if ("chart".equals(e.type()) && (e.width() < 240 || e.height() < 160 || e.chart().categories().size() > 10
                            || e.chart().categories().stream().anyMatch(c -> c.length() > 14)
                            || e.chart().series().stream().anyMatch(s -> s.name().length() > 14)))
                        issue(issues, "WARNING", "CHART_DENSITY", slide, e, "图表空间较小或分类较多，请检查图例与标签可读性");
                }
                overlaps(slide, issues);
            }
            return new Validation(issues);
        } catch (PptFailure failure) { throw failure; }
        catch (Exception failure) { throw new PptFailure("PPT_MEASURE_FAILED", "排版检查失败", failure); }
    }
    TextMeasurement measure(Deck deck, Element e) {
        PptSceneValidation.require(new Deck(deck.title(), deck.width(), deck.height(), deck.theme(),
                List.of(new Slide("measure-slide".equals(e.id()) ? "measure-slide-2" : "measure-slide", "", "", "", false, List.of(e)))), fonts);
        try (XMLSlideShow show = new XMLSlideShow()) { return measure(deck, show.createSlide(), e); }
        catch (PptFailure failure) { throw failure; }
        catch (Exception failure) { throw new PptFailure("PPT_MEASURE_FAILED", "文字测量失败", failure); }
    }
    private TextMeasurement measure(Deck deck, XSLFSlide scratch, Element e) {
        Font font = fonts.font(e.fontFamily(), e.bold(), PptPoiScene.fontSize(e));
        List<String> missing = e.text().codePoints().filter(c -> !Character.isWhitespace(c) && !font.canDisplay(c))
                .distinct().limit(20).mapToObj(c -> new String(Character.toChars(c))).toList();
        XSLFTextBox text = scratch.createTextBox(); PptPoiScene.text(text, e, PptThemes.find(deck.theme()));
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB); Graphics2D graphics = image.createGraphics();
        try {
            PptRenderer.configure(graphics); double required = text.getTextHeight(graphics), available = Math.max(0, e.height() - 12);
            return new TextMeasurement(required, available, required > available + .1, font.getFamily(java.util.Locale.ROOT), missing);
        } finally { graphics.dispose(); image.flush(); scratch.removeShape(text); }
    }
    private void checkText(Deck deck, XSLFSlide scratch, Slide slide, Element e, List<Issue> issues) {
        TextMeasurement m = measure(deck, scratch, e);
        if (m.overflow()) issue(issues, "ERROR", "TEXT_OVERFLOW", slide, e,
                String.format(java.util.Locale.ROOT, "文字需要 %.1f 点高度，可用 %.1f 点；请缩写、扩大文本框或拆页", m.requiredHeight(), m.availableHeight()));
        if (!m.missingCharacters().isEmpty()) issue(issues, "ERROR", "MISSING_GLYPHS", slide, e, "字体缺少字符：" + String.join("", m.missingCharacters()) + "；请选择包含这些字符的字体，或由管理员配置 loopper.ppt.font-dir 后重试");
        if (PptPoiScene.fontSize(e) < 14) issue(issues, "WARNING", "SMALL_TEXT", slide, e, "字号小于 14 点，请检查投影可读性");
    }
    private void checkTable(Deck deck, XSLFSlide scratch, Slide slide, Element e, List<Issue> issues) {
        for (int rowIndex = 0; rowIndex < e.rows().size(); rowIndex++) for (String cell : e.rows().get(rowIndex)) {
            Element text = new Element(e.id(), "text", 0, 0, e.width() / e.rows().getFirst().size(), e.height() / e.rows().size(),
                    cell, null, e.fontFamily(), e.fontSize(), e.color(), null, rowIndex == 0 || e.bold(), "left", null, null, 0,
                    false, false, null, null, null, false, null, null);
            TextMeasurement result = measure(deck, scratch, text);
            if (result.overflow()) { issue(issues, "ERROR", "TABLE_OVERFLOW", slide, e, "表格至少一个单元格文字溢出，请扩大表格或减少内容"); return; }
            if (!result.missingCharacters().isEmpty()) { issue(issues, "ERROR", "MISSING_GLYPHS", slide, e, "表格字体缺少部分字符"); return; }
        }
    }
    private static void overlaps(Slide slide, List<Issue> issues) {
        for (int i = 0; i < slide.elements().size(); i++) for (int j = i + 1; j < slide.elements().size(); j++) {
            Element a = slide.elements().get(i), b = slide.elements().get(j);
            if (a.allowOverlap() || b.allowOverlap() || "line".equals(a.type()) || "line".equals(b.type())) continue;
            Rectangle2D intersection = bounds(a).createIntersection(bounds(b));
            if (intersection.getWidth() > 2 && intersection.getHeight() > 2)
                issue(issues, "WARNING", "OVERLAP", slide, a, "与对象 " + b.id() + " 重叠；若为有意覆盖，请声明 allowOverlap");
        }
    }
    private static Rectangle2D bounds(Element e) {
        Rectangle2D rect = PptPoiScene.rect(e);
        return AffineTransform.getRotateInstance(Math.toRadians(e.rotation()), rect.getCenterX(), rect.getCenterY()).createTransformedShape(rect).getBounds2D();
    }
    private static void issue(List<Issue> issues, String severity, String code, Slide slide, Element e, String message) {
        if (issues.size() < 499) issues.add(new Issue(severity, code, slide.id(), e.id(), message));
        else if (issues.size() == 499) issues.add(new Issue("ERROR", "TOO_MANY_ISSUES", slide.id(), null, "问题超过显示上限，请先修复当前问题后重检"));
    }
}
