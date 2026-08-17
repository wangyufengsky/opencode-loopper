package io.opencode.loopper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AiOutputExtractorTest {
    private static final Pattern MARKER = Pattern.compile(
            "(?is)<!--\\s*TEST_START\\s*-->(.*?)<!--\\s*TEST_END\\s*-->");
    private final AiOutputExtractor extractor = new AiOutputExtractor(new ObjectMapper());

    @Test
    void acceptsMarkerBareFenceProseAndBomWithoutRepairingJsonSyntax() {
        assertThat(extract("<!-- TEST_START -->{\"status\":\"pass\",\"items\":[\"a\"]}<!-- TEST_END -->")
                .source()).isEqualTo(AiOutputExtractor.CandidateSource.MARKER);
        assertThat(extract("{\"status\":\"PASS\",\"items\":[]}").value().status()).isEqualTo("PASS");
        assertThat(extract("说明如下：\n```json\n{\"status\":\"PASS\",\"items\":[]}\n```\n请查收")
                .source()).isEqualTo(AiOutputExtractor.CandidateSource.FENCE);
        assertThat(extract("\ufeff先说明 {\"status\":\"PASS\",\"items\":[]} 后结束")
                .normalizations()).contains("WRAPPER_TOLERATED");
    }

    @Test
    void handlesBracesAndEscapedQuotesInsideStrings() {
        var result = extract("result={\"status\":\"PASS\",\"items\":[\"{x} and \\\"quoted\\\"\"]}");
        assertThat(result.value().items()).containsExactly("{x} and \"quoted\"");
    }

    @Test
    void normalizesUnambiguousFieldEnumAndCollectionShapes() {
        var result = extract("{\"STATUS\":\"pass\",\"it-ems\":\"one\",\"ignored\":true}");
        assertThat(result.value()).isEqualTo(new Payload("PASS", List.of("one")));
        assertThat(result.normalizations()).contains("FIELD_NAME_NORMALIZED",
                "SINGLETON_COLLECTION_NORMALIZED", "UNKNOWN_FIELDS_IGNORED");
    }

    @Test
    void acceptsEquivalentCandidatesButRejectsConflictingValidCandidates() {
        var equivalent = extract("draft {\"status\":\"PASS\",\"items\":[]} final {\"items\":[],\"status\":\"PASS\"}");
        assertThat(equivalent.normalizations()).contains("EQUIVALENT_CANDIDATES_DEDUPLICATED");

        assertThatThrownBy(() -> extract("{\"status\":\"PASS\",\"items\":[]} {\"status\":\"BLOCKED\",\"items\":[]}"))
                .isInstanceOf(BadRequestException.class)
                .extracting(failure -> ((BadRequestException) failure).code())
                .isEqualTo("TEST_OUTPUT_AMBIGUOUS");
    }

    @Test
    void prefersValidMarkerAndFallsBackWhenMarkerBodyIsInvalid() {
        var preferred = extract("{\"status\":\"BLOCKED\",\"items\":[]}"
                + "<!-- TEST_START -->{\"status\":\"PASS\",\"items\":[]}<!-- TEST_END -->");
        assertThat(preferred.value().status()).isEqualTo("PASS");

        var fallback = extract("<!-- TEST_START -->{'status':'PASS'}<!-- TEST_END -->"
                + " final {\"status\":\"PASS\",\"items\":[]}");
        assertThat(fallback.value().status()).isEqualTo("PASS");
    }

    @Test
    void rejectsArrayRootTruncatedAndJson5Syntax() {
        for (String invalid : List.of("[{\"status\":\"PASS\"}]", "{\"status\":\"PASS\"",
                "{'status':'PASS'}", "{\"status\":\"PASS\",}")) {
            assertThatThrownBy(() -> extract(invalid))
                    .isInstanceOf(BadRequestException.class)
                    .extracting(failure -> ((BadRequestException) failure).code())
                    .isEqualTo("TEST_OUTPUT_UNPARSEABLE");
        }
    }

    @Test
    void extractsMarkerFenceOrPlainMarkdownAndRejectsConflictingFences() {
        assertThat(extractor.extractMarkdown("<!-- TEST_START -->\n# 标题\n<!-- TEST_END -->",
                MARKER, "TEST_MARKDOWN", 100).source())
                .isEqualTo(AiOutputExtractor.CandidateSource.MARKER);
        assertThat(extractor.extractMarkdown("说明\n```markdown\n# 标题\n```\n结束",
                MARKER, "TEST_MARKDOWN", 100).value()).isEqualTo("# 标题");
        assertThat(extractor.extractMarkdown("# 直接内容", MARKER, "TEST_MARKDOWN", 100)
                .normalizations()).containsExactly("WRAPPER_TOLERATED");

        assertThatThrownBy(() -> extractor.extractMarkdown(
                "```md\n# 一\n```\n```\n# 二\n```", MARKER, "TEST_MARKDOWN", 100))
                .isInstanceOf(BadRequestException.class)
                .extracting(failure -> ((BadRequestException) failure).code())
                .isEqualTo("TEST_MARKDOWN_AMBIGUOUS");
    }

    private AiOutputExtractor.ExtractionResult<Payload> extract(String output) {
        return extractor.extractJson(output, MARKER, "TEST_OUTPUT", Payload.class,
                value -> new Payload(value.status() == null ? null : value.status().toUpperCase(), value.items()),
                value -> {
                    if (!List.of("PASS", "BLOCKED").contains(value.status())) {
                        throw new BadRequestException("TEST_STATUS_INVALID", "status invalid");
                    }
                });
    }

    private record Payload(String status, List<String> items) {
        private Payload { items = items == null ? List.of() : List.copyOf(items); }
    }
}
