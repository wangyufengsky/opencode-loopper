package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import io.opencode.loopper.template.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Source intake creates a genuine frozen Designer requirement; Task admission remains a later formal action. */
@Service
public final class SourceDevelopmentBootstrap {
    private final SourceTemplateAdmission admission;
    private final SourceTestProfileService testProfiles;
    private final DocumentDevelopmentProfile profiles;
    private final LoopperMapper domain;
    private final LoopDraftService drafts;
    private final LifecycleTransitionService lifecycle;
    private final DesignerConversationCoordinator conversations;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    public SourceDevelopmentBootstrap(SourceTemplateAdmission admission, SourceTestProfileService testProfiles,
            DocumentDevelopmentProfile profiles, LoopperMapper domain, LoopDraftService drafts,
            LifecycleTransitionService lifecycle, DesignerConversationCoordinator conversations,
            TransactionTemplate transactions, ObjectMapper json) {
        this.admission = admission; this.testProfiles = testProfiles; this.profiles = profiles; this.domain = domain;
        this.drafts = drafts; this.lifecycle = lifecycle; this.conversations = conversations; this.transactions = transactions; this.json = json;
    }
    public DesignerSessionRow create(SourceTemplateRunRow input, SourceTemplateContract contract) {
        require(input);
        if (input.designerId() != null) return domain.findDesignerSession(input.designerId()).orElseThrow();
        var testProfile = testProfiles.require(input.id());
        var files = domain.sourceDevelopmentFiles(input.id()).stream().filter(f -> testProfile.modules().stream()
                .anyMatch(m -> m.sourcePaths().contains(f.path()))).toList();
        if (files.isEmpty()) throw new BadRequestException("SOURCE_TEST_OBJECTS_EMPTY", "没有适用的单元测试对象");
        if (files.size() > 4096) throw new BadRequestException("SOURCE_DESIGN_CAPACITY", "源码测试对象超过当前设计容量，已保留完整范围等待处理");
        var segments = files.stream().map(file -> new DesignerSessionService.RequirementSegment(
                SourceRequirementContext.reference(file.ordinal()), file.path())).toList();
        var prepared = profiles.prepare(input.projectId(), input.title());
        boolean large = files.size() > 12 || files.stream().mapToLong(SourceTemplateMapper.File::sizeBytes).sum() > 160000;
        String index = SourceRequirementContext.index(input, testProfile);
        return transactions.execute(ignored -> {
            var run = admission.require(input.id()); require(run);
            if (run.designerId() != null) return domain.findDesignerSession(run.designerId()).orElseThrow();
            if (run.version() != input.version()) throw SourceTemplateAdmission.conflict();
            String now = Instant.now().toString(), designerId = UUID.randomUUID().toString(), revisionId = UUID.randomUUID().toString();
            String[] model = contract.model().split("/", 2);
            var draft = drafts.createNew(new LoopSpec("v2", run.projectId(), run.title(), index,
                    List.of(new LoopSpec.StageSpec("根据冻结源码设计正常、边界、异常与分支单测", List.of(), List.of(), List.of(), List.of())),
                    new LoopSpec.Limits(contract.maxStageAttempts(), contract.maxTaskAttempts(), contract.sessionErrorLimit(),
                            null, contract.maxDurationSeconds(), contract.attemptTimeoutSeconds(), null, contract.timeoutEnabled()),
                    new LoopSpec.ModelSpec(model[0], model[1], null), null, null));
            var designer = new DesignerSessionRow(designerId, run.projectId(), "PENDING_HANDOFF", "READ_ONLY", now, now, 0,
                    null, "PENDING", draft.id(), "DECOMPOSING", 0, 0, 1, null, "REQUIREMENT", 0, "NONE");
            lifecycle.create(subject(LifecycleMachineType.DESIGNER_SESSION, designerId, run.projectId()), designer.state(),
                    Map.of("sourceTemplateRun", run.id()), () -> domain.insertDesignerSession(designer), SourceTemplateAdmission::conflict);
            conversations.enable(designerId);
            var message = new DesignerMessageRow(UUID.randomUUID().toString(), designerId, 1, "user", index, "PERSISTED", now, "USER", 1, null);
            if (domain.insertDesignerMessage(message) != 1) throw SourceTemplateAdmission.conflict();
            var revision = new DesignRequirementRevisionRow(revisionId, designerId, 1, message.id(), index,
                    json.writeValueAsString(segments), draft.version(), "ACTIVE", 0, 96, now, now, 0);
            lifecycle.create(subject(LifecycleMachineType.DESIGN_REQUIREMENT_REVISION, revisionId, run.projectId()), revision.state(),
                    Map.of("sourceTemplateRun", run.id(), "manifestSha256", testProfile.manifestSha256()),
                    () -> domain.insertDesignRequirementRevision(revision), SourceTemplateAdmission::conflict);
            if (domain.insertDesignerTaskProfile(profiles.frozen(prepared, designerId, revisionId, run.id(), "template-source-unit-test", large)) != 1
                    || domain.insertSourceDevelopmentDesign(new SourceDevelopmentContextMapper.SourceBinding(
                            revisionId, run.id(), testProfile.manifestSha256(), now)) != 1
                    || domain.linkSourceDesigner(run.id(), run.version(), designerId, now) != 1) throw SourceTemplateAdmission.conflict();
            return domain.findDesignerSession(designerId).orElseThrow();
        });
    }
    private static void require(SourceTemplateRunRow run) {
        if (!run.templateId().equals("UNIT_TEST_DEVELOPMENT") || !run.state().equals("DESIGNING") || run.snapshotJson() == null)
            throw SourceTemplateAdmission.conflict();
    }
    private static LifecycleTransitionService.Subject subject(LifecycleMachineType type, String id, String project) {
        return new LifecycleTransitionService.Subject(type, id, LifecycleScopeType.PROJECT, project);
    }
}
