package io.opencode.loopper.service;

import io.opencode.loopper.template.TemplateAnalysis;
import io.opencode.loopper.template.TemplateAnalysisValidation;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Accepts only the frozen structured protocol; prose or task-success claims have no authority. */
@Component
public final class TemplateCandidateCodec {
    private final ObjectMapper json;
    private SnapshotReviewProtocol snapshots;
    public TemplateCandidateCodec(ObjectMapper json) { this.json = json; }
    @org.springframework.beans.factory.annotation.Autowired
    public TemplateCandidateCodec(ObjectMapper json, SnapshotReviewProtocol snapshots) { this.json = json; this.snapshots = snapshots; }
    public String snapshotPrompt(io.opencode.loopper.persistence.TemplateTaskBatchRow row) { return snapshots.prompt(row); }
    public String snapshot(io.opencode.loopper.persistence.TemplateTaskBatchRow row, String output) { return snapshots.validate(row, output); }

    public String review(String response, List<TemplateAnalysis.Unit> units) {
        try {
            var candidate = json.readerFor(TemplateAnalysis.BatchCandidate.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(object(response));
            List<TemplateAnalysis.UnitReview> reviews = TemplateAnalysisValidation.batch(units, (TemplateAnalysis.BatchCandidate) candidate);
            return json.writeValueAsString(new TemplateAnalysis.BatchCandidate(reviews));
        } catch (RuntimeException invalid) { throw invalid(invalid); }
    }

    public String contributor(String response, String identity, Set<String> evidenceIds) {
        try {
            TemplateAnalysis.ContributorCandidate candidate = json.readerFor(TemplateAnalysis.ContributorCandidate.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(object(response));
            return json.writeValueAsString(TemplateAnalysisValidation.contributor(identity, evidenceIds, candidate));
        } catch (RuntimeException invalid) { throw invalid(invalid); }
    }

    private static String object(String response) {
        if (response == null || response.length() > 200_000) throw new IllegalArgumentException("候选为空或超过内容上限");
        String value = response.strip();
        String start = TemplateAnalysisPromptFactory.START, end = TemplateAnalysisPromptFactory.END;
        int first = value.indexOf(start);
        if (first >= 0) {
            int last = value.indexOf(end, first + start.length());
            if (first != value.lastIndexOf(start) || last < 0 || last != value.lastIndexOf(end)) {
                throw new IllegalArgumentException("候选标记必须成对且只出现一次");
            }
            value = value.substring(first + start.length(), last).strip();
        }
        if (!value.startsWith("{") || !value.endsWith("}")) throw new IllegalArgumentException("必须提交指定的 JSON 对象");
        return value;
    }

    private static BadRequestException invalid(RuntimeException cause) {
        // Do not echo model output or arbitrary Jackson diagnostics into task alerts.
        String detail = cause instanceof IllegalArgumentException && cause.getMessage() != null
                && !cause.getMessage().contains("\n") && cause.getMessage().length() < 200
                ? cause.getMessage() : "结构、证据覆盖或评分依据未通过校验";
        return new BadRequestException("TEMPLATE_CANDIDATE_INVALID", "报告候选需要修正：" + detail);
    }
}
