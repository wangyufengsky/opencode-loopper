package io.opencode.loopper.service;

import static io.opencode.loopper.service.PackageBehaviorContract.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

/** Validates provenance and bounded model-review evidence; this is not a natural-language proof. */
final class PackageBehaviorSourceReview {
    static final String VERSION = "PACKAGE_SOURCE_REVIEW_V1";
    record SourceCheck(String sourceRef, String quote, String disposition, List<String> obligationRefs, String reason) { }
    record Review(String version, String sourceSha256, String extractionSha256, String contextSha256, PackageBehaviorContract book,
            List<SourceCheck> sourceChecks, List<String> findings) { }
    record Result(boolean accepted, String reviewJson, String bookJson, String bookSha256, List<String> problems) { }
    private final ObjectMapper json;
    PackageBehaviorSourceReview(ObjectMapper json) { this.json = json; }
    Result validate(String original, String extraction, String context, String output) { return validate(original, extraction, context, output, true); }
    Result validate(String original, String extraction, String context, String output, boolean checkLogic) {
        if (output == null || output.getBytes(StandardCharsets.UTF_8).length > 65536) return failed("SOURCE_REVIEW_OUTPUT_LIMIT");
        Review review;
        try { review = json.readerFor(Review.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(objectText(output)); }
        catch (RuntimeException invalid) { return failed("SOURCE_REVIEW_INVALID_JSON"); }
        if (review == null || !VERSION.equals(review.version()) || !hash(original).equals(review.sourceSha256())
                || !hash(extraction).equals(review.extractionSha256()) || !hash(context).equals(review.contextSha256()) || review.sourceChecks() == null || review.findings() == null
                || review.findings().size() > 32 || review.findings().stream().anyMatch(f -> f == null || f.isBlank() || f.length() > 1000)) return failed("SOURCE_REVIEW_BINDING_INVALID");
        var sources = PackageRequirementSources.index(original);
        var errors = new ArrayList<>(PackageBehaviorValidation.validate(review.book(), sources.keySet()));
        if (!errors.isEmpty()) return new Result(false, json.writeValueAsString(review), null, null, errors);
        var known = new HashSet<String>(); review.book().obligations().forEach(o -> known.add(o.key())); review.book().invariants().forEach(i -> known.add(i.key()));
        var seen = new HashSet<String>(); var reviewed = new HashSet<String>();
        if (review.sourceChecks().size() != sources.size()) errors.add("SOURCE_REVIEW_COVERAGE");
        for (var check : review.sourceChecks()) {
            if (check == null || !sources.containsKey(check.sourceRef()) || !seen.add(check.sourceRef())
                    || check.quote() == null || check.quote().isBlank() || check.quote().length() > 1000
                    || !sources.get(check.sourceRef()).text().contains(check.quote()) || check.reason() == null || check.reason().isBlank()
                    || check.reason().length() > 1000 || check.obligationRefs() == null || !known.containsAll(check.obligationRefs())) {
                errors.add("SOURCE_REVIEW_REFERENCE_INVALID"); continue;
            }
            reviewed.addAll(check.obligationRefs());
            if ("MODELED".equals(check.disposition())) {
                if (check.obligationRefs().isEmpty() || review.book().unmodeledSources().contains(check.sourceRef())) errors.add("SOURCE_REVIEW_MODELED_BINDING");
                for (String ref : check.obligationRefs()) {
                    boolean linked = review.book().obligations().stream().anyMatch(o -> o.key().equals(ref) && o.sourceRefs().contains(check.sourceRef()))
                            || review.book().invariants().stream().anyMatch(i -> i.key().equals(ref) && i.sourceRefs().contains(check.sourceRef()));
                    if (!linked) errors.add("SOURCE_REVIEW_OBLIGATION_SOURCE_MISMATCH");
                }
            } else if (!"NON_BEHAVIOR".equals(check.disposition()) || !check.obligationRefs().isEmpty()
                    || !review.book().unmodeledSources().contains(check.sourceRef())) errors.add("SOURCE_REVIEW_UNCONFIRMED");
        }
        if (!reviewed.equals(known)) errors.add("SOURCE_REVIEW_OBLIGATION_COVERAGE");
        if (!seen.equals(sources.keySet())) errors.add("SOURCE_REVIEW_COVERAGE");
        if (!review.findings().isEmpty()) errors.addAll(review.findings());
        if (errors.isEmpty() && checkLogic) {
            var branches = review.book().obligations().stream().map(o -> new Branch(o.key(), List.of(o.key()), o.event(), o.when(), o.effects())).toList();
            var scenarios = review.book().obligations().stream().map(Obligation::key).collect(java.util.stream.Collectors.toSet());
            var report = new PackageBehaviorChecker(new PackageBehaviorSatSolver()).check(review.book(), branches, sources.keySet(), scenarios);
            if (!report.passed()) errors.addAll(report.findings().stream().map(PackageBehaviorChecker.Finding::code).toList());
        }
        String book = json.writeValueAsString(review.book());
        if (book.getBytes(StandardCharsets.UTF_8).length > 32768) errors.add("SOURCE_MODEL_SIZE_LIMIT");
        return new Result(errors.isEmpty(), json.writeValueAsString(review), errors.isEmpty() ? book : null,
                errors.isEmpty() ? hash(book) : null, List.copyOf(errors));
    }
    static String objectText(String output) {
        String value = output.strip();
        if (value.startsWith("```json\n") && value.endsWith("```")) return value.substring(8, value.length() - 3).strip();
        if (value.startsWith("```\n") && value.endsWith("```")) return value.substring(4, value.length() - 3).strip();
        return value;
    }
    static String hash(String value) { return PackageDesignEvidencePreparation.hash(value.getBytes(StandardCharsets.UTF_8)); }
    private Result failed(String code) { return new Result(false, null, null, null, List.of(code)); }
}
