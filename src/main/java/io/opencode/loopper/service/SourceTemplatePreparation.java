package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.nio.file.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Durable manifest precedes byte writes. Recovery restores precisely that manifest or remains blocked. */
@Service
public class SourceTemplatePreparation {
    private final SourceTemplateAdmission admission;
    private final SourceTemplateMapper mapper;
    private final SourceWorkspaceGuard workspace;
    private final SourceTreeCapture capture;
    private final SourceSnapshotStorage storage;
    private final ObjectMapper json;
    public SourceTemplatePreparation(SourceTemplateAdmission admission, SourceTemplateMapper mapper, SourceWorkspaceGuard workspace,
            SourceTreeCapture capture, SourceSnapshotStorage storage, ObjectMapper json) {
        this.admission = admission; this.mapper = mapper; this.workspace = workspace;
        this.capture = capture; this.storage = storage; this.json = json;
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SourceTemplateRunRow freeze(String id) {
        var row = admission.require(id);
        if (!row.state().equals("PREPARING")) throw SourceTemplateAdmission.conflict();
        if (row.snapshotJson() != null && json.readValue(row.snapshotJson(), SourceSnapshot.class).ready()) return row;
        var parameters = json.readValue(row.parametersJson(), SourceTemplateParameters.class);
        requireNoWriter(Path.of(parameters.projectRoot()));
        if (row.snapshotJson() != null && persistedBytesReady(row.id())) return admission.ready(row);
        var captured = capture.capture(parameters, SourceTemplateDefinition.require(row.templateId()).development());
        requireNoWriter(Path.of(parameters.projectRoot()));
        if (captured.manifest().targetCount() == 0)
            throw new BadRequestException("SOURCE_NO_APPLICABLE_FILES", "该路径没有可处理的源码，请检查排除项或选择其他路径");
        row = admission.planSnapshot(row, captured.manifest());
        storage.write(id, captured.contents());
        return admission.ready(row);
    }
    public void requireNoWriter(Path root) {
        workspace.requireNoWriter(root);
    }
    private boolean persistedBytesReady(String id) {
        try {
            for (var file : mapper.files(id)) if (file.sha256() != null) storage.read(id, file.sha256());
            return true;
        } catch (ConflictException unavailable) { return false; }
    }
}
