package io.opencode.loopper.service;

import io.opencode.loopper.runtime.GitEvidenceProcess;
import io.opencode.loopper.template.TemplateDateRange;
import io.opencode.loopper.template.TemplateGitEvidence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Legacy task boundary; the same evidence algorithm also runs in the supervised workflow helper. */
@Service
public class TemplateGitEvidenceCollector {
    private final GitHistoryReader reader;
    public TemplateGitEvidenceCollector(GitEvidenceProcess git) { reader = new GitHistoryReader(git); }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TemplateGitEvidence collect(TemplateGitSnapshotService.Snapshot snapshot, String branchId, TemplateDateRange dates) {
        return reader.collect(snapshot.repository(), snapshot.head(), snapshot.projectPrefix(), branchId, dates);
    }

    public static String hash(String value) { return GitHistoryReader.hash(value); }
}
