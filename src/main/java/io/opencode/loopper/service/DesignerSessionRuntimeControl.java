package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignerSessionState;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

/** Owns remote abort and local stop guards without depending on Designer orchestration. */
@Service
public final class DesignerSessionRuntimeControl {
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final OpenCodeClient openCode;

    public DesignerSessionRuntimeControl(LoopperMapper mapper, ProjectService projects, OpenCodeClient openCode) {
        this.mapper = mapper;
        this.projects = projects;
        this.openCode = openCode;
    }

    public void abort(String externalSessionId, String projectId) {
        Path root = Path.of(projects.get(projectId).rootPath());
        openCode.abort(new OpenCodeClient.OpenCodeSession(externalSessionId, root));
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
        try {
            abort(externalSessionId, projectId);
        } catch (RuntimeException ignored) { }
    }

    public boolean stopping(String sessionId) {
        return mapper.findDesignerSession(sessionId)
                .map(row -> DesignerSessionState.STOPPING.name().equals(row.state())
                        || DesignerSessionState.CANCELLED.name().equals(row.state()))
                .orElse(true);
    }
}
