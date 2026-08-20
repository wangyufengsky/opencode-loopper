package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignWorkflowPhase;
import io.opencode.loopper.domain.DesignerActor;
import io.opencode.loopper.persistence.AnalysisReportRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** Read-only live projection for the currently active Designer role. */
@Service
public final class DesignerActivityService {
    private static final int MAX_PARTS = 64;
    private static final int MAX_PART_CHARS = 8_192;
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final OpenCodeClient openCode;

    public DesignerActivityService(LoopperMapper mapper, ProjectService projects, OpenCodeClient openCode) {
        this.mapper = mapper;
        this.projects = projects;
        this.openCode = openCode;
    }

    public View activity(String sessionId) {
        DesignerSessionRow session = mapper.findDesignerSession(sessionId)
                .orElseThrow(() -> new NotFoundException("Designer session not found: " + sessionId));
        DesignerActor actor = actor(session);
        Remote remote = remote(session, actor);
        String observedAt = Instant.now().toString();
        if (remote.id() == null || remote.id().isBlank()) {
            return new View(actor.name(), remote.state(), false, observedAt, step(session, actor), List.of(),
                    "当前角色尚未创建可观测的远端会话");
        }
        Path root = Path.of(projects.get(session.projectId()).rootPath()).toAbsolutePath().normalize();
        try {
            OpenCodeClient.OpenCodeSession openCodeSession = new OpenCodeClient.OpenCodeSession(remote.id(), root);
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(openCodeSession);
            boolean structured = actor == DesignerActor.ROUTER || actor == DesignerActor.DECOMPOSER
                    || actor == DesignerActor.COMPILER || actor == DesignerActor.REVIEWER;
            List<Part> allParts = openCode.sessionTranscript(openCodeSession).parts().stream()
                    .filter(part -> !structured || "TOOL".equals(part.type()))
                    .map(this::part).toList();
            List<Part> parts = allParts.size() <= MAX_PARTS ? allParts
                    : List.copyOf(allParts.subList(allParts.size() - MAX_PARTS, allParts.size()));
            return new View(actor.name(), status.state(), true, observedAt, step(session, actor), parts,
                    bounded(status.detail()));
        } catch (RuntimeException failure) {
            return new View(actor.name(), remote.state(), false, observedAt, step(session, actor), List.of(),
                    bounded(failure.getMessage()));
        }
    }

    private Remote remote(DesignerSessionRow session, DesignerActor actor) {
        if (actor == DesignerActor.ROUTER) {
            return mapper.findLatestTaskProfileRouterRun(session.id())
                    .map(row -> new Remote(row.externalSessionId(), row.externalSessionState()))
                    .orElse(new Remote(null, session.externalSessionState()));
        }
        if (actor == DesignerActor.DECOMPOSER) {
            return mapper.findLatestTaskDecomposition(session.id())
                    .map(row -> new Remote(row.externalSessionId(), row.externalSessionState()))
                    .orElse(new Remote(null, session.externalSessionState()));
        }
        if (actor == DesignerActor.COMPILER) {
            return mapper.findLatestLoopSpecCompilation(session.id())
                    .map(row -> new Remote(row.externalSessionId(), row.externalSessionState()))
                    .orElse(new Remote(null, session.externalSessionState()));
        }
        if (actor == DesignerActor.REVIEWER) {
            AnalysisReportRow report = mapper.listAnalysisReports(session.id()).stream().findFirst().orElse(null);
            return report == null ? new Remote(null, session.externalSessionState())
                    : new Remote(report.externalSessionId(), report.externalSessionState());
        }
        return new Remote(session.externalSessionId(), session.externalSessionState());
    }

    private String step(DesignerSessionRow session, DesignerActor actor) {
        if (actor == DesignerActor.DECOMPOSER) {
            return mapper.findLatestTaskDecomposition(session.id()).map(row -> row.workflowStep()).orElse(null);
        }
        if (actor == DesignerActor.COMPILER) {
            return mapper.findLatestLoopSpecCompilation(session.id()).map(row -> row.workflowStep()).orElse(null);
        }
        return null;
    }

    private DesignerActor actor(DesignerSessionRow session) {
        return switch (DesignWorkflowPhase.valueOf(session.workflowPhase())) {
            case ROUTING -> DesignerActor.ROUTER;
            case DISCUSSING_REQUIREMENT, QUESTIONING_PACKAGE, DESIGNING, REDESIGNING -> DesignerActor.DESIGNER;
            case DECOMPOSING -> DesignerActor.DECOMPOSER;
            case COMPILING -> DesignerActor.COMPILER;
            case GENERATING_REPORT -> DesignerActor.REVIEWER;
            case VALIDATING_DECOMPOSITION, VALIDATING, AGGREGATING, VALIDATING_REPORT -> DesignerActor.VALIDATOR;
            case REPORT_READY, REVIEWING_PACKAGE, FINAL_REVIEW, COMPLETED, FAILED -> DesignerActor.SYSTEM;
        };
    }

    private Part part(OpenCodeClient.SessionPart source) {
        return new Part(source.id(), source.type(), source.label(), boundedContent(source.content()),
                source.status(), source.startedAt());
    }

    private String boundedContent(String value) {
        if (value == null || value.length() <= MAX_PART_CHARS) return value;
        return value.substring(0, MAX_PART_CHARS) + "\n…输出已截断";
    }

    private String bounded(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= 512 ? value : value.substring(0, 512) + "…";
    }

    private record Remote(String id, String state) { }
    public record Part(String id, String type, String label, String content, String status, String startedAt) { }
    public record View(String actor, String remoteState, boolean connected, String observedAt,
                       String structuredStep, List<Part> parts, String detail) { }
}
