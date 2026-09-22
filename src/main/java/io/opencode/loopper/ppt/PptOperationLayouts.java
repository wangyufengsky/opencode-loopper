package io.opencode.loopper.ppt;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static io.opencode.loopper.ppt.PptOperations.*;

/** Bounded geometric operations; never rewrites content or reduces font sizes. */
final class PptOperationLayouts {
    private PptOperationLayouts() { }
    static void apply(ObjectNode deck, JsonNode op, List<ObjectNode> selected) {
        require(!selected.isEmpty(), "至少需要一个对象");
        switch (op.path("op").asText()) {
            case "align" -> align(op, selected);
            case "distribute" -> distribute(op, selected);
            case "group" -> {
                require(selected.size() >= 2, "分组至少需要两个对象");
                String group = op.hasNonNull("groupId") ? requiredText(op, "groupId") : newId();
                selected.forEach(e -> e.put("groupId", group));
            }
            case "ungroup" -> selected.forEach(e -> e.putNull("groupId"));
            case "apply_layout" -> layout(deck, op, selected);
            default -> throw new PptFailure("PPT_INVALID_OPERATION", "未知排版操作");
        }
    }
    private static void align(JsonNode op, List<ObjectNode> nodes) {
        require(nodes.size() >= 2, "对齐至少需要两个对象");
        double left = min(nodes, "x"), top = min(nodes, "y");
        double right = edge(nodes, "x", "width"), bottom = edge(nodes, "y", "height");
        for (ObjectNode node : nodes) {
            switch (requiredText(op, "alignment")) {
                case "left" -> node.put("x", left);
                case "center" -> node.put("x", (left + right - value(node, "width")) / 2);
                case "right" -> node.put("x", right - value(node, "width"));
                case "top" -> node.put("y", top);
                case "middle" -> node.put("y", (top + bottom - value(node, "height")) / 2);
                case "bottom" -> node.put("y", bottom - value(node, "height"));
                default -> throw new PptFailure("PPT_INVALID_OPERATION", "不支持的对齐方向");
            }
        }
    }
    private static void distribute(JsonNode op, List<ObjectNode> nodes) {
        require(nodes.size() >= 3, "等间距至少需要三个对象");
        String axis = requiredText(op, "axis"); require(Set.of("horizontal", "vertical").contains(axis), "等间距方向无效");
        String coord = "horizontal".equals(axis) ? "x" : "y", extent = "horizontal".equals(axis) ? "width" : "height";
        List<ObjectNode> sorted = nodes.stream().sorted(Comparator.comparingDouble(n -> value(n, coord))).toList();
        double start = value(sorted.getFirst(), coord), end = value(sorted.getLast(), coord) + value(sorted.getLast(), extent);
        double gap = (end - start - sorted.stream().mapToDouble(n -> value(n, extent)).sum()) / (sorted.size() - 1);
        require(gap >= 0, "空间不足以等间距排列，请先扩大范围或缩小对象");
        double position = start;
        for (ObjectNode node : sorted) { node.put(coord, position); position += value(node, extent) + gap; }
    }
    private static void layout(ObjectNode deck, JsonNode op, List<ObjectNode> nodes) {
        String layout = requiredText(op, "layout"); require(PptThemes.LAYOUTS.contains(layout), "不支持的版式");
        double width = value(deck, "width"), height = value(deck, "height"), margin = width * .05, gap = width * .025;
        if ("title_content".equals(layout)) {
            rect(nodes.getFirst(), margin, height * .06, width - 2 * margin, height * .13);
            if (nodes.size() > 1) grid(nodes.subList(1, nodes.size()), margin, height * .25, width - 2 * margin, height * .65, 1, gap);
            return;
        }
        int columns = switch (layout) { case "two_columns", "image_text" -> 2; case "three_columns" -> 3; default -> (int) Math.ceil(Math.sqrt(nodes.size())); };
        grid(nodes, margin, height * .15, width - 2 * margin, height * .75, columns, gap);
    }
    private static void grid(List<ObjectNode> nodes, double x, double y, double w, double h, int columns, double gap) {
        columns = Math.min(columns, nodes.size()); int rows = (nodes.size() + columns - 1) / columns;
        double cw = (w - gap * (columns - 1)) / columns, rh = (h - gap * (rows - 1)) / rows;
        require(cw > 0 && rh > 0, "对象太多，版式空间不足");
        for (int i = 0; i < nodes.size(); i++) rect(nodes.get(i), x + i % columns * (cw + gap), y + i / columns * (rh + gap), cw, rh);
    }
    private static void rect(ObjectNode node, double x, double y, double width, double height) {
        node.put("x", x); node.put("y", y); node.put("width", width); node.put("height", height);
    }
    private static double min(List<ObjectNode> nodes, String key) { return nodes.stream().mapToDouble(n -> value(n, key)).min().orElseThrow(); }
    private static double edge(List<ObjectNode> nodes, String coord, String extent) { return nodes.stream().mapToDouble(n -> value(n, coord) + value(n, extent)).max().orElseThrow(); }
    private static double value(JsonNode node, String key) { return node.path(key).asDouble(); }
}
