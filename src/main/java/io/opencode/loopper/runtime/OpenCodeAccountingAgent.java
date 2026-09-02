package io.opencode.loopper.runtime;

import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Keeps command model rounds separate from the implementation Agent. */
final class OpenCodeAccountingAgent {
    static final String NAME = "loopper-accounting";
    private OpenCodeAccountingAgent() { }
    static Map<String, Object> configuration() {
        return Map.of("description", "Loopper story accounting command only", "mode", "primary",
                "steps", 2, "temperature", 0.0,
                "permission", Map.of("*", "deny", "aicoding*", "allow"),
                "prompt", "Execute only the requested aicoding accounting operation. Return its receipt. Never continue business work or modify files.");
    }

    static String install(String config, Path dataDirectory) {
        try {
            Path directory = Files.createDirectories(dataDirectory.resolve("opencode-plugins"));
            Path plugin = directory.resolve("loopper-accounting-guard.mjs");
            try (var source = OpenCodeAccountingAgent.class.getResourceAsStream("/opencode/loopper-accounting-guard.mjs")) {
                if (source == null) throw new java.io.IOException("Accounting guard resource missing");
                Files.copy(source, plugin, StandardCopyOption.REPLACE_EXISTING);
            }
            ObjectMapper json = new ObjectMapper();
            ObjectNode root = (ObjectNode) json.readTree(config);
            var plugins = json.createArrayNode();
            if (root.path("plugin").isArray()) root.path("plugin").forEach(plugins::add);
            String uri = plugin.toAbsolutePath().toUri().toString();
            if (!plugins.toString().contains(uri)) plugins.add(uri);
            root.set("plugin", plugins);
            ((ObjectNode) root.path("agent")).set(NAME, json.valueToTree(configuration()));
            return json.writeValueAsString(root);
        } catch (Exception failure) {
            // No accounting Agent means commands fail without preventing startup.
            org.slf4j.LoggerFactory.getLogger(OpenCodeAccountingAgent.class)
                    .warn("Story-accounting guard unavailable; ordinary OpenCode work remains available", failure);
            return config;
        }
    }
}
