package io.opencode.loopper.service;

import io.opencode.loopper.persistence.DesignerAttachmentSubmissionRow;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.LoopperMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesignerAttachmentIdempotencyTest {
    @Test
    void publishedSubmissionRetryDoesNotConsumeBudgetOrWriteFilesAgain() throws Exception {
        LoopperMapper mapper = mock(LoopperMapper.class);
        DesignerAttachmentStore store = mock(DesignerAttachmentStore.class);
        DesignerAttachmentContext context = new DesignerAttachmentContext(
                mapper, store, mock(PlatformTransactionManager.class));
        String now = Instant.now().toString();
        DesignerSessionRow session = new DesignerSessionRow(
                "designer-1", "project-1", "ACTIVE", "READ_ONLY", now, now, 0,
                null, null, null, "DISCUSSING_REQUIREMENT", 0, 0);
        DesignerMessageRow message = new DesignerMessageRow(
                "message-1", session.id(), 1, "USER", "Use the attached context.", "SENT", now, "USER");
        DesignerAttachmentContext.SubmitAttachmentMessage command =
                new DesignerAttachmentContext.SubmitAttachmentMessage(
                        "submission-1", session.id(), message.id(),
                        DesignerAttachmentContext.AttachmentScope.requirement(), message.content());
        DesignerAttachmentStore.PreparedFile prepared = new DesignerAttachmentStore.PreparedFile(
                "context.pdf", "application/pdf", 20L * 1024 * 1024, "file-sha", new byte[]{1},
                "PDFBOX", "3.0.7", "text/plain", 7L, "text-sha", new byte[]{2}, "PDF");
        String canonical = String.join("\n", session.id(), message.id(), "REQUIREMENT", "", message.content(),
                prepared.filename(), prepared.sha256());
        String requestSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        when(mapper.findDesignerSession(session.id())).thenReturn(Optional.of(session));
        when(mapper.findDesignerMessage(message.id())).thenReturn(Optional.of(message));
        when(mapper.findDesignerAttachmentSubmission(command.submissionId())).thenReturn(Optional.of(
                new DesignerAttachmentSubmissionRow(command.submissionId(), session.id(), "REQUIREMENT", null,
                        requestSha, "PUBLISHED", null, null, null, null, null, now, now, 1)));
        when(mapper.listDesignerAttachmentsForMessage(message.id())).thenReturn(List.of());

        DesignerAttachmentContext.ChangeReceipt receipt = context.changePrepared(
                command, new DesignerAttachmentContext.PreparedUpload(List.of(prepared)));

        assertThat(receipt.state()).isEqualTo("PUBLISHED");
        verify(mapper, never()).sumDesignerAttachmentBytes(session.id());
        verify(store, never()).write(session.id(), prepared);
    }
}
