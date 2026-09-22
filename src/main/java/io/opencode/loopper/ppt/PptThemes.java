package io.opencode.loopper.ppt;

import java.awt.Color;
import java.util.List;
import static io.opencode.loopper.ppt.PptModel.*;

final class PptThemes {
    static final List<Theme> ALL = List.of(
            new Theme("business", "商务蓝", "FFFFFF", "18324F", "246BCE", "62748A", List.of("246BCE", "19A7A0", "E9A23B", "9061C2", "D45A6B")),
            new Theme("minimal", "简约灰", "FAFAF8", "252525", "476B5D", "727272", List.of("476B5D", "9C7855", "657EA0", "AC6679", "B29A42")),
            new Theme("dark", "深色科技", "122033", "F2F6FC", "63B8FF", "A9BCD1", List.of("63B8FF", "56D4BB", "F2BF64", "B6A0EF", "EF8DAB")));
    static final List<String> LAYOUTS = List.of("title_content", "two_columns", "three_columns", "grid", "image_text");

    private PptThemes() { }
    static Theme find(String id) {
        return ALL.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new PptFailure("PPT_INVALID_THEME", "未知主题：" + id));
    }
    static Theme resolve(String override, Theme inherited) { return override == null ? inherited : find(override); }
    static Color color(String value, String fallback) {
        return Color.decode("#" + (value == null || value.isBlank() ? fallback : value.replace("#", "")));
    }
    static Capabilities capabilities(PptFonts fonts) {
        boolean available = fonts.available();
        return new Capabilities(100, 200, 100, List.of("text", "image", "shape", "table", "chart", "line"),
                List.of("bar", "line", "pie"), LAYOUTS, ALL, available ? fonts.families() : List.of(PptFonts.DEFAULT), available, fonts.problem());
    }
}
