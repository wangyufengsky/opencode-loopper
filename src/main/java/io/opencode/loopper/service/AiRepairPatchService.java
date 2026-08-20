package io.opencode.loopper.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Applies a bounded RFC-6902-like subset to model-owned semantic fields only. */
@Component
public class AiRepairPatchService {
    private static final int MAX_PATCHES = 16;
    private static final int MAX_RESULT_LENGTH = 128 * 1024;
    private final ObjectMapper json;
    private final AiOutputExtractor extractor;

    public AiRepairPatchService(ObjectMapper json, AiOutputExtractor extractor) {
        this.json = json;
        this.extractor = extractor;
    }

    public Result apply(String currentJson, String output, Pattern marker, String prefix,
                        Set<String> allowedRoots) {
        AiOutputExtractor.ExtractionResult<PatchEnvelope> extracted = extractor.extractJson(output, marker, prefix,
                PatchEnvelope.class, PatchEnvelope::normalized, envelope -> {
                    if (envelope.patches().isEmpty() || envelope.patches().size() > MAX_PATCHES) {
                        throw new BadRequestException(prefix + "_COUNT_INVALID",
                                "Semantic repair requires 1-16 patch operations");
                    }
                });
        JsonNode root;
        try { root = json.readTree(currentJson); }
        catch (JacksonException failure) {
            throw new BadRequestException(prefix + "_BASE_INVALID", "Frozen semantic snapshot is unreadable");
        }
        if (!(root instanceof ObjectNode object)) {
            throw new BadRequestException(prefix + "_BASE_INVALID", "Frozen semantic snapshot must be an object");
        }
        ObjectNode result = object.deepCopy();
        List<String> normalizations = new ArrayList<>(extracted.normalizations());
        for (PatchOperation patch : extracted.value().patches()) {
            applyOne(result, patch, allowedRoots, prefix, normalizations);
        }
        String value = result.toString();
        if (value.length() > MAX_RESULT_LENGTH) {
            throw new BadRequestException(prefix + "_TOO_LARGE", "Patched semantic object is too large");
        }
        return new Result(value, List.copyOf(new java.util.LinkedHashSet<>(normalizations)));
    }

    private void applyOne(ObjectNode root, PatchOperation patch, Set<String> allowedRoots, String prefix,
                          List<String> normalizations) {
        if (patch == null || !Set.of("add", "replace", "remove").contains(patch.op())
                || patch.path() == null || !patch.path().startsWith("/")) {
            throw new BadRequestException(prefix + "_OP_INVALID", "Only add, replace, and remove JSON paths are allowed");
        }
        String path = normalizeCompilerCompactPath(patch.path(), allowedRoots, normalizations);
        List<String> tokens = pointerTokens(path);
        if (tokens.isEmpty() || !allowedRoots.contains(tokens.getFirst())) {
            throw new BadRequestException(prefix + "_PATH_FORBIDDEN",
                    "Patch path is outside model-owned semantic fields: " + path);
        }
        JsonNode parent = root;
        for (int index = 0; index < tokens.size() - 1; index++) {
            String token = tokens.get(index);
            parent = child(parent, token, prefix, path);
        }
        String leaf = tokens.getLast();
        if (parent instanceof ObjectNode object) {
            boolean exists = object.has(leaf);
            if ("remove".equals(patch.op())) {
                if (!exists) invalidPath(prefix, path);
                object.remove(leaf);
            } else {
                if ("replace".equals(patch.op()) && !exists) {
                    normalizations.add("PATCH_REPLACE_ABSENT_AS_ADD");
                }
                if (patch.value() == null) throw new BadRequestException(prefix + "_VALUE_REQUIRED",
                        patch.op() + " requires value");
                object.set(leaf, patch.value());
            }
            return;
        }
        if (parent instanceof ArrayNode array) {
            if (!"remove".equals(patch.op()) && patch.value() == null) {
                throw new BadRequestException(prefix + "_VALUE_REQUIRED", patch.op() + " requires value");
            }
            if ("add".equals(patch.op()) && "-".equals(leaf)) {
                array.add(patch.value());
                return;
            }
            int index = arrayIndex(leaf, array.size(), "add".equals(patch.op()), prefix, path);
            if ("remove".equals(patch.op())) array.remove(index);
            else if ("add".equals(patch.op())) array.insert(index, patch.value());
            else array.set(index, patch.value());
            return;
        }
        invalidPath(prefix, path);
    }

    private String normalizeCompilerCompactPath(String path, Set<String> allowedRoots,
                                                List<String> normalizations) {
        if (allowedRoots.contains("stages")
                && path.matches("^/stages/(0|[1-9][0-9]*)/verifiers(?:/.*)?$")) {
            normalizations.add("PATCH_FINAL_VERIFIERS_TO_COMPACT_EVIDENCE");
            return path.replaceFirst("^(/stages/(?:0|[1-9][0-9]*))/verifiers", "$1/evidence");
        }
        return path;
    }

    private JsonNode child(JsonNode parent, String token, String prefix, String path) {
        JsonNode result;
        if (parent instanceof ObjectNode object) result = object.get(token);
        else if (parent instanceof ArrayNode array) result = array.get(arrayIndex(token, array.size(), false, prefix, path));
        else result = null;
        if (result == null) invalidPath(prefix, path);
        return result;
    }

    private int arrayIndex(String token, int size, boolean allowEnd, String prefix, String path) {
        try {
            int index = Integer.parseInt(token);
            int max = allowEnd ? size : size - 1;
            if (index < 0 || index > max) invalidPath(prefix, path);
            return index;
        } catch (NumberFormatException failure) {
            invalidPath(prefix, path);
            return -1;
        }
    }

    private void invalidPath(String prefix, String path) {
        throw new BadRequestException(prefix + "_PATH_INVALID", "Patch path does not exist: " + path);
    }

    private List<String> pointerTokens(String path) {
        List<String> result = new ArrayList<>();
        for (String token : path.substring(1).split("/", -1)) {
            result.add(token.replace("~1", "/").replace("~0", "~"));
        }
        return result;
    }

    public record PatchOperation(String op, String path, JsonNode value) {
        PatchOperation normalized() {
            return new PatchOperation(op == null ? null : op.trim().toLowerCase(), path, value);
        }
    }
    public record PatchEnvelope(List<PatchOperation> patches) {
        PatchEnvelope normalized() {
            return new PatchEnvelope(patches == null ? List.of() : patches.stream()
                    .map(item -> item == null ? null : item.normalized()).toList());
        }
    }
    public record Result(String json, List<String> normalizations) { }
}
