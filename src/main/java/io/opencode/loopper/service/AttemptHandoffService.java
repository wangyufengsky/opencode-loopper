package io.opencode.loopper.service;

import io.opencode.loopper.domain.LoopSpec;
import io.opencode.loopper.domain.TaskFailure;
import io.opencode.loopper.domain.VerificationState;
import io.opencode.loopper.verification.VerifierEngine;
import io.opencode.loopper.verification.VerifierOutcome;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Builds a bounded, deterministic handoff between isolated implementation Attempts. */
@org.springframework.stereotype.Service
public class AttemptHandoffService {
    private static final int MAX_CAPTURED_PATHS = 64;
    private static final int MAX_PROMPT_PATHS = 24;
    private static final int MAX_PATH_CHARS = 256;
    private static final int MAX_SUMMARY_CHARS = 400;
    private static final int MAX_FAILURE_CHARS = 2_000;
    private static final int MAX_TEMPLATE_CHARS = 4_000;
    private static final int MAX_RETRY_PROMPT_CHARS = 12_000;
    private static final long MAX_HASHED_FILE_BYTES = 16L * 1024 * 1024;
    private final VerifierEngine verifiers;

    public AttemptHandoffService(VerifierEngine verifiers) {
        this.verifiers = verifiers;
    }

    public Capture capture(Path worktree, String baseline, String stageId, String attemptId, int attemptOrdinal,
                           List<VerificationFact> verificationFacts, String failureSummary, Duration timeout) {
        List<VerificationFact> boundedFacts = verificationFacts == null ? List.of() : verificationFacts.stream()
                .limit(32)
                .map(fact -> new VerificationFact(trim(fact.type(), 64), trim(fact.state(), 32),
                        trim(fact.summary(), MAX_SUMMARY_CHARS)))
                .toList();
        try {
            LoopSpec.VerifierSpec snapshotSpec = new LoopSpec.VerifierSpec(
                    "GIT_DIFF", null, null, false, List.of(), List.of(), false);
            VerifierOutcome outcome = verifiers.verify(worktree, baseline, snapshotSpec, timeout);
            if (outcome.state() == VerificationState.ERROR) {
                return unavailable(stageId, attemptId, attemptOrdinal, boundedFacts, failureSummary,
                        "Workspace diff inspection returned an error: " + outcome.summary());
            }
            List<String> allChangedPaths = changedPaths(outcome);
            WorkspaceFingerprint workspace = fingerprint(worktree, allChangedPaths);
            List<String> capturedPaths = allChangedPaths.stream().limit(MAX_CAPTURED_PATHS)
                    .map(path -> trim(path, MAX_PATH_CHARS)).toList();
            String failure = trim(failureSummary, MAX_FAILURE_CHARS);
            String stagnationFingerprint = workspace.reliable()
                    ? sha256(stageId + "\u0000" + failedSignature(boundedFacts) + "\u0000" + workspace.sha256())
                    : null;
            return new Capture("v1", stageId, attemptId, attemptOrdinal, failure, boundedFacts,
                    capturedPaths, allChangedPaths.size(), workspace.sha256(), workspace.reliable(),
                    workspace.reason(), stagnationFingerprint);
        } catch (RuntimeException failure) {
            return unavailable(stageId, attemptId, attemptOrdinal, boundedFacts, failureSummary,
                    failure instanceof TaskFailure taskFailure
                            ? taskFailure.code() + ": " + taskFailure.getMessage()
                            : failure.getMessage());
        }
    }

    public String retryPrompt(Capture capture, String nextAttemptPromptTemplate) {
        return retryPrompt(capture, nextAttemptPromptTemplate, "");
    }

    public String explicitRetryPrompt(Capture capture, String nextAttemptPromptTemplate) {
        return retryPrompt(capture, nextAttemptPromptTemplate,
                "The user explicitly approved one fresh retry after Loopper stopped an unchanged or policy-blocked loop.\n");
    }

    private String retryPrompt(Capture capture, String nextAttemptPromptTemplate, String prefix) {
        String changed = capture.changedPaths().isEmpty() ? "(none)"
                : String.join(", ", capture.changedPaths().stream().limit(MAX_PROMPT_PATHS).toList());
        String verification = capture.verifications().stream()
                .map(fact -> fact.type() + "=" + fact.state() + " (" + fact.summary() + ")")
                .reduce((left, right) -> left + "; " + right).orElse("(none)");
        String structured = prefix + "Previous Attempt handoff (server-generated, bounded, and read-only):\n"
                + "- Attempt: " + capture.attemptOrdinal() + "\n"
                + "- Verification failure: " + capture.failureSummary() + "\n"
                + "- Verification results: " + verification + "\n"
                + "- Current changed paths: " + changed
                + (capture.changedPathCount() > capture.changedPaths().size()
                ? " (showing " + capture.changedPaths().size() + " of " + capture.changedPathCount() + ")" : "") + "\n"
                + "- Workspace fingerprint: " + (capture.workspaceReliable() ? capture.workspaceSha256() : "unavailable") + "\n"
                + "Inspect the current files before editing. Preserve valid prior work, fix the stated evidence failure, "
                + "and do not repeat an approach that leaves both the failure and workspace unchanged.";
        if (nextAttemptPromptTemplate == null || nextAttemptPromptTemplate.isBlank()) return trim(structured, MAX_RETRY_PROMPT_CHARS);
        String template = trim(nextAttemptPromptTemplate, MAX_TEMPLATE_CHARS)
                .replace("${attemptOrdinal}", Integer.toString(capture.attemptOrdinal()))
                .replace("${failureSummary}", capture.failureSummary())
                .replace("${verificationSummary}", verification)
                .replace("${changedPaths}", changed)
                .replace("${workspaceFingerprint}", capture.workspaceReliable() ? capture.workspaceSha256() : "unavailable");
        return trim(structured + "\nLoopSpec next-attempt instructions:\n" + template, MAX_RETRY_PROMPT_CHARS);
    }

    private Capture unavailable(String stageId, String attemptId, int attemptOrdinal,
                                List<VerificationFact> facts, String failureSummary, String reason) {
        return new Capture("v1", stageId, attemptId, attemptOrdinal,
                trim(failureSummary, MAX_FAILURE_CHARS), facts, List.of(), 0, null, false,
                trim(reason == null ? "Workspace fingerprint unavailable" : reason, MAX_FAILURE_CHARS), null);
    }

    private List<String> changedPaths(VerifierOutcome outcome) {
        Object value = outcome.evidence() == null ? null : outcome.evidence().get("changedPaths");
        if (!(value instanceof List<?> values)) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Object item : values) {
            if (item instanceof String path && !path.isBlank()) unique.add(path);
        }
        return unique.stream().sorted().toList();
    }

    private WorkspaceFingerprint fingerprint(Path worktree, List<String> changedPaths) {
        if (changedPaths.size() > MAX_CAPTURED_PATHS) {
            return WorkspaceFingerprint.unavailable("Changed path count exceeds the bounded fingerprint limit");
        }
        try {
            Path root = worktree.toRealPath();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long consumed = 0;
            byte[] buffer = new byte[8192];
            for (String relative : changedPaths) {
                Path candidate = root.resolve(relative).normalize();
                if (!candidate.startsWith(root)) {
                    return WorkspaceFingerprint.unavailable("Changed path escaped the execution workspace");
                }
                update(digest, relative);
                if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    update(digest, "MISSING");
                    continue;
                }
                if (Files.isSymbolicLink(candidate)) {
                    update(digest, "SYMLINK:" + Files.readSymbolicLink(candidate));
                    continue;
                }
                Path resolved = candidate.toRealPath();
                if (!resolved.startsWith(root)) {
                    return WorkspaceFingerprint.unavailable("Changed path resolved outside the execution workspace");
                }
                if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
                    update(digest, "NON_REGULAR");
                    continue;
                }
                BasicFileAttributes before = Files.readAttributes(resolved, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                long size = before.size();
                if (size < 0 || consumed + size > MAX_HASHED_FILE_BYTES) {
                    return WorkspaceFingerprint.unavailable("Changed file content exceeds the bounded fingerprint byte budget");
                }
                update(digest, "FILE:" + size);
                long actualBytes;
                try (InputStream input = Files.newInputStream(resolved)) {
                    actualBytes = digestBounded(input, digest, buffer, MAX_HASHED_FILE_BYTES - consumed);
                } catch (FingerprintLimitExceededException exceeded) {
                    return WorkspaceFingerprint.unavailable("Changed file content exceeds the bounded fingerprint byte budget");
                }
                BasicFileAttributes after = Files.readAttributes(resolved, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!unchangedDuringRead(before, after, actualBytes)) {
                    return WorkspaceFingerprint.unavailable("Changed file content changed while its fingerprint was being read");
                }
                consumed += actualBytes;
            }
            return new WorkspaceFingerprint(HexFormat.of().formatHex(digest.digest()), true, null);
        } catch (Exception failure) {
            return WorkspaceFingerprint.unavailable("Unable to fingerprint the workspace: " + failure.getMessage());
        }
    }

    static long digestBounded(InputStream input, MessageDigest digest, byte[] buffer, long remainingBytes)
            throws IOException {
        long readBytes = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            if (readBytes + read > remainingBytes) throw new FingerprintLimitExceededException();
            digest.update(buffer, 0, read);
            readBytes += read;
        }
        return readBytes;
    }

    static boolean unchangedDuringRead(BasicFileAttributes before, BasicFileAttributes after, long actualBytes) {
        return before.isRegularFile() && after.isRegularFile()
                && actualBytes == before.size()
                && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && Objects.equals(before.fileKey(), after.fileKey());
    }

    private static final class FingerprintLimitExceededException extends IOException { }

    private String failedSignature(List<VerificationFact> facts) {
        return facts.stream().filter(fact -> !"PASS".equals(fact.state()))
                .map(fact -> fact.type() + "\u0000" + fact.state() + "\u0000" + fact.summary())
                .reduce((left, right) -> left + "\u0001" + right).orElse("NO_FAILED_FACT");
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String trim(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    public record VerificationFact(String type, String state, String summary) { }
    public record Capture(String schemaVersion, String stageId, String attemptId, int attemptOrdinal,
                          String failureSummary, List<VerificationFact> verifications,
                          List<String> changedPaths, int changedPathCount,
                          String workspaceSha256, boolean workspaceReliable, String workspaceUnavailableReason,
                          String stagnationFingerprint) {
        public boolean comparableForStagnation() {
            return workspaceReliable && stagnationFingerprint != null && !stagnationFingerprint.isBlank();
        }
    }
    private record WorkspaceFingerprint(String sha256, boolean reliable, String reason) {
        private static WorkspaceFingerprint unavailable(String reason) {
            return new WorkspaceFingerprint(null, false, reason);
        }
    }
}
