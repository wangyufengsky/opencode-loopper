package io.opencode.loopper.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and normalizes the bounded Judge response contract. */
final class TaskJudgeDecisionParser {
    private static final Pattern JSON_MARKER = Pattern.compile(
            "(?s)<LOOPPER_JUDGE_JSON>\\s*(.*?)\\s*</LOOPPER_JUDGE_JSON>");
    private static final Pattern VERDICT_LABEL = Pattern.compile(
            "(?im)^\\s*(?:VERDICT|判定)\\s*[:：]\\s*(PASS|REVISE|BLOCKED)\\s*$");
    private static final Pattern REASON_LABEL = Pattern.compile(
            "(?ims)^\\s*(?:REASON|理由)\\s*[:：]\\s*(.+?)\\s*$");
    private final AiOutputExtractor extractor;

    TaskJudgeDecisionParser(AiOutputExtractor extractor) {
        this.extractor = extractor;
    }

    Decision parse(String rawOutput) {
        try {
            AiOutputExtractor.ExtractionResult<Payload> extracted = extractor.extractJson(
                    rawOutput, JSON_MARKER, "JUDGE_OUTPUT", Payload.class, payload -> payload,
                    payload -> {
                        if (payload.verdict() == null || !Set.of("PASS", "REVISE", "BLOCKED")
                                .contains(payload.verdict().trim().toUpperCase())) {
                            throw new BadRequestException("JUDGE_OUTPUT_VERDICT_INVALID",
                                    "Judge verdict must be exactly PASS, REVISE, or BLOCKED");
                        }
                        if (payload.reason() == null || payload.reason().isBlank()) {
                            throw new BadRequestException("JUDGE_OUTPUT_REASON_REQUIRED",
                                    "Judge response requires a non-empty reason");
                        }
                    });
            return new Decision(extracted.value().verdict().trim().toUpperCase(),
                    extracted.value().reason().trim(), null, extracted.normalizations());
        } catch (BadRequestException exception) {
            Decision labeled = parseLabeled(rawOutput);
            if (labeled != null) return labeled;
            return new Decision(null, null,
                    exception.code() + ": " + safeMessage(exception.getMessage()), List.of());
        }
    }

    boolean isLabeled(String rawOutput) {
        return parseLabeled(rawOutput) != null;
    }

    private Decision parseLabeled(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) return null;
        Matcher verdictMatcher = VERDICT_LABEL.matcher(rawOutput);
        LinkedHashMap<String, Boolean> verdicts = new LinkedHashMap<>();
        while (verdictMatcher.find()) verdicts.put(verdictMatcher.group(1).toUpperCase(), Boolean.TRUE);
        if (verdicts.size() != 1) return null;
        Matcher reasonMatcher = REASON_LABEL.matcher(rawOutput);
        if (!reasonMatcher.find()) return null;
        String reason = reasonMatcher.group(1).trim();
        if (reason.isBlank()) return null;
        return new Decision(verdicts.keySet().iterator().next(), reason, null,
                List.of("LABELED_JUDGE_OUTPUT_NORMALIZED"));
    }

    private String safeMessage(String value) {
        return value == null ? "Unknown error" : value.substring(0, Math.min(value.length(), 4_000));
    }

    private record Payload(String verdict, String reason) { }

    record Decision(String verdict, String reason, String parseError, List<String> normalizations) {
        Decision {
            normalizations = normalizations == null ? List.of() : List.copyOf(normalizations);
        }
    }
}
