package io.opencode.loopper.service;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** Supplies one unguessable credential for an attested OpenCode create request. */
@Component
final class AcceptanceCandidateCreationCredentialSource {
    private static final int CREDENTIAL_BYTES = 32;
    private final SecureRandom random;

    AcceptanceCandidateCreationCredentialSource() {
        this(new SecureRandom());
    }

    AcceptanceCandidateCreationCredentialSource(SecureRandom random) {
        this.random = java.util.Objects.requireNonNull(random);
    }

    String create() {
        byte[] value = new byte[CREDENTIAL_BYTES];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
