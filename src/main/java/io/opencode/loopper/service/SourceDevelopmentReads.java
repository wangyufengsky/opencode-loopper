package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Source development reads share snapshot bytes while keeping per-role read receipts independent. */
@Service
public final class SourceDevelopmentReads {
    private final SourceDevelopmentScope scopes;
    private final SourceDevelopmentScopeMapper receipts;
    private final SourceTemplateMapper runs;
    private final SourceTemplateReadService reads;
    private final SourceTestProfileService profiles;
    private final TransactionTemplate transactions;
    public SourceDevelopmentReads(SourceDevelopmentScope scopes, SourceDevelopmentScopeMapper receipts, SourceTemplateMapper runs,
            SourceTemplateReadService reads, SourceTestProfileService profiles, TransactionTemplate transactions) {
        this.scopes = scopes; this.receipts = receipts; this.runs = runs; this.reads = reads; this.profiles = profiles; this.transactions = transactions;
    }
    public Object work(String token) {
        var scope = scopes.authorize(token); var result = new LinkedHashMap<>(scopes.guide(token));
        var profile = profiles.require(scope.runId());
        result.put("targetCount", profile.modules().stream().mapToInt(m -> m.sourcePaths().size()).sum());
        result.put("moduleCount", profile.modules().size());
        result.put("navigation", List.of("list_source_development_files", "read_source_development_file"));
        return result;
    }
    public Object files(String token, int after, int limit) {
        var scope = scopes.authorize(token);
        if (after < -1 || limit < 1 || limit > 100) throw invalid();
        var profile = profiles.require(scope.runId());
        var all = runs.files(scope.runId()).stream().filter(f -> f.ordinal() > after).limit(limit + 1L).toList();
        var page = all.stream().limit(limit).toList();
        return Map.of("items", page.stream().map(f -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("ordinal", f.ordinal()); row.put("path", f.path()); row.put("sha256", f.sha256());
            row.put("sizeBytes", f.sizeBytes()); row.put("exclusion", f.exclusion());
            profile.modules().stream().filter(m -> m.sourcePaths().contains(f.path())).findFirst().ifPresent(m -> {
                row.put("sourceRef", SourceRequirementContext.reference(f.ordinal())); row.put("framework", m.framework());
                row.put("testRoots", m.testRoots()); row.put("fixtureRoots", m.fixtureRoots()); row.put("regressionCommand", m.command());
            });
            return row;
        }).toList(), "nextAfter", all.size() > limit ? page.getLast().ordinal() : -1);
    }
    public SourceTemplateReadService.SourceText read(String token, String path, String hash, int start, int limit) {
        if (start < 1 || limit < 1 || limit > 200) throw invalid();
        var scope = scopes.authorize(token);
        var value = reads.source(scope.runId(), path, start, limit);
        if (!value.sha256().equals(hash)) throw SourceTemplateAdmission.conflict();
        transactions.executeWithoutResult(ignored -> {
            if (!scopes.authorize(token).equals(scope)) throw SourceTemplateAdmission.conflict();
            receipts.read(new SourceDevelopmentScopeMapper.Read(scope.externalSessionId(), path, hash, value.startLine(),
                    value.endLine(), value.totalLines(), Instant.now().toString()));
        });
        return value;
    }
    private static BadRequestException invalid() { return new BadRequestException("SOURCE_READ_PARAMETERS_INVALID", "冻结源码读取参数无效"); }
}
