package io.opencode.loopper.runtime;

import java.nio.file.Path;
import java.util.Map;

/** Only trusted Git network operations may request a short-lived child environment. */
public interface GitCredentialProvider {
    Map<String, String> environment(Path registeredProject, String remoteUrl);
}
