package io.opencode.loopper.service;

import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class DocumentBatchRetryService {
    private final DocumentTemplateMapper runs;
    private final DocumentTemplateModelMapper models;
    private final DocumentModelStore store;
    private final DocumentTemplateControl control;
    private final ObjectMapper json;
    private final DocumentAssessmentMapper progress;
    public DocumentBatchRetryService(DocumentTemplateMapper runs, DocumentTemplateModelMapper models,
            DocumentModelStore store, DocumentTemplateControl control, ObjectMapper json, DocumentAssessmentMapper progress) {
        this.runs = runs; this.models = models; this.store = store; this.control = control; this.json = json; this.progress = progress;
    }
    public CursorPage<TemplateTaskReadMapper.FailedBatch> list(String id, String cursor, int limit) {
        require(id);
        if (cursor != null && cursor.length() > 512) throw new BadRequestException("PAGE_CURSOR_INVALID", "分页位置无效");
        var after = PageCursor.decode(cursor); PageCursor.limit(limit);
        var rows = models.failedBatchPage(id, after == null ? null : after.value(), after == null ? null : after.id(), limit + 1);
        var page = rows.stream().limit(limit).map(row -> new TemplateTaskReadMapper.FailedBatch(row.id(), row.ordinal(),
                row.purpose(), row.generation(), row.state(), message(row.errorMessage()), row.version(), row.createdAt())).toList();
        return new CursorPage<>(page, rows.size() > limit ? new PageCursor(page.getLast().createdAt(), page.getLast().id()).encode() : null);
    }
    @Transactional
    public DocumentTemplateModelRow retry(String id, String batchId, long expectedVersion) {
        var run = require(id);
        if (!java.util.Set.of("ASSESSING", "VERIFYING").contains(run.state()))
            throw new ConflictException("DOCUMENT_BATCH_RETRY_UNAVAILABLE", "请先恢复当前评审阶段，并处理总预算或时限限制");
        var batch = store.require(batchId);
        if (!batch.runId().equals(id)) throw new NotFoundException("批次不属于当前需求任务");
        var phase = run.state().equals("ASSESSING") ? "DOCUMENT_CODE_ASSESSMENT_V2" : "DOCUMENT_CODE_REVIEW_V2";
        var round = progress.progress(id).orElseThrow();
        var input = json.readValue(batch.inputJson(), io.opencode.loopper.template.DocumentModelInput.class);
        if (!phase.equals(batch.candidateKind()) || batch.generation() != round.round() || input.sourceRevision() != run.sourceRevision())
            throw new ConflictException("DOCUMENT_BATCH_SCOPE_STALE", "该批次属于旧评审轮次或原文版本，请刷新后选择当前批次");
        var contract = json.readValue(run.contractJson(), DocumentTemplateService.Contract.class);
        control.budget(run, contract);
        return store.retry(batchId, expectedVersion, true, contract);
    }
    private DocumentTemplateRunRow require(String id) {
        var run = runs.find(id).orElseThrow(() -> new NotFoundException("需求任务不存在"));
        if (!run.templateId().equals("REQUIREMENT_CODE_REVIEW") || !run.templateVersion().equals("3"))
            throw new BadRequestException("DOCUMENT_BATCH_RETRY_UNAVAILABLE", "此入口适用于新版需求代码评审，历史任务使用原恢复入口");
        return run;
    }
    private static String message(String code) {
        if ("DOCUMENT_SUBMISSION_MISSING".equals(code)) return "模型会话已结束，但未提交有效结果。";
        if ("DOCUMENT_MODEL_FAILED".equals(code)) return "模型会话失败，请检查模型连接后重试该批次。";
        return "该批次未完成，可在停止确认后单独重试。";
    }
}
