package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.service.*;
import io.opencode.loopper.service.assist.*;
import io.opencode.loopper.service.roles.RoleConfigurationService.OwnerRef;
import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Candidate tools reuse the formal candidate ownership guards; specialized workspaces stay isolated. */
@Service
public final class WorkflowKnowledgeAccess {
    private final WorkflowKnowledgeMapper mapper;
    private final RoleConfigurationMapper roles;
    private final WorkflowKnowledgeBindings bindings;
    private final MachineCandidateSubmission submissions;
    private final List<CandidateRunGuard> guards;
    private final ObjectMapper json;
    public WorkflowKnowledgeAccess(WorkflowKnowledgeMapper mapper, RoleConfigurationMapper roles,
            WorkflowKnowledgeBindings bindings, MachineCandidateSubmission submissions, List<CandidateRunGuard> guards, ObjectMapper json) {
        this.mapper = mapper; this.roles = roles; this.bindings = bindings; this.submissions = submissions; this.guards = guards; this.json = json;
    }
    public WorkflowKnowledgeMapper.Binding require(AssistScopeService.Scope scope) {
        var role = roles.sessionSnapshot(scope.externalSessionId());
        if (role == null) throw denied();
        var binding = bindings.require(new OwnerRef(role.ownerType(), role.ownerId()), scope.projectId());
        String run = mapper.candidate(scope.externalSessionId());
        if (run != null) validate(run, scope.externalSessionId());
        return binding;
    }
    public Optional<AssistScopeService.Scope> specialized(AssistMapper.Session session) {
        if (!WorkflowKnowledgePolicy.supports(session.profile()) || !WorkflowKnowledgePolicy.evidenceOnly(session.profile())) return Optional.empty();
        String id = mapper.candidate(session.externalSessionId());
        var role = roles.sessionSnapshot(session.externalSessionId());
        if (role == null) return Optional.empty();
        var binding = mapper.binding(role.ownerType(), role.ownerId());
        if (binding == null) return Optional.empty();
        if (id == null) {
            if (!session.profile().equals("PROJECT_CONVENTION_READ_ONLY")) return Optional.empty();
            if (!role.ownerType().equals("PROJECT_CONVENTION_DRAFT")
                    || !binding.projectId().equals(mapper.convention(role.ownerId(), session.externalSessionId()))) throw denied();
        } else {
            var candidate = validate(id, session.externalSessionId());
            // Normal task Judges retain their existing task evidence boundary.
            if (candidate.scope().type() != MachineCandidateSubmission.CandidateScopeType.PROJECT) return Optional.empty();
            if (!binding.projectId().equals(candidate.scope().id())) throw denied();
        }
        List<String> tools = json.readValue(session.toolsJson(), new TypeReference<>() { });
        return Optional.of(new AssistScopeService.Scope(session.externalSessionId(), "WORKFLOW:" + role.ownerType() + ":" + role.ownerId(),
                binding.projectId(), null, null, null, null, session.profile(), Path.of(session.directory()),
                tools.stream().filter(AssistToolCatalog::knowledgeTool).toList(), List.of()));
    }
    private MachineCandidateSubmission.RunSnapshot validate(String id, String session) {
        var run = submissions.find(id).orElseThrow(WorkflowKnowledgeAccess::denied);
        if (run.state() != io.opencode.loopper.domain.MachineCandidateRunState.OPEN || !session.equals(run.externalSessionId())) throw denied();
        for (var guard : guards) guard.validate(run, MachineCandidateSubmission.SubmissionChannel.INTERNAL_MCP);
        return run;
    }
    private static AssistFailure denied() { return new AssistFailure("WORKFLOW_KNOWLEDGE_DENIED", "知识读取不属于当前活动角色或冻结项目", "REAUTHORIZE"); }
}
