package io.opencode.loopper.runtime;

import java.nio.file.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Installs the native research boundary before any research permission can be issued. */
final class OpenCodeKnowledgeAgent {
    private OpenCodeKnowledgeAgent() { }
    static String install(String config, Path data) {
        try {
            Path plugin = Files.createDirectories(data.resolve("opencode-plugins")).resolve("loopper-knowledge-guard.mjs");
            try (var source = OpenCodeKnowledgeAgent.class.getResourceAsStream("/opencode/loopper-knowledge-guard.mjs")) {
                if (source == null) throw new java.io.IOException("Knowledge guard resource missing");
                Files.copy(source, plugin, StandardCopyOption.REPLACE_EXISTING);
            }
            var json = new ObjectMapper(); var root = (ObjectNode) json.readTree(config);
            var plugins = json.createArrayNode(); if (root.path("plugin").isArray()) root.path("plugin").forEach(plugins::add);
            String uri = plugin.toAbsolutePath().toUri().toString(); if (!plugins.toString().contains(uri)) plugins.add(uri);
            root.set("plugin", plugins); return json.writeValueAsString(root);
        } catch (Exception failure) { throw new IllegalStateException("Knowledge native read guard could not be installed", failure); }
    }
}
