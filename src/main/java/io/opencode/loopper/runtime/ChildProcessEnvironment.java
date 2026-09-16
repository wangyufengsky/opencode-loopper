package io.opencode.loopper.runtime;

import java.io.IOException;

/** Master encryption keys belong to the Loopper JVM, never to tools or model runtimes. */
public final class ChildProcessEnvironment {
    private ChildProcessEnvironment() { }
    public static Process start(ProcessBuilder builder) throws IOException {
        builder.environment().remove("LOOPPER_GIT_MASTER_KEY");
        builder.environment().remove("LOOPPER_DATABASE_MASTER_KEY");
        return builder.start();
    }
}
