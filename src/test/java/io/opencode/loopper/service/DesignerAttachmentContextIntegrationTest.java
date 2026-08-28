package io.opencode.loopper.service;

import io.opencode.loopper.LoopperApplication;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.DesignerSessionRow;
import io.opencode.loopper.persistence.ProjectRow;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.FakeOpenCodeClient;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = LoopperApplication.class, properties = {
        "loopper.opencode.mode=fake", "loopper.monitor-delay=1h",
        "loopper.data-dir=target/designer-attachment-context-test"
})
class DesignerAttachmentContextIntegrationTest {
    @Autowired private Flyway flyway;
    @Autowired private ProjectService projects;
    @Autowired private DesignerSessionService designerSessions;
    @Autowired private DesignerAttachmentCommandService attachmentCommands;
    @Autowired private DesignerAttachmentContext attachmentContext;
    @Autowired private DesignerAttachmentReadService attachmentReads;
    @Autowired private TaskProfileService taskProfiles;
    @Autowired private OpenCodeClient openCode;
    @TempDir Path temp;

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void rejectsTheWholeAttachmentMessageWhenAnyFileIsInvalid() throws Exception {
        ProjectRow project = projects.create("attachment-atomic", Files.createDirectory(temp.resolve("project")).toString());
        DesignerSessionRow session = designerSessions.create(project.id(), "Use the attached requirements as reference.");
        DesignerMessageRow initial = designerSessions.messages(session.id()).stream()
                .filter(message -> "USER".equals(message.role())).findFirst().orElseThrow();

        DesignerAttachmentContext.SubmitAttachmentMessage command =
                new DesignerAttachmentContext.SubmitAttachmentMessage(
                        UUID.randomUUID().toString(), session.id(), initial.id(),
                        DesignerAttachmentContext.AttachmentScope.requirement(), initial.content());

        assertThatThrownBy(() -> attachmentContext.change(command, List.of(
                new DesignerAttachmentContext.IncomingFile("notes.txt", "text/plain",
                        "valid context".getBytes(StandardCharsets.UTF_8)),
                new DesignerAttachmentContext.IncomingFile("archive.zip", "application/zip",
                        new byte[]{'P', 'K', 3, 4, 0, 0}))))
                .isInstanceOfSatisfying(BadRequestException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ATTACHMENT_TYPE_UNSUPPORTED"));

        assertThat(attachmentReads.forSession(session.id())).isEmpty();
    }

    @Test
    void validatesInitialFilesBeforeCreatingTheDesignerSession() throws Exception {
        ProjectRow project = projects.create("attachment-initial-atomic",
                Files.createDirectory(temp.resolve("initial-atomic-project")).toString());

        assertThatThrownBy(() -> attachmentCommands.create(project.id(), null,
                "Use these files as the initial requirement context.", UUID.randomUUID().toString(),
                List.of(
                        new DesignerAttachmentContext.IncomingFile("valid.txt", "text/plain",
                                "valid context".getBytes(StandardCharsets.UTF_8)),
                        new DesignerAttachmentContext.IncomingFile("archive.zip", "application/zip",
                                new byte[]{'P', 'K', 3, 4, 0, 0}))))
                .isInstanceOfSatisfying(BadRequestException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ATTACHMENT_TYPE_UNSUPPORTED"));

        assertThat(designerSessions.listOpen(project.id())).isEmpty();
    }

    @Test
    void retriesAnAlreadyPublishedInitialSubmissionWithoutCreatingAnotherSession() throws Exception {
        ProjectRow project = projects.create("attachment-initial-retry",
                Files.createDirectory(temp.resolve("initial-retry-project")).toString());
        String submissionId = UUID.randomUUID().toString();
        List<DesignerAttachmentContext.IncomingFile> files = List.of(
                new DesignerAttachmentContext.IncomingFile("context.txt", "text/plain",
                        "stable context".getBytes(StandardCharsets.UTF_8)));

        DesignerSessionRow first = attachmentCommands.create(project.id(), null,
                "Use the stable attachment context.", submissionId, files);
        DesignerSessionRow retried = attachmentCommands.create(project.id(), null,
                "Use the stable attachment context.", submissionId, files);

        assertThat(retried.id()).isEqualTo(first.id());
        assertThat(attachmentReads.forSession(first.id())).hasSize(1);
    }

    @Test
    void replacesOnlyAnExactFilenameInsideTheSameScope() throws Exception {
        ProjectRow project = projects.create("attachment-replacement", Files.createDirectory(temp.resolve("replacement-project")).toString());
        DesignerSessionRow session = designerSessions.create(project.id(), "Compare the attached design revisions.");
        DesignerMessageRow message = designerSessions.messages(session.id()).stream()
                .filter(item -> "USER".equals(item.role())).findFirst().orElseThrow();

        DesignerAttachmentContext.ChangeReceipt oldRequirement = submit(
                session, message, DesignerAttachmentContext.AttachmentScope.requirement(),
                "design.md", "requirement v1");
        DesignerAttachmentContext.ChangeReceipt packageCopy = submit(
                session, message, DesignerAttachmentContext.AttachmentScope.workPackage("WP-1"),
                "design.md", "package v1");
        DesignerAttachmentContext.ChangeReceipt replacement = submit(
                session, message, DesignerAttachmentContext.AttachmentScope.requirement(),
                "design.md", "requirement v2");

        List<DesignerAttachmentReadService.View> history = attachmentReads.forSession(session.id());
        assertThat(history).hasSize(3);
        assertThat(history).filteredOn(item -> "WP-1".equals(item.scopeKey()))
                .singleElement().extracting(DesignerAttachmentReadService.View::state).isEqualTo("ACTIVE");
        assertThat(history).filteredOn(item -> "REQUIREMENT".equals(item.scopeKey()))
                .extracting(DesignerAttachmentReadService.View::state)
                .containsExactly("SUPERSEDED", "ACTIVE");
        assertThat(history).filteredOn(item -> item.id().equals(oldRequirement.attachments().getFirst().id()))
                .singleElement().extracting(DesignerAttachmentReadService.View::supersededByAttachmentId)
                .isEqualTo(replacement.attachments().getFirst().id());
        assertThat(packageCopy.attachments().getFirst().id()).isNotEqualTo(replacement.attachments().getFirst().id());
    }

    @Test
    void extractsOfficeTextAndSendsBothOriginalAndDeterministicRepresentation() throws Exception {
        ProjectRow project = projects.create("attachment-office", Files.createDirectory(temp.resolve("office-project")).toString());
        DesignerSessionRow session = designerSessions.create(project.id(), "Use the Office reference as context.");
        DesignerMessageRow message = designerSessions.messages(session.id()).stream()
                .filter(item -> "USER".equals(item.role())).findFirst().orElseThrow();
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Approved interface: uploadAttachment");
            document.write(output);
            docx = output.toByteArray();
        }

        attachmentContext.change(new DesignerAttachmentContext.SubmitAttachmentMessage(
                        UUID.randomUUID().toString(), session.id(), message.id(),
                        DesignerAttachmentContext.AttachmentScope.requirement(), message.content()),
                List.of(new DesignerAttachmentContext.IncomingFile(
                        "design.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx)));

        DesignerAttachmentReadService.View attachment = attachmentReads.forSession(session.id()).getFirst();
        assertThat(attachment.mediaType()).isEqualTo(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(attachment.extractorId()).isEqualTo("OOXML_DOCX");
        assertThat(attachment.previewKind()).isEqualTo("OFFICE");
        assertThat(attachmentReads.previewForSession(session.id(), attachment.id()).text())
                .contains("Approved interface: uploadAttachment");
        OpenCodeClient.PromptRequest prompt = attachmentContext.withContext(
                DesignerAttachmentContext.ContextUse.requirement(session.id()),
                OpenCodeClient.PromptRequest.text("Continue the design."));
        assertThat(prompt.text()).contains("untrusted supplemental reference material", "Continue the design.");
        assertThat(prompt.files()).extracting(OpenCodeClient.FilePart::filename)
                .containsExactly("design.docx", "design.docx.loopper-context.txt");
    }

    @Test
    void validatesImagesAndExtractsPdfTextWithoutModelSummarization() throws Exception {
        ProjectRow project = projects.create("attachment-binary", Files.createDirectory(temp.resolve("binary-project")).toString());
        DesignerSessionRow session = designerSessions.create(project.id(), "Use the PDF and image as design evidence.");
        DesignerMessageRow message = designerSessions.messages(session.id()).stream()
                .filter(item -> "USER".equals(item.role())).findFirst().orElseThrow();
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Approved PDF context");
                content.endText();
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        byte[] png;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
            png = output.toByteArray();
        }

        attachmentContext.change(new DesignerAttachmentContext.SubmitAttachmentMessage(
                        UUID.randomUUID().toString(), session.id(), message.id(),
                        DesignerAttachmentContext.AttachmentScope.requirement(), message.content()),
                List.of(
                        new DesignerAttachmentContext.IncomingFile("reference.pdf", "application/pdf", pdf),
                        new DesignerAttachmentContext.IncomingFile("diagram.png", "image/png", png)));

        assertThat(attachmentReads.forSession(session.id()))
                .extracting(DesignerAttachmentReadService.View::mediaType)
                .containsExactlyInAnyOrder("application/pdf", "image/png");
        OpenCodeClient.PromptRequest prompt = attachmentContext.withContext(
                DesignerAttachmentContext.ContextUse.requirement(session.id()),
                OpenCodeClient.PromptRequest.text("Continue."));
        assertThat(prompt.files()).extracting(OpenCodeClient.FilePart::filename)
                .containsExactly("diagram.png", "reference.pdf", "reference.pdf.loopper-context.txt");
        DesignerAttachmentReadService.View pdfView = attachmentReads.forSession(session.id()).stream()
                .filter(item -> item.filename().equals("reference.pdf")).findFirst().orElseThrow();
        DesignerAttachmentReadService.View imageView = attachmentReads.forSession(session.id()).stream()
                .filter(item -> item.filename().equals("diagram.png")).findFirst().orElseThrow();
        assertThat(attachmentReads.previewForSession(session.id(), pdfView.id()).text())
                .contains("Approved PDF context");
        assertThat(attachmentReads.contentForSession(session.id(), imageView.id()).bytes()).isEqualTo(png);
    }

    @Test
    void keepsRouterTextOnlyAndDeliversAttachmentsToTheRequirementDesigner() throws Exception {
        ProjectRow project = projects.create("attachment-router", Files.createDirectory(temp.resolve("router-project")).toString());
        DesignerSessionRow created = attachmentCommands.create(project.id(), null,
                "Implement the explicit attachment workflow.", UUID.randomUUID().toString(),
                List.of(new DesignerAttachmentContext.IncomingFile(
                        "contract.txt", "text/plain", "immutable context".getBytes(StandardCharsets.UTF_8))));
        FakeOpenCodeClient fake = (FakeOpenCodeClient) openCode;
        String routerSession = fake.promptHistory().stream().map(FakeOpenCodeClient.PromptCall::sessionId)
                .filter(id -> fake.profileForSession(id) == OpenCodeClient.SessionProfile.ROUTER_NO_TOOLS)
                .findFirst().orElseThrow();
        assertThat(fake.promptRequestForSession(routerSession).files()).isEmpty();

        designerSessions.pollActiveHandoffs();
        TaskProfileService.View profile = taskProfiles.current(created.id());
        if (!profile.confirmationReady()) taskProfiles.confirmRecommendation(created.id(), profile.version());
        designerSessions.continueAfterTaskProfileDecision(created.id());

        DesignerSessionRow running = designerSessions.get(created.id());
        OpenCodeClient.PromptRequest designerPrompt = fake.promptRequestForSession(running.externalSessionId());
        assertThat(designerPrompt.files()).extracting(OpenCodeClient.FilePart::filename)
                .containsExactly("contract.txt");
        assertThat(designerPrompt.messageId()).startsWith("loopper-attachment-");
    }

    private DesignerAttachmentContext.ChangeReceipt submit(
            DesignerSessionRow session, DesignerMessageRow message,
            DesignerAttachmentContext.AttachmentScope scope, String filename, String content) {
        return attachmentContext.change(new DesignerAttachmentContext.SubmitAttachmentMessage(
                        UUID.randomUUID().toString(), session.id(), message.id(), scope, message.content()),
                List.of(new DesignerAttachmentContext.IncomingFile(
                        filename, "text/plain", content.getBytes(StandardCharsets.UTF_8))));
    }
}
