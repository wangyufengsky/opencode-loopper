package io.opencode.loopper.service;

import io.opencode.loopper.runtime.GitEvidenceProcess;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Application transaction boundary for the same Git algorithm used by the supervised worker. */
@Component
public final class GitCommitSnapshots {
    private final GitCommitReader reader;
    public GitCommitSnapshots(GitEvidenceProcess git) { reader = new GitCommitReader(git); }
    public GitCommitReader.Source source(Path project, ProjectBranchService.Branch branch) {
        outsideTransaction();
        return reader.source(project, branch == null ? null : new GitCommitReader.Selection(branch.ref(), branch.remote()));
    }
    public String resolve(GitCommitReader.Source source) { outsideTransaction(); return reader.resolve(source); }
    public GitCommitReader.Snapshot capture(GitCommitReader.Source source, Path repository, String commit) {
        outsideTransaction(); return reader.capture(source, repository, commit);
    }
    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Git commit snapshot I/O in transaction");
    }
}
