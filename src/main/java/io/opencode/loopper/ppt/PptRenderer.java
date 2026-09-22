package io.opencode.loopper.ppt;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.poi.sl.draw.*;
import org.apache.poi.sl.usermodel.GraphicalFrame;
import org.apache.poi.xslf.usermodel.*;
import static io.opencode.loopper.ppt.PptModel.*;

/** Bounded binary rendering. A new object graph per call avoids POI thread-safety hazards. */
final class PptRenderer {
    private final PptFonts fonts;
    PptRenderer(PptFonts fonts) { this.fonts = fonts; }
    byte[] pptx(Deck deck, PptEngine.AssetResolver assets) {
        try (XMLSlideShow show = PptPoiScene.build(deck, assets, fonts); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            show.write(output); return output.toByteArray();
        } catch (PptFailure failure) { throw failure; }
        catch (Exception failure) { throw new PptFailure("PPT_EXPORT_FAILED", "PPTX 生成失败：" + failure.getClass().getSimpleName(), failure); }
    }
    byte[] png(Deck deck, String slideId, double scale, PptEngine.AssetResolver assets) {
        PptSceneValidation.require(deck, fonts);
        if (!Double.isFinite(scale) || scale < .25 || scale > 3) throw new PptFailure("PPT_INVALID_SCALE", "预览比例必须在 0.25–3 之间");
        Slide scene = deck.slides().stream().filter(s -> s.id().equals(slideId)).findFirst()
                .orElseThrow(() -> new PptFailure("PPT_OBJECT_NOT_FOUND", "页面不存在"));
        int width = (int) Math.ceil(deck.width() * scale), height = (int) Math.ceil(deck.height() * scale);
        if ((long) width * height > 16_000_000) throw new PptFailure("PPT_INVALID_SCALE", "预览超过 1600 万像素");
        Deck single = new Deck(deck.title(), deck.width(), deck.height(), deck.theme(), java.util.List.of(scene));
        byte[] encoded = pptx(single, assets);
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(encoded)); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB); Graphics2D graphics = image.createGraphics();
            try {
                configure(graphics); graphics.scale(scale, scale);
                graphics.setRenderingHint(Drawable.DRAW_FACTORY, chartFactory(scene, PptThemes.resolve(scene.theme(), PptThemes.find(deck.theme()))));
                show.getSlides().getFirst().draw(graphics);
            } finally { graphics.dispose(); }
            ImageIO.write(image, "PNG", output); image.flush(); return output.toByteArray();
        } catch (PptFailure failure) { throw failure; }
        catch (Exception failure) { throw new PptFailure("PPT_PREVIEW_FAILED", "页面预览失败：" + failure.getClass().getSimpleName(), failure); }
    }
    static void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setRenderingHint(Drawable.FONT_FALLBACK, Map.of("*", PptFonts.DEFAULT));
    }
    private DrawFactory chartFactory(Slide slide, Theme theme) {
        Map<String, Element> charts = new HashMap<>();
        slide.elements().stream().filter(e -> "chart".equals(e.type())).forEach(e -> charts.put(e.id(), e));
        return new DrawFactory() {
            @Override public DrawGraphicalFrame getDrawable(GraphicalFrame<?, ?> frame) {
                Element element = charts.get(frame.getShapeName());
                if (element == null) return super.getDrawable(frame);
                return new DrawGraphicalFrame(frame) {
                    @Override public void draw(Graphics2D graphics) { PptChartPreview.draw(graphics, element, PptThemes.resolve(element.theme(), theme), frame.getAnchor(), fonts); }
                };
            }
        };
    }
}
