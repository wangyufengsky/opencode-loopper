package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskArtifactRow;
import io.opencode.loopper.persistence.TaskLineageRow;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists recovery lineage and inherited immutable design evidence atomically. */
@Service
public class RecoveryPersistence {
    private static final Set<String> DESIGN_ARTIFACT_KINDS = Set.of(
            "REQUIREMENT_CONTEXT", "DECOMPOSITION_CONTEXT", "WORK_PACKAGE_DESIGN",
            "WORK_PACKAGE_COMPILATION_SUMMARY", "DESIGN_CONTEXT");

    private final LoopperMapper mapper;

    public RecoveryPersistence(LoopperMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void link(TaskLineageRow lineage) {
        mapper.insertTaskLineage(lineage);
        for (TaskArtifactRow source : mapper.listTaskArtifacts(lineage.parentTaskId())) {
            if (!DESIGN_ARTIFACT_KINDS.contains(source.kind())) continue;
            mapper.insertTaskArtifact(new TaskArtifactRow(UUID.randomUUID().toString(), lineage.childTaskId(), null, null,
                    source.kind(), source.name(), source.contentType(), source.content(), source.metadataJson(),
                    Instant.now().toString()));
        }
    }
}
