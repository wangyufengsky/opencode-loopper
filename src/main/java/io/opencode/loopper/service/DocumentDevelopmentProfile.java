package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.persistence.DesignerTaskProfileRow;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Software intent comes from the selected template; technology facts still come from the repository. */
@Service
public final class DocumentDevelopmentProfile {
    private final ProjectStackProfileService stacks;
    private final TaskProfileRouter router;
    private final RolePackRegistry packs;
    private final ObjectMapper json;
    public DocumentDevelopmentProfile(ProjectStackProfileService stacks, TaskProfileRouter router,
            RolePackRegistry packs, ObjectMapper json) {
        this.stacks = stacks; this.router = router; this.packs = packs; this.json = json;
    }
    public Prepared prepare(String project, String goal) {
        var decision = router.genericFallback(stacks.ensureCurrent(project), goal, "TEMPLATE_DECLARED_SOFTWARE");
        var pack = packs.resolve(TaskIntent.SOFTWARE_CHANGE, decision.technologies(), List.of(ArtifactKind.SOURCE_CODE));
        return new Prepared(decision, pack);
    }
    public DesignerTaskProfileRow frozen(Prepared prepared, String designer, String revision, String run) {
        return frozen(prepared, designer, revision, run, "template-requirement-development", false);
    }
    public DesignerTaskProfileRow frozen(Prepared prepared, String designer, String revision, String run, String source, boolean large) {
        var decision = prepared.decision(); var pack = prepared.pack(); String now = Instant.now().toString();
        var evidence = new ArrayList<>(decision.evidence());
        evidence.add(source + "=" + run); evidence.add("requirement-tests=required");
        evidence.add("software-intent=user-declared;technology-selection=repository-facts");
        return new DesignerTaskProfileRow(UUID.randomUUID().toString(), designer, revision, "FROZEN",
                TaskIntent.SOFTWARE_CHANGE.name(), (large ? WorkflowTemplate.FULL_PACKAGE_DESIGN : WorkflowTemplate.DIRECT_SOFTWARE_DESIGN).name(), MutationMode.WRITE_CODE.name(),
                json.writeValueAsString(List.of(ArtifactKind.SOURCE_CODE)), json.writeValueAsString(decision.technologies()),
                TestPolicy.REQUIRED.name(), pack.executionStrategy().name(), pack.id(), pack.version(), decision.confidence(),
                json.writeValueAsString(evidence), "TEMPLATE_DECLARED", 0, now, now, 0,
                decision.projectStackProfileId(), json.writeValueAsString(decision.componentKeys()), decision.stackFingerprint());
    }
    public record Prepared(TaskProfileRouter.Decision decision, RolePackRegistry.RolePack pack) { }
}
