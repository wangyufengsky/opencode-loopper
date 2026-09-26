package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.ConflictException;
import io.opencode.loopper.service.roles.RoleConfigurationService.OwnerRef;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** DB-only source selection in the owner's creation transaction; file content is cited at read time. */
@Service
public class WorkflowKnowledgeBindings {
    private final WorkflowKnowledgeMapper mapper;
    private final LoopperMapper projects;
    private final ObjectMapper json;
    public WorkflowKnowledgeBindings(WorkflowKnowledgeMapper mapper, LoopperMapper projects, ObjectMapper json) {
        this.mapper = mapper; this.projects = projects; this.json = json;
    }
    public void freeze(OwnerRef owner, OwnerRef parent) {
        if (mapper.binding(owner.type(), owner.id()) != null) return;
        if (parent != null) {
            var inherited = mapper.binding(parent.type(), parent.id());
            // No retroactive authorization for an old parent, including recovery descendants.
            if (inherited != null) save(owner, inherited.projectId(), inherited.sourcesJson());
            return;
        }
        String project = mapper.project(owner.type(), owner.id());
        if (project == null) return;
        freezeProject(owner, project);
    }
    /** Legacy convention transport creates the remote Session before its draft row; project is server-selected. */
    @org.springframework.transaction.annotation.Transactional
    public void freezeProject(OwnerRef owner, String project) {
        var existing = mapper.binding(owner.type(), owner.id());
        if (existing != null) { require(owner, project); return; }
        var root = projects.findProject(project).orElseThrow();
        List<KnowledgeSources.Bound> selected = new ArrayList<>();
        selected.add(bound("code", "CODE", "项目代码", root.rootPath(), root.version()));
        selected.add(bound("documents", "DOCUMENTS", "项目文档", root.rootPath(), root.version()));
        if (root.documentPath() != null && !root.documentPath().isBlank())
            selected.add(bound("project-documents", "DOCUMENTS", "项目文档目录", root.documentPath(), root.version()));
        var extra = mapper.sources(project);
        if (extra.size() + selected.size() > 98) throw new ConflictException("WORKFLOW_KNOWLEDGE_LIMIT", "项目资料过多，需预留任务与 Git 来源，请整理知识库后重新创建流程");
        extra.forEach(s -> selected.add(new KnowledgeSources.Bound(s.id(), s.kind(), s.name(), s.path(), s.sha256(), s.state(), s.detail(), s.version())));
        save(owner, project, json.writeValueAsString(selected));
    }
    public boolean available(OwnerRef owner) { return mapper.binding(owner.type(), owner.id()) != null; }
    public String project(OwnerRef owner) {
        var binding = mapper.binding(owner.type(), owner.id());
        return binding == null ? null : binding.projectId();
    }
    public WorkflowKnowledgeMapper.Binding require(OwnerRef owner, String project) {
        var binding = mapper.binding(owner.type(), owner.id());
        if (binding == null || !binding.projectId().equals(project))
            throw new ConflictException("WORKFLOW_KNOWLEDGE_DENIED", "当前流程没有此项目的冻结知识来源，请新建流程");
        return binding;
    }
    public List<KnowledgeSources.Bound> sources(WorkflowKnowledgeMapper.Binding binding) {
        return json.readValue(binding.sourcesJson(), new TypeReference<>() { });
    }
    private void save(OwnerRef owner, String project, String sources) {
        if (mapper.insert(new WorkflowKnowledgeMapper.Binding(owner.type(), owner.id(), project, sources, Instant.now().toString())) != 1)
            throw new ConflictException("WORKFLOW_KNOWLEDGE_CONFLICT", "流程知识来源冻结冲突，请保留当前流程并重试");
    }
    private static KnowledgeSources.Bound bound(String id, String kind, String name, String path, long version) {
        return new KnowledgeSources.Bound(id, kind, name, path, null, "READY", "流程创建时授权的来源；引用标记实际读取版本", version);
    }
}
