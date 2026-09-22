package io.opencode.loopper.ppt;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static io.opencode.loopper.ppt.PptModel.*;

/** Measures the bounded preview label slots, making every ellipsis explicit to the text-only agent. */
final class PptChartLabelChecks {
    private PptChartLabelChecks() { }
    static List<Issue> check(Slide slide, Element element, PptFonts fonts) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scratch.createGraphics(); List<Issue> issues = new ArrayList<>();
        try {
            PptRenderer.configure(graphics); graphics.setFont(fonts.font(PptPoiScene.fontFamily(element), false, 12));
            for (var label : PptChartPreview.labels(element)) {
                double required = graphics.getFontMetrics().stringWidth(label.value());
                if (required > label.availableWidth()) issues.add(new Issue("ERROR", "CHART_LABEL_OVERFLOW", slide.id(), element.id(),
                        String.format(Locale.ROOT, "%s 标签“%s”需要 %.1f 点宽度，可用 %.1f 点；预览将显示省略号。请加宽图表、减少分类/系列或显式提供较短标签，再重新检查。",
                                label.field(), label.value(), required, Math.max(0, label.availableWidth()))));
                if (graphics.getFont().canDisplayUpTo(label.value()) >= 0) issues.add(new Issue("ERROR", "MISSING_GLYPHS", slide.id(), element.id(),
                        label.field() + " 的字体缺少部分字符；请选择包含这些字符的字体，或由管理员配置 loopper.ppt.font-dir 后重试"));
            }
            return issues;
        } finally { graphics.dispose(); scratch.flush(); }
    }
}
