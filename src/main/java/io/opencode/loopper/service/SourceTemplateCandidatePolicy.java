package io.opencode.loopper.service;

import io.opencode.loopper.domain.MachineCandidateKind;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.runtime.SourceTemplateProfiles;
import io.opencode.loopper.template.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public final class SourceTemplateCandidatePolicy implements CandidatePolicy {
    private final SourceTemplateModelMapper models;
    private final SourceTemplateMapper runs;
    private final ObjectMapper json;
    public SourceTemplateCandidatePolicy(SourceTemplateModelMapper models, SourceTemplateMapper runs, ObjectMapper json) {
        this.models = models; this.runs = runs; this.json = json;
    }
    @Override public boolean supports(MachineCandidateKind kind) { return SourceTemplateProfiles.supports(kind); }
    @Override public Decision evaluate(Context context, String candidateJson) {
        try {
            var model = models.find(context.owner().id()).orElseThrow(SourceTemplateAdmission::conflict);
            if (!context.candidateKind().name().equals(model.candidateKind()) || context.sourceRevision() != model.generation()
                    || !context.contractVersion().equals(model.candidateKind())) throw SourceTemplateAdmission.conflict();
            var input = input(model);
            var snapshot = json.readValue(runs.find(model.runId()).orElseThrow().snapshotJson(), SourceSnapshot.class);
            if (!snapshot.ready() || !snapshot.manifestSha256().equals(input.manifestSha256())) throw SourceTemplateAdmission.conflict();
            if (candidateJson.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
                throw new BadRequestException("SOURCE_DESIGN_LIMIT", "单批设计超过 256 KiB，请精简重复内容");
            Object value;
            if (context.candidateKind() == MachineCandidateKind.SOURCE_DETAILED_DESIGN_V1)
                value = SourceDesignValidation.design(input, json.readValue(candidateJson, SourceDesign.Candidate.class),
                        path -> models.reads(model.id(), path));
            else {
                var draft = models.find(input.draftModelId()).orElseThrow(SourceTemplateAdmission::conflict);
                if (!draft.runId().equals(model.runId()) || !draft.state().equals("VALIDATED")
                        || !draft.outputSha256().equals(input.draftSha256())) throw SourceTemplateAdmission.conflict();
                for (var contextDraft : models.current(model.runId(), "SOURCE_DETAILED_DESIGN_V1", model.generation()))
                    if (!contextDraft.state().equals("VALIDATED") || models.resultReads(model.id(), contextDraft.id(), contextDraft.outputSha256())
                            != SourceModelReads.partCount(contextDraft.outputJson()))
                        throw new BadRequestException("SOURCE_DRAFT_NOT_READ", "请完整读取本轮全部模块文档，核对跨模块一致性后再提交复核结论");
                value = SourceDesignValidation.review(input, json.readValue(candidateJson, SourceDesign.Review.class),
                        path -> models.reads(model.id(), path));
            }
            return Decision.accepted(json.writeValueAsString(value));
        } catch (DocumentCandidateProblem invalid) {
            return Decision.rejected(true, List.of(new MachineCandidateSubmission.Problem(invalid.code(), invalid.pointer(), invalid.getMessage())));
        } catch (BadRequestException invalid) {
            return Decision.rejected(true, List.of(new MachineCandidateSubmission.Problem(invalid.code(), "/candidate", invalid.getMessage())));
        } catch (JacksonException invalid) {
            return Decision.rejected(true, List.of(new MachineCandidateSubmission.Problem("SOURCE_CANDIDATE_JSON_INVALID", "/candidate", "请按本角色完整参数结构提交")));
        }
    }
    public SourceDesign.Input input(SourceTemplateModelRow model) {
        if (!SourceTreeCapture.hash(model.inputJson().getBytes(StandardCharsets.UTF_8)).equals(model.inputSha256()))
            throw new ConflictException("SOURCE_MODEL_INPUT_CHANGED", "冻结模型输入校验失败");
        return json.readValue(model.inputJson(), SourceDesign.Input.class);
    }
}
