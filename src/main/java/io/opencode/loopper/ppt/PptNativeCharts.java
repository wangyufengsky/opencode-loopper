package io.opencode.loopper.ppt;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import org.apache.poi.util.Units;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xddf.usermodel.*;
import org.apache.poi.xddf.usermodel.chart.*;
import org.openxmlformats.schemas.presentationml.x2006.main.CTGraphicalObjectFrame;
import static io.opencode.loopper.ppt.PptModel.*;

/** Native charts with embedded editable workbook, using the same explicit palette/range as the preview. */
final class PptNativeCharts {
    private PptNativeCharts() { }
    static CTGraphicalObjectFrame add(XMLSlideShow show, XSLFSlide slide, Element element, Theme theme) {
        Chart spec = element.chart(); XSLFChart chart = show.createChart(slide);
        XSSFWorkbook workbook = new XSSFWorkbook(); var sheet = workbook.createSheet("Data");
        var header = sheet.createRow(0); header.createCell(0).setCellValue("分类");
        for (int s = 0; s < spec.series().size(); s++) header.createCell(s + 1).setCellValue(spec.series().get(s).name());
        for (int i = 0; i < spec.categories().size(); i++) {
            var row = sheet.createRow(i + 1); row.createCell(0).setCellValue(spec.categories().get(i));
            for (int s = 0; s < spec.series().size(); s++) row.createCell(s + 1).setCellValue(spec.series().get(s).values().get(i));
        }
        chart.setWorkbook(workbook); chart.setAutoTitleDeleted(true); chartText(chart, theme, PptPoiScene.fontFamily(element));
        XDDFChartData data = data(chart, spec);
        if (data instanceof XDDFBarChartData bars) { bars.setBarDirection(BarDirection.COL); bars.setBarGrouping(BarGrouping.CLUSTERED); bars.setGapWidth(80); }
        if (data instanceof XDDFLineChartData lines) lines.setGrouping(Grouping.STANDARD);
        if (data instanceof XDDFPieChartData pie) pie.setVaryColors(true);
        var categories = XDDFDataSourcesFactory.fromStringCellRange(sheet, new CellRangeAddress(1, spec.categories().size(), 0, 0));
        for (int s = 0; s < spec.series().size(); s++) {
            var source = spec.series().get(s);
            var values = XDDFDataSourcesFactory.fromNumericCellRange(sheet, new CellRangeAddress(1, spec.categories().size(), s + 1, s + 1));
            var series = data.addSeries(categories, values); series.setTitle(source.name(), chart.setSheetTitle(source.name(), s + 1));
            XDDFSolidFillProperties fill = solid(seriesColor(source, s, theme)); series.setFillProperties(fill);
            XDDFLineProperties line = new XDDFLineProperties(); line.setFillProperties(fill); line.setWidth(2.); series.setLineProperties(line);
            if (series instanceof XDDFLineChartData.Series ls) { ls.setSmooth(false); ls.setMarkerStyle(MarkerStyle.CIRCLE); }
            if ("pie".equals(spec.type())) for (int i = 0; i < spec.categories().size(); i++) {
                XDDFShapeProperties props = new XDDFShapeProperties(); props.setFillProperties(solid(palette(i, theme)));
                series.getDataPoint(i).setShapeProperties(props);
            }
        }
        chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM); chart.plot(data);
        chart.getOrAddShapeProperties().setFillProperties(solid(PptThemes.color(element.fill(), theme.background())));
        // Unlike XSLFShape.setAnchor, addChart takes raw EMUs in POI 5.5.1.
        slide.addChart(chart, new Rectangle2D.Double(Units.toEMU(element.x()), Units.toEMU(element.y()),
                Units.toEMU(element.width()), Units.toEMU(element.height())));
        var tree = slide.getXmlObject().getCSld().getSpTree();
        return tree.getGraphicFrameArray(tree.sizeOfGraphicFrameArray() - 1);
    }
    private static XDDFChartData data(XSLFChart chart, Chart spec) {
        if ("pie".equals(spec.type())) return chart.createData(ChartTypes.PIE, null, null);
        var category = chart.createCategoryAxis(AxisPosition.BOTTOM);
        var value = chart.createValueAxis(AxisPosition.LEFT);
        double[] range = range(spec); value.setMinimum(range[0]); value.setMaximum(range[1]); value.setCrosses(AxisCrosses.AUTO_ZERO);
        // POI defaults to MIDPOINT_CATEGORY, which clips the outer bar clusters in WPS.
        // Both cartesian previews position category centers inside full slots, including line markers.
        value.setCrossBetween(AxisCrossBetween.BETWEEN);
        category.crossAxis(value); value.crossAxis(category);
        category.getOrAddTextProperties().setFontSize(12.); value.getOrAddTextProperties().setFontSize(12.);
        return chart.createData("bar".equals(spec.type()) ? ChartTypes.BAR : ChartTypes.LINE, category, value);
    }
    private static void chartText(XSLFChart chart, Theme theme, String fontFamily) {
        var body = chart.getCTChartSpace().addNewTxPr(); body.addNewBodyPr(); body.addNewLstStyle();
        var run = body.addNewP().addNewPPr().addNewDefRPr(); run.setSz(1200);
        run.addNewLatin().setTypeface(fontFamily); run.addNewEa().setTypeface(fontFamily);
        Color color = PptThemes.color(theme.foreground(), theme.foreground());
        run.addNewSolidFill().addNewSrgbClr().setVal(new byte[]{(byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue()});
    }
    static double[] range(Chart chart) {
        double min = Math.min(0, chart.series().stream().flatMap(s -> s.values().stream()).mapToDouble(Double::doubleValue).min().orElse(0));
        double max = Math.max(0, chart.series().stream().flatMap(s -> s.values().stream()).mapToDouble(Double::doubleValue).max().orElse(0));
        if (min == max) max = min + 1;
        double padding = (max - min) * .1; return new double[]{min < 0 ? min - padding : 0, max > 0 ? max + padding : 0};
    }
    static Color seriesColor(Series series, int index, Theme theme) { return PptThemes.color(series.color(), theme.palette().get(index % theme.palette().size())); }
    static Color palette(int index, Theme theme) { return PptThemes.color(theme.palette().get(index % theme.palette().size()), theme.accent()); }
    private static XDDFSolidFillProperties solid(Color color) { return new XDDFSolidFillProperties(XDDFColor.from(new byte[]{(byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue()})); }
}
