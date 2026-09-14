package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Server-side source evidence for existing design compilation; never injects whole documents into prompts. */
final class DocumentDirectDesignContext {
    private static final ObjectMapper JSON = new ObjectMapper();
    private DocumentDirectDesignContext() { }
    static String resolve(DocumentDesignContextMapper mapper, DocumentDevelopmentMapper.Design source, DesignWorkPackageRow pack) {
        var entries = new ArrayList<DocumentRequirementContext.Entry>();
        var refs = new HashSet<>(List.of(JSON.readValue(pack.requirementRefsJson(), String[].class)));
        int characters = 0;
        for (var file : mapper.sourceFiles(source.runId(), source.documentRevision())) {
            String key = "DOC-" + (file.ordinal() + 1);
            if (!refs.contains(key) && !mapper.documentGlobalSourceRefs(pack.id()).contains(key)) continue;
            var content = new StringBuilder(); int offset = 0;
            var citations = new ArrayList<Map<String, Object>>();
            while (true) {
                var page = mapper.sourceSections(source.runId(), source.documentRevision(), file.id(), offset, 100);
                for (var section : page) {
                    if (!DocumentModelStore.hash(section.content()).equals(section.sha256())) throw invalid();
                    content.append(section.title()).append('\n').append(section.content()).append("\n\n");
                    citations.add(Map.of("fileId", file.id(), "section", section.ordinal(), "sha256", section.sha256()));
                }
                if (page.size() < 100) break;
                offset = page.getLast().ordinal() + 1;
            }
            characters += content.length();
            if (characters > 20_000_000) throw invalid();
            entries.add(new DocumentRequirementContext.Entry(key, file.filename(), content.toString(), new String[0], JSON.writeValueAsString(citations)));
        }
        if (entries.isEmpty()) throw invalid();
        if (mapper.documentLastPackage(pack.id())) entries.add(new DocumentRequirementContext.Entry(DocumentRequirementContext.FINAL_REGRESSION,
                "模板授权中的最终整体回归", DocumentRequirementContext.REGRESSION_RULE, new String[0], "[]"));
        return "LOOPPER_DOCUMENT_REQUIREMENT_SOURCES_V1\n" + JSON.writeValueAsString(new DocumentRequirementContext.Envelope(
                source.runId(), source.documentRevision(), source.manifestSha256(), entries));
    }
    private static ConflictException invalid() {
        return new ConflictException("DOCUMENT_SOURCE_CONTEXT_INVALID", "本包冻结原文缺失、超限或哈希不符，请检查来源与拆包范围");
    }
}
