package io.opencode.loopper.ppt;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

/** Each engine has its own font allowlist; administrators can extend it with an explicit offline directory. */
final class PptFonts {
    static final String DEFAULT = "Noto Sans CJK SC";
    private final String directory;
    private volatile Map<String, Map<Integer, Font>> loaded;
    private volatile String problem = "";
    PptFonts(String directory) { this.directory = directory == null ? "" : directory.strip(); }

    synchronized void initialize() {
        if (loaded != null) return;
        Map<String, Map<Integer, Font>> fonts = new TreeMap<>();
        try {
            for (String name : List.of("Regular", "Bold")) {
                try (InputStream input = PptFonts.class.getResourceAsStream("/ppt/fonts/NotoSansCJKsc-" + name + ".otf")) {
                    if (input == null) throw new IllegalStateException("内置 Noto 字体资源缺失");
                    add(fonts, Font.createFont(Font.TRUETYPE_FONT, input), "Bold".equals(name) ? Font.BOLD : Font.PLAIN);
                }
            }
            if (!directory.isBlank()) loadDirectory(fonts);
            if (!fonts.get(DEFAULT).get(Font.PLAIN).canDisplay('中')) throw new IllegalStateException("内置中文字体不可用");
            Map<String, Map<Integer, Font>> snapshot = new TreeMap<>();
            fonts.forEach((family, styles) -> snapshot.put(family, Map.copyOf(styles)));
            loaded = Map.copyOf(snapshot); problem = "";
        } catch (Exception | LinkageError | java.awt.AWTError | InternalError error) {
            problem = "PPT 字体加载失败；请检查完整 JDK 21 的 java.desktop/Java2D、内置字体资源，以及 loopper.ppt.font-dir 指定目录中的 TTF/OTF 文件后重试（"
                    + error.getClass().getSimpleName() + "）";
            throw new PptFailure("PPT_FONT_UNAVAILABLE", problem, error);
        }
    }
    private void loadDirectory(Map<String, Map<Integer, Font>> fonts) throws Exception {
        Path root = Path.of(directory).toRealPath();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("字体路径不是目录");
        List<Path> files;
        try (var entries = Files.list(root)) {
            files = entries.filter(path -> path.getFileName().toString().matches("(?i).+\\.(ttf|otf)"))
                    .sorted().limit(33).toList();
        }
        if (files.size() > 32) throw new IllegalArgumentException("字体文件超过 32 个");
        long total = 0;
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(root))
                throw new IllegalArgumentException("字体文件不属于指定目录");
            long size = Files.size(file); total += size;
            if (size <= 0 || size > 32L * 1024 * 1024 || total > 128L * 1024 * 1024) throw new IllegalArgumentException("字体资源超过大小限制");
            try (InputStream input = Files.newInputStream(file)) {
                Font font = Font.createFont(Font.TRUETYPE_FONT, input);
                String name = font.getFontName(Locale.ROOT).toLowerCase(Locale.ROOT);
                int style = (name.contains("bold") ? Font.BOLD : 0) | (name.contains("italic") ? Font.ITALIC : 0);
                if (!DEFAULT.equals(font.getFamily(Locale.ROOT))) add(fonts, font, style);
            }
        }
    }
    private static void add(Map<String, Map<Integer, Font>> fonts, Font font, int style) {
        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
        fonts.computeIfAbsent(font.getFamily(Locale.ROOT), unused -> new HashMap<>()).put(style, font);
    }
    List<String> families() { initialize(); return loaded.keySet().stream().sorted().toList(); }
    boolean available() { try { initialize(); return true; } catch (RuntimeException failure) { return false; } }
    String problem() { return problem; }
    Font font(String family, boolean bold, double size) {
        initialize(); String actual = family == null || family.isBlank() ? DEFAULT : family;
        var styles = loaded.get(actual);
        if (styles == null) throw new PptFailure("PPT_INVALID_FONT", "字体不可用；请从字体目录选择，或让管理员配置 loopper.ppt.font-dir 后重启服务");
        int style = bold ? Font.BOLD : Font.PLAIN;
        Font base = styles.getOrDefault(style, styles.getOrDefault(Font.PLAIN, styles.values().iterator().next()));
        return base.deriveFont(style, (float) size);
    }
}
