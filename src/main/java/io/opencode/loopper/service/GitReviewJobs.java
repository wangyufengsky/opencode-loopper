package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.*;
import io.opencode.loopper.template.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Supervised fixed-version capture transport. The owning node must separately prove stop and accept its result. */
@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class GitReviewJobs {
    private final ImmutableContentStore storage;
    private final GitCredentialProvider credentials;
    public GitReviewJobs(LoopperProperties properties, GitCredentialProvider credentials) {
        storage = new ImmutableContentStore(properties.getDataDir().resolve("workflow-git-review")); this.credentials = credentials;
    }
    public record Prepared(DurableCommandProtocol.Request request, String inputSha256) { }
    public Prepared prepare(String attemptId, GitReviewJobProtocol.Input input, int timeoutSeconds) {
        try {
            Path directory = storage.directory(input.nodeId()); byte[] bytes = GitReviewJobProtocol.input(input);
            GitSnapshotJobProtocol.publish(directory.resolve("input"), bytes); String sha = DurableCommandProtocol.hash(bytes);
            Path helper = DurableHelperArchive.write(directory.getParent().resolve("helpers"), GitReviewWorker.class,
                    GitReviewJobProtocol.class, GitReviewEvidenceCodec.class, GitReviewReader.class, SnapshotReview.class,
                    SnapshotDateSelection.class, SnapshotReviewUnits.class, SnapshotReviewInitialEvidence.class,
                    SnapshotReviewLightweightPolicy.class, TemplateAnalysis.class, TemplateDateRange.class,
                    GitSnapshotJobProtocol.class, GitCommitReader.class, GitSnapshotInventory.class, BadRequestException.class,
                    GitEvidenceProcess.class, GitEvidenceDiagnostic.class, GitProjectScope.class, GitCredentialProvider.class,
                    GitHttpAuthentication.class, SafeProcessRunner.class, ProcessResult.class, ExecutableResolver.class,
                    ChildProcessEnvironment.class, TaskFailure.class, DurableCommandProtocol.class);
            String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
            return new Prepared(new DurableCommandProtocol.Request(attemptId, directory.toString(), List.of(java, "-jar", helper.toString(), directory.toString(), sha), timeoutSeconds), sha);
        } catch (IOException failure) { throw invalid(); }
    }
    public Map<String, String> environment(GitReviewJobProtocol.Input input) {
        if (Files.exists(evidence(input.nodeId()), LinkOption.NOFOLLOW_LINKS)) {
            try { read(input, DurableCommandProtocol.hash(GitReviewJobProtocol.input(input))); }
            catch (IOException failure) { throw invalid(); }
            return new GitCredentialProvider.Scope("", false, Map.of()).forWorker();
        }
        var scope = input.source().remote() == null ? new GitCredentialProvider.Scope("", false, Map.of()) : credentials.scope(Path.of(input.source().projectPath()));
        return scope.forWorker();
    }
    public GitReviewJobProtocol.Frozen read(GitReviewJobProtocol.Input input, String expectedHash) {
        try {
            byte[] bytes = GitReviewJobProtocol.input(input);
            if (!DurableCommandProtocol.hash(bytes).equals(expectedHash)) throw invalid();
            Path directory = storage.location(input.nodeId());
            if (!Arrays.equals(GitSnapshotJobProtocol.read(directory.resolve("input")), bytes)) throw invalid();
            var value = GitReviewEvidenceCodec.read(evidence(input.nodeId())); GitReviewJobProtocol.requireBinding(input, expectedHash, value);
            if (!value.selection().equals(GitReviewJobProtocol.selection(GitSnapshotJobProtocol.read(directory.resolve("selection"))))) throw invalid();
            return value;
        } catch (IOException | RuntimeException failure) { throw invalid(); }
    }
    public Path repository(String nodeId) { return storage.location(nodeId).resolve("review.git"); }
    public Path evidence(String nodeId) { return storage.location(nodeId).resolve("evidence"); }
    private static TaskFailure invalid() { return new TaskFailure("WORKFLOW_REVIEW_SOURCE_INVALID", "原版本审查采集记录缺失或不一致，请保留现场后恢复原节点"); }
}
