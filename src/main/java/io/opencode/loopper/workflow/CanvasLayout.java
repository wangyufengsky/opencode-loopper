package io.opencode.loopper.workflow;

import java.util.Map;

/** Presentation versioning is independent from the execution plan revision. */
public record CanvasLayout(Map<String, Point> positions, double x, double y, double zoom) {
    public CanvasLayout { positions = positions == null ? Map.of() : Map.copyOf(positions); }
    public static CanvasLayout empty() { return new CanvasLayout(Map.of(), 0, 0, 1); }
    public record Point(double x, double y) { }
}
