package io.opencode.loopper.service;

import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.runtime.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import static io.opencode.loopper.service.GitSnapshotJobProtocol.*;

/** Captures current-version review material only after a durable process grant; never executes project code. */
public final class GitReviewWorker {
    private GitReviewWorker() { }
    public static void main(String[] args) {
        try {
            if (args.length != 2) throw new IllegalArgumentException();
            execute(Path.of(args[0]), args[1]); System.out.println("LOOPPER_GIT_REVIEW_READY");
        } catch (TaskFailure failure) { fail(failure.code()); }
        catch (BadRequestException failure) { fail(failure.code()); }
        catch (GitReviewEvidenceCodec.LimitException failure) { fail("SNAPSHOT_EVIDENCE_LIMIT"); }
        catch (IOException | RuntimeException | LinkageError failure) { fail("WORKFLOW_REVIEW_SOURCE_INVALID"); }
    }
    static void execute(Path directory, String expectedHash) throws IOException {
        DurableCommandProtocol.check(directory);
        if (!directory.isAbsolute() || !directory.equals(directory.toRealPath())) throw new IOException("Review directory identity");
        try (var channel = FileChannel.open(directory.resolve("capture.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Review already owned");
            byte[] bytes = read(directory.resolve("input")); var input = GitReviewJobProtocol.input(bytes); var sourceInput = input.source();
            if (!DurableCommandProtocol.hash(bytes).equals(expectedHash) || !directory.getFileName().toString().equals(input.nodeId()))
                throw new IOException("Review input identity");
            var git = new GitEvidenceProcess(new SafeProcessRunner()); var scope = GitCredentialProvider.Scope.fromWorker();
            git.credentialProvider((project, remote) -> scope.forRemote(remote)); git.requireSupported(directory);
            var reader = new GitCommitReader(git); Path selection = directory.resolve("selection"); GitReviewJobProtocol.Selection selected;
            if (Files.exists(selection, LinkOption.NOFOLLOW_LINKS)) {
                selected = GitReviewJobProtocol.selection(read(selection));
                if (!selected.binding().inputSha256().equals(expectedHash)) throw new IOException("Review selection differs");
            } else {
                var source = reader.source(Path.of(sourceInput.projectPath()), sourceInput.selection());
                if (sourceInput.remote() == null) reader.requireCompleteHistory(source.repository());
                selected = new GitReviewJobProtocol.Selection(Binding.from(expectedHash, source, reader.resolve(source)), Instant.now().toString());
                publish(selection, GitReviewJobProtocol.selection(selected));
            }
            Path evidence = directory.resolve("evidence");
            if (Files.exists(evidence, LinkOption.NOFOLLOW_LINKS)) {
                var frozen = GitReviewEvidenceCodec.read(evidence); GitReviewJobProtocol.requireBinding(input, expectedHash, frozen);
                if (!frozen.selection().equals(selected)) throw new IOException("Review selection differs");
            }
            var fixed = selected.binding(); Path repository = directory.resolve("review.git");
            reader.captureHistory(fixed.source(sourceInput), repository, fixed.commit(), () -> {
                var source = reader.source(Path.of(sourceInput.projectPath()), sourceInput.selection());
                if (!fixed.matches(source)) throw new BadRequestException("DOCUMENT_CODE_SOURCE_INVALID", "原项目的 Git 归属已改变，不能替换原版本审查来源");
                if (sourceInput.remote() == null) reader.requireCompleteHistory(source.repository());
                return source.remote();
            });
            var snapshot = new GitReviewReader(git).collect(repository, fixed.commit(), fixed.prefix(), input.mode(), input.dates(),
                    "3", input.scope(fixed), selected.capturedAt());
            GitReviewEvidenceCodec.publish(evidence, new GitReviewJobProtocol.Frozen(selected, snapshot));
        }
    }
    private static void fail(String code) {
        System.out.println("LOOPPER_GIT_REVIEW_FAILURE:" + (code != null && code.matches("[A-Z][A-Z0-9_]{0,100}") ? code : "WORKFLOW_REVIEW_SOURCE_INVALID"));
        System.exit(1);
    }
}
