package io.opencode.loopper.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.opencode.loopper.api.CursorPage;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionHistoryRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopDraftRow;
import io.opencode.loopper.persistence.ProjectRow;
import io.opencode.loopper.persistence.ReadModelMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Read-optimized Designer projections. Large conversations and design documents stay out of overview responses. */
@Service
public class DesignerReadService {
    private static final Set<String> STATUS_MODES = Set.of(
            "PROCESSING", "REVIEWING", "WAITING_INPUT", "FAILED", "CONFIRMED");

    private final ReadModelMapper mapper;
    private final DesignerSessionService sessions;
    private final MeterRegistry metrics;

    public DesignerReadService(ReadModelMapper mapper, DesignerSessionService sessions, MeterRegistry metrics) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.metrics = metrics;
    }

    public CursorPage<HistoryItem> history(String projectId, String status, String archive,
                                           String query, String order, String cursor, Integer requestedLimit) {
        return measured("designer.history", () -> {
            int limit = PageCursor.limit(requestedLimit);
            boolean oldest = "oldest".equalsIgnoreCase(order);
            PageCursor decoded = PageCursor.decode(cursor);
            String statusMode = optionalUpper(status);
            if (statusMode != null && !STATUS_MODES.contains(statusMode)) {
                throw new BadRequestException("DESIGNER_HISTORY_STATUS_INVALID", "Unsupported history status: " + status);
            }
            String archiveMode = optionalUpper(archive);
            if (archiveMode == null) archiveMode = "ACTIVE";
            if (!Set.of("ACTIVE", "ARCHIVED", "ALL").contains(archiveMode)) {
                throw new BadRequestException("DESIGNER_HISTORY_ARCHIVE_INVALID", "Unsupported archive filter: " + archive);
            }
            List<DesignerSessionHistoryRow> rows = mapper.designerHistoryPage(projectId, statusMode, archiveMode,
                    like(query), decoded == null ? null : decoded.value(), decoded == null ? null : decoded.id(),
                    oldest, limit + 1);
            boolean more = rows.size() > limit;
            List<DesignerSessionHistoryRow> pageRows = more ? rows.subList(0, limit) : rows;
            List<HistoryItem> items = pageRows.stream().map(HistoryItem::from).toList();
            String next = more && !pageRows.isEmpty()
                    ? new PageCursor(pageRows.getLast().updatedAt(), pageRows.getLast().id()).encode() : null;
            recordRows("designer.history", items.size());
            return new CursorPage<>(items, next, Map.of());
        });
    }

    public Overview overview(String id) {
        return measured("designer.overview", () -> {
            DesignerSessionRow row = sessions.get(id);
            ProjectRow project = sessions.project(id);
            LoopDraftRow draft = sessions.draft(id);
            DesignerSessionService.CandidateStatus candidate = sessions.candidateStatus(id);
            Overview result = new Overview(row.id(), row.projectId(), project.name(), row.state(), row.workflowPhase(),
                    sessions.activeActor(row), row.accessMode(), row.createdAt(), row.updatedAt(),
                    draft == null ? null : new DraftSummary(draft.id(), draft.status(), draft.goal(), draft.updatedAt()),
                    sessions.pendingQuestions(id), sessions.answeredQuestions(id), sessions.compilerStatus(id),
                    sessions.requirementStatus(id), sessions.decompositionStatus(id), sessions.workPackageStatuses(id),
                    row.currentRequirementRevision(), row.activeWorkPackageId(), row.discussionScope(),
                    row.discussionRevision(), candidate == null ? null : new CandidateSummary(
                            candidate.syncState(), candidate.discussionRevision(), candidate.workPackageId(),
                            candidate.detail(), candidate.spec() != null),
                    sessions.finalConfirmationEligible(id), sessions.archived(id));
            recordRows("designer.overview", 1);
            return result;
        });
    }

    public CursorPage<MessageItem> messages(String id, String cursor, Integer requestedLimit) {
        return measured("designer.messages", () -> {
            sessions.get(id);
            int limit = PageCursor.limit(requestedLimit);
            PageCursor decoded = PageCursor.decode(cursor);
            int before = decoded == null ? Integer.MAX_VALUE : ordinal(decoded.value());
            List<DesignerMessageRow> rows = mapper.designerMessagesPage(id, before, limit + 1);
            boolean more = rows.size() > limit;
            List<DesignerMessageRow> pageRows = new ArrayList<>(more ? rows.subList(0, limit) : rows);
            String next = more && !pageRows.isEmpty()
                    ? new PageCursor(Integer.toString(pageRows.getLast().ordinal()), pageRows.getLast().id()).encode() : null;
            Collections.reverse(pageRows);
            List<MessageItem> items = pageRows.stream().map(MessageItem::from).toList();
            recordRows("designer.messages", items.size());
            return new CursorPage<>(items, next, Map.of());
        });
    }

    private int ordinal(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new BadRequestException("PAGE_CURSOR_INVALID", "The page cursor is invalid");
        }
    }

    private String like(String query) {
        if (query == null || query.isBlank()) return null;
        String escaped = query.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private String optionalUpper(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    private <T> T measured(String model, Supplier<T> supplier) {
        return metrics.timer("loopper.read_model.duration", "model", model).record(supplier);
    }

    private void recordRows(String model, int rows) {
        metrics.summary("loopper.read_model.rows", "model", model).record(rows);
    }

    public record HistoryItem(String id, String projectId, String projectName, String state, String workflowPhase,
                              String createdAt, String updatedAt, String draftId, String draftStatus, String goal,
                              Integer requirementRevision, String activeWorkPackageId, boolean archived,
                              String archivedAt, String taskId, String taskState) {
        static HistoryItem from(DesignerSessionHistoryRow row) {
            return new HistoryItem(row.id(), row.projectId(), row.projectName(), row.state(), row.workflowPhase(),
                    row.createdAt(), row.updatedAt(), row.draftId(), row.draftStatus(), row.goal(),
                    row.requirementRevision(), row.activeWorkPackageId(), row.archived() == 1, row.archivedAt(),
                    row.taskId(), row.taskState());
        }
    }

    public record DraftSummary(String id, String status, String goal, String updatedAt) { }
    public record CandidateSummary(String syncState, int designRevision, String activeWorkPackageId,
                                   String notice, boolean documentAvailable) { }
    public record Overview(String id, String projectId, String projectName, String state, String workflowPhase,
                           String activeActor, String accessMode, String createdAt, String updatedAt,
                           DraftSummary draft, List<DesignerSessionService.PendingQuestion> pendingQuestions,
                           List<DesignerSessionService.AnsweredQuestion> answeredQuestions,
                           DesignerSessionService.CompilerStatus compiler,
                           DesignerSessionService.RequirementRevisionStatus requirement,
                           DesignerSessionService.DecompositionStatus decomposition,
                           List<DesignerSessionService.WorkPackageStatus> workPackages,
                           Integer requirementRevision, String activeWorkPackageId, String discussionScope,
                           int discussionRevision, CandidateSummary candidate,
                           boolean finalConfirmationEligible, boolean archived) { }
    public record MessageItem(String id, int ordinal, String role, String actor, String content,
                              String deliveryState, String createdAt, Integer requirementRevision,
                              String workPackageId) {
        static MessageItem from(DesignerMessageRow row) {
            return new MessageItem(row.id(), row.ordinal(), row.role(), row.actor(), row.content(),
                    row.deliveryState(), row.createdAt(), row.requirementRevision(), row.workPackageId());
        }
    }
}
