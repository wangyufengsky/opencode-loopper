package io.opencode.loopper.service.ppt.agent;

import io.opencode.loopper.domain.*;
import io.opencode.loopper.lifecycle.LifecycleTransitionService;
import io.opencode.loopper.persistence.PptAgentMapper;
import io.opencode.loopper.persistence.PptAgentRows.*;
import io.opencode.loopper.service.ConflictException;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Only short, audited CAS transactions. No model calls or rendering belongs here. */
@Service
public class PptAgentPersistence {
    private final PptAgentMapper mapper;
    private final LifecycleTransitionService lifecycle;
    public PptAgentPersistence(PptAgentMapper mapper, LifecycleTransitionService lifecycle) {
        this.mapper = mapper; this.lifecycle = lifecycle;
    }
    @Transactional
    public Run begin(Run desired, Runnable revalidate) {
        var old = mapper.replay(desired.documentId(), desired.idempotencyKey());
        if (old.isPresent()) {
            if (!old.get().inputSha().equals(desired.inputSha())) throw conflict("同一请求标识已用于不同请求");
            return old.get();
        }
        revalidate.run();
        if (mapper.active(desired.documentId()).isPresent()) throw conflict("PPT 助手仍在运行或等待回答，请先完成或停止当前请求");
        lifecycle.create(subject(desired), PptAgentState.PREPARED.name(), Map.of(), () -> mapper.insert(desired),
                () -> conflict("请求已登记，请重新读取"));
        return require(desired.id());
    }
    @Transactional
    public void creating(Run row, String plan, String generation) {
        lifecycle.mutateWithoutTransition(() -> mapper.plan(row.id(), row.version(), plan, generation), () -> conflict("会话计划已变化"));
        state(require(row.id()), PptAgentState.CREATING, "正在建立 PPT 会话");
        var current = require(row.id());
        lifecycle.mutateWithoutTransition(() -> mapper.createDispatched(current.id(), current.version()), () -> conflict("会话派发已变化"));
    }
    public Run require(String id) { return mapper.run(id).orElseThrow(() -> new io.opencode.loopper.service.NotFoundException("PPT 助手请求不存在")); }
    @Transactional
    public void state(Run row, PptAgentState state, String detail) {
        if (row.state().equals(state.name())) { detail(row, detail); return; }
        lifecycle.transition(subject(row), row.state(), state.name(), null, Map.of(),
                () -> mapper.state(row.id(), row.version(), state.name(), detail, Instant.now().toString()),
                () -> conflict("PPT 助手状态已变化"));
    }
    public void detail(Run row, String detail) {
        if (row.detail().equals(detail)) return;
        lifecycle.mutateWithoutTransition(() -> mapper.detail(row.id(), row.version(), detail, Instant.now().toString()),
                () -> conflict("PPT 助手状态已变化"));
    }
    @Transactional
    public void stop(String document, String reason) {
        var active = mapper.active(document); if (active.isEmpty()) return;
        var row = active.get();
        if (row.state().equals("STOPPING")) {
            if ("USER".equals(reason) && !"USER".equals(row.stopReason())) changeReason(row, reason);
            return;
        }
        changeReason(row, reason); row = require(row.id());
        state(row, PptAgentState.STOPPING, "INPUT".equals(reason) ? "正在安全暂停，确认后可以回答问题" : "正在确认 PPT 助手停止");
    }
    private void changeReason(Run row, String reason) {
        lifecycle.mutateWithoutTransition(() -> mapper.stopReason(row.id(), row.version(), reason), () -> conflict("停止请求已变化"));
    }
    @Transactional
    public void runtimeStopped(Run observed, String proof) {
        var current = require(observed.id());
        if (PptAgentState.valueOf(current.state()).terminal()) return;
        if (!java.util.Objects.equals(current.generation(), observed.generation())) throw conflict("运行环境身份已变化");
        stop(current.documentId(), "RUNTIME_EXITED");
        proven(require(current.id()), PptAgentState.STOPPED, proof, "原受管运行环境已确认退出，页面与候选已保留，可继续发送请求");
    }
    @Transactional
    public void proven(Run row, PptAgentState state, String proof, String detail) {
        lifecycle.mutateWithoutTransition(() -> mapper.proof(row.id(), row.version(), proof), () -> conflict("停止证明已变化"));
        state(require(row.id()), state, detail);
        if (state.terminal()) mapper.closeQuestions(row.id());
    }
    @Transactional
    public void dispatch(Run row, String request, String sha) {
        lifecycle.mutateWithoutTransition(() -> mapper.request(row.id(), row.version(), request, sha), () -> conflict("投递已登记"));
        if (mapper.prompt(row.id(), row.round(), row.messageId(), request, sha, Instant.now().toString()) != 1) throw conflict("投递记录冲突");
        state(require(row.id()), PptAgentState.SENDING, "正在发送 PPT 制作请求");
    }
    @Transactional
    public Run reply(Question question, String answer, String key, String sha,
                     long revision, String context, Runnable revalidate) {
        var current = mapper.question(question.documentId(), question.id()).orElseThrow();
        if (current.state().equals("ANSWERED") && key.equals(current.replyKey())) {
            if (!sha.equals(current.replySha())) throw conflict("回答请求标识已用于不同回答");
            return require(current.runId());
        }
        revalidate.run(); var run = require(current.runId());
        if (!current.state().equals("PENDING") || current.version() != question.version() || !run.state().equals("WAITING_INPUT"))
            throw conflict("问题尚未安全暂停或已被回答，请刷新状态");
        if (mapper.reply(question.id(), question.version(), answer, key, sha) != 1) throw conflict("回答状态已变化");
        state(run, PptAgentState.PREPARED, "已保存回答，准备继续制作");
        var fresh = require(run.id()); String message = "msg_ppt_" + run.id().replace("-", "") + "_" + (run.round() + 1);
        lifecycle.mutateWithoutTransition(() -> mapper.resume(fresh.id(), fresh.version(), message, revision, context), () -> conflict("继续请求已变化"));
        return require(run.id());
    }
    private LifecycleTransitionService.Subject subject(Run row) {
        return new LifecycleTransitionService.Subject(LifecycleMachineType.PPT_AGENT_RUN, row.id(), LifecycleScopeType.PPT_DOCUMENT, row.documentId());
    }
    public static ConflictException conflict(String message) { return new ConflictException("PPT_AGENT_CONFLICT", message); }
}
