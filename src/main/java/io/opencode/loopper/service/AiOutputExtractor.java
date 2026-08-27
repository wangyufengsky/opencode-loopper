package io.opencode.loopper.service;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 从常见 AI 文本包装中确定性提取严格 JSON，并在进入业务校验前完成无歧义的形状规范化。
 * 本组件不修复残缺 JSON，也不补造业务语义。
 */
@Component
public class AiOutputExtractor {
    private static final int MAX_OUTPUT_LENGTH = 128 * 1024;
    private static final int MAX_CANDIDATES = 16;
    private static final Pattern JSON_FENCE = Pattern.compile(
            "(?is)```[\\t ]*(json)?[\\t ]*(?:\\R|$)(.*?)```");
    private static final Pattern MARKDOWN_FENCE = Pattern.compile(
            "(?is)```[\\t ]*(?:markdown|md)?[\\t ]*(?:\\R|$)(.*?)```");

    private final ObjectMapper json;

    public AiOutputExtractor(ObjectMapper json) {
        this.json = json;
    }

    public <T> ExtractionResult<T> extractJson(String output, Pattern markerPattern, String prefix,
                                                Class<T> type, UnaryOperator<T> normalizer,
                                                Consumer<T> validator) {
        return extractJson(output, markerPattern, prefix, type, normalizer, validator, null);
    }

    public <T> ExtractionResult<T> extractJson(String output, Pattern markerPattern, String prefix,
                                                Class<T> type, UnaryOperator<T> normalizer,
                                                Consumer<T> validator, Consumer<JsonNode> rawValidator) {
        return extractJson(output, markerPattern, prefix, type, normalizer, validator, rawValidator,
                CandidatePolicy.PREFER_MARKER);
    }

    public <T> ExtractionResult<T> extractJson(String output, Pattern markerPattern, String prefix,
                                                Class<T> type, UnaryOperator<T> normalizer,
                                                Consumer<T> validator, Consumer<JsonNode> rawValidator,
                                                CandidatePolicy candidatePolicy) {
        if (output == null || output.isBlank()) {
            throw new BadRequestException(prefix + "_MISSING", "Read-only model completed without output");
        }
        if (output.length() > MAX_OUTPUT_LENGTH) {
            throw new BadRequestException(prefix + "_TOO_LARGE", "AI output exceeds the bounded extraction size");
        }
        String source = stripBom(output);
        boolean strictClosedChoice = candidatePolicy == CandidatePolicy.STRICT_CLOSED_CHOICE;
        int scanLimit = strictClosedChoice ? Integer.MAX_VALUE : MAX_CANDIDATES;
        List<Candidate> candidates = new ArrayList<>();
        if (markerPattern != null) {
            Matcher marker = markerPattern.matcher(source);
            while (marker.find()) {
                addObjects(marker.group(1), CandidateSource.MARKER, candidates, scanLimit);
                if (candidates.size() >= scanLimit) break;
            }
        }
        Matcher fence = JSON_FENCE.matcher(source);
        while (fence.find() && candidates.size() < scanLimit) {
            addObjects(fence.group(2), CandidateSource.FENCE, candidates, scanLimit);
        }
        if (candidates.size() < scanLimit) {
            addObjects(source, CandidateSource.EMBEDDED, candidates, scanLimit);
        }
        candidates = deduplicate(candidates);
        if (strictClosedChoice && candidates.size() > MAX_CANDIDATES) {
            throw new BadRequestException(prefix + "_TOO_MANY_CANDIDATES",
                    "Closed-choice output contains too many distinct JSON objects");
        }
        if (candidates.isEmpty()) {
            throw new BadRequestException(prefix + "_UNPARSEABLE",
                    "Output did not contain a complete valid JSON object");
        }

        List<Decoded<T>> decoded = new ArrayList<>();
        BadRequestException semanticFailure = null;
        for (Candidate candidate : candidates) {
            try {
                LinkedHashSet<String> notes = new LinkedHashSet<>();
                if (rawValidator != null) rawValidator.accept(candidate.node());
                JsonNode normalizedTree = normalize(candidate.node(), type, notes);
                T value = json.treeToValue(normalizedTree, type);
                if (normalizer != null) value = normalizer.apply(value);
                if (validator != null) validator.accept(value);
                JsonNode canonical = json.valueToTree(value);
                if (!canonical.equals(normalizedTree)) notes.add("CONTRACT_METADATA_DERIVED");
                decoded.add(new Decoded<>(value, canonical, candidate.source(), notes));
            } catch (BadRequestException failure) {
                if (candidatePolicy == CandidatePolicy.STRICT_CLOSED_CHOICE) throw failure;
                if (semanticFailure == null) semanticFailure = failure;
            } catch (RuntimeException failure) {
                // 该候选不符合目标类型，继续检查其他完整 JSON 对象。
            }
        }
        if (decoded.isEmpty()) {
            if (semanticFailure != null) throw semanticFailure;
            throw new BadRequestException(prefix + "_INVALID",
                    "JSON objects were found but none matched the required response contract");
        }

        List<Decoded<T>> preferred = candidatePolicy == CandidatePolicy.STRICT_CLOSED_CHOICE ? List.of()
                : decoded.stream().filter(item -> item.source() == CandidateSource.MARKER).toList();
        List<Decoded<T>> eligible = preferred.isEmpty() ? decoded : preferred;
        JsonNode canonical = eligible.getFirst().canonical();
        if (eligible.stream().anyMatch(item -> !canonical.equals(item.canonical()))) {
            throw new BadRequestException(prefix + "_AMBIGUOUS",
                    "AI output contains multiple non-equivalent valid response objects");
        }
        Decoded<T> selected = eligible.getFirst();
        LinkedHashSet<String> notes = new LinkedHashSet<>(selected.notes());
        if (selected.source() != CandidateSource.MARKER) notes.add("WRAPPER_TOLERATED");
        if (eligible.size() > 1) notes.add("EQUIVALENT_CANDIDATES_DEDUPLICATED");
        return new ExtractionResult<>(selected.value(), selected.source(), List.copyOf(notes),
                selected.canonical().toString());
    }

    public TextExtractionResult extractMarkdown(String output, Pattern markerPattern, String prefix,
                                                int maxLength) {
        if (output == null || output.isBlank()) {
            throw new BadRequestException(prefix + "_MISSING", "Read-only model completed without output");
        }
        if (output.length() > MAX_OUTPUT_LENGTH) {
            throw new BadRequestException(prefix + "_TOO_LARGE", "AI output exceeds the bounded extraction size");
        }
        String source = stripBom(output);
        List<TextCandidate> marked = textCandidates(source, markerPattern, CandidateSource.MARKER);
        List<TextCandidate> candidates = marked.isEmpty()
                ? textCandidates(source, MARKDOWN_FENCE, CandidateSource.FENCE) : marked;
        if (candidates.isEmpty()) {
            candidates = List.of(new TextCandidate(source.trim(), CandidateSource.EMBEDDED));
        }
        Map<String, TextCandidate> distinct = new LinkedHashMap<>();
        for (TextCandidate candidate : candidates) {
            String content = candidate.content().trim();
            if (!content.isBlank()) distinct.putIfAbsent(content, new TextCandidate(content, candidate.source()));
        }
        if (distinct.isEmpty()) {
            throw new BadRequestException(prefix + "_INVALID", "AI Markdown payload is empty");
        }
        if (distinct.size() > 1) {
            throw new BadRequestException(prefix + "_AMBIGUOUS",
                    "AI output contains multiple non-equivalent Markdown payloads");
        }
        TextCandidate selected = distinct.values().iterator().next();
        if (selected.content().length() > maxLength) {
            throw new BadRequestException(prefix + "_TOO_LARGE", "AI Markdown payload is too large");
        }
        List<String> notes = selected.source() == CandidateSource.MARKER
                ? List.of() : List.of("WRAPPER_TOLERATED");
        return new TextExtractionResult(selected.content(), selected.source(), notes);
    }

    private List<TextCandidate> textCandidates(String source, Pattern pattern, CandidateSource candidateSource) {
        if (pattern == null) return List.of();
        List<TextCandidate> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find() && result.size() < MAX_CANDIDATES) {
            result.add(new TextCandidate(matcher.group(1), candidateSource));
        }
        return result;
    }

    private void addObjects(String text, CandidateSource source, List<Candidate> candidates, int limit) {
        if (text == null || text.isBlank()) return;
        String value = stripBom(text).trim();
        try {
            JsonNode complete = json.readTree(value);
            if (complete != null) {
                if (complete.isObject()) candidates.add(new Candidate(complete, source));
                return;
            }
        } catch (JacksonException ignored) {
            // 外围说明文字不是 JSON 时，再扫描其中完整的 object；标准数组根仍会在上面直接拒绝。
        }
        int cursor = 0;
        while (cursor < value.length() && candidates.size() < limit) {
            int start = value.indexOf('{', cursor);
            if (start < 0) return;
            int end = matchingObjectEnd(value, start);
            if (end < 0) {
                cursor = start + 1;
                continue;
            }
            String candidate = value.substring(start, end + 1);
            try {
                JsonNode node = json.readTree(candidate);
                if (node != null && node.isObject()) candidates.add(new Candidate(node, source));
            } catch (JacksonException ignored) {
                // 只接受标准完整 JSON；不尝试 JSON5 或启发式修复。
            }
            cursor = end + 1;
        }
    }

    private int matchingObjectEnd(String value, int start) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
                continue;
            }
            if (current == '"') quoted = true;
            else if (current == '{') depth++;
            else if (current == '}' && --depth == 0) return index;
        }
        return -1;
    }

    private List<Candidate> deduplicate(List<Candidate> input) {
        Map<String, Candidate> result = new LinkedHashMap<>();
        for (Candidate candidate : input) {
            String key = candidate.node().toString();
            Candidate previous = result.get(key);
            if (previous == null || candidate.source().priority < previous.source().priority) {
                result.put(key, candidate);
            }
        }
        return List.copyOf(result.values());
    }

    private JsonNode normalize(JsonNode node, Type targetType, Set<String> notes) {
        if (targetType instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (raw instanceof Class<?> rawClass && Collection.class.isAssignableFrom(rawClass)) {
                ArrayNode result = json.createArrayNode();
                Type itemType = parameterized.getActualTypeArguments()[0];
                if (node == null || node.isNull()) {
                    notes.add("NULL_COLLECTION_NORMALIZED");
                    return result;
                }
                if (node.isArray()) {
                    for (JsonNode item : node) result.add(normalize(item, itemType, notes));
                    return result;
                }
                notes.add("SINGLETON_COLLECTION_NORMALIZED");
                result.add(normalize(node, itemType, notes));
                return result;
            }
            return node;
        }
        if (!(targetType instanceof Class<?> targetClass)) return node;
        if (targetClass.isEnum() && node != null && node.isTextual()) {
            String requested = normalizedName(node.asText());
            for (Object constant : targetClass.getEnumConstants()) {
                Enum<?> enumValue = (Enum<?>) constant;
                if (normalizedName(enumValue.name()).equals(requested)) {
                    if (!enumValue.name().equals(node.asText())) notes.add("ENUM_NORMALIZED");
                    return json.getNodeFactory().stringNode(enumValue.name());
                }
            }
            return node;
        }
        if (!targetClass.isRecord() || node == null || !node.isObject()) return node;

        Map<String, RecordComponent> components = new LinkedHashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        for (RecordComponent component : targetClass.getRecordComponents()) {
            String normalized = normalizedName(component.getName());
            if (components.putIfAbsent(normalized, component) != null) ambiguous.add(normalized);
        }
        ObjectNode result = json.createObjectNode();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String normalized = normalizedName(entry.getKey());
            RecordComponent component = ambiguous.contains(normalized) ? null : components.get(normalized);
            if (component == null) {
                notes.add("UNKNOWN_FIELDS_IGNORED");
                continue;
            }
            if (!component.getName().equals(entry.getKey())) notes.add("FIELD_NAME_NORMALIZED");
            JsonNode value = entry.getValue();
            if ((value == null || value.isNull()) && collectionType(component.getGenericType())) {
                result.set(component.getName(), json.createArrayNode());
                notes.add("NULL_COLLECTION_NORMALIZED");
            } else {
                result.set(component.getName(), normalize(value, component.getGenericType(), notes));
            }
        }
        return result;
    }

    private boolean collectionType(Type type) {
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return Collection.class.isAssignableFrom(raw);
        }
        return type instanceof Class<?> raw && Collection.class.isAssignableFrom(raw);
    }

    private static String normalizedName(String value) {
        return value == null ? "" : value.replaceAll("[-_\\s]", "").toLowerCase(Locale.ROOT);
    }

    private static String stripBom(String value) {
        return value != null && !value.isEmpty() && value.charAt(0) == '\ufeff' ? value.substring(1) : value;
    }

    public enum CandidateSource {
        STRUCTURED(0), MARKER(1), FENCE(2), EMBEDDED(3);
        private final int priority;
        CandidateSource(int priority) { this.priority = priority; }
    }

    public enum CandidatePolicy { PREFER_MARKER, STRICT_CLOSED_CHOICE }

    public record ExtractionResult<T>(T value, CandidateSource source, List<String> normalizations,
                                      String canonicalJson) {
        public ExtractionResult {
            normalizations = normalizations == null ? List.of() : List.copyOf(normalizations);
        }
        public boolean normalized() { return !normalizations.isEmpty(); }
    }

    public record TextExtractionResult(String value, CandidateSource source, List<String> normalizations) {
        public TextExtractionResult {
            normalizations = normalizations == null ? List.of() : List.copyOf(normalizations);
        }
        public boolean normalized() { return !normalizations.isEmpty(); }
    }

    private record Candidate(JsonNode node, CandidateSource source) { }
    private record Decoded<T>(T value, JsonNode canonical, CandidateSource source, Set<String> notes) { }
    private record TextCandidate(String content, CandidateSource source) { }
}
