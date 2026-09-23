package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.persistence.KnowledgeV2Mapper.Question;
import io.opencode.loopper.runtime.OpenCodeClient;
import io.opencode.loopper.runtime.OpenCodeClient.*;
import io.opencode.loopper.service.KnowledgeEventHub;
import io.opencode.loopper.service.assist.AssistRedaction;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Durable user replies. An ambiguous external delivery is never resent automatically. */
@Service
public final class KnowledgeQuestions {
    private final KnowledgeV2Mapper mapper;
    private final KnowledgeMapper turns;
    private final OpenCodeClient remote;
    private final ObjectMapper json;
    private final KnowledgeEventHub events;
    public KnowledgeQuestions(KnowledgeV2Mapper mapper, KnowledgeMapper turns, OpenCodeClient remote, ObjectMapper json, KnowledgeEventHub events) {
        this.mapper = mapper; this.turns = turns; this.remote = remote; this.json = json; this.events = events;
    }
    public record View(String id, String state, List<QuestionPrompt> questions, List<List<String>> answers, long version) { }
    public record Reply(String idempotencyKey, List<List<String>> answers, long version) { }
    public static View view(Question row, ObjectMapper json) {
        var prompt = json.readValue(row.promptJson(), PendingQuestion.class);
        List<List<String>> answers = row.answersJson() == null ? List.of() : json.readValue(row.answersJson(), new TypeReference<>() { });
        return new View(row.id(), row.state(), prompt.questions(), answers, row.version());
    }
    public View reply(String conversation, String id, Reply reply) {
        Question row = mapper.question(conversation, id);
        if (row == null) throw KnowledgeSources.bad("问题不存在或不属于当前对话");
        if (reply == null || reply.idempotencyKey() == null || !reply.idempotencyKey().matches("[a-zA-Z0-9_-]{16,100}")) throw KnowledgeSources.bad("回答请求标识无效");
        String answers = json.writeValueAsString(validate(json.readValue(row.promptJson(), PendingQuestion.class), reply.answers()));
        if (reply.idempotencyKey().equals(row.answerKey())) {
            if (!answers.equals(row.answersJson())) throw KnowledgePersistence.conflict("相同请求标识不能提交不同回答");
            return view(row, json);
        }
        if (!row.state().equals("PENDING") || row.version() != reply.version()
                || mapper.prepareAnswer(id, row.version(), reply.idempotencyKey(), answers, Instant.now().toString()) != 1)
            throw KnowledgePersistence.conflict("问题状态已变化，请重新读取当前对话");
        mapper.activity(conversation, Instant.now().toString()); events.publish(conversation, "question");
        return view(mapper.question(conversation, id), json);
    }
    /** true means this turn is waiting for a user or the exact reply outcome. */
    public boolean poll(OpenCodeSession session, KnowledgeRows.Turn turn) {
        return poll(session, turn, remote.pendingQuestions(session));
    }
    public boolean poll(OpenCodeSession session, KnowledgeRows.Turn turn, List<PendingQuestion> pending) {
        if (pending.size() > 10) throw KnowledgeSources.bad("待回答问题过多，请停止本轮后缩小问题范围");
        for (var prompt : pending) {
            if (!session.id().equals(prompt.sessionId()) || prompt.questions().isEmpty() || prompt.questions().size() > 10) throw KnowledgeSources.bad("问题身份或内容无效，请停止本轮后重试");
            String encoded = AssistRedaction.text(json.writeValueAsString(prompt));
            if (encoded.length() > 24000) throw KnowledgeSources.bad("问题内容过长，请停止本轮后重试");
            String now = Instant.now().toString();
            if (mapper.insertQuestion(new Question(UUID.randomUUID().toString(), turn.conversationId(), turn.id(), prompt.id(), encoded, "PENDING", null, null, now, now, 0)) == 1)
                events.publish(turn.conversationId(), "question");
        }
        boolean waiting = false;
        for (Question row : mapper.questions(turn.id())) {
            if (row.state().equals("ANSWERED") || row.state().equals("CLOSED")) continue;
            waiting = true;
            if (row.state().equals("SENDING")) change(row, "UNKNOWN");
            else if (row.state().equals("PREPARED")) deliver(session, turn, row, pending);
        }
        return waiting;
    }
    private void deliver(OpenCodeSession session, KnowledgeRows.Turn turn, Question row, List<PendingQuestion> pending) {
        if (pending.stream().noneMatch(p -> p.id().equals(row.remoteId()))) { change(row, "UNKNOWN"); return; }
        if (!turns.turn(turn.id()).map(t -> t.state().equals("RUNNING")).orElse(false) || !change(row, "SENDING")) return;
        Question sending = mapper.question(turn.conversationId(), row.id());
        try {
            remote.replyQuestion(session, row.remoteId(), json.readValue(row.answersJson(), new TypeReference<>() { }));
            change(sending, "ANSWERED");
        } catch (RuntimeException unknown) { change(sending, "UNKNOWN"); }
    }
    private boolean change(Question row, String next) {
        boolean changed = mapper.questionState(row.id(), row.version(), row.state(), next, Instant.now().toString()) == 1;
        if (changed) events.publish(row.conversationId(), "question");
        return changed;
    }
    static List<List<String>> validate(PendingQuestion prompt, List<List<String>> answers) {
        if (answers == null || answers.size() != prompt.questions().size()) throw KnowledgeSources.bad("请回答每个问题");
        var result = new ArrayList<List<String>>(); int size = 0;
        for (int i = 0; i < answers.size(); i++) {
            var question = prompt.questions().get(i);
            List<String> selected = answers.get(i) == null ? List.of() : answers.get(i).stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isBlank()).distinct().toList();
            if (selected.isEmpty() || !question.multiple() && selected.size() != 1 || selected.size() > 100) throw KnowledgeSources.bad("请按问题类型选择有效答案");
            if (!question.custom() && !question.options().stream().map(QuestionOption::label).toList().containsAll(selected)) throw KnowledgeSources.bad("此问题只能选择列出的选项");
            size += selected.stream().mapToInt(String::length).sum(); if (size > 24000) throw KnowledgeSources.bad("回答过长，请控制在 24000 字符以内");
            result.add(selected);
        }
        return List.copyOf(result);
    }
}
