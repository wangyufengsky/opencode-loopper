package io.opencode.loopper.verification;

import java.nio.file.Path;

/** Additional server-frozen mutation policies apply before and after formal verification. */
public interface VerificationScopeGuard {
    void verify(String taskId, Path worktree);
}
