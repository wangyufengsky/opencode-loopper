package io.opencode.loopper.service;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Document intake becomes a Designer only after source review; no Task or remote side effect is manufactured. */
@Service
public class DocumentDevelopmentBootstrap {
    private final DocumentTemplateAdmission admission;
    private final DocumentDevelopmentMapper bindings;
    private final DocumentRequirementMapper requirements;
    private final DocumentDevelopmentProfile profiles;
    private final LoopperMapper domain;
    private final LoopDraftService drafts;
    private final LifecycleTransitionService lifecycle;
    private final DesignerConversationCoordinator conversations;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public DocumentDevelopmentBootstrap(DocumentTemplateAdmission admission, DocumentDevelopmentMapper bindings,
            DocumentRequirementMapper requirements, DocumentDevelopmentProfile profiles, LoopperMapper domain,
            LoopDraftService drafts, LifecycleTransitionService lifecycle, DesignerConversationCoordinator conversations,
            ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.admission = admission; this.bindings = bindings; this.requirements = requirements; this.profiles = profiles;
        this.domain = domain; this.drafts = drafts; this.lifecycle = lifecycle; this.conversations = conversations;
        this.json = json; this.transactions = new TransactionTemplate(transactionManager);
    }
    public DesignerSessionRow create(DocumentTemplateRunRow input, DocumentTemplateService.Contract contract) {
        if (input.designerId() != null) return domain.findDesignerSession(input.designerId()).orElseThrow();
        requireAuthorized(input, contract);
        var source = requirements.revision(input.id(), input.requirementRevision()).orElseThrow(DocumentDevelopmentBootstrap::conflict);
        var segments = segments(input);
        if (segments.isEmpty()) throw new BadRequestException("DOCUMENT_REQUIREMENTS_EMPTY", "需求清单没有可开发内容，请补充需求");
        // Stack observation happens outside the short identity/link transaction.
        var profile = profiles.prepare(input.projectId(), input.title());
        return transactions.execute(status -> {
            var run = admission.require(input.id()); requireAuthorized(run, contract);
            if (run.designerId() != null) return domain.findDesignerSession(run.designerId()).orElseThrow();
            if (run.version() != input.version() || run.requirementRevision() != source.revision()) throw conflict();
            String now = Instant.now().toString(), designerId = UUID.randomUUID().toString(), revisionId = UUID.randomUUID().toString();
            String index = DocumentRequirementContext.index(run, source.manifestSha256(), segments.size());
            var draft = drafts.createNew(new LoopSpec("v2", run.projectId(), run.title(), index,
                    List.of(new LoopSpec.StageSpec("根据冻结需求形成可验证的软件设计", List.of(), List.of(), List.of(), List.of())),
                    new LoopSpec.Limits(contract.maxStageAttempts(), contract.maxTaskAttempts(), contract.sessionErrorLimit(),
                            null, contract.maxDurationSeconds(), contract.attemptTimeoutSeconds(), null, contract.timeoutEnabled()),
                    model(contract.model()), null, null));
            var designer = new DesignerSessionRow(designerId, run.projectId(), DesignerSessionState.PENDING_HANDOFF.name(),
                    "READ_ONLY", now, now, 0, null, "PENDING", draft.id(), DesignWorkflowPhase.DECOMPOSING.name(),
                    0, 0, 1, null, "REQUIREMENT", 0, "NONE");
            lifecycle.create(subject(LifecycleMachineType.DESIGNER_SESSION, designerId, run.projectId()), designer.state(),
                    Map.of("templateRun", run.id()), () -> domain.insertDesignerSession(designer), DocumentDevelopmentBootstrap::conflict);
            conversations.enable(designerId);
            var message = new DesignerMessageRow(UUID.randomUUID().toString(), designerId, 1, "user", index,
                    "PERSISTED", now, "USER", 1, null);
            if (domain.insertDesignerMessage(message) != 1) throw conflict();
            var revision = new DesignRequirementRevisionRow(revisionId, designerId, 1, message.id(), index,
                    json.writeValueAsString(segments), draft.version(), "ACTIVE", 0, 96, now, now, 0);
            lifecycle.create(subject(LifecycleMachineType.DESIGN_REQUIREMENT_REVISION, revisionId, run.projectId()), revision.state(),
                    Map.of("templateRun", run.id(), "documentRevision", source.revision()),
                    () -> domain.insertDesignRequirementRevision(revision), DocumentDevelopmentBootstrap::conflict);
            if (domain.insertDesignerTaskProfile(profiles.frozen(profile, designerId, revisionId, run.id())) != 1
                    || bindings.insertDesign(new DocumentDevelopmentMapper.Design(revisionId, run.id(), source.revision(), source.manifestSha256(), now)) != 1
                    || bindings.linkDesigner(run.id(), run.version(), designerId, now) != 1) throw conflict();
            return domain.findDesignerSession(designerId).orElseThrow();
        });
    }
    private List<DesignerSessionService.RequirementSegment> segments(DocumentTemplateRunRow run) {
        var result = new ArrayList<DesignerSessionService.RequirementSegment>(); int after = -1;
        while (true) {
            var page = requirements.page(run.id(), run.requirementRevision(), after, 100);
            for (var item : page) {
                if (!json.readTree(item.issuesJson()).isEmpty()) throw new BadRequestException("DOCUMENT_BUSINESS_DECISION_REQUIRED",
                        item.requirementKey() + " 存在未解决的业务问题，请在需求待处理项中补充依据后继续");
                result.add(new DesignerSessionService.RequirementSegment(item.requirementKey(), item.title()));
            }
            if (page.size() < 100) return List.copyOf(result);
            after = page.getLast().ordinal();
        }
    }
    private static LoopSpec.ModelSpec model(String configured) {
        if (configured == null || !configured.contains("/")) return new LoopSpec.ModelSpec(null, configured, null);
        int slash = configured.indexOf('/');
        return new LoopSpec.ModelSpec(configured.substring(0, slash), configured.substring(slash + 1), null);
    }
    private static void requireAuthorized(DocumentTemplateRunRow run, DocumentTemplateService.Contract contract) {
        if (!run.templateId().equals("REQUIREMENT_DEVELOPMENT") || !run.state().equals("DESIGNING")
                || !contract.autoDevelopment() || !contract.executionPolicy().equals("CURRENT_DIRECTORY")
                || run.requirementRevision() < 1) throw conflict();
    }
    private static LifecycleTransitionService.Subject subject(LifecycleMachineType type, String id, String project) {
        return new LifecycleTransitionService.Subject(type, id, LifecycleScopeType.PROJECT, project);
    }
    private static ConflictException conflict() {
        return new ConflictException("DOCUMENT_DEVELOPMENT_DESIGN_CONFLICT", "需求开发授权、设计关联或冻结需求已变化，请刷新后重试");
    }
}
