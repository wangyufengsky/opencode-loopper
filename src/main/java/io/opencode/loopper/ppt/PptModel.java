package io.opencode.loopper.ppt;

import java.util.List;
import java.util.Map;

/** Durable, renderer-independent scene. Geometry uses points; IDs survive page reordering. */
public final class PptModel {
    private PptModel() { }

    public record Deck(String title, Double width, Double height, String theme, List<Slide> slides) {
        public Deck {
            title = title == null ? "未命名演示文稿" : title;
            width = width == null || width == 0 ? 960. : width;
            height = height == null || height == 0 ? 540. : height;
            theme = theme == null || theme.isBlank() ? "business" : theme;
            slides = slides == null ? List.of() : List.copyOf(slides);
        }
        public Deck(String title, double width, double height, String theme, List<Slide> slides) {
            this(title, Double.valueOf(width), Double.valueOf(height), theme, slides);
        }
        public static Deck empty(String title) { return new Deck(title, 960., 540., "business", List.of()); }
    }

    public record Slide(String id, String title, String section, String notes, Boolean locked, List<Element> elements, String theme) {
        public Slide(String id, String title, String section, String notes, boolean locked, List<Element> elements) {
            this(id, title, section, notes, locked, elements, null);
        }
        public Slide {
            locked = locked != null && locked;
            title = title == null ? "" : title;
            section = section == null ? "" : section;
            notes = notes == null ? "" : notes;
            elements = elements == null ? List.of() : List.copyOf(elements);
        }
    }

    public record Element(String id, String type, double x, double y, double width, double height,
                          String text, String assetId, String fontFamily, Double fontSize, String color,
                          String fill, Boolean bold, String align, String fit, String shape, Double rotation,
                          Boolean locked, Boolean allowOverlap, String groupId, List<List<String>> rows,
                          Chart chart, Boolean bullets, String stroke, Double lineWidth, String theme) {
        public Element(String id, String type, double x, double y, double width, double height,
                       String text, String assetId, String fontFamily, Double fontSize, String color,
                       String fill, boolean bold, String align, String fit, String shape, double rotation,
                       boolean locked, boolean allowOverlap, String groupId, List<List<String>> rows,
                       Chart chart, boolean bullets, String stroke, Double lineWidth) {
            this(id, type, x, y, width, height, text, assetId, fontFamily, fontSize, color, fill, bold, align,
                    fit, shape, rotation, locked, allowOverlap, groupId, rows, chart, bullets, stroke, lineWidth, null);
        }
        public Element {
            bold = bold != null && bold;
            locked = locked != null && locked;
            allowOverlap = allowOverlap != null && allowOverlap;
            bullets = bullets != null && bullets;
            rotation = rotation == null ? 0. : rotation;
            text = text == null ? "" : text;
            rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
        }
    }

    public record Chart(String type, List<String> categories, List<Series> series) {
        public Chart {
            categories = categories == null ? List.of() : List.copyOf(categories);
            series = series == null ? List.of() : List.copyOf(series);
        }
    }

    public record Series(String name, List<Double> values, String color) {
        public Series {
            name = name == null ? "" : name;
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record OperationResult(Deck deck, Map<String, String> createdIds) {
        public OperationResult { createdIds = Map.copyOf(createdIds); }
    }
    public record Issue(String severity, String code, String slideId, String elementId, String message) { }
    public record Validation(List<Issue> issues) {
        public Validation { issues = List.copyOf(issues); }
        public boolean valid() { return issues.stream().noneMatch(i -> "ERROR".equals(i.severity())); }
    }
    public record TextMeasurement(double requiredHeight, double availableHeight, boolean overflow,
                                  String fontFamily, List<String> missingCharacters) { }
    public record Theme(String id, String name, String background, String foreground, String accent,
                        String muted, List<String> palette) { }
    public record Capabilities(int maxSlides, int maxElementsPerSlide, int maxOperations,
                               List<String> elementTypes, List<String> chartTypes, List<String> layouts,
                               List<Theme> themes, List<String> fonts, boolean renderingAvailable, String renderingMessage) { }
}
