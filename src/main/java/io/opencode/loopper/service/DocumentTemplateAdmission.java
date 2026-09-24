package io.opencode.loopper.service;

import io.opencode.loopper.domain.DocumentTemplateState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.assist.AssistDocumentParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Short transaction boundary: expected immutable bytes are registered before external persistence. */
@Service
public class DocumentTemplateAdmission {
    @org.springframework.beans.factory.annotation.Autowired(required = false) private io.opencode.loopper.service.RoleSessions roleSessions;
    private final DocumentTemplateMapper mapper;
    private final LifecycleTransitionService lifecycle;
    private final ObjectMapper json;
    public DocumentTemplateAdmission(DocumentTemplateMapper mapper, LifecycleTransitionService lifecycle, ObjectMapper json) {
        this.mapper = mapper; this.lifecycle = lifecycle; this.json = json;
    }

    @Transactional
    public DocumentTemplateRunRow create(DocumentTemplateRunRow proposed, List<DocumentTemplateStorage.Prepared> files) {
        var existing = mapper.findRequest(proposed.requestKey()).orElse(null);
        if (existing != null) return sameRequest(existing, proposed.requestSha256());
        lifecycle.create(subject(proposed), proposed.state(), Map.of("template", proposed.templateId()),
                () -> mapper.insert(proposed), DocumentTemplateAdmission::conflict);
        RoleSessions.freeze(roleSessions, "DOCUMENT_TEMPLATE_RUN", proposed.id(), null, null);
        appendFiles(proposed, files, 0);
        return require(proposed.id());
    }
    /** Caller owns the short admission transaction; file bytes are persisted after commit. */
    public void appendFiles(DocumentTemplateRunRow proposed, List<DocumentTemplateStorage.Prepared> files, int firstOrdinal) {
        for (int index = 0; index < files.size(); index++) {
            int ordinal = firstOrdinal + index;
            var prepared = files.get(index);
            String fileId = UUID.randomUUID().toString();
            var row = new DocumentTemplateFileRow(fileId, proposed.id(), ordinal, prepared.filename(),
                    prepared.document().format(), prepared.bytes().length, prepared.sha256(), prepared.representationSha256(),
                    AssistDocumentParser.VERSION, proposed.id() + "/" + fileId + ".original",
                    prepared.document().sections().size(), json.writeValueAsString(prepared.document().limitations()));
            if (mapper.insertFile(row) != 1) throw conflict();
            for (var section : prepared.document().sections()) {
                if (mapper.insertSection(new DocumentTemplateMapper.Section(fileId, Integer.parseInt(section.id()),
                        section.title(), section.markdown(), DocumentTemplateStorage.hash(
                        section.markdown().getBytes(StandardCharsets.UTF_8)))) != 1) throw conflict();
            }
        }
    }

    public DocumentTemplateRunRow transition(DocumentTemplateRunRow run, DocumentTemplateState next,
            LifecycleEvent event, String code, String message) {
        String resume = next == DocumentTemplateState.WAITING_INPUT ? run.state() : null;
        return transition(run, next, event, code, message, resume);
    }
    public DocumentTemplateRunRow transition(DocumentTemplateRunRow run, DocumentTemplateState next,
            LifecycleEvent event, String code, String message, String resume) {
        lifecycle.transition(subject(run), run.state(), next.name(), event, code, Map.of(),
                () -> mapper.transition(run.id(), run.version(), next.name(), resume, code, message,
                        Instant.now().toString()), DocumentTemplateAdmission::conflict);
        return require(run.id());
    }

    public DocumentTemplateRunRow require(String id) {
        return mapper.find(id).orElseThrow(() -> new NotFoundException("需求模板任务不存在"));
    }
    public static DocumentTemplateRunRow sameRequest(DocumentTemplateRunRow row, String digest) {
        if (!row.requestSha256().equals(digest))
            throw new ConflictException("DOCUMENT_TEMPLATE_REQUEST_CONFLICT", "该发起标识已使用不同文档或参数，请重新发起");
        return row;
    }
    private static LifecycleTransitionService.Subject subject(DocumentTemplateRunRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.DOCUMENT_TEMPLATE_RUN, row.id(),
                LifecycleScopeType.PROJECT, row.projectId());
    }
    private static ConflictException conflict() {
        return new ConflictException("DOCUMENT_TEMPLATE_VERSION_CONFLICT", "需求模板任务已变化，请刷新后重试");
    }
}
