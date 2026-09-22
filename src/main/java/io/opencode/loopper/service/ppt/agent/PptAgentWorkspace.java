package io.opencode.loopper.service.ppt.agent;

import java.nio.file.Path;
import tools.jackson.databind.JsonNode;

/** Business boundary: implementations must revalidate inside each short committing transaction. */
public interface PptAgentWorkspace {
    record Workspace(String id, String phase, long revision, String model, Path root, JsonNode context) { }
    Workspace workspace(String documentId);
    Object invoke(String documentId, String tool, JsonNode args, Runnable revalidate);
}
