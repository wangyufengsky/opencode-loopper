package io.opencode.loopper.service;

import io.opencode.loopper.config.LoopperProperties;
import io.opencode.loopper.persistence.DocumentTemplateRunRow;
import java.time.Instant;
import java.util.*;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Seeds the frozen V1 admission contract so recovery tests do not reopen obsolete public creation. */
final class LegacyDocumentFixture {
    private LegacyDocumentFixture() { }
    static DocumentTemplateRunRow create(ApplicationContext context, DocumentTemplateService.Request request,
                                         List<DocumentTemplateStorage.Incoming> incoming) {
        var json = context.getBean(ObjectMapper.class);
        var properties = context.getBean(LoopperProperties.class);
        var storage = context.getBean(DocumentTemplateStorage.class);
        var prepared = storage.prepare(incoming);
        var project = context.getBean(ProjectService.class).get(request.projectId());
        boolean review = request.templateId().equals("REQUIREMENT_CODE_REVIEW");
        String branch = review ? json.writeValueAsString(context.getBean(ProjectBranchService.class).require(project.id(), request.branchId())) : null;
        String digest = DocumentModelStore.hash(json.writeValueAsString(Map.of("request", request, "files", prepared.stream()
                .map(file -> Map.of("filename", file.filename(), "sha256", file.sha256())).toList())));
        String contract = json.writeValueAsString(new DocumentTemplateService.Contract("1", properties.getOpenCode().getModel(),
                properties.getMaxDuration().toSeconds(), properties.getAttemptTimeout().toSeconds(), properties.getMaxTaskAttempts(),
                properties.getMaxStageAttempts(), properties.getSessionErrorLimit(), !review, review ? "STATIC_ONLY" : "CURRENT_DIRECTORY",
                properties.isTimeoutEnabled(), 1));
        String now = Instant.now().toString();
        var row = new DocumentTemplateRunRow(UUID.randomUUID().toString(), request.requestKey(), digest, project.id(),
                request.templateId(), "1", "历史需求任务", "PREPARING", null, branch, null, contract, null, null, 0, null, null, 0, now, now, 0);
        var created = context.getBean(DocumentTemplateAdmission.class).create(row, prepared);
        return context.getBean(DocumentTemplatePreparation.class).finish(created, prepared);
    }
}
