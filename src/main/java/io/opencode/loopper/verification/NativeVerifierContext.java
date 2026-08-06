package io.opencode.loopper.verification;

import java.nio.file.Path;
import java.time.Duration;

record NativeVerifierContext(Path worktree, Duration timeout, BinaryArtifactStore artifacts) { }
