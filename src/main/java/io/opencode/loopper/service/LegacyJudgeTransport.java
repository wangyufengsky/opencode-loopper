package io.opencode.loopper.service;

import io.opencode.loopper.domain.ModelResponseMode;
import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.JudgeRunRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.persistence.TaskRow;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeStructuredSchemas;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Keeps the legacy structured/text Judge transport isolated from the MCP candidate workflow. */
@Component
final class LegacyJudgeTransport {
    private final LoopperMapper mapper;
    private final OpenCodeClient openCode;
    private final AiOutputAuditService audit;
    private final TaskEvidenceService evidence;
    private final DesignerAttachmentContext attachments;
    private final TaskEventService events;
    private final TaskJudgeDecisionParser parser;
    private final io.opencode.loopper.lifecycle.LifecycleTransitionService lifecycle;
    private final tools.jackson.databind.ObjectMapper json;

    LegacyJudgeTransport(LoopperMapper mapper, OpenCodeClient openCode, AiOutputAuditService audit,
                         TaskEvidenceService evidence, DesignerAttachmentContext attachments,
                         TaskEventService events, AiOutputExtractor extractor,
                         io.opencode.loopper.lifecycle.LifecycleTransitionService lifecycle,
                         tools.jackson.databind.ObjectMapper json) {
        this.mapper = mapper; this.openCode = openCode; this.audit = audit; this.evidence = evidence;
        this.attachments = attachments; this.events = events;
        this.parser = new TaskJudgeDecisionParser(extractor);
        this.lifecycle = lifecycle; this.json = json;
    }

    ModelResponseMode responseMode(TaskRow task, String role, OpenCodeClient.OpenCodeModel jsonModel) {
        JudgeRunRow previous = mapper.latestJudgeRun(task.id(), role).orElse(null);
        if (previous != null && ModelResponseMode.JSON_SCHEMA.name().equals(previous.responseMode())
                && "SESSION_ERROR".equals(previous.state()) && previous.reason() != null
                && (previous.reason().startsWith("OPENCODE_STRUCTURED_FORMAT_UNSUPPORTED:")
                || previous.reason().startsWith("OPENCODE_STRUCTURED_OUTPUT_FAILED:"))) {
            return ModelResponseMode.TEXT_MARKER;
        }
        OpenCodeClient.StructuredOutputCapability capability = openCode.structuredOutputCapability(jsonModel);
        return capability.transport() == OpenCodeClient.CapabilityState.UNAVAILABLE
                || capability.selectedModel() == OpenCodeClient.CapabilityState.UNAVAILABLE
                ? ModelResponseMode.TEXT_MARKER : ModelResponseMode.JSON_SCHEMA;
    }

    String output(JudgeRunRow judge, OpenCodeClient.OpenCodeSession remote) {
        if (!ModelResponseMode.JSON_SCHEMA.name().equals(judge.responseMode())) {
            return openCode.sessionOutput(remote);
        }
        OpenCodeClient.SessionResult result = openCode.sessionResult(remote);
        if (result.structuredRetryCount() != 0) throw new SessionFailure(
                "OPENCODE_STRUCTURED_RETRY_UNEXPECTED",
                "OpenCode performed an unbudgeted structured-output retry");
        if (result.hasStructured()) return write(result.structured());
        String raw = openCode.sessionOutput(remote);
        if (parser.isLabeled(raw)) return raw;
        String detail = result.errorDetail() != null && !result.errorDetail().isBlank() ? result.errorDetail()
                : result.errorType() != null && !result.errorType().isBlank() ? result.errorType()
                : "OpenCode completed without the requested structured Judge object";
        throw new SessionFailure("OPENCODE_STRUCTURED_OUTPUT_FAILED", detail);
    }

    boolean recoverToolLoop(TaskRow task, JudgeRunRow judge, OpenCodeClient.OpenCodeSession failedRemote,
                            SessionFailure failure, OpenCodeClient.OpenCodeModel model) {
        if (!audit.claimToolLoopRecovery("JUDGE_RUN", judge.id(), judge.role(),
                "JUDGE", failure.getMessage())) return false;
        String priorEvidence = boundedToolEvidence(failedRemote);
        try {
            try { openCode.abort(failedRemote); } catch (RuntimeException ignored) { }
            OpenCodeClient.OpenCodeSession finalizer = openCode.createSession(Path.of(task.worktreePath()),
                    roleTitle(judge.role()) + " Finalizer (MCP_ONLY)", model,
                    OpenCodeClient.SessionProfile.MACHINE_FINALIZER_NO_TOOLS);
            JudgeRunRow recovered = new JudgeRunRow(judge.id(), judge.taskId(), judge.attemptId(), judge.role(),
                    judge.ordinal(), finalizer.id(), judge.state(), judge.verdict(), judge.reason(),
                    judge.rawOutput(), judge.createdAt(), judge.endedAt(), judge.version(),
                    judge.responseMode(), judge.responseSchemaId(), judge.reviewBatchId(), judge.sourceRevision());
            lifecycle.mutateWithoutTransition(() -> mapper.updateJudgeRun(recovered),
                    () -> new ConflictException("JUDGE_VERSION_CONFLICT", "Judge run was updated concurrently"));
            String prompt = evidence.frozenLegacyJudgeSource(task, judge).source().prompt()
                    + "\n\nFINALIZER RECOVERY: Do not call built-in tools. Configured MCP tools remain allowed;"
                    + " return the requested Judge object now." + priorEvidence;
            OpenCodeClient.PromptRequest request = ModelResponseMode.JSON_SCHEMA.name().equals(judge.responseMode())
                    ? new OpenCodeClient.PromptRequest(prompt, null, null,
                    OpenCodeStructuredSchemas.format(OpenCodeStructuredSchemas.JUDGE_DECISION_V1))
                    : OpenCodeClient.PromptRequest.text(prompt);
            openCode.promptAsync(finalizer, attachments.withContext(
                    DesignerAttachmentContext.ContextUse.taskAllPackages(task.id()), request));
            events.emit(task.id(), "AI_TOOL_LOOP_FINALIZER_STARTED", Map.of(
                    "judgeRunId", judge.id(), "role", judge.role(), "externalSessionId", finalizer.id()));
            return true;
        } catch (RuntimeException recoveryFailure) { return false; }
    }

    private String boundedToolEvidence(OpenCodeClient.OpenCodeSession remote) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        try {
            for (OpenCodeClient.SessionPart part : openCode.sessionTranscript(remote).parts()) {
                if (!"TOOL".equals(part.type())) continue;
                String content = part.content() == null ? "completed" : part.content();
                values.add("- " + (part.label() == null ? "tool" : part.label()) + ": "
                        + content.substring(0, Math.min(content.length(), 800)));
                if (values.size() >= 12) break;
            }
        } catch (RuntimeException ignored) { }
        return "\nBounded prior tool evidence:\n" + (values.isEmpty()
                ? "- No reusable tool evidence was available." : String.join("\n", values));
    }

    private static String roleTitle(String role) {
        return "REQUIREMENT".equals(role) ? "Requirement Judge" : "Risk Judge";
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (RuntimeException failure) { throw new IllegalStateException(failure); }
    }
}
