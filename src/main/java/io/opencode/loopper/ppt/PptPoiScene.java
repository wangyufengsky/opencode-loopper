package io.opencode.loopper.ppt;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.poi.sl.usermodel.*;
import org.apache.poi.common.usermodel.fonts.FontGroup;
import org.apache.poi.xslf.usermodel.*;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.presentationml.x2006.main.*;
import static io.opencode.loopper.ppt.PptModel.*;

/** Compiles the owned scene to editable OOXML objects; assets are resolved by ID only. */
final class PptPoiScene {
    private PptPoiScene() { }
    static XMLSlideShow build(Deck deck, PptEngine.AssetResolver assets, PptFonts fonts) throws IOException {
        PptSceneValidation.require(deck, fonts); fonts.initialize();
        XMLSlideShow show = new XMLSlideShow();
        try {
            show.setPageSize(new Dimension((int) Math.round(deck.width()), (int) Math.round(deck.height())));
            Theme deckTheme = PptThemes.find(deck.theme());
            for (PptModel.Slide page : deck.slides()) {
                Theme theme = PptThemes.resolve(page.theme(), deckTheme);
                XSLFSlide slide = show.createSlide(); slide.getBackground().setFillColor(PptThemes.color(theme.background(), theme.background()));
                Map<String, List<CompiledShape>> groups = new LinkedHashMap<>();
                for (Element element : page.elements()) {
                    XmlObject shape = add(show, slide, element, PptThemes.resolve(element.theme(), theme), assets);
                    name(shape, element.id());
                    if (element.groupId() != null) groups.computeIfAbsent(element.groupId(), unused -> new ArrayList<>()).add(new CompiledShape(shape, element));
                }
                for (var entry : groups.entrySet()) group(slide, entry.getKey(), entry.getValue());
                if (!page.notes().isBlank()) notes(show, slide, page.notes());
            }
            return show;
        } catch (Exception failure) { show.close(); throw failure; }
    }
    private static XmlObject add(XMLSlideShow show, XSLFSlide slide, Element e, Theme theme, PptEngine.AssetResolver assets) throws IOException {
        return switch (e.type()) {
            case "text" -> { XSLFTextBox box = slide.createTextBox(); text(box, e, theme); yield box.getXmlObject(); }
            case "shape" -> shape(slide, e, theme).getXmlObject();
            case "line" -> line(slide, e, theme).getXmlObject();
            case "image" -> image(show, slide, e, assets).getXmlObject();
            case "table" -> table(slide, e, theme).getXmlObject();
            case "chart" -> PptNativeCharts.add(show, slide, e, theme);
            default -> throw new PptFailure("PPT_INVALID_SCENE", "不支持的页面对象");
        };
    }
    static void text(XSLFTextShape box, Element e, Theme theme) {
        box.setAnchor(rect(e)); box.setRotation(e.rotation()); box.clearText();
        box.setLeftInset(6); box.setRightInset(6); box.setTopInset(6); box.setBottomInset(6);
        box.setWordWrap(true); box.setTextAutofit(TextShape.TextAutofit.NONE);
        box.setVerticalAlignment(VerticalAlignment.TOP);
        if (e.fill() != null && !e.fill().isBlank()) box.setFillColor(PptThemes.color(e.fill(), theme.background()));
        for (String line : e.text().split("\n", -1)) {
            XSLFTextParagraph p = box.addNewTextParagraph();
            p.setTextAlign(switch (Objects.toString(e.align(), "left")) {
                case "center" -> TextParagraph.TextAlign.CENTER;
                case "right" -> TextParagraph.TextAlign.RIGHT;
                default -> TextParagraph.TextAlign.LEFT;
            });
            p.setSpaceBefore(0.); p.setSpaceAfter(0.); p.setLineSpacing(115.);
            if (e.bullets()) { p.setBullet(true); p.setBulletFont(PptFonts.DEFAULT); p.setLeftMargin(fontSize(e)); p.setIndent(-fontSize(e) * .7); }
            XSLFTextRun run = p.addNewTextRun(); run.setText(line); run.setFontFamily(fontFamily(e));
            run.setFontFamily(fontFamily(e), FontGroup.EAST_ASIAN);
            run.setFontSize(fontSize(e)); run.setBold(e.bold()); run.setFontColor(PptThemes.color(e.color(), theme.foreground()));
        }
    }
    private static XSLFAutoShape shape(XSLFSlide slide, Element e, Theme theme) {
        XSLFAutoShape shape = slide.createAutoShape();
        shape.setShapeType(switch (Objects.toString(e.shape(), "rect")) {
            case "roundRect" -> ShapeType.ROUND_RECT;
            case "ellipse" -> ShapeType.ELLIPSE;
            case "arrow" -> ShapeType.RIGHT_ARROW;
            default -> ShapeType.RECT;
        });
        text(shape, e, theme); shape.setFillColor(PptThemes.color(e.fill(), theme.accent()));
        shape.setLineColor(e.stroke() == null ? null : PptThemes.color(e.stroke(), theme.accent()));
        shape.setLineWidth(e.lineWidth() == null ? 1 : e.lineWidth()); return shape;
    }
    private static XSLFConnectorShape line(XSLFSlide slide, Element e, Theme theme) {
        XSLFConnectorShape line = slide.createConnector(); line.setAnchor(rect(e)); line.setRotation(e.rotation());
        line.setLineColor(PptThemes.color(e.stroke() == null ? e.color() : e.stroke(), theme.accent()));
        line.setLineWidth(e.lineWidth() == null ? 2 : e.lineWidth());
        if ("arrow".equals(e.shape())) line.setLineTailDecoration(LineDecoration.DecorationShape.TRIANGLE);
        return line;
    }
    private static XSLFPictureShape image(XMLSlideShow show, XSLFSlide slide, Element e, PptEngine.AssetResolver assets) throws IOException {
        if (assets == null) throw new PptFailure("PPT_ASSET_MISSING", "缺少图片素材读取器");
        byte[] bytes = assets.read(e.assetId());
        if (bytes == null || bytes.length == 0 || bytes.length > 20 * 1024 * 1024) throw new PptFailure("PPT_ASSET_INVALID", "图片为空或超过 20 MiB");
        ImageSize size = dimensions(bytes);
        PictureData.PictureType type = bytes.length > 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50
                ? PictureData.PictureType.PNG : PictureData.PictureType.JPEG;
        XSLFPictureShape picture = slide.createPicture(show.addPicture(bytes, type));
        double ratio = (double) size.width / size.height, boxRatio = e.width() / e.height();
        Rectangle2D anchor = rect(e);
        if ("cover".equals(e.fit())) {
            var src = ((CTPicture) picture.getXmlObject()).getBlipFill().addNewSrcRect();
            if (ratio > boxRatio) { int crop = (int) Math.round((1 - boxRatio / ratio) * 50000); src.setL(crop); src.setR(crop); }
            else { int crop = (int) Math.round((1 - ratio / boxRatio) * 50000); src.setT(crop); src.setB(crop); }
        } else {
            double width = ratio > boxRatio ? e.width() : e.height() * ratio;
            double height = ratio > boxRatio ? e.width() / ratio : e.height();
            anchor = new Rectangle2D.Double(e.x() + (e.width() - width) / 2, e.y() + (e.height() - height) / 2, width, height);
        }
        picture.setAnchor(anchor); picture.setRotation(e.rotation()); return picture;
    }
    private static ImageSize dimensions(byte[] bytes) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new PptFailure("PPT_ASSET_INVALID", "无法解码图片");
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!Set.of("png", "jpeg", "jpg").contains(format.toLowerCase(Locale.ROOT))) throw new PptFailure("PPT_ASSET_INVALID", "仅支持 PNG/JPEG 图片");
                reader.setInput(input); int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || (long) w * h > 40_000_000) throw new PptFailure("PPT_ASSET_INVALID", "图片像素数量超过限制");
                return new ImageSize(w, h);
            } finally { reader.dispose(); }
        }
    }
    private static XSLFTable table(XSLFSlide slide, Element e, Theme theme) {
        XSLFTable table = slide.createTable(e.rows().size(), e.rows().getFirst().size()); table.setAnchor(rect(e));
        int columns = e.rows().getFirst().size();
        for (int c = 0; c < columns; c++) table.setColumnWidth(c, e.width() / columns);
        for (int r = 0; r < e.rows().size(); r++) {
            XSLFTableRow row = table.getRows().get(r); row.setHeight(e.height() / e.rows().size());
            for (int c = 0; c < columns; c++) {
                XSLFTableCell cell = row.getCells().get(c); cell.setText(e.rows().get(r).get(c));
                cell.setTopInset(5); cell.setBottomInset(5); cell.setLeftInset(6); cell.setRightInset(6);
                cell.setFillColor(PptThemes.color(r == 0 ? theme.accent() : e.fill(), theme.background()));
                for (XSLFTextParagraph paragraph : cell.getTextParagraphs()) for (XSLFTextRun run : paragraph.getTextRuns()) {
                    run.setFontFamily(fontFamily(e)); run.setFontSize(fontSize(e)); run.setBold(r == 0 || e.bold());
                    run.setFontFamily(fontFamily(e), FontGroup.EAST_ASIAN);
                    run.setFontColor(PptThemes.color(r == 0 ? "FFFFFF" : e.color(), theme.foreground()));
                }
            }
        }
        return table;
    }
    private static void notes(XMLSlideShow show, XSLFSlide slide, String text) {
        XSLFNotes notes = show.getNotesSlide(slide);
        for (XSLFShape shape : notes.getShapes()) if (shape instanceof XSLFTextShape box && box.getTextType() == Placeholder.BODY) {
            box.setText(text); return;
        }
        XSLFTextBox box = notes.createTextBox(); box.setPlaceholder(Placeholder.BODY); box.setText(text);
    }
    private static void group(XSLFSlide slide, String id, List<CompiledShape> members) {
        XSLFGroupShape group = slide.createGroup();
        Rectangle2D bounds = null;
        for (CompiledShape member : members) {
            Rectangle2D anchor = rect(member.element()); bounds = bounds == null ? anchor : bounds.createUnion(anchor);
        }
        if (bounds == null) throw new PptFailure("PPT_INVALID_SCENE", "分组缺少可定位对象");
        group.setAnchor(bounds); group.setInteriorAnchor(bounds); name(group.getXmlObject(), id);
        CTGroupShape xml = (CTGroupShape) group.getXmlObject();
        for (CompiledShape member : members) {
            var child = member.xml();
            if (child instanceof CTShape shape) xml.addNewSp().set(shape);
            else if (child instanceof CTPicture picture) xml.addNewPic().set(picture);
            else if (child instanceof CTConnector connector) xml.addNewCxnSp().set(connector);
            else if (child instanceof CTGraphicalObjectFrame frame) xml.addNewGraphicFrame().set(frame);
            else throw new PptFailure("PPT_INVALID_SCENE", "对象无法分组");
        }
        // Moving an XMLBeans node invalidates its old Java wrapper, so populate it before the final move.
        try (var source = xml.newCursor(); var target = members.getFirst().xml().newCursor()) { source.moveXml(target); }
        // Removing the XML nodes directly preserves chart/image relationships needed by the copied children.
        for (CompiledShape member : members) try (var cursor = member.xml().newCursor()) { cursor.removeXml(); }
    }
    private static void name(XmlObject xml, String id) {
        if (xml instanceof CTShape s) s.getNvSpPr().getCNvPr().setName(id);
        else if (xml instanceof CTPicture s) s.getNvPicPr().getCNvPr().setName(id);
        else if (xml instanceof CTConnector s) s.getNvCxnSpPr().getCNvPr().setName(id);
        else if (xml instanceof CTGraphicalObjectFrame s) s.getNvGraphicFramePr().getCNvPr().setName(id);
        else if (xml instanceof CTGroupShape s) s.getNvGrpSpPr().getCNvPr().setName(id);
    }
    static Rectangle2D rect(Element e) { return new Rectangle2D.Double(e.x(), e.y(), e.width(), e.height()); }
    static double fontSize(Element e) { return e.fontSize() == null ? 24 : e.fontSize(); }
    static String fontFamily(Element e) { return e.fontFamily() == null || e.fontFamily().isBlank() ? PptFonts.DEFAULT : e.fontFamily(); }
    private record ImageSize(int width, int height) { }
    private record CompiledShape(XmlObject xml, Element element) { }
}
