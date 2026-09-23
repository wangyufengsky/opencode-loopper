package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import org.springframework.stereotype.Service;

/** Each final Judge must independently read all target source files before its verdict can be accepted. */
@Service
public final class SourceOriginalReadCoverage {
    private final SourceTemplateMapper runs;
    private final SourceDevelopmentScopeMapper scopes;
    private final SourceTestProfileService profiles;
    public SourceOriginalReadCoverage(SourceTemplateMapper runs, SourceDevelopmentScopeMapper scopes, SourceTestProfileService profiles) {
        this.runs = runs; this.scopes = scopes; this.profiles = profiles;
    }
    public boolean complete(String task, String session) {
        var run = runs.task(task).orElse(null);
        if (run == null || !run.templateId().equals("UNIT_TEST_DEVELOPMENT")) return true;
        if (session == null) return false;
        var scope = scopes.scope(session).orElse(null); var profile = profiles.require(run.id());
        if (scope == null || !scope.runId().equals(run.id()) || !scope.manifestSha256().equals(profile.manifestSha256())) return false;
        for (var module : profile.modules()) for (var path : module.sourcePaths()) {
            var file = runs.file(run.id(), path).orElseThrow();
            int next = 1, total = -1;
            for (var read : scopes.reads(session, path)) {
                if (!read.sha256().equals(file.sha256()) || read.startLine() > next || total >= 0 && total != read.totalLines()) return false;
                next = Math.max(next, read.endLine() + 1); total = read.totalLines();
            }
            if (total < 0 || next <= total) return false;
        }
        return true;
    }
}
