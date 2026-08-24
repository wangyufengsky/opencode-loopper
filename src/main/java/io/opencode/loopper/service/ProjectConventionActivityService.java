package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.ProjectConventionDraftRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** One-read live activity and provider-authoritative token projection for AGENTS.md generation. */
@Service
public class ProjectConventionActivityService {
    private static final int MAX_PART_CHARS = 8_192;
    private final LoopperMapper mapper;
    private final ProjectService projects;
    private final OpenCodeClient openCode;

    public ProjectConventionActivityService(LoopperMapper mapper, ProjectService projects, OpenCodeClient openCode) {
        this.mapper = mapper;
        this.projects = projects;
        this.openCode = openCode;
    }

    public View activity(String projectId, String draftId) {
        ProjectRow project = projects.get(projectId);
        ProjectConventionDraftRow draft = mapper.findProjectConventionDraft(draftId)
                .filter(row -> projectId.equals(row.projectId()))
                .orElseThrow(() -> new NotFoundException("AGENTS.md proposal not found: " + draftId));
        String observedAt = Instant.now().toString();
        if (draft.externalSessionId() == null || draft.externalSessionId().isBlank()) {
            return new View("PROJECT_CONVENTION", draft.externalSessionState(), false, observedAt, List.of(),
                    "正在建立只读项目公约会话", usage(null, observedAt));
        }
        try {
            OpenCodeClient.OpenCodeSession remote = new OpenCodeClient.OpenCodeSession(
                    draft.externalSessionId(), Path.of(project.rootPath()));
            OpenCodeClient.SessionStatus status = openCode.sessionStatus(remote);
            OpenCodeClient.SessionTranscript transcript = openCode.sessionTranscript(remote);
            List<Part> parts = transcript.parts().isEmpty() ? List.of()
                    : List.of(part(transcript.parts().getLast()));
            return new View("PROJECT_CONVENTION", status.state(), true, observedAt, parts,
                    bounded(status.detail()), usage(transcript.usage(), observedAt));
        } catch (RuntimeException failure) {
            return new View("PROJECT_CONVENTION", draft.externalSessionState(), false, observedAt, List.of(),
                    bounded(failure.getMessage()), usage(null, observedAt));
        }
    }

    private Part part(OpenCodeClient.SessionPart source) {
        return new Part(source.id(), source.type(), source.label(), boundedContent(source.content()),
                source.status(), source.startedAt());
    }

    private ModelTokenUsageProjectionService.UsageView usage(
            List<OpenCodeClient.UsageRecord> records, String observedAt) {
        ModelTokenUsageProjectionService.Snapshot snapshot =
                ModelTokenUsageProjectionService.snapshot(records == null ? List.of() : records);
        return new ModelTokenUsageProjectionService.UsageView(
                snapshot.totalTokens(), snapshot.reliable() ? 0 : 1, observedAt);
    }

    private String boundedContent(String value) {
        if (value == null || value.length() <= MAX_PART_CHARS) return value;
        return value.substring(0, MAX_PART_CHARS) + "\n…输出已截断";
    }

    private String bounded(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= 512 ? value : value.substring(0, 512) + "…";
    }

    public record Part(String id, String type, String label, String content, String status, String startedAt) { }
    public record View(String actor, String remoteState, boolean connected, String observedAt,
                       List<Part> parts, String detail,
                       ModelTokenUsageProjectionService.UsageView usage) { }
}
