package io.opencode.loopper.service.knowledge;

import io.opencode.loopper.persistence.*;
import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.lifecycle.LifecycleTransitionService.Subject;
import io.opencode.loopper.persistence.KnowledgeRows.*;
import io.opencode.loopper.service.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short CAS transactions only. No runtime, filesystem or model I/O. */
@Service
public class KnowledgePersistence {
    private final KnowledgeMapper mapper;
    private final LifecycleTransitionService lifecycle;
    public KnowledgePersistence(KnowledgeMapper mapper, LifecycleTransitionService lifecycle) { this.mapper = mapper; this.lifecycle = lifecycle; }
    public void create(Conversation row) {
        lifecycle.create(conversationSubject(row), "IDLE", Map.of(), () -> mapper.insertConversation(row), () -> conflict("会话创建标识已存在，请重新读取"));
    }
    public Conversation require(String id) { return mapper.conversation(id).orElseThrow(() -> new NotFoundException("知识库会话不存在")); }
    @Transactional
    public Turn begin(String id, String key, String text) {
        var old = mapper.replay(id, key);
        if (old.isPresent()) {
            if (!old.get().userText().equals(text)) throw conflict("同一请求标识不能用于不同问题");
            return old.get();
        }
        var conversation = require(id);
        if (!conversation.state().equals("IDLE") || mapper.active(id).isPresent()) throw conflict("当前回答尚未结束，请等待完成或停止生成");
        String turnId = UUID.randomUUID().toString(), now = Instant.now().toString();
        var turn = new Turn(turnId, id, mapper.nextOrdinal(id), key, "msg_loopper_knowledge_" + turnId.replace("-", ""),
                "PREPARED", text, "", "", null, null, null, null, now, now, 0);
        conversationState(conversation, "RUNNING");
        lifecycle.create(turnSubject(turn), "PREPARED", Map.of(), () -> mapper.insertTurn(turn), () -> conflict("问题已登记，请重新读取")); return turn;
    }
    @Transactional
    public void state(Turn turn, String state, String detail) {
        if (turn.state().equals(state)) { detail(turn, detail); return; }
        lifecycle.transition(turnSubject(turn), turn.state(), state, null, Map.of(),
                () -> mapper.turnState(turn.id(), turn.version(), state, detail, Instant.now().toString()), () -> conflict("问答状态已变化，请读取最新状态"));
    }
    @Transactional
    public void finish(Turn turn, String state, String detail) {
        state(turn, state, detail); var conversation = require(turn.conversationId());
        conversationState(conversation, "IDLE");
    }
    @Transactional
    public void stop(String id) {
        var conversation = require(id); var active = mapper.active(id);
        if (active.isEmpty() || active.get().state().equals("STOPPING")) return;
        state(active.get(), "STOPPING", "正在确认模型停止");
        conversationState(conversation, "STOPPING");
    }
    public void detail(Turn turn, String detail) {
        lifecycle.mutateWithoutTransition(() -> mapper.detail(turn.id(), turn.version(), detail, Instant.now().toString()), () -> conflict("问答状态已变化"));
    }
    public void dispatch(Turn turn, String request, String sha) {
        lifecycle.transition(turnSubject(turn), turn.state(), "SENDING", null, Map.of(),
                () -> mapper.dispatch(turn.id(), turn.version(), request, sha, Instant.now().toString()), () -> conflict("派发状态已变化"));
    }
    private void conversationState(Conversation row, String state) {
        lifecycle.transition(conversationSubject(row), row.state(), state, null, Map.of(),
                () -> mapper.conversationState(row.id(), row.version(), state, Instant.now().toString()), () -> conflict("会话状态已变化"));
    }
    private Subject conversationSubject(Conversation row) { return new Subject(LifecycleMachineType.KNOWLEDGE_CONVERSATION, row.id(), LifecycleScopeType.PROJECT, row.projectId()); }
    private Subject turnSubject(Turn row) { return new Subject(LifecycleMachineType.KNOWLEDGE_TURN, row.id(), LifecycleScopeType.PROJECT, require(row.conversationId()).projectId()); }
    public static ConflictException conflict(String message) { return new ConflictException("KNOWLEDGE_CONVERSATION_CONFLICT", message); }
}
