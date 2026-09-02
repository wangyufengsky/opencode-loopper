package io.opencode.loopper.service;

import io.opencode.loopper.domain.SessionFailure;
import io.opencode.loopper.persistence.DesignDiscussionRevisionRow;
import io.opencode.loopper.persistence.DesignerMessageRow;
import io.opencode.loopper.persistence.LoopperMapper;
import io.opencode.loopper.runtime.OpenCodeClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the OpenCode question capability boundary and the durable question/answer wire shape.
 * It deliberately does not own Designer lifecycle transitions or remote Session replacement.
 */
@Component
public final class DesignerQuestionSupport {
    static final String CHAT_QUESTION = "CHAT_QUESTION";
    static final String CHAT_QUESTIONING = "CHAT_QUESTIONING";
    static final String WAITING_CHAT_ANSWER = "WAITING_CHAT_ANSWER";
    static final String CHAT_DESIGNING = "CHAT_DESIGNING";
    private static final Set<String> CHAT_STATES = Set.of(
            CHAT_QUESTIONING, WAITING_CHAT_ANSWER, CHAT_DESIGNING);
    private static final Pattern LEGACY_DESIGNER_PAYLOAD = Pattern.compile(
            "<!--\\s*LOOPSPEC_JSON_START\\s*-->.*?<!--\\s*LOOPSPEC_JSON_END\\s*-->",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final LoopperMapper mapper;
    private final OpenCodeClient openCode;
    private final ObjectMapper json;

    public DesignerQuestionSupport(LoopperMapper mapper, OpenCodeClient openCode, ObjectMapper json) {
        this.mapper = mapper;
        this.openCode = openCode;
        this.json = json;
    }

    boolean nativeQuestionAvailable(Path projectRoot) {
        OpenCodeClient.ToolCapabilityProbe capability = openCode.toolCapabilities(projectRoot);
        return capability.state() == OpenCodeClient.CapabilityState.AVAILABLE
                && capability.contains("question");
    }

    boolean chatMode(DesignDiscussionRevisionRow discussion) {
        return discussion != null && CHAT_STATES.contains(discussion.state());
    }

    Interaction interaction(DesignDiscussionRevisionRow discussion) {
        if (discussion == null || !discussion.questionRequired()
                || discussion.questionAnswered() && !chatMode(discussion)) {
            return new Interaction("NONE", false);
        }
        return new Interaction(chatMode(discussion) ? "CHAT_FALLBACK" : "NATIVE_TOOL",
                WAITING_CHAT_ANSWER.equals(discussion.state()));
    }

    List<OpenCodeClient.PendingQuestion> pendingQuestions(OpenCodeClient.OpenCodeSession remote) {
        try {
            return openCode.pendingQuestions(remote);
        } catch (SessionFailure failure) {
            throw new ServiceUnavailableException(failure.code(), safeMessage(failure.getMessage()));
        }
    }

    OpenCodeClient.PendingQuestion pendingQuestion(OpenCodeClient.OpenCodeSession remote, String questionId) {
        if (questionId == null || questionId.isBlank()) {
            throw new BadRequestException("QUESTION_ID_REQUIRED", "Question id is required");
        }
        return pendingQuestions(remote).stream().filter(question -> questionId.equals(question.id()))
                .findFirst().orElseThrow(() -> new NotFoundException(
                        "Pending question not found for this Designer Session: " + questionId));
    }

    List<List<String>> validateAnswers(OpenCodeClient.PendingQuestion pending, List<List<String>> answers) {
        if (answers == null || answers.size() != pending.questions().size()) {
            throw new BadRequestException("QUESTION_ANSWERS_INVALID",
                    "Answers must contain one entry for every question");
        }
        List<List<String>> result = new ArrayList<>();
        for (int index = 0; index < pending.questions().size(); index++) {
            OpenCodeClient.QuestionPrompt prompt = pending.questions().get(index);
            List<String> answer = answers.get(index) == null ? List.of() : answers.get(index);
            List<String> normalized = answer.stream().filter(value -> value != null && !value.isBlank())
                    .map(String::trim).distinct().toList();
            if (normalized.isEmpty()) {
                throw new BadRequestException("QUESTION_ANSWER_REQUIRED", "Every question requires an answer");
            }
            if (!prompt.multiple() && normalized.size() > 1) {
                throw new BadRequestException("QUESTION_ANSWER_MULTIPLE_FORBIDDEN",
                        "This question accepts only one answer");
            }
            if (!prompt.custom()) {
                List<String> labels = prompt.options().stream().map(OpenCodeClient.QuestionOption::label).toList();
                if (!labels.containsAll(normalized)) {
                    throw new BadRequestException("QUESTION_CUSTOM_ANSWER_FORBIDDEN",
                            "This question only accepts listed options");
                }
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    List<List<String>> recommendedAnswers(OpenCodeClient.PendingQuestion pending) {
        return pending.questions().stream().map(prompt -> {
            List<String> recommended = prompt.options().stream()
                    .filter(option -> recommended(option.label()) || recommended(option.description()))
                    .map(OpenCodeClient.QuestionOption::label).toList();
            if (!recommended.isEmpty()) return prompt.multiple() ? recommended : List.of(recommended.getFirst());
            if (!prompt.options().isEmpty()) return List.of(prompt.options().getFirst().label());
            throw new ConflictException("AUTO_RECOMMENDATION_MISSING",
                    "问题缺少推荐选项和可用的首选项，已停止全自动模式");
        }).toList();
    }

    String appendDecision(String existingJson, OpenCodeClient.PendingQuestion pending,
                          List<List<String>> answers, String answerSource) {
        List<Map<String, Object>> decisions = mutableDecisions(existingJson);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("schemaVersion", 2);
        decision.put("questionId", pending.id());
        decision.put("questions", pending.questions().stream().map(prompt -> {
            Map<String, Object> question = new LinkedHashMap<>();
            question.put("question", prompt.question());
            question.put("header", prompt.header());
            question.put("options", prompt.options().stream().map(option -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("label", option.label());
                item.put("description", option.description());
                return item;
            }).toList());
            question.put("multiple", prompt.multiple());
            question.put("custom", prompt.custom());
            return question;
        }).toList());
        decision.put("answers", answers == null ? List.of() : answers);
        decision.put("answerSource", answerSource == null ? "MANUAL" : answerSource);
        decision.put("answeredAt", Instant.now().toString());
        decisions.add(decision);
        return write(decisions);
    }

    String appendChatDecision(String existingJson, DesignerMessageRow question, String answer) {
        OpenCodeClient.PendingQuestion pending = new OpenCodeClient.PendingQuestion(
                "chat-" + question.id(), "chat", List.of(new OpenCodeClient.QuestionPrompt(
                question.content(), "兼容提问", List.of(), false, true)));
        return appendDecision(existingJson, pending, List.of(List.of(answer)), "CHAT_FALLBACK");
    }

    DesignerMessageRow chatQuestionMessage(DesignDiscussionRevisionRow discussion) {
        if (discussion.designMessageId() == null || discussion.designMessageId().isBlank()) {
            throw new ConflictException("DESIGN_CHAT_QUESTION_MISSING", "兼容提问消息不存在，请刷新后重试");
        }
        return mapper.findDesignerMessage(discussion.designMessageId())
                .filter(message -> CHAT_QUESTION.equals(message.deliveryState()))
                .orElseThrow(() -> new ConflictException(
                        "DESIGN_CHAT_QUESTION_MISSING", "兼容提问消息不存在，请刷新后重试"));
    }

    public List<HistoryQuestion> history(String sessionId) {
        return answeredDecisions(mapper.listDesignDiscussionRevisions(sessionId)).stream()
                .map(entry -> new HistoryQuestion(entry.questionId(), entry.scope(), entry.discussionRevision(),
                        entry.designMessageId(), entry.answeredAt(), entry.prompts())).toList();
    }

    List<AnsweredDecision> answeredDecisions(List<DesignDiscussionRevisionRow> revisions) {
        List<AnsweredDecision> result = new ArrayList<>();
        for (DesignDiscussionRevisionRow revision : revisions) {
            try {
                for (DecodedDecision decision : decode(revision.decisionLogJson())) {
                    if (!decision.questionId().isBlank() && !decision.prompts().isEmpty()) {
                        result.add(new AnsweredDecision(decision.questionId(), revision.scopeKey(), revision.revision(),
                                revision.designMessageId(), decision.answeredAt(), decision.prompts()));
                    }
                }
            } catch (JacksonException ignored) {
                // A malformed historical decision must not hide later recoverable discussion rounds.
            }
        }
        return List.copyOf(result);
    }

    void appendSnapshotDecisions(StringBuilder snapshot, String decisionLogJson) {
        if (decisionLogJson == null || decisionLogJson.isBlank()) return;
        try {
            List<DecodedDecision> decisions = decode(decisionLogJson);
            if (decisions.isEmpty()) return;
            snapshot.append("### 最终回答\n\n");
            for (DecodedDecision decision : decisions) {
                for (AnsweredPrompt prompt : decision.prompts()) {
                    snapshot.append("- 问题：").append(prompt.question()).append("\n")
                            .append("  - 回答：").append(String.join("；", prompt.answers())).append("\n");
                }
            }
            snapshot.append("\n");
        } catch (JacksonException failure) {
            throw new ConflictException("REQUIREMENT_DECISION_LOG_INVALID", "已保存的需求回答无法生成快照");
        }
    }

    int openRequirementModelCalls(List<DesignDiscussionRevisionRow> revisions, boolean serverSnapshot) {
        return revisions.stream()
                .filter(row -> "REQUIREMENT".equals(row.scopeKey()) && row.requirementRevision() == null)
                .mapToInt(row -> 1 + row.questionRetryCount()
                        + (!serverSnapshot && row.decisionLogJson().contains("\"answerSource\":\"CHAT_FALLBACK\"")
                        ? 1 : 0)).sum();
    }

    String markdown(String output) {
        if (output == null || output.isBlank()) return "";
        return LEGACY_DESIGNER_PAYLOAD.matcher(output).replaceAll("").trim();
    }

    private List<Map<String, Object>> mutableDecisions(String existingJson) {
        List<Map<String, Object>> decisions = new ArrayList<>();
        if (existingJson == null || existingJson.isBlank()) return decisions;
        try {
            decisions.addAll(json.readValue(existingJson, new TypeReference<List<Map<String, Object>>>() { }));
        } catch (JacksonException ignored) {
            // Historical malformed content is replaced only when a new authoritative answer is saved.
        }
        return decisions;
    }

    private List<DecodedDecision> decode(String source) throws JacksonException {
        if (source == null || source.isBlank()) return List.of();
        List<Map<String, Object>> raw = json.readValue(source,
                new TypeReference<List<Map<String, Object>>>() { });
        List<DecodedDecision> result = new ArrayList<>();
        for (Map<String, Object> decision : raw) {
            List<List<String>> answers = answerLists(decision.get("answers"));
            result.add(new DecodedDecision(text(decision.get("questionId")), text(decision.get("answeredAt")),
                    answeredPrompts(decision.get("questions"), answers)));
        }
        return List.copyOf(result);
    }

    private List<AnsweredPrompt> answeredPrompts(Object rawQuestions, List<List<String>> answers) {
        if (!(rawQuestions instanceof List<?> questions)) return List.of();
        List<AnsweredPrompt> result = new ArrayList<>();
        for (int index = 0; index < questions.size(); index++) {
            Object rawQuestion = questions.get(index);
            List<String> selected = index < answers.size() ? answers.get(index) : List.of();
            if (rawQuestion instanceof String question) {
                result.add(new AnsweredPrompt(question, "", List.of(), false, true, selected));
            } else if (rawQuestion instanceof Map<?, ?> prompt) {
                List<Option> options = prompt.get("options") instanceof List<?> rawOptions
                        ? rawOptions.stream().filter(Map.class::isInstance).map(Map.class::cast)
                        .map(option -> new Option(text(option.get("label")), text(option.get("description")))).toList()
                        : List.of();
                result.add(new AnsweredPrompt(text(prompt.get("question")), text(prompt.get("header")), options,
                        Boolean.TRUE.equals(prompt.get("multiple")), !Boolean.FALSE.equals(prompt.get("custom")), selected));
            }
        }
        return List.copyOf(result);
    }

    private static List<List<String>> answerLists(Object rawAnswers) {
        if (!(rawAnswers instanceof List<?> answers)) return List.of();
        return answers.stream().map(rawAnswer -> rawAnswer instanceof List<?> values
                ? values.stream().map(DesignerQuestionSupport::text).filter(value -> !value.isBlank()).toList()
                : List.<String>of()).toList();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalStateException("Unable to serialize Designer question decisions", failure);
        }
    }

    private static boolean recommended(String value) {
        return value != null && (value.toLowerCase(java.util.Locale.ROOT).contains("(recommended)")
                || value.contains("（推荐）") || value.contains("推荐"));
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "Unknown OpenCode failure" : message;
    }

    record Interaction(String mode, boolean awaitingAnswer) { }
    record AnsweredDecision(String questionId, String scope, int discussionRevision,
                            String designMessageId, String answeredAt, List<AnsweredPrompt> prompts) { }
    public record HistoryQuestion(String id, String scope, int discussionRevision,
                                  String designMessageId, String answeredAt, List<AnsweredPrompt> questions) { }
    public record AnsweredPrompt(String question, String header, List<Option> options,
                          boolean multiple, boolean custom, List<String> answers) { }
    public record Option(String label, String description) { }
    private record DecodedDecision(String questionId, String answeredAt, List<AnsweredPrompt> prompts) { }
}
