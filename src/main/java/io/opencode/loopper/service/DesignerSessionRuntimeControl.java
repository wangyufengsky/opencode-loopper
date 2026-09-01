package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignerSessionState;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Service;

/** Owns remote abort and local stop guards without depending on Designer orchestration. */
@Service
public final class DesignerSessionRuntimeControl {
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final OpenCodeClient openCode;
    private final AcceptanceCandidateInternalTerminationIntentStore internalTerminations;

    public DesignerSessionRuntimeControl(LoopperMapper mapper, ProjectService projects, OpenCodeClient openCode,
            AcceptanceCandidateInternalTerminationIntentStore internalTerminations) {
        this.mapper = mapper;
        this.projects = projects;
        this.openCode = openCode;
        this.internalTerminations = internalTerminations;
    }

    public OpenCodeClient.AbortConfirmation abort(String externalSessionId, String projectId) {
        requireGenericOwnership(externalSessionId);
        Path root = Path.of(projects.get(projectId).rootPath());
        return openCode.abortWithConfirmation(new OpenCodeClient.OpenCodeSession(externalSessionId, root));
    }

    public void requireStoppedBeforeReplacement(String externalSessionId, String projectId) {
        if (externalSessionId == null || externalSessionId.isBlank()) return;
        try {
            abort(externalSessionId, projectId);
        } catch (RuntimeException failure) {
            throw new ServiceUnavailableException("DESIGNER_SESSION_REPLACEMENT_ABORT_FAILED",
                    "旧的远端角色会话尚未确认停止，未创建替代会话");
        }
    }

    public void abortQuietly(String externalSessionId, String projectId) {
        if (externalSessionId == null || externalSessionId.isBlank()) return;
        requireGenericOwnership(externalSessionId);
        try {
            abort(externalSessionId, projectId);
        } catch (RuntimeException ignored) { }
    }

    public void requireNonInternalDesignerSessionsStopped(String designerSessionId, String projectId) {
        for (String remoteId : new LinkedHashSet<>(mapper.listDesignerRemoteSessionIds(designerSessionId))) {
            if (remoteId == null || remoteId.isBlank() || internalTerminations.ownsExternalSession(remoteId)) continue;
            requireStoppedBeforeReplacement(remoteId, projectId);
        }
    }

    public boolean stopping(String sessionId) {
        return mapper.findDesignerSession(sessionId)
                .map(row -> DesignerSessionState.STOPPING.name().equals(row.state())
                        || DesignerSessionState.CANCELLED.name().equals(row.state())
                        || internalTerminations.hasActiveForDesigner(sessionId))
                .orElse(true);
    }

    private void requireGenericOwnership(String externalSessionId) {
        if (externalSessionId != null && !externalSessionId.isBlank()
                && internalTerminations.ownsExternalSession(externalSessionId)) {
            throw new ConflictException("ACCEPTANCE_INTERNAL_TERMINATION_REQUIRED",
                    "内部 MCP 候选 Session 必须通过持久化 termination intent 停止");
        }
    }
}
