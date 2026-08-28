package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignWorkPackageState;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Serializes explicit and restart-driven rolling package design continuation. */
@Service
final class RollingPackageDesignContinuationService {
    private static final Set<String> RESUMABLE_STATES = Set.of(
            DesignWorkPackageState.PENDING.name(), DesignWorkPackageState.QUESTIONING.name(),
            DesignWorkPackageState.DESIGNING.name());
    private static final Set<String> REPLACE_REMOTE_STATES = Set.of("FAILED", "ABORTED", "SUPERSEDED");
    private final LoopperMapper mapper;
    private final DesignerSessionRuntimeControl runtimeControl;
    private final ObjectProvider<DesignerSessionService> designers;

    RollingPackageDesignContinuationService(LoopperMapper mapper, DesignerSessionRuntimeControl runtimeControl,
                                            ObjectProvider<DesignerSessionService> designers) {
        this.mapper = mapper;
        this.runtimeControl = runtimeControl;
        this.designers = designers;
    }

    void resume(String sessionId, String workPackageId, String continuationPrompt) {
        DesignerSessionService designer = designers.getObject();
        synchronized (designer) {
            DesignerSessionRow session = mapper.findDesignerSession(sessionId).orElseThrow(() ->
                    new ConflictException("DESIGNER_SESSION_MISSING", "设计会话已不存在"));
            DesignWorkPackageRow workPackage = mapper.findDesignWorkPackage(workPackageId).orElseThrow(() ->
                    new ConflictException("DESIGN_WORK_PACKAGE_MISSING", "工作包设计来源不存在"));
            requireResumable(session, workPackage);
            if (workPackage.designerExternalSessionId() == null
                    || workPackage.designerExternalSessionId().isBlank()
                    || REPLACE_REMOTE_STATES.contains(String.valueOf(workPackage.designerExternalSessionState()))) {
                designer.dispatchPackageDesigner(session, workPackage, continuationPrompt, false);
            } else {
                designer.pollWorkPackageDesigner(workPackage);
            }
        }
    }

    private void requireResumable(DesignerSessionRow session, DesignWorkPackageRow workPackage) {
        if (!session.id().equals(workPackage.designerSessionId())) {
            throw new ConflictException("DESIGN_WORK_PACKAGE_SESSION_MISMATCH", "工作包不属于当前设计会话");
        }
        if (runtimeControl.stopping(session.id())) {
            throw new ConflictException("DESIGNER_SESSION_STOPPING", "设计会话正在停止，暂时不能继续");
        }
        if (!RESUMABLE_STATES.contains(String.valueOf(workPackage.state()))) {
            throw new ConflictException("PACKAGE_DESIGN_CONTINUATION_UNAVAILABLE",
                    "当前工作包设计阶段已更新，请刷新后重试");
        }
    }
}
