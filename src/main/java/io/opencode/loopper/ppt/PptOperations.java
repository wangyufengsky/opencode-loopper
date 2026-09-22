package io.opencode.loopper.ppt;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static io.opencode.loopper.ppt.PptModel.*;

/** Applies an entire batch to a detached tree. The caller either receives a new scene or no change. */
final class PptOperations {
    private static final Set<String> ELEMENT_FIELDS = Set.of("id", "type", "x", "y", "width", "height", "text", "assetId", "fontFamily",
            "fontSize", "color", "fill", "bold", "align", "fit", "shape", "rotation", "locked", "allowOverlap", "groupId", "rows", "chart", "bullets", "stroke", "lineWidth", "theme");
    private final ObjectMapper mapper;
    private final PptFonts fonts;
    PptOperations(ObjectMapper mapper, PptFonts fonts) { this.mapper = mapper; this.fonts = fonts; }

    OperationResult apply(Deck deck, List<JsonNode> operations, boolean agent) {
        PptSceneValidation.require(deck, fonts);
        require(operations != null && !operations.isEmpty() && operations.size() <= 100, "操作批次需要 1–100 项");
        ObjectNode copy = mapper.valueToTree(deck);
        Map<String, String> created = new LinkedHashMap<>();
        try {
            for (JsonNode operation : operations) {
                require(operation != null && operation.isObject(), "操作必须是对象");
                applyOne(copy, operation, agent, created);
                for (JsonNode slide : slides(copy)) normalizeGroups((ObjectNode) slide);
            }
            Deck result = mapper.treeToValue(copy, Deck.class);
            PptSceneValidation.require(result, fonts);
            return new OperationResult(result, created);
        } catch (PptFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw new PptFailure("PPT_INVALID_OPERATION", "操作产生了无效页面字段", failure); }
    }

    private void applyOne(ObjectNode deck, JsonNode op, boolean agent, Map<String, String> created) {
        switch (requiredText(op, "op")) {
            case "create_slide" -> create(deck, op, agent, created);
            case "duplicate_slide" -> duplicate(deck, op, created);
            case "move_slide" -> move(deck, op);
            case "delete_slide" -> delete(deck, op);
            case "update_slide" -> updateSlide(deck, op, agent);
            case "add_element" -> add(deck, op, agent, created);
            case "update_element" -> updateElement(deck, op, agent);
            case "remove_element" -> remove(deck, op);
            case "move_element" -> moveElement(deck, op);
            case "apply_theme" -> theme(deck, op);
            case "align", "distribute", "group", "ungroup", "apply_layout" -> layout(deck, op);
            default -> throw new PptFailure("PPT_INVALID_OPERATION", "不支持的 PPT 操作");
        }
    }
    private void create(ObjectNode deck, JsonNode op, boolean agent, Map<String, String> created) {
        ObjectNode slide = object(op, "slide").deepCopy();
        checkFields(slide, Set.of("id", "title", "section", "notes", "locked", "elements", "theme"));
        String id = assignId(slide);
        if (!slide.has("elements")) slide.set("elements", mapper.createArrayNode());
        require(slide.path("elements").isArray(), "页面 elements 必须是数组");
        if (agent) require(!slide.path("locked").asBoolean(false), "Agent 不能锁定页面");
        for (JsonNode node : slide.withArray("elements")) {
            require(node.isObject(), "页面对象必须是对象"); checkElement(node, true); assignId((ObjectNode) node);
            if (agent) require(!node.path("locked").asBoolean(false), "Agent 不能锁定对象");
        }
        ArrayNode slides = slides(deck);
        slides.insert(index(op, slides.size(), slides.size()), slide);
        remember(op, id, created);
    }
    private void duplicate(ObjectNode deck, JsonNode op, Map<String, String> created) {
        ObjectNode original = slide(deck, requiredText(op, "slideId"));
        unlocked(original);
        ObjectNode copy = original.deepCopy(); copy.put("id", newId()); copy.put("locked", false);
        Map<String, String> groups = new HashMap<>();
        for (JsonNode node : copy.withArray("elements")) {
            ObjectNode element = (ObjectNode) node;
            unlocked(element); element.put("id", newId());
            if (element.hasNonNull("groupId")) element.put("groupId", groups.computeIfAbsent(element.path("groupId").asText(), unused -> newId()));
        }
        ArrayNode slides = slides(deck); slides.insert(index(op, slides.size(), slides.size()), copy);
        remember(op, copy.path("id").asText(), created);
    }
    private void move(ObjectNode deck, JsonNode op) {
        ArrayNode slides = slides(deck); ObjectNode slide = slide(deck, requiredText(op, "slideId")); unlocked(slide);
        int target = index(op, slides.size() - 1, -1); slides.remove(position(slides, slide)); slides.insert(target, slide);
    }
    private void delete(ObjectNode deck, JsonNode op) {
        ObjectNode slide = slide(deck, requiredText(op, "slideId")); unlocked(slide);
        for (JsonNode e : slide.withArray("elements")) unlocked(e);
        slides(deck).remove(position(slides(deck), slide));
    }
    private void updateSlide(ObjectNode deck, JsonNode op, boolean agent) {
        ObjectNode slide = slide(deck, requiredText(op, "slideId")); ObjectNode patch = object(op, "patch");
        checkFields(patch, Set.of("title", "section", "notes", "locked", "theme"));
        checkLockPatch(slide, patch, agent); patch.properties().forEach(e -> slide.set(e.getKey(), e.getValue()));
    }
    private void add(ObjectNode deck, JsonNode op, boolean agent, Map<String, String> created) {
        ObjectNode slide = slide(deck, requiredText(op, "slideId")); unlocked(slide);
        ObjectNode element = object(op, "element").deepCopy();
        checkElement(element, true);
        if (agent) require(!element.path("locked").asBoolean(false), "Agent 不能锁定对象");
        String id = assignId(element); slide.withArray("elements").add(element); remember(op, id, created);
    }
    private void updateElement(ObjectNode deck, JsonNode op, boolean agent) {
        ObjectNode slide = slide(deck, requiredText(op, "slideId")); unlocked(slide);
        ObjectNode e = element(slide, requiredText(op, "elementId")); ObjectNode patch = object(op, "patch");
        checkElement(patch, false);
        require(!patch.has("id") && !patch.has("type"), "不能修改对象 ID 或类型");
        checkLockPatch(e, patch, agent); patch.properties().forEach(entry -> e.set(entry.getKey(), entry.getValue()));
    }
    private void remove(ObjectNode deck, JsonNode op) {
        ObjectNode slide = slide(deck, requiredText(op, "slideId")); unlocked(slide);
        ObjectNode e = element(slide, requiredText(op, "elementId")); unlocked(e);
        slide.withArray("elements").remove(position(slide.withArray("elements"), e));
    }
    private void theme(ObjectNode deck, JsonNode op) {
        PptThemes.find(requiredText(op, "theme"));
        String inherited = deck.path("theme").asText();
        for (JsonNode node : slides(deck)) {
            ObjectNode slide = (ObjectNode) node;
            String effective = slide.hasNonNull("theme") ? slide.path("theme").asText() : inherited;
            if (slide.path("locked").asBoolean(false)) { slide.put("theme", effective); continue; }
            for (JsonNode elementNode : slide.path("elements")) {
                ObjectNode element = (ObjectNode) elementNode;
                if (element.path("locked").asBoolean(false)) {
                    if (!element.hasNonNull("theme")) element.put("theme", effective);
                } else element.putNull("theme");
            }
            slide.putNull("theme");
        }
        deck.put("theme", op.path("theme").asText());
    }
    private void moveElement(ObjectNode deck, JsonNode op) {
        ObjectNode slide = slide(deck, requiredText(op, "slideId")); unlocked(slide);
        ObjectNode element = element(slide, requiredText(op, "elementId")); unlocked(element);
        require(!element.hasNonNull("groupId"), "请先取消分组，再单独调整对象层级");
        ArrayNode elements = slide.withArray("elements"); int target = index(op, elements.size() - 1, -1);
        elements.remove(position(elements, element));
        if (target > 0 && target < elements.size()) {
            JsonNode previous = elements.get(target - 1).get("groupId"), next = elements.get(target).get("groupId");
            require(previous == null || previous.isNull() || !previous.equals(next), "不能将对象层级插入分组内部");
        }
        elements.insert(target, element);
    }
    private void normalizeGroups(ObjectNode slide) {
        ArrayNode elements = slide.withArray("elements"), normalized = mapper.createArrayNode();
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
        for (JsonNode element : elements) if (element.hasNonNull("groupId"))
            groups.computeIfAbsent(element.path("groupId").asText(), unused -> new ArrayList<>()).add(element);
        Set<String> emitted = new HashSet<>();
        for (JsonNode element : elements) {
            if (!element.hasNonNull("groupId")) normalized.add(element);
            else if (emitted.add(element.path("groupId").asText())) groups.get(element.path("groupId").asText()).forEach(normalized::add);
        }
        slide.set("elements", normalized);
    }
    private void layout(ObjectNode deck, JsonNode op) {
        ObjectNode slide = slide(deck, requiredText(op, "slideId")); unlocked(slide);
        List<ObjectNode> selected = new ArrayList<>();
        JsonNode ids = op.get("elementIds");
        if (ids == null && "apply_layout".equals(op.path("op").asText())) {
            slide.withArray("elements").forEach(e -> selected.add((ObjectNode) e));
        } else {
            require(ids != null && ids.isArray() && !ids.isEmpty(), "需要选择对象 ID");
            Set<String> distinct = new HashSet<>();
            for (JsonNode id : ids) {
                require(id.isString() && distinct.add(id.asText()), "对象 ID 必须是唯一字符串");
                selected.add(element(slide, id.asText()));
            }
        }
        selected.forEach(PptOperations::unlocked);
        PptOperationLayouts.apply(deck, op, selected);
    }
    private void checkLockPatch(JsonNode target, ObjectNode patch, boolean agent) {
        if (agent) require(!patch.has("locked"), "Agent 不能修改锁定状态");
        if (target.path("locked").asBoolean(false)) {
            require(!agent && patch.size() == 1 && patch.has("locked") && patch.get("locked").isBoolean()
                    && !patch.path("locked").asBoolean(), "请先单独解锁后修改");
        }
    }
    private static int index(JsonNode op, int max, int fallback) {
        if (!op.has("index")) { require(fallback >= 0, "需要目标页码 index"); return fallback; }
        require(op.path("index").isIntegralNumber(), "index 必须是整数");
        int value = op.path("index").asInt(-1); require(value >= 0 && value <= max, "index 超出页面范围"); return value;
    }
    private static String assignId(ObjectNode node) {
        if (!node.hasNonNull("id")) node.put("id", newId());
        require(node.path("id").isString(), "ID 必须是字符串"); return node.path("id").asText();
    }
    private static void remember(JsonNode op, String id, Map<String, String> created) {
        if (!op.has("clientRef")) return;
        String key = requiredText(op, "clientRef"); require(!created.containsKey(key), "clientRef 不能重复"); created.put(key, id);
    }
    private static ArrayNode slides(ObjectNode deck) { return deck.withArray("slides"); }
    private static ObjectNode slide(ObjectNode deck, String id) { return find(slides(deck), id, "页面"); }
    private static ObjectNode element(ObjectNode slide, String id) { return find(slide.withArray("elements"), id, "对象"); }
    private static ObjectNode find(ArrayNode nodes, String id, String label) {
        for (JsonNode node : nodes) if (id.equals(node.path("id").asText())) return (ObjectNode) node;
        throw new PptFailure("PPT_OBJECT_NOT_FOUND", label + "不存在：" + id);
    }
    private static int position(ArrayNode nodes, JsonNode target) {
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i) == target) return i;
        throw new PptFailure("PPT_OBJECT_NOT_FOUND", "对象不存在");
    }
    static String requiredText(JsonNode node, String key) {
        require(node.has(key) && node.get(key).isString() && !node.get(key).asText().isBlank(), key + " 必须是非空字符串");
        return node.get(key).asText();
    }
    private static ObjectNode object(JsonNode node, String key) {
        require(node.has(key) && node.get(key).isObject(), key + " 必须是对象"); return (ObjectNode) node.get(key);
    }
    private static void checkFields(JsonNode node, Set<String> allowed) {
        for (var entry : node.properties()) require(allowed.contains(entry.getKey()), "不支持字段：" + entry.getKey());
    }
    private static void checkElement(JsonNode node, boolean creation) {
        checkFields(node, ELEMENT_FIELDS);
        for (String key : List.of("x", "y", "width", "height")) {
            if (creation || node.has(key)) require(node.has(key) && node.get(key).isNumber(), key + " 必须显式提供数字");
        }
        for (String key : List.of("rotation", "fontSize", "lineWidth"))
            if (node.hasNonNull(key)) require(node.get(key).isNumber(), key + " 必须是数字");
        for (String key : List.of("bold", "locked", "allowOverlap", "bullets"))
            if (node.hasNonNull(key)) require(node.get(key).isBoolean(), key + " 必须是布尔值");
        for (String key : List.of("id", "type", "text", "assetId", "fontFamily", "color", "fill", "align", "fit", "shape", "groupId", "stroke", "theme"))
            if (node.hasNonNull(key)) require(node.get(key).isString(), key + " 必须是字符串");
        if (creation) requiredText(node, "type");
    }
    static void unlocked(JsonNode node) { if (node.path("locked").asBoolean(false)) throw new PptFailure("PPT_LOCKED", "选定页面或对象已锁定，请先解锁"); }
    static String newId() { return UUID.randomUUID().toString(); }
    static void require(boolean condition, String message) { if (!condition) throw new PptFailure("PPT_INVALID_OPERATION", message); }
}
