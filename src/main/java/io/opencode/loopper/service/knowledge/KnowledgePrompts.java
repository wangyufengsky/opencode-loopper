package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.service.roles.RolePromptResources;

/** MCP evidence first, with project-scoped native investigation for remaining gaps. */
final class KnowledgePrompts {
    private KnowledgePrompts() { }
    static final String RESEARCH = RolePromptResources.read("knowledge.research");
    static String research() { return RolePromptResources.read("knowledge.research"); }
}
