package io.opencode.loopper.runtime;

import java.util.List;

/** These read tools require their own signed source capability even when a role's tool policy includes them. */
public final class SourceDevelopmentProfiles {
    private SourceDevelopmentProfiles() { }
    public static final List<String> TOOLS = List.of("get_source_development_work", "list_source_development_files", "read_source_development_file");
}
