package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TemplateCandidateSubmissionMapper;
import io.opencode.loopper.persistence.TemplateTaskBatchRow;
import io.opencode.loopper.runtime.InternalMcpRuntimeAccess;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Validates one frozen batch and returns repair feedback without advancing execution or calling the provider. */
@Service
public class TemplateCandidateSubmissionService {
    private final TemplateBatchStore batches;
    private final TemplateCandidateSubmissionMapper receipts;
    private final LoopperMapper mapper;
    private final InternalMcpRuntimeAccess access;
    private final TemplateCandidateCodec codec;
    private final ObjectMapper json;

    TemplateCandidateSubmissionService(TemplateBatchStore batches, TemplateCandidateSubmissionMapper receipts,
            LoopperMapper mapper, InternalMcpRuntimeAccess access, TemplateCandidateCodec codec, ObjectMapper json) {
        this.batches = batches; this.receipts = receipts; this.mapper = mapper;
        this.access = access; this.codec = codec; this.json = json;
    }

    @Transactional
    public String submit(String batchId, String key, long expectedRevision, String candidate) {
        TemplateTaskBatchRow batch = batches.require(batchId);
        requireIdentity(batch);
        String digest = TemplateGitEvidenceCollector.hash(candidate);
        var replay = receipts.replay(batchId, key).orElse(null);
        if (replay != null) {
            if (!digest.equals(replay.candidateSha256())) throw conflict("TEMPLATE_IDEMPOTENCY_CONFLICT", "同一请求键不能提交不同候选");
            return replay.responseJson();
        }
        batches.requireRunning(batch.taskId(), batch.attemptId());
        if (!List.of("DISPATCHING", "RUNNING").contains(batch.state())) {
            throw conflict("TEMPLATE_SUBMISSION_CLOSED", "批次已停止或尚未允许投递，不接受新候选");
        }
        long revision = receipts.revision(batchId);
        if (expectedRevision != revision) return response("REJECTED", revision, "REFRESH_REVISION_AND_RESUBMIT",
                "TEMPLATE_SUBMISSION_REVISION_CONFLICT", "/expectedSubmissionRevision", "使用返回的 submissionRevision 重新提交完整候选");
        if (receipts.accepted(batchId).isPresent()) throw conflict("TEMPLATE_SUBMISSION_CLOSED", "候选已接受，请结束会话");
        String output = null;
        String result;
        try {
            var input = json.readValue(batch.inputJson(), TemplateBatchExecution.Input.class);
            output = input.snapshot() != null ? codec.snapshot(batch, candidate) : batch.purpose().equals("REVIEW") ? codec.review(candidate, input.units())
                    : codec.contributor(candidate, input.person().author().identity(), input.person().evidenceIds());
            result = response("ACCEPTED", revision + 1, "STOP", null, null, null);
        } catch (BadRequestException invalid) {
            result = response("REJECTED", revision + 1, "FIX_AND_RESUBMIT", invalid.code(), "/candidate", invalid.getMessage());
        }
        var receipt = new TemplateCandidateSubmissionMapper.Receipt(batchId, revision + 1, key, digest,
                output == null ? 0 : 1, output, result, Instant.now().toString());
        if (receipts.insert(receipt) != 1) throw conflict("TEMPLATE_SUBMISSION_CONFLICT", "提交发生冲突，请重试原请求");
        return result;
    }

    public SubmissionContractReadService.Contract contract(String batchId) {
        var batch = batches.require(batchId);
        requireIdentity(batch);
        batches.requireRunning(batch.taskId(), batch.attemptId());
        if (!List.of("DISPATCHING", "RUNNING").contains(batch.state()))
            throw conflict("TEMPLATE_SUBMISSION_CLOSED", "批次当前不接受新候选");
        var input = json.readValue(batch.inputJson(), TemplateBatchExecution.Input.class);
        var context = new java.util.LinkedHashMap<String, Object>();
        context.put("batchOrdinal", batch.ordinal() + 1); context.put("generation", batch.generation());
        context.put("purpose", batch.purpose()); context.put("readOnly", true);
        if (input.snapshot() != null) {
            context.put("phase", input.snapshot().phase());
            context.put("candidateShape", SnapshotReviewProtocol.shape(input.snapshot().phase()));
        } else if (batch.purpose().equals("REVIEW")) context.put("requiredReviews", input.units().stream().map(unit -> Map.of(
                "unitId", unit.id(), "path", unit.path(), "locations", TemplateAnalysisPromptFactory.locations(unit))).toList());
        else {
            context.put("identity", input.person().author().identity());
            context.put("evidenceIds", input.person().evidenceIds());
            context.put("instruction", "只评价该身份的本人贡献；证据编号从给定集合选取，评分等级按冻结维度填写。");
        }
        return new SubmissionContractReadService.Contract(io.opencode.loopper.runtime.InternalMcpContractCatalog.TEMPLATE_TOOL,
                batch.purpose(), receipts.revision(batchId), Map.copyOf(context));
    }

    private void requireIdentity(TemplateTaskBatchRow batch) {
        if (batch.creationPlanJson() == null || batch.sessionId() == null) throw conflict("TEMPLATE_SUBMISSION_CLOSED", "批次没有冻结会话");
        var plan = json.readValue(batch.creationPlanJson(), OpenCodeClient.SessionCreationPlan.class);
        var active = access.current().orElseThrow(() -> conflict("TEMPLATE_SUBMISSION_GENERATION_CHANGED", "托管运行环境未就绪"));
        var session = mapper.findSession(batch.sessionId()).orElseThrow();
        if ((plan.profile() != OpenCodeClient.SessionProfile.TEMPLATE_ANALYSIS_CANDIDATE_NO_TOOLS
                && plan.profile() != OpenCodeClient.SessionProfile.SNAPSHOT_CODE_REVIEW_NO_TOOLS)
                || !plan.managed() || !active.generation().equals(plan.runtimeGenerationId())
                || !active.serverName().equals(plan.internalMcpServer())
                || !batch.taskId().equals(session.taskId()) || !batch.attemptId().equals(session.attemptId())
                || session.externalSessionId() == null) {
            throw conflict("TEMPLATE_SUBMISSION_GENERATION_CHANGED", "提交不属于本批次冻结的模板 MCP 会话与运行环境");
        }
    }

    private String response(String outcome, long revision, String action, String code, String pointer, String detail) {
        return json.writeValueAsString(Map.of("outcome", outcome, "submissionRevision", revision, "action", action,
                "problems", code == null ? List.of() : List.of(Map.of("code", code, "jsonPointer", pointer, "detail", detail))));
    }
    private static ConflictException conflict(String code, String detail) { return new ConflictException(code, detail); }
}
