package io.opencode.loopper.ppt;

import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static io.opencode.loopper.ppt.PptModel.*;

/** Java2D adapter for the chart types not rendered by POI's graphical frame renderer. */
final class PptChartPreview {
    private PptChartPreview() { }
    static void draw(Graphics2D source, Element e, Theme theme, Rectangle2D anchor, PptFonts fonts) {
        Graphics2D g = (Graphics2D) source.create();
        try {
            g.translate(anchor.getX(), anchor.getY()); g.clip(new Rectangle2D.Double(0, 0, anchor.getWidth(), anchor.getHeight()));
            double w = anchor.getWidth(), h = anchor.getHeight();
            g.setColor(PptThemes.color(e.fill(), theme.background())); g.fill(new Rectangle2D.Double(0, 0, w, h));
            g.setFont(fonts.font(PptPoiScene.fontFamily(e), false, 12));
            if ("pie".equals(e.chart().type())) pie(g, e.chart(), theme, w, h);
            else cartesian(g, e.chart(), theme, w, h);
        } finally { g.dispose(); }
    }
    private static void cartesian(Graphics2D g, Chart c, Theme theme, double w, double h) {
        double left = axisWidth(w), top = 16, pw = plotWidth(w), ph = Math.max(1, h - 90);
        double[] range = PptNativeCharts.range(c); double low = range[0], high = range[1];
        g.setStroke(new BasicStroke(1));
        for (int i = 0; i <= 4; i++) {
            double value = low + (high - low) * i / 4, y = top + ph - ph * i / 4;
            g.setColor(new Color(128, 128, 128, 55)); g.draw(new Line2D.Double(left, y, left + pw, y));
            g.setColor(PptThemes.color(theme.muted(), theme.foreground())); text(g, number(value), 0, y + 4, left - 5);
        }
        double slot = pw / c.categories().size(), baseline = top + ph * high / (high - low);
        for (int i = 0; i < c.categories().size(); i++) {
            g.setColor(PptThemes.color(theme.foreground(), theme.foreground()));
            text(g, c.categories().get(i), left + i * slot + 2, top + ph + 20, slot - 4);
        }
        for (int s = 0; s < c.series().size(); s++) {
            Series series = c.series().get(s); g.setColor(PptNativeCharts.seriesColor(series, s, theme));
            Path2D path = new Path2D.Double();
            for (int i = 0; i < series.values().size(); i++) {
                double x = left + (i + .5) * slot, y = top + ph * (high - series.values().get(i)) / (high - low);
                if ("bar".equals(c.type())) {
                    double bw = slot * .72 / c.series().size();
                    g.fill(new Rectangle2D.Double(left + i * slot + slot * .14 + s * bw, Math.min(y, baseline), Math.max(1, bw - 2), Math.max(.5, Math.abs(y - baseline))));
                } else {
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    g.fill(new Ellipse2D.Double(x - 3, y - 3, 6, 6));
                }
            }
            if ("line".equals(c.type())) { g.setStroke(new BasicStroke(2)); g.draw(path); }
        }
        legend(g, c, theme, w, h, false);
    }
    private static void pie(Graphics2D g, Chart c, Theme theme, double w, double h) {
        double size = Math.max(1, Math.min(w - 30, h - 65)), x = (w - size) / 2, y = 8;
        double total = c.series().getFirst().values().stream().mapToDouble(Double::doubleValue).sum(), angle = 90;
        for (int i = 0; i < c.categories().size(); i++) {
            double sweep = -360 * c.series().getFirst().values().get(i) / total;
            g.setColor(PptNativeCharts.palette(i, theme)); g.fill(new Arc2D.Double(x, y, size, size, angle, sweep, Arc2D.PIE)); angle += sweep;
        }
        legend(g, c, theme, w, h, true);
    }
    private static void legend(Graphics2D g, Chart c, Theme theme, double w, double h, boolean pie) {
        int count = pie ? c.categories().size() : c.series().size(); double entry = Math.max(1, (w - 20) / count);
        for (int i = 0; i < count; i++) {
            double x = 10 + i * entry;
            g.setColor(pie ? PptNativeCharts.palette(i, theme) : PptNativeCharts.seriesColor(c.series().get(i), i, theme));
            g.fill(new Rectangle2D.Double(x, h - 23, 9, 9));
            g.setColor(PptThemes.color(theme.foreground(), theme.foreground()));
            text(g, pie ? c.categories().get(i) : c.series().get(i).name(), x + 13, h - 14, entry - 16);
        }
    }
    private static void text(Graphics2D g, String value, double x, double baseline, double maxWidth) {
        if (maxWidth <= 0) return;
        g.drawString(abbreviate(g.getFontMetrics(), value, maxWidth), (float) x, (float) baseline);
    }
    static String abbreviate(FontMetrics metrics, String value, double maxWidth) {
        if (metrics.stringWidth(value) <= maxWidth) return value;
        String rendered = value;
        while (!rendered.isEmpty() && metrics.stringWidth(rendered + "…") > maxWidth)
            rendered = rendered.substring(0, rendered.offsetByCodePoints(rendered.length(), -1));
        return rendered + "…";
    }
    static List<Label> labels(Element e) {
        List<Label> labels = new ArrayList<>(); Chart chart = e.chart();
        boolean pie = "pie".equals(chart.type());
        if (!pie) {
            double slot = plotWidth(e.width()) / chart.categories().size();
            for (int i = 0; i < chart.categories().size(); i++)
                labels.add(new Label("chart.categories[" + i + "]", chart.categories().get(i), slot - 4));
            double[] range = PptNativeCharts.range(chart);
            for (int i = 0; i <= 4; i++) labels.add(new Label("chart.valueAxis[" + i + "]",
                    number(range[0] + (range[1] - range[0]) * i / 4), axisWidth(e.width()) - 5));
        }
        int count = pie ? chart.categories().size() : chart.series().size();
        double available = Math.max(1, (e.width() - 20) / count) - 16;
        for (int i = 0; i < count; i++) labels.add(new Label(pie ? "chart.categories[" + i + "]" : "chart.series[" + i + "].name",
                pie ? chart.categories().get(i) : chart.series().get(i).name(), available));
        return labels;
    }
    private static double axisWidth(double width) { return Math.min(64, width * .18); }
    private static double plotWidth(double width) { return Math.max(1, width - axisWidth(width) - 20); }
    record Label(String field, String value, double availableWidth) { }
    private static String number(double number) {
        if (Math.abs(number) >= 1_000_000) return String.format(Locale.ROOT, "%.1e", number);
        return java.math.BigDecimal.valueOf(number).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
