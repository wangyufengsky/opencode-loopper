package io.opencode.loopper.verification;

import io.opencode.loopper.domain.TaskFailure;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;

final class VerifierSafety {
    private VerifierSafety() { }

    static Path managedRelative(Path worktree, String input) {
        if (input == null || input.isBlank()) throw new TaskFailure("VERIFIER_PATH_INVALID", "File verifier requires a path");
        final Path supplied;
        try { supplied = Path.of(input); }
        catch (RuntimeException invalidPath) { throw new TaskFailure("VERIFIER_PATH_INVALID", "Verifier path is not valid on this platform"); }
        if (supplied.isAbsolute()) throw new TaskFailure("VERIFIER_PATH_ESCAPE", "Verifier paths must be relative to the worktree");
        final Path root;
        try { root = worktree.toRealPath(); }
        catch (Exception unavailable) { throw new TaskFailure("WORKTREE_UNAVAILABLE", "Worktree cannot be resolved for file verification"); }
        Path resolved = root.resolve(supplied).normalize();
        if (!resolved.startsWith(root)) throw new TaskFailure("VERIFIER_PATH_ESCAPE", "Verifier path escaped its worktree");
        try {
            Path existing = resolved;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing == null || !existing.toRealPath().startsWith(root)) {
                throw new TaskFailure("VERIFIER_SYMLINK_ESCAPE", "Verifier path resolved outside its worktree");
            }
        } catch (TaskFailure failure) { throw failure; }
        catch (Exception invalid) { throw new TaskFailure("VERIFIER_PATH_INVALID", "Verifier path cannot be resolved safely"); }
        return resolved;
    }

    static URI requireLoopbackHttp(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!"http".equals(scheme) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new TaskFailure("VERIFIER_LOOPBACK_REQUIRED", "Verifier URL must be an http loopback URL");
            }
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) throw new TaskFailure("VERIFIER_LOOPBACK_REQUIRED", "Verifier URL host did not resolve");
            for (InetAddress address : addresses) {
                if (!address.isLoopbackAddress()) throw new TaskFailure("VERIFIER_LOOPBACK_REQUIRED", "Verifier URL must resolve only to loopback");
            }
            return uri;
        } catch (TaskFailure failure) { throw failure; }
        catch (IllegalArgumentException | UnknownHostException invalid) {
            throw new TaskFailure("VERIFIER_LOOPBACK_REQUIRED", "Verifier URL must be a resolvable loopback URL");
        }
    }

    static void requireCssSelector(String selector) {
        if (selector == null || selector.isBlank() || selector.length() > 1_024
                || selector.contains(">>") || selector.startsWith("xpath=") || selector.startsWith("text=")
                || selector.startsWith("javascript:")) {
            throw new TaskFailure("BROWSER_SELECTOR_INVALID", "BROWSER accepts bounded CSS selectors only");
        }
    }
}
