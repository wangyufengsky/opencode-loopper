package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Role reads expose immutable bodies only and record exactly what was returned. */
@Service
public final class SourceModelReads {
    private static final int PART_SIZE = 24000;
    private final SourceModelAccess access;
    private final SourceTemplateModelMapper models;
    private final SourceTemplateMapper runs;
    private final SourceTemplateReadService reads;
    private final SourceTemplateCandidatePolicy policy;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    public SourceModelReads(SourceModelAccess access, SourceTemplateModelMapper models, SourceTemplateMapper runs,
            SourceTemplateReadService reads, SourceTemplateCandidatePolicy policy,
            TransactionTemplate transactions, ObjectMapper json) {
        this.access = access; this.models = models; this.runs = runs; this.reads = reads;
        this.policy = policy; this.transactions = transactions; this.json = json;
    }
    public String authorizedSession(String id) { return access.require(id).externalSessionId(); }
    public Object work(String id) {
        var model = access.require(id); var input = policy.input(model);
        var files = input.paths().stream().map(path -> {
            var file = runs.file(model.runId(), path).orElseThrow();
            return Map.of("path", path, "sha256", file.sha256(), "sizeBytes", file.sizeBytes(),
                    "readRanges", models.reads(id, path).stream().map(r -> List.of(r.startLine(), r.endLine())).toList());
        }).toList();
        return Map.of("input", input, "files", files, "role", model.candidateKind());
    }
    public Object files(String id, int after, int limit) {
        var model = access.require(id);
        if (after < -1 || limit < 1 || limit > 100) throw invalid();
        var found = runs.files(model.runId()).stream().filter(f -> f.ordinal() > after).limit(limit + 1L).toList();
        var page = found.stream().limit(limit).toList();
        return Map.of("items", page, "nextAfter", found.size() > limit ? page.getLast().ordinal() : -1);
    }
    public SourceTemplateReadService.SourceText read(String id, String path, String expected, int start, int limit) {
        if (start < 1 || limit < 1 || limit > 200) throw invalid();
        var model = access.require(id);
        var value = reads.source(model.runId(), path, start, limit);
        if (!value.sha256().equals(expected)) throw SourceTemplateAdmission.conflict();
        transactions.executeWithoutResult(ignored -> {
            var current = access.require(id);
            if (!current.equals(model)) throw SourceTemplateAdmission.conflict();
            models.readEvidence(new SourceTemplateModelMapper.Read(id, path, value.sha256(),
                    value.startLine(), value.endLine(), value.totalLines(), value.content(), Instant.now().toString()));
        });
        return value;
    }
    public Object results(String id, int after, int limit) {
        if (after < -1 || limit < 1 || limit > 100) throw invalid();
        var model = access.require(id);
        var all = models.current(model.runId(), "SOURCE_DETAILED_DESIGN_V1", model.generation()).stream()
                .filter(r -> r.state().equals("VALIDATED") && r.ordinal() > after).limit(limit + 1L).toList();
        var page = all.stream().limit(limit).toList();
        return Map.of("items", page.stream().map(r -> {
            var candidate = json.readValue(r.outputJson(), SourceDesign.Candidate.class);
            return Map.of("id", r.id(), "ordinal", r.ordinal(), "sha256", r.outputSha256(),
                    "title", candidate.title(), "summary", candidate.summary(), "parts", partCount(r.outputJson()));
        }).toList(), "nextAfter", all.size() > limit ? page.getLast().ordinal() : -1);
    }
    public Object result(String id, String resultId, String expected, int part) {
        var model = access.require(id);
        var draft = models.find(resultId).orElseThrow(() -> new NotFoundException("设计结果不存在"));
        if (!draft.runId().equals(model.runId()) || draft.generation() != model.generation()
                || !draft.state().equals("VALIDATED") || !draft.candidateKind().equals("SOURCE_DETAILED_DESIGN_V1")
                || !Objects.equals(expected, draft.outputSha256())) throw SourceTemplateAdmission.conflict();
        if (part < 0 || part >= partCount(draft.outputJson())) throw invalid();
        int offset = part * PART_SIZE;
        String text = draft.outputJson().substring(offset, Math.min(draft.outputJson().length(), offset + PART_SIZE));
        transactions.executeWithoutResult(ignored -> {
            access.require(id);
            models.resultRead(id, resultId, expected, part, Instant.now().toString());
        });
        return Map.of("id", resultId, "sha256", expected, "part", part,
                "parts", partCount(draft.outputJson()), "content", text);
    }
    static int partCount(String value) { return Math.max(1, (value.length() + PART_SIZE - 1) / PART_SIZE); }
    private static BadRequestException invalid() {
        return new BadRequestException("SOURCE_READ_PARAMETERS_INVALID", "源码读取参数无效，请按工具结构和范围重试");
    }
}
