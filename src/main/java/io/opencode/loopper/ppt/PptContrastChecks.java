package io.opencode.loopper.ppt;

import java.awt.Color;
import java.util.Locale;
import static io.opencode.loopper.ppt.PptModel.*;

/** Conservative color evidence; overlapping translucent scenes still need a visual check. */
final class PptContrastChecks {
    private PptContrastChecks() { }

    static Issue check(Deck deck, Slide slide, Element element) {
        Theme page = PptThemes.resolve(slide.theme(), PptThemes.find(deck.theme()));
        Theme style = PptThemes.resolve(element.theme(), page);
        if ("table".equals(element.type())) return table(slide, element, style);
        if (element.text().isBlank() || !("text".equals(element.type()) || "shape".equals(element.type()))) return null;
        String background = "shape".equals(element.type()) ? style.accent() : page.background();
        double ratio = ratio(PptThemes.color(element.color(), style.foreground()), PptThemes.color(element.fill(), background));
        boolean definite = "text".equals(element.type()) && (present(element.fill()) || slide.elements().stream()
                .noneMatch(other -> other != element && !"line".equals(other.type())
                        && PptPoiScene.rect(element).intersects(PptPoiScene.rect(other))));
        double threshold = largeText(element) ? 3 : 4.5;
        return ratio < threshold ? issue(slide, element, ratio, definite, "文字", !definite && "text".equals(element.type())) : null;
    }

    private static Issue table(Slide slide, Element element, Theme theme) {
        double worst = Double.POSITIVE_INFINITY;
        String area = "表头";
        if (element.rows().getFirst().stream().anyMatch(value -> !value.isBlank()))
            worst = ratio(Color.WHITE, PptThemes.color(theme.accent(), theme.accent()));
        if (element.rows().stream().skip(1).flatMap(java.util.Collection::stream).anyMatch(value -> !value.isBlank())) {
            double body = ratio(PptThemes.color(element.color(), theme.foreground()), PptThemes.color(element.fill(), theme.background()));
            if (body < worst) { worst = body; area = "表格正文"; }
        }
        return worst < (largeText(element) ? 3 : 4.5) ? issue(slide, element, worst, false, area, false) : null;
    }

    private static Issue issue(Slide slide, Element element, double ratio, boolean definite, String area, boolean estimated) {
        String message = String.format(Locale.ROOT, "%s与背景的对比度约 %.2f:1，可能难以辨认。", area, ratio);
        if (estimated) message += "存在重叠对象，此处仅按页面背景估算，请检查实际预览。";
        if (present(element.color()) || present(element.fill()))
            message += "显式 color/fill 不会随主题自动换色；请调整颜色或设为 null 以跟随主题。";
        else message += "请调整文字或背景颜色，并检查实际预览。";
        return new Issue(definite && ratio < 3 ? "ERROR" : "WARNING", "LOW_CONTRAST", slide.id(), element.id(), message);
    }

    private static boolean largeText(Element element) {
        return PptPoiScene.fontSize(element) >= 18 || element.bold() && PptPoiScene.fontSize(element) >= 14;
    }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static double ratio(Color first, Color second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }
    private static double luminance(Color color) {
        return .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen()) + .0722 * linear(color.getBlue());
    }
    private static double linear(int component) {
        double value = component / 255.;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }
}
