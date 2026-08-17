package io.opencode.loopper.service;

import io.opencode.loopper.persistence.AiOutputHandlingEventRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Persists bounded correction metadata without copying raw model output into the audit stream. */
@Service
public class AiOutputAuditService {
    private final LoopperMapper mapper;
    private final ObjectMapper json;

    public AiOutputAuditService(LoopperMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    public void recordNormalization(String scopeType, String scopeId, String role, String workflowStep,
                                    List<String> categories, String canonicalOutput) {
        if (categories == null || categories.isEmpty()) return;
        insert(scopeType, scopeId, role, workflowStep, "NORMALIZED", categories, sha256(canonicalOutput));
    }

    public boolean claimToolLoopRecovery(String scopeType, String scopeId, String role,
                                         String workflowStep, String toolSignature) {
        return insert(scopeType, scopeId, role, workflowStep, "TOOL_LOOP_FINALIZER",
                List.of("REPEATED_TOOL_CALL", "SIGNATURE_" + sha256(toolSignature).substring(0, 12)), "ONCE");
    }

    private boolean insert(String scopeType, String scopeId, String role, String workflowStep,
                           String eventType, List<String> categories, String fingerprint) {
        try {
            return mapper.insertAiOutputHandlingEvent(new AiOutputHandlingEventRow(UUID.randomUUID().toString(),
                    scopeType, scopeId, role, workflowStep, eventType,
                    json.writeValueAsString(categories), fingerprint, Instant.now().toString())) == 1;
        } catch (JacksonException failure) {
            throw new IllegalStateException("Unable to persist AI output audit metadata", failure);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
