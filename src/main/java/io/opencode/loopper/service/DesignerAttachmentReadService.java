package io.opencode.loopper.service;

import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.DesignerAttachmentRow;
import io.opencode.loopper.persistence.TaskDesignAttachmentRow;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DesignerAttachmentReadService {
    private final LoopperMapper mapper;
    private final DesignerAttachmentStore store;
    public DesignerAttachmentReadService(LoopperMapper mapper, DesignerAttachmentStore store) {
        this.mapper = mapper;
        this.store = store;
    }

    public List<View> forSession(String sessionId) {
        return mapper.listDesignerAttachments(sessionId).stream().map(row -> new View(
                row.id(), row.designerMessageId(), row.originalFilename(), row.detectedMediaType(),
                row.sizeBytes(), row.sha256(), row.scopeKey(), row.workPackageId(), row.extractorId(),
                row.previewKind(), row.state(), row.supersededByAttachmentId())).toList();
    }

    public List<View> forMessage(String messageId) {
        return mapper.listDesignerAttachmentsForMessage(messageId).stream().map(row -> new View(
                row.id(), row.designerMessageId(), row.originalFilename(), row.detectedMediaType(),
                row.sizeBytes(), row.sha256(), row.scopeKey(), row.workPackageId(), row.extractorId(),
                row.previewKind(), row.state(), row.supersededByAttachmentId())).toList();
    }

    public Preview previewForSession(String sessionId, String attachmentId) {
        DesignerAttachmentRow row = mapper.findDesignerAttachment(attachmentId)
                .filter(item -> item.designerSessionId().equals(sessionId))
                .orElseThrow(() -> new NotFoundException("Designer attachment not found"));
        return preview(row.originalFilename(), row.previewKind(), row.detectedMediaType(), row.relativePath(),
                row.sha256(), row.extractedRelativePath(), row.extractedSha256());
    }

    public BinaryContent contentForSession(String sessionId, String attachmentId) {
        DesignerAttachmentRow row = mapper.findDesignerAttachment(attachmentId)
                .filter(item -> item.designerSessionId().equals(sessionId))
                .orElseThrow(() -> new NotFoundException("Designer attachment not found"));
        return binary(row.originalFilename(), row.previewKind(), row.detectedMediaType(), row.relativePath(), row.sha256());
    }

    public Preview previewForTask(String taskId, String attachmentId) {
        TaskDesignAttachmentRow row = mapper.findTaskDesignAttachment(attachmentId)
                .filter(item -> item.taskId().equals(taskId))
                .orElseThrow(() -> new NotFoundException("Task design attachment not found"));
        String kind = row.extractedRelativePath() != null
                ? ("application/pdf".equals(row.detectedMediaType()) ? "PDF" : "OFFICE")
                : row.detectedMediaType().startsWith("image/") ? "IMAGE" : "TEXT";
        return preview(row.originalFilename(), kind, row.detectedMediaType(), row.relativePath(), row.sha256(),
                row.extractedRelativePath(), row.extractedSha256());
    }

    public BinaryContent contentForTask(String taskId, String attachmentId) {
        TaskDesignAttachmentRow row = mapper.findTaskDesignAttachment(attachmentId)
                .filter(item -> item.taskId().equals(taskId))
                .orElseThrow(() -> new NotFoundException("Task design attachment not found"));
        String kind = row.detectedMediaType().startsWith("image/") ? "IMAGE"
                : "application/pdf".equals(row.detectedMediaType()) ? "PDF" : "TEXT";
        return binary(row.originalFilename(), kind, row.detectedMediaType(), row.relativePath(), row.sha256());
    }

    private Preview preview(String filename, String kind, String mediaType, String relativePath, String sha256,
                            String extractedRelativePath, String extractedSha256) {
        if ("IMAGE".equals(kind)) return new Preview(filename, kind, mediaType, null, true);
        byte[] bytes = extractedRelativePath == null
                ? store.read(relativePath, sha256) : store.read(extractedRelativePath, extractedSha256);
        return new Preview(filename, kind, extractedRelativePath == null ? mediaType : "text/plain",
                new String(bytes, StandardCharsets.UTF_8), "PDF".equals(kind));
    }

    private BinaryContent binary(String filename, String kind, String mediaType,
                                 String relativePath, String sha256) {
        if (!"IMAGE".equals(kind) && !"PDF".equals(kind)) {
            throw new BadRequestException("ATTACHMENT_INLINE_CONTENT_FORBIDDEN",
                    "只有经过验证的图片和 PDF 可以内联返回");
        }
        return new BinaryContent(filename, mediaType, store.read(relativePath, sha256));
    }

    public record View(String id, String designerMessageId, String filename, String mediaType,
                       long sizeBytes, String sha256, String scopeKey, String workPackageId,
                       String extractorId, String previewKind, String state, String supersededByAttachmentId) { }
    public record Preview(String filename, String previewKind, String mediaType, String text,
                          boolean inlineContentAvailable) { }
    public record BinaryContent(String filename, String mediaType, byte[] bytes) {
        public BinaryContent { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
