package io.opencode.loopper.ppt;

import java.util.HashSet;
import java.util.Set;
import static io.opencode.loopper.ppt.PptModel.*;

/** Hard scene validity, independent of layout quality. Invalid scenes never persist. */
final class PptSceneValidation {
    private PptSceneValidation() { }
    static void require(Deck deck, PptFonts fonts) {
        check(deck != null, "演示文稿不能为空");
        check(finite(deck.width()) && finite(deck.height()) && deck.width() >= 100 && deck.width() <= 4000
                && deck.height() >= 100 && deck.height() <= 4000, "页面尺寸必须在 100–4000 点之间");
        PptThemes.find(deck.theme());
        check(deck.title().length() <= 300, "标题超过 300 字符");
        check(deck.slides().size() <= 100, "最多支持 100 页");
        Set<String> ids = new HashSet<>();
        for (Slide slide : deck.slides()) {
            if (slide.theme() != null) PptThemes.find(slide.theme());
            identifier(slide.id(), ids);
            check(slide.title().length() <= 300 && slide.section().length() <= 300 && slide.notes().length() <= 20000, "页面标题、章节或讲稿过长");
            check(slide.elements().size() <= 200, "每页最多 200 个对象");
            for (Element element : slide.elements()) element(element, ids, fonts);
        }
    }
    private static void element(Element e, Set<String> ids, PptFonts fonts) {
        if (e.theme() != null) PptThemes.find(e.theme());
        identifier(e.id(), ids);
        check(Set.of("text", "image", "shape", "table", "chart", "line").contains(e.type() == null ? "" : e.type()), "不支持的对象类型");
        check(finite(e.x()) && finite(e.y()) && finite(e.width()) && finite(e.height()) && finite(e.rotation()), "几何参数必须是有限数字");
        check(e.width() > 0 && e.height() > 0 && e.width() <= 8000 && e.height() <= 8000
                && Math.abs(e.x()) <= 8000 && Math.abs(e.y()) <= 8000, "对象尺寸无效");
        check(e.rotation() >= -360 && e.rotation() <= 360, "旋转角度超限");
        check(!Set.of("table", "chart").contains(e.type()) || e.rotation() == 0, "表格和原生图表暂不支持旋转");
        check(e.text().length() <= 10000, "单个对象文字超过 10000 字符");
        check(e.fontSize() == null || finite(e.fontSize()) && e.fontSize() >= 8 && e.fontSize() <= 144, "字号必须在 8–144 点之间");
        if (e.fontFamily() != null && !e.fontFamily().isBlank() && !fonts.families().contains(e.fontFamily()))
            throw new PptFailure("PPT_INVALID_FONT", "字体不可用；请从字体目录选择，或配置 loopper.ppt.font-dir 后重启服务");
        check(e.lineWidth() == null || finite(e.lineWidth()) && e.lineWidth() >= 0 && e.lineWidth() <= 30, "线宽无效");
        color(e.color()); color(e.fill()); color(e.stroke());
        choice(e.align(), Set.of("left", "center", "right"), "对齐方式");
        choice(e.fit(), Set.of("contain", "cover"), "图片适配方式");
        choice(e.shape(), Set.of("rect", "roundRect", "ellipse", "arrow"), "图形");
        check(e.groupId() == null || e.groupId().matches("[A-Za-z0-9_-]{1,100}"), "分组 ID 无效");
        if ("image".equals(e.type())) check(e.assetId() != null && e.assetId().matches("[A-Za-z0-9_-]{1,100}"), "图片必须引用受管素材 ID");
        if ("table".equals(e.type())) table(e);
        if ("chart".equals(e.type())) chart(e.chart());
    }
    private static void table(Element e) {
        check(!e.rows().isEmpty() && e.rows().size() <= 30, "表格需要 1–30 行");
        int columns = e.rows().getFirst().size();
        check(columns > 0 && columns <= 12, "表格需要 1–12 列");
        for (var row : e.rows()) {
            check(row.size() == columns, "表格各行列数必须相同");
            for (String cell : row) check(cell != null && cell.length() <= 2000, "单元格内容无效或过长");
        }
    }
    private static void chart(Chart c) {
        check(c != null && Set.of("bar", "line", "pie").contains(c.type() == null ? "" : c.type()), "不支持的图表类型");
        check(!c.categories().isEmpty() && c.categories().size() <= 30 && !c.series().isEmpty() && c.series().size() <= 8, "图表需 1–30 类、1–8 个系列");
        if ("pie".equals(c.type())) check(c.series().size() == 1, "饼图仅支持一个系列");
        for (String category : c.categories()) check(category != null && category.length() <= 100, "图表分类标签无效");
        for (Series series : c.series()) {
            check(series.name().length() <= 100 && series.values().size() == c.categories().size(), "系列名称过长或数值与分类数量不一致");
            color(series.color());
            for (Double value : series.values()) check(value != null && finite(value) && Math.abs(value) <= 1e12, "图表数值必须有限且不超过 1e12");
            if ("pie".equals(c.type())) check(series.values().stream().allMatch(v -> v >= 0)
                    && series.values().stream().mapToDouble(Double::doubleValue).sum() > 0, "饼图需要非负且总和大于零的数据");
        }
    }
    private static void identifier(String id, Set<String> ids) {
        check(id != null && id.matches("[A-Za-z0-9_-]{1,100}") && ids.add(id), "页面与对象 ID 必须有效且全稿唯一");
    }
    private static void color(String value) { check(value == null || value.isBlank() || value.matches("#?[0-9a-fA-F]{6}"), "颜色必须为六位十六进制值"); }
    private static void choice(String value, Set<String> choices, String label) { check(value == null || choices.contains(value), "不支持的" + label); }
    private static boolean finite(double number) { return Double.isFinite(number); }
    static void check(boolean condition, String message) { if (!condition) throw new PptFailure("PPT_INVALID_SCENE", message); }
}
