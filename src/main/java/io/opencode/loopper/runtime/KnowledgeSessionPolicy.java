package io.opencode.loopper.runtime;

import io.opencode.loopper.runtime.OpenCodeClient.SessionProfile;
import java.util.List;

/** New research sessions keep native investigation separate from optional knowledge MCP grants. */
public final class KnowledgeSessionPolicy {
    public static final String MARKER = "loopper_knowledge_research";
    public static final List<String> NATIVE_TOOLS = List.of("read", "glob", "grep", "todowrite", "todoread");
    private KnowledgeSessionPolicy() { }
    public static boolean research(SessionProfile profile) {
        return profile == SessionProfile.KNOWLEDGE_RESEARCH_READ_ONLY || profile == SessionProfile.KNOWLEDGE_RESEARCH_INTERACTIVE_READ_ONLY;
    }
    public static boolean interactive(SessionProfile profile) {
        return profile == SessionProfile.KNOWLEDGE_INTERACTIVE_READ_ONLY || profile == SessionProfile.KNOWLEDGE_RESEARCH_INTERACTIVE_READ_ONLY;
    }
}
