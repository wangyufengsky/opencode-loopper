package io.opencode.loopper.api;

import io.opencode.loopper.domain.ArtifactKind;
import io.opencode.loopper.domain.TaskIntent;
import io.opencode.loopper.service.*;
import java.util.List;

/** Read-only Designer projections; write request contracts remain in the controller. */
public final class DesignerSessionViews {
    private DesignerSessionViews() { }
    public record DesignerSessionDto(String id, String taskId, String projectId, String projectName, String state,
                                     String workflowPhase, String activeActor, String accessMode,
                                     boolean readOnly, String permissionSummary, String createdAt, String updatedAt,
                                     DesignerDraftDto draft, List<DesignerMessageDto> messages,
                                     List<DesignerSessionService.PendingQuestion> pendingQuestions,
                                     List<DesignerSessionService.AnsweredQuestion> answeredQuestions,
                                     DesignerSessionService.QuestionInteractionStatus questionInteraction,
                                     DesignerSessionService.RequirementSnapshot requirementSnapshot,
                                     DesignerSessionService.CompilerStatus compiler,
                                     DesignerSessionService.RequirementRevisionStatus requirement,
                                     DesignerSessionService.DecompositionStatus decomposition,
                                     List<DesignerSessionService.WorkPackageStatus> workPackages,
                                     Integer requirementRevision, String activeWorkPackageId,
                                     String discussionScope, int discussionRevision,
                                     DesignerSessionService.CandidateStatus candidate,
                                     boolean finalConfirmationEligible, boolean archived,
                                     DesignerAutoModeService.View autoMode,
                                     TaskProfileService.View taskProfile,
                                     TaskProfileRouterRunService.RouterRunView routerRun,
                                     List<TaskIntent> availableProfileOverrides,
                                     List<ArtifactKind> availableArtifactOverrides,
                                     List<AnalysisReportService.Summary> reports,
                                     StoryBindingConfiguration storyBinding,
                                     List<io.opencode.loopper.service.DesignerConversationCoordinator.View> designConversations) { }
    public record DesignerSessionSummaryDto(String id, String projectId, String state, String workflowPhase,
                                            String updatedAt, String draftId, String draftStatus, String goal,
                                            Integer requirementRevision, String activeWorkPackageId) { }
    public record DesignerSessionHistoryDto(String id, String projectId, String projectName,
                                            String state, String workflowPhase,
                                            String createdAt, String updatedAt,
                                            String draftId, String draftStatus, String goal,
                                            Integer requirementRevision, String activeWorkPackageId,
                                            boolean archived, String archivedAt,
                                            String taskId, String taskState) { }
    public record DesignerDraftDto(String id, String status, String updatedAt,
                                   io.opencode.loopper.domain.LoopSpec spec) { }
    public record DesignerMessageDto(String id, int ordinal, String role, String actor, String content,
                                     String deliveryState, String createdAt,
                                     Integer requirementRevision, String workPackageId,
                                     List<DesignerAttachmentDto> attachments) { }
    public record DesignerAttachmentDto(String id, String filename, String mediaType, long sizeBytes,
                                        String sha256, String scopeKey, String workPackageId,
                                        String extractorId, String previewKind, String state,
                                        String supersededByAttachmentId) { }
}
