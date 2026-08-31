package io.opencode.loopper.runtime;

import java.net.URI;

/** Credentials stay process-local; only the adjacent non-secret identity is persisted. */
record OpenCodeConnectionDetails(URI baseUrl, String username, String password, boolean managed,
                                 String generation, String internalMcpServer) { }
