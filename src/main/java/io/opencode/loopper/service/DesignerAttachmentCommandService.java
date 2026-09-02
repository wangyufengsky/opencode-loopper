package io.opencode.loopper.service;

import io.opencode.loopper.domain.DesignWorkflowPhase;
import io.opencode.loopper.domain.DesignWorkPackageState;
import io.opencode.loopper.domain.DesignerActor;
import io.opencode.loopper.domain.DesignerSessionState;
import io.opencode.loopper.persistence.DesignWorkPackageRow;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import java.util.List;
import org.springframework.stereotype.Service;

/** Coordinates attachment-only commands without expanding the legacy Designer workflow service. */
@Service
public class DesignerAttachmentCommandService {
    private final DesignerSessionService sessions;
    private final LoopDraftService drafts;
    private final DesignerSessionRuntimeControl runtimeControl;
    private final DesignerAttachmentContext attachments;

    public DesignerAttachmentCommandService(DesignerSessionService sessions, LoopDraftService drafts,
            DesignerSessionRuntimeControl runtimeControl, DesignerAttachmentContext attachments) {
        this.sessions = sessions;
        this.drafts = drafts;
        this.runtimeControl = runtimeControl;
        this.attachments = attachments;
    }

    public DesignerSessionRow create(String projectId, String loopDraftId, String content, String submissionId,
            List<DesignerAttachmentContext.IncomingFile> files) {
        return create(projectId, loopDraftId, content, submissionId, files,
                StoryBindingConfiguration.disabled());
    }

    public DesignerSessionRow create(String projectId, String loopDraftId, String content, String submissionId,
            List<DesignerAttachmentContext.IncomingFile> files,
            StoryBindingConfiguration storyBinding) {
        DesignerAttachmentContext.PreparedUpload prepared = attachments.prepare(files);
        var replay = attachments.publishedMessageRetry(submissionId, null,
                DesignerAttachmentContext.AttachmentScope.requirement(), content, prepared);
        if (replay.isPresent()) {
            DesignerSessionRow existing = sessions.get(replay.get().designerSessionId());
            if (!existing.projectId().equals(projectId) || !java.util.Objects.equals(existing.loopDraftId(), loopDraftId)) {
                throw new ConflictException("ATTACHMENT_SUBMISSION_ID_REUSED",
                        "submissionId 已用于另一个项目或草稿");
            }
            return existing;
        }
        DesignerSessionRow session = sessions.create(projectId, loopDraftId, content, storyBinding);
        DesignerMessageRow user = sessions.messages(session.id()).stream()
                .filter(message -> DesignerActor.USER.name().equals(message.actor())).findFirst()
                .orElseThrow(() -> new ConflictException(
                        "ATTACHMENT_MESSAGE_MISSING", "初始附件没有可绑定的用户消息"));
        attachments.changePrepared(new DesignerAttachmentContext.SubmitAttachmentMessage(
                submissionId, session.id(), user.id(), DesignerAttachmentContext.AttachmentScope.requirement(),
                user.content()), prepared);
        return sessions.get(session.id());
    }

    public DesignerAttachmentContext.ChangeReceipt stopFutureUse(
            String sessionId, String attachmentId, String commandId) {
        DesignerSessionRow session = sessions.get(sessionId);
        if (!DesignerSessionState.REVIEWING.name().equals(session.state())) {
            throw new ConflictException("ATTACHMENT_STOP_REVIEW_REQUIRED",
                    "只能在当前设计回合完成并等待确认时停止附件未来使用");
        }
        if ("CONFIRMED".equals(drafts.get(session.loopDraftId()).status())) {
            throw new ConflictException("ATTACHMENT_FROZEN", "设计已确认，冻结附件不能再停用");
        }
        if (!blank(session.externalSessionId())) {
            runtimeControl.requireStoppedBeforeReplacement(session.externalSessionId(), session.projectId());
        }
        DesignerAttachmentContext.ChangeReceipt receipt = attachments.change(
                new DesignerAttachmentContext.StopFutureUse(commandId, session.id(), attachmentId), List.of());
        DesignerAttachmentContext.AttachmentRef stopped = receipt.attachments().getFirst();
        if (stopped.workPackageId() != null) clearPackageSession(session, stopped.workPackageId());
        DesignerSessionRow reset = sessions.updateDesignerDiscussionProjection(sessions.get(session.id()),
                DesignerSessionState.REVIEWING, DesignWorkflowPhase.valueOf(session.workflowPhase()), null,
                "STOPPED_FOR_ATTACHMENT_CHANGE", session.discussionScope(), session.discussionRevision(),
                session.candidateSyncState(), session.activeWorkPackageId());
        sessions.appendMessage(session.id(), DesignerActor.SYSTEM,
                "附件未来使用已停止；既有消息与历史读取事实保留，下一条讨论将使用新的 OpenCode Session。",
                "PERSISTED", session.currentRequirementRevision(), session.activeWorkPackageId());
        sessions.publish(reset, "STATUS", DesignerActor.SYSTEM, true, "", "附件未来使用已停止");
        return receipt;
    }

    private void clearPackageSession(DesignerSessionRow session, String packageId) {
        DesignWorkPackageRow workPackage = sessions.requireCurrentPackage(session, packageId);
        sessions.updateWorkPackage(workPackage, DesignWorkPackageState.valueOf(workPackage.state()), null,
                "STOPPED_FOR_ATTACHMENT_CHANGE", workPackage.designMessageId(), workPackage.designRevision(),
                workPackage.redesignCount(), workPackage.designerTransportRetryCount(), workPackage.compilerSummary(),
                workPackage.handoffSummary(), workPackage.lastErrorCode(), workPackage.lastErrorDetail());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
