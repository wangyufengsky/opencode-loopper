package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignerAutoModeState;
import io.opencode.loopper.domain.DesignerSessionState;
import io.opencode.loopper.domain.LifecycleEvent;
import io.opencode.loopper.domain.LifecycleMachineType;
import io.opencode.loopper.domain.LifecycleScopeType;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Stops every remote writer/reader owned by one Designer session before archiving it. */
@Service
public final class DesignerStopService {
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final OpenCodeClient openCode;
    private final LifecycleTransitionService lifecycle;
    private final DesignerAutoModeService autoMode;

    public DesignerStopService(LoopperMapper mapper, ProjectService projects, OpenCodeClient openCode,
                               LifecycleTransitionService lifecycle, DesignerAutoModeService autoMode) {
        this.mapper = mapper;
        this.projects = projects;
        this.openCode = openCode;
        this.lifecycle = lifecycle;
        this.autoMode = autoMode;
    }

    public Result stop(String sessionId) {
        DesignerSessionRow session = session(sessionId);
        if (DesignerSessionState.CANCELLED.name().equals(session.state())) {
            mapper.archiveDesignerSession(sessionId, now());
            return new Result(DesignerSessionState.CANCELLED.name(), true, 0, 0);
        }
        if (!DesignerSessionState.STOPPING.name().equals(session.state())) {
            session = beginStopping(session);
        }
        disableAutoMode(sessionId);
        Path root = Path.of(projects.get(session.projectId()).rootPath()).toAbsolutePath().normalize();
        Set<String> remoteIds = allRemoteIds(session);
        int stopped = 0;
        int failed = 0;
        for (String remoteId : remoteIds) {
            try {
                openCode.abort(new OpenCodeClient.OpenCodeSession(remoteId, root));
                stopped++;
            } catch (RuntimeException failure) {
                failed++;
            }
        }
        if (failed > 0) {
            return new Result(DesignerSessionState.STOPPING.name(), false, stopped, failed);
        }
        String stoppedAt = now();
        mapper.stopTaskProfileRouterRuns(sessionId, stoppedAt);
        mapper.supersedeActiveTaskProfiles(sessionId, stoppedAt);
        mapper.stopTaskDecompositions(sessionId, stoppedAt);
        mapper.stopDesignWorkPackages(sessionId, stoppedAt);
        mapper.stopLoopSpecCompilations(sessionId, stoppedAt);
        mapper.stopAnalysisReports(sessionId, stoppedAt);
        completeCancellation(session(sessionId));
        mapper.archiveDesignerSession(sessionId, stoppedAt);
        return new Result(DesignerSessionState.CANCELLED.name(), true, stopped, 0);
    }

    private DesignerSessionRow beginStopping(DesignerSessionRow session) {
        DesignerSessionRow updated = copy(session, DesignerSessionState.STOPPING, "STOPPING");
        lifecycle.transition(subject(session), session.state(), updated.state(), LifecycleEvent.CANCEL,
                "USER_CLEAR_DESIGNER", Map.of(), () -> mapper.updateDesignerSession(updated), conflict());
        return session(session.id());
    }

    private void completeCancellation(DesignerSessionRow session) {
        DesignerSessionRow updated = copy(session, DesignerSessionState.CANCELLED, "ABORTED");
        lifecycle.transition(subject(session), session.state(), updated.state(), LifecycleEvent.FINISH,
                "REMOTE_SESSIONS_STOPPED", Map.of(), () -> mapper.updateDesignerSession(updated), conflict());
    }

    private DesignerSessionRow copy(DesignerSessionRow row, DesignerSessionState state, String remoteState) {
        return new DesignerSessionRow(row.id(), row.projectId(), state.name(), row.accessMode(), row.createdAt(), now(),
                row.version(), row.externalSessionId(), remoteState, row.loopDraftId(), row.workflowPhase(),
                row.designRevision(), row.redesignCount(), row.currentRequirementRevision(), row.activeWorkPackageId(),
                row.discussionScope(), row.discussionRevision(), row.candidateSyncState());
    }

    private Set<String> allRemoteIds(DesignerSessionRow session) {
        Set<String> ids = new LinkedHashSet<>();
        mapper.listDesignerRemoteSessionIds(session.id()).forEach(remoteId -> add(ids, remoteId));
        return ids;
    }

    private void disableAutoMode(String sessionId) {
        DesignerAutoModeService.View current = autoMode.get(sessionId);
        if (Set.of(DesignerAutoModeState.ACTIVE.name(), DesignerAutoModeState.BLOCKED.name())
                .contains(current.state())) {
            autoMode.setEnabled(sessionId, false, current.version());
        }
    }

    private DesignerSessionRow session(String id) {
        return mapper.findDesignerSession(id)
                .orElseThrow(() -> new NotFoundException("Designer session not found: " + id));
    }

    private LifecycleTransitionService.Subject subject(DesignerSessionRow row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.DESIGNER_SESSION, row.id(),
                LifecycleScopeType.PROJECT, row.projectId());
    }

    private java.util.function.Supplier<ConflictException> conflict() {
        return () -> new ConflictException("DESIGNER_SESSION_VERSION_CONFLICT", "设计会话已发生并发变化，请重试");
    }

    private static void add(Set<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value);
    }

    private static String now() { return Instant.now().toString(); }

    public record Result(String stopStatus, boolean archived, int stoppedSessions, int failedSessions) { }
}
