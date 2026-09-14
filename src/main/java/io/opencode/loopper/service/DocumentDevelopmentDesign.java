package io.opencode.loopper.service;

import io.opencode.loopper.persistence.*;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits the deterministic single package before dispatch; a restart can finish the same launch. */
@Service
public final class DocumentDevelopmentDesign {
    private final LoopperMapper mapper;
    private final DesignerSessionService designers;
    private final TransactionTemplate transactions;
    public DocumentDevelopmentDesign(LoopperMapper mapper, DesignerSessionService designers, PlatformTransactionManager manager) {
        this.mapper = mapper; this.designers = designers; this.transactions = new TransactionTemplate(manager);
    }
    public void startSingle(String designerId) {
        var workPackage = transactions.execute(status -> {
            var session = designers.get(designerId);
            var revision = mapper.findCurrentDesignRequirementRevision(designerId).orElseThrow(DocumentDevelopmentDesign::changed);
            if (mapper.documentDesign(revision.id(), designerId).isEmpty() || session.taskId() != null) throw changed();
            var profile = mapper.findCurrentDesignerTaskProfile(designerId).orElseThrow(DocumentDevelopmentDesign::changed);
            if (!profile.workflowTemplate().equals("DIRECT_SOFTWARE_DESIGN")) throw changed();
            var existing = mapper.listDesignWorkPackages(revision.id());
            if (!existing.isEmpty()) return existing.getFirst();
            if (!session.state().equals("PENDING_HANDOFF") || !session.workflowPhase().equals("DECOMPOSING")) throw changed();
            return designers.prepareDirectSoftwarePackage(session, revision);
        });
        if (workPackage != null && workPackage.state().equals("PENDING")) {
            var session = designers.get(designerId);
            if (Set.of("STOPPING", "CANCELLED").contains(session.state())) return;
            designers.dispatchPackageDesigner(session, workPackage, null, PackageDesignDispatch.CONTINUE);
        }
    }
    public void startLarge(String designerId) {
        var session = designers.get(designerId);
        var revision = mapper.findCurrentDesignRequirementRevision(designerId).orElseThrow(DocumentDevelopmentDesign::changed);
        if (mapper.documentDesign(revision.id(), designerId).isEmpty() || session.taskId() != null) throw changed();
        if (!mapper.findCurrentDesignerTaskProfile(designerId).orElseThrow(DocumentDevelopmentDesign::changed)
                .workflowTemplate().equals("FULL_PACKAGE_DESIGN")) throw changed();
        if (mapper.findTaskDecompositionByRevision(revision.id()).isPresent()) return;
        if (!session.state().equals("PENDING_HANDOFF") || !session.workflowPhase().equals("DECOMPOSING")) throw changed();
        designers.retryDecomposition(designerId);
    }
    private static ConflictException changed() {
        return new ConflictException("DOCUMENT_DESIGN_SOURCE_CHANGED", "需求开发设计或冻结范围已变化，未重复创建工作包");
    }
}
